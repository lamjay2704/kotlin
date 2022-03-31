/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.lower

import org.jetbrains.kotlin.backend.common.ir.passTypeArgumentsFrom
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.common.phaser.makeIrFilePhase
import org.jetbrains.kotlin.backend.jvm.*
import org.jetbrains.kotlin.backend.jvm.MemoizedMultiFieldValueClassReplacements.ValueParameterTemplate
import org.jetbrains.kotlin.backend.jvm.MultiFieldValueClassSpecificDeclarations.ImplementationAgnostic
import org.jetbrains.kotlin.backend.jvm.MultiFieldValueClassSpecificDeclarations.VirtualProperty
import org.jetbrains.kotlin.backend.jvm.ir.erasedUpperBound
import org.jetbrains.kotlin.backend.jvm.ir.isMultiFieldValueClassType
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.transformStatement
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.isNullable
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.name.Name

val jvmMultiFieldValueClassPhase = makeIrFilePhase(
    ::JvmMultiFieldValueClassLowering,
    name = "Multi-field Value Classes",
    description = "Lower multi-field value classes",
    // Collection stubs may require mangling by multi-field value class rules.
    // SAM wrappers may require mangling for fun interfaces with multi-field value class parameters
    prerequisite = setOf(collectionStubMethodLowering, singleAbstractMethodPhase),
)

private class JvmMultiFieldValueClassLowering(context: JvmBackendContext) : JvmValueClassAbstractLowering(context) {
    private inner class SymbolsRemapper {
        private val symbol2getter = mutableMapOf<IrValueSymbol, ExpressionGenerator<Unit>>()
        private val symbol2setters = mutableMapOf<IrValueSymbol, List<ExpressionSupplier<Unit>?>>()
        private val knownExpressions = mutableMapOf<IrExpression, ImplementationAgnostic<Unit>>()

        fun remapSymbol(original: IrValueSymbol, replacement: IrValueDeclaration) {
            symbol2getter[original] = { irGet(replacement) }
            symbol2setters[original] = listOf(if (replacement.isAssignable) { _, value -> irSet(replacement, value) } else null)
        }

        fun remapSymbol(original: IrValueSymbol, unboxed: List<VirtualProperty<Unit>>): Unit =
            remapSymbol(original, replacements.getDeclarations(original.owner.type.erasedUpperBound)!!.ImplementationAgnostic(unboxed))

        fun remapSymbol(original: IrValueSymbol, unboxed: ImplementationAgnostic<Unit>) {
            symbol2getter[original] = {
                unboxed.boxedExpression(this, it).also { irExpression -> knownExpressions[irExpression] = unboxed }
            }
            symbol2setters[original] = unboxed.virtualFields.map { it.assigner }
        }

        fun IrBuilderWithScope.getter(original: IrValueSymbol): IrExpression? = symbol2getter[original]?.invoke(this, Unit)
        fun setter(original: IrValueSymbol): List<ExpressionSupplier<Unit>?>? = symbol2setters[original]

        fun implementationAgnostic(expression: IrExpression): ImplementationAgnostic<Unit>? = knownExpressions[expression]

        fun IrBuilderWithScope.subfield(expression: IrExpression, name: Name): IrExpression? =
            implementationAgnostic(expression)?.get(name)?.let { (expressionGenerator, representation) ->
                val res = expressionGenerator(this, Unit)
                representation?.let { knownExpressions[res] = it }
                res
            }

    }

    private val remapper = SymbolsRemapper()

    override val replacements
        get() = context.multiFieldValueClassReplacements

    override fun IrClass.isSpecificLoweringLogicApplicable(): Boolean = isMultiFieldValueClass

    override fun IrFunction.isFieldGetterToRemove(): Boolean = isMultiFieldValueClassOriginalFieldGetter

    override fun visitClassNew(declaration: IrClass): IrStatement {
//        // The arguments to the primary constructor are in scope in the initializers of IrFields.
//        declaration.primaryConstructor?.let {
//            replacements.getReplacementFunction(it)?.let { replacement -> addBindingsFor(it, replacement) }
//        } todo

        if (declaration.isSpecificLoweringLogicApplicable()) {
            handleSpecificNewClass(declaration)
        }

        declaration.transformDeclarationsFlat { memberDeclaration ->
            if (memberDeclaration is IrFunction) {
                withinScope(memberDeclaration) {
                    transformFunctionFlat(memberDeclaration)
                }
            } else {
                memberDeclaration.accept(this, null)
                null
            }
        }
        //todo flatten class

        return declaration
    }

    override fun handleSpecificNewClass(declaration: IrClass) {
        replacements.setOldFields(declaration, declaration.fields.toList())
        val newDeclarations = replacements.getDeclarations(declaration)!!
        if (newDeclarations.valueClass != declaration) error("Unexpected IrClass ${newDeclarations.valueClass} instead of $declaration")
        newDeclarations.replaceFields()
        newDeclarations.replaceProperties()
        newDeclarations.buildPrimaryMultiFieldValueClassConstructor()
        newDeclarations.buildBoxFunction()
        newDeclarations.buildUnboxFunctions()
        newDeclarations.buildSpecializedEqualsMethod()
    }

    override fun transformSecondaryConstructorFlat(constructor: IrConstructor, replacement: IrSimpleFunction): List<IrDeclaration> {
        replacement.valueParameters.forEach { it.transformChildrenVoid() }
        replacement.body = context.createIrBuilder(replacement.symbol).irBlockBody {
            val thisVar = irTemporary(irType = replacement.returnType, nameHint = "\$this")
            constructor.body?.statements?.forEach { statement ->
                +statement.transformStatement(object : IrElementTransformerVoid() {
                    override fun visitClass(declaration: IrClass): IrStatement = declaration

                    override fun visitDelegatingConstructorCall(expression: IrDelegatingConstructorCall): IrExpression {
                        val oldPrimaryConstructor = replacements.getDeclarations(constructor.constructedClass)!!.oldPrimaryConstructor
                        thisVar.initializer = irCall(oldPrimaryConstructor).apply {
                            copyTypeAndValueArgumentsFrom(expression)
                        }
                        return irBlock {}
                    }

                    override fun visitGetValue(expression: IrGetValue): IrExpression = when (expression.symbol.owner) {
                        constructor.constructedClass.thisReceiver!! -> irGet(thisVar)
                        else -> super.visitGetValue(expression)
                    }

                    override fun visitReturn(expression: IrReturn): IrExpression {
                        expression.transformChildrenVoid()
                        if (expression.returnTargetSymbol != constructor.symbol)
                            return expression

                        return irReturn(irBlock(expression.startOffset, expression.endOffset) {
                            +expression.value
                            +irGet(thisVar)
                        })
                    }
                })
            }
            +irReturn(irGet(thisVar))
        }
            .also { addBindingsFor(constructor, replacement) }
            .transform(this@JvmMultiFieldValueClassLowering, null)
            .patchDeclarationParents(replacement)
        return listOf(replacement)
    }

    private fun MultiFieldValueClassSpecificDeclarations.replaceFields() {
        valueClass.declarations.removeIf { it is IrField }
        valueClass.declarations += fields
        for (field in fields) {
            field.parent = valueClass
        }
    }

    private fun MultiFieldValueClassSpecificDeclarations.replaceProperties() {
        valueClass.declarations.removeIf { it is IrFunction && it.isFieldGetterToRemove() }
        properties.values.forEach {
            it.parent = valueClass
        }
        valueClass.declarations += properties.values.map { it.getter!!.apply { parent = valueClass } }
    }

    override fun createBridgeFunction(function: IrSimpleFunction, replacement: IrSimpleFunction): IrSimpleFunction? {
        return null // todo
        // todo change return type to non-nullable for base class
    }

    override fun addBindingsFor(original: IrFunction, replacement: IrFunction) {
        val parametersStructure = replacements.bindingParameterTemplateStructure[original]!!
        require(parametersStructure.size == original.explicitParameters.size) {
            "Wrong value parameters structure: $parametersStructure"
        }
        require(parametersStructure.sumOf { it.size } == replacement.explicitParameters.size) {
            "Wrong value parameters structure: $parametersStructure"
        }
        val old2newList = original.explicitParameters.zip(
            parametersStructure.scan(0) { partial: Int, templates: List<ValueParameterTemplate> -> partial + templates.size }
                .zipWithNext { start: Int, finish: Int -> replacement.explicitParameters.slice(start until finish) }
        )
        for ((param, newParamList) in old2newList) {
            val single = newParamList.singleOrNull()
            if (single != null) {
                remapper.remapSymbol(param.symbol, single)
            } else {
                remapper.remapSymbol(param.symbol, newParamList.map { VirtualProperty(it) })
            }
        }
    }

    fun MultiFieldValueClassSpecificDeclarations.buildPrimaryMultiFieldValueClassConstructor() {
        valueClass.declarations.removeIf { it is IrConstructor && it.isPrimary }
        val primaryConstructorReplacements = listOf(primaryConstructor, primaryConstructorImpl)
        for (exConstructor in primaryConstructorReplacements) {
            exConstructor.parent = valueClass
        }
        valueClass.declarations += primaryConstructorReplacements

        val initializersStatements = valueClass.declarations.filterIsInstance<IrAnonymousInitializer>().flatMap { it.body.statements }
        remapper.remapSymbol(
            oldPrimaryConstructor.constructedClass.thisReceiver!!.symbol,
            primaryConstructorImpl.valueParameters.map { VirtualProperty(it) }
        )
        primaryConstructorImpl.body = context.createIrBuilder(primaryConstructorImpl.symbol).irBlockBody {
            for (stmt in initializersStatements) {
                +stmt.transformStatement(this@JvmMultiFieldValueClassLowering).patchDeclarationParents(primaryConstructorImpl)
            }
        }
        valueClass.declarations.removeIf { it is IrAnonymousInitializer }
    }

    fun MultiFieldValueClassSpecificDeclarations.buildBoxFunction() {
        boxMethod.body = with(context.createIrBuilder(boxMethod.symbol)) {
            irExprBody(irCall(primaryConstructor.symbol).apply {
                passTypeArgumentsFrom(boxMethod)
                for (i in leaves.indices) {
                    putValueArgument(i, irGet(boxMethod.valueParameters[i]))
                }
            })
        }
        valueClass.declarations += boxMethod
        boxMethod.parent = valueClass
    }

    fun MultiFieldValueClassSpecificDeclarations.buildUnboxFunctions() {
        valueClass.declarations += unboxMethods
    }

    @Suppress("unused")
    fun MultiFieldValueClassSpecificDeclarations.buildSpecializedEqualsMethod() {
        // todo defaults
        specializedEqualsMethod.parent = valueClass
        specializedEqualsMethod.body = with(context.createIrBuilder(specializedEqualsMethod.symbol)) {
            // TODO: Revisit this once we allow user defined equals methods in inline/multi-field value classes.
            leaves.indices.map {
                val left = irGet(specializedEqualsMethod.valueParameters[it])
                val right = irGet(specializedEqualsMethod.valueParameters[it + leaves.size])
                irEquals(left, right)
            }.reduce { acc, current ->
                irCall(context.irBuiltIns.andandSymbol).apply {
                    putValueArgument(0, acc)
                    putValueArgument(1, current)
                }
            }.let { irExprBody(it) }
        }
        valueClass.declarations += specializedEqualsMethod
    }

    override fun visitFunctionReference(expression: IrFunctionReference): IrExpression {
        // todo implement
        return super.visitFunctionReference(expression)
    }

    override fun visitFunctionAccess(expression: IrFunctionAccessExpression): IrExpression {
        val function = expression.symbol.owner
        val replacement = replacements.getReplacementFunction(function)
        val currentScope = currentScope!!.irElement as IrDeclaration
        return when {
            function is IrConstructor && function.isPrimary && function.constructedClass.isMultiFieldValueClass &&
                    currentScope.origin != JvmLoweredDeclarationOrigin.SYNTHETIC_MULTI_FIELD_VALUE_CLASS_MEMBER -> {
                context.createIrBuilder(currentScope.symbol).irBlock {
                    val thisReplacement = irTemporary(expression)
                    +irGet(thisReplacement)
                }.transform(this, null) // transform with visitVariable
            }
            replacement != null -> buildReplacement(function, expression, replacement)
            else -> super.visitFunctionAccess(expression)
        }
    }

    override fun visitCall(expression: IrCall): IrExpression {
        val callee = expression.symbol.owner
        return when {
            callee.isMultiFieldValueClassOriginalFieldGetter -> {
                val receiver = expression.dispatchReceiver!!.transform(this, null)
                with(remapper) {
                    with(context.createIrBuilder(expression.symbol)) {
                        subfield(receiver, callee.correspondingPropertySymbol!!.owner.name) ?: super.visitCall(expression)
                    }
                }
            }
            expression.isSpecializedInlineClassEqEq -> {
                expression.transformChildrenVoid()
                // todo
                expression
            }
            else -> super.visitCall(expression)
        }
    }

    private fun buildReplacement(
        originalFunction: IrFunction,
        original: IrMemberAccessExpression<*>,
        replacement: IrSimpleFunction
    ): IrContainerExpression {
        val parameter2expression = typedArgumentList(originalFunction, original)
        return context.createIrBuilder(original.symbol).irBlock {
            val structure = replacements.bindingParameterTemplateStructure[originalFunction]!!
            require(parameter2expression.size == structure.size)
            require(structure.sumOf { it.size } == replacement.explicitParametersCount)
            val newArguments: List<IrExpression?> = List(parameter2expression.size) { i ->
                val expression = parameter2expression[i].second?.transform(this@JvmMultiFieldValueClassLowering, null)
                when {
                    expression == null -> List(structure[i].size) { null }
                    structure[i].size == 1 -> listOf(expression)
                    else -> when (val representation = remapper.implementationAgnostic(expression)) {
                        null -> {
                            val irVariable = irTemporary(expression)
                            val declarations = replacements.getDeclarations(irVariable.type.erasedUpperBound)
                            require(declarations != null) { "Cannot flatten ${irVariable.type.erasedUpperBound}" }
                            require(structure[i].size == declarations.leaves.size) { "Wrong flattened size ${structure[i].size} but got ${declarations.leaves.size}" }
                            declarations.unboxMethods.map {
                                irCall(it).apply {
                                    dispatchReceiver = irGet(irVariable)
                                }
                            }
                        }
                        else -> representation.regularDeclarations.leaves.map {
                            representation.nodeToExpressionGetters[it]!!.invoke(this, Unit)
                        }
                    }
                }
            }.flatten()
            +irCall(replacement.symbol).apply {
                copyTypeArgumentsFrom(original)
                for ((parameter, argument) in replacement.explicitParameters zip newArguments) {
                    if (argument == null) continue
                    putArgument(replacement, parameter, argument.transform(this@JvmMultiFieldValueClassLowering, null))
                }
            }
        }
    }

    // Note that reference equality (x === y) is not allowed on values of inline class type,
    // so it is enough to check for eqeq.
    private val IrCall.isSpecializedInlineClassEqEq: Boolean
        get() = symbol == context.irBuiltIns.eqeqSymbol &&
                getValueArgument(0)?.type?.classOrNull?.owner?.takeIf { it.isMultiFieldValueClass } != null

    override fun visitGetField(expression: IrGetField): IrExpression {
        val field = expression.symbol.owner
        val parent = field.parent
        if (field.origin == IrDeclarationOrigin.PROPERTY_BACKING_FIELD &&
            parent is IrClass &&
            parent.multiFieldValueClassRepresentation?.containsPropertyWithName(field.name) == true
        ) {
            val receiver = expression.receiver!!.transform(this, null)
            return with(remapper) {
                with(context.createIrBuilder(expression.symbol)) {
                    subfield(receiver, field.name) ?: super.visitGetField(expression)
                }
            }
        }
        return super.visitGetField(expression)
    }

    override fun visitGetValue(expression: IrGetValue): IrExpression = with(context.createIrBuilder(expression.symbol)) {
        with(remapper) {
            getter(expression.symbol) ?: super.visitGetValue(expression)
        }
    }

    override fun visitSetValue(expression: IrSetValue): IrExpression {
        val setters = remapper.setter(expression.symbol) ?: return super.visitSetValue(expression)
        return context.createIrBuilder(expression.symbol).irBlock {
            val declarations = replacements.getDeclarations(expression.symbol.owner.type.erasedUpperBound)!!
            val variables = declarations.leaves.map { irTemporary(irType = it.type) }
            // We flatten to temp variables because code can throw an exception otherwise and partially update variables
            flattenExpressionTo(expression.value, variables)
            for ((setter, variable) in setters zip variables) {
                setter?.invoke(this, Unit, irGet(variable))?.let { +it }
            }
        }
    }

    override fun visitSetField(expression: IrSetField): IrExpression {
        // todo implement
        return super.visitSetField(expression)
    }

    override fun visitVariable(declaration: IrVariable): IrStatement {
        val initializer = declaration.initializer
        if (declaration.type.isMultiFieldValueClassType() && !declaration.type.isNullable() &&
            (initializer?.let { it is IrConstructorCall && it.symbol.owner.isPrimary } != false)
        ) {
            val irClass = declaration.type.erasedUpperBound
            val declarations = replacements.getDeclarations(irClass)!!
            return context.createIrBuilder((currentFunction!!.irElement as IrFunction).symbol).irBlock {
                val variables = declarations.leaves.map { leaf ->
                    irTemporary(
                        nameHint = "${declaration.name.asString()}$${declarations.nodeFullNames[leaf]!!.asString()}",
                        irType = leaf.type,
                        isMutable = declaration.isVar
                    )
                }
                initializer?.let { flattenExpressionTo(it, variables) }
                remapper.remapSymbol(declaration.symbol, variables.map { VirtualProperty(it) })
            }
        }
        return super.visitVariable(declaration)
    }

    fun IrBlockBuilder.flattenExpressionTo(expression: IrExpression, variables: List<IrVariable>) {
        remapper.implementationAgnostic(expression)?.virtualFields?.map { it.makeGetter(this, Unit) }?.let {
            require(variables.size == it.size)
            for ((variable, subExpression) in variables zip it) {
                +irSet(variable, subExpression)
            }
            return
        }
        if (expression.type.isNullable() || !expression.type.isMultiFieldValueClassType()) {
            require(variables.size == 1)
            +irSet(variables.single(), expression.transform(this@JvmMultiFieldValueClassLowering, null))
            return
        }
        val declarations = replacements.getDeclarations(expression.type.erasedUpperBound)!!
        require(variables.size == declarations.leaves.size)
        if (expression is IrConstructorCall) {
            val constructor = expression.symbol.owner
            if (constructor.isPrimary && constructor.constructedClass.isMultiFieldValueClass) {
                val oldArguments = List(expression.valueArgumentsCount) { expression.getValueArgument(it) }
                val root = declarations.loweringRepresentation
                require(root.fields.size == oldArguments.size) {
                    "$constructor must have ${root.fields.size} arguments but got ${oldArguments.size}"
                }
                var curOffset = 0
                for ((treeField, argument) in root.fields zip oldArguments) {
                    val size = when (treeField.node) {
                        is MultiFieldValueClassTree.InternalNode -> replacements.getDeclarations(treeField.node.irClass!!)!!.leaves.size
                        is MultiFieldValueClassTree.Leaf -> 1
                    }
                    val subVariables = variables.slice(curOffset until (curOffset + size)).also { curOffset += size }
                    argument?.let { flattenExpressionTo(it, subVariables) } ?: List(size) { null }
                }
                +irCall(declarations.primaryConstructorImpl).apply {
                    copyTypeArgumentsFrom(expression)
                    variables.forEachIndexed { index, variable -> putValueArgument(index, irGet(variable)) }
                }
                return
            }
        }
        val boxed = irTemporary(expression.transform(this@JvmMultiFieldValueClassLowering, null))
        for ((variable, unboxMethod) in variables zip declarations.unboxMethods) {
            +irSet(variable, irCall(unboxMethod).apply { dispatchReceiver = irGet(boxed) })
        }

    }

    override fun visitStatementContainer(container: IrStatementContainer) {
        super.visitStatementContainer(container)
        visitStatementContainer(container.statements)
    }

    private fun visitStatementContainer(statements: MutableList<IrStatement>) {
        fun IrStatement.isBoxCallStatement() = this is IrCall && remapper.implementationAgnostic(this) != null
        statements.removeIf {
            it.isBoxCallStatement() ||
                    it is IrTypeOperatorCall && it.operator == IrTypeOperator.IMPLICIT_COERCION_TO_UNIT && it.argument.isBoxCallStatement()
        }
    }

}