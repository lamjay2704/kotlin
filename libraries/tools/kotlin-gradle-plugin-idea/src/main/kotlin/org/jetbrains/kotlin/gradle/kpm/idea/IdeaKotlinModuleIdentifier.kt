/*
 * Copyright 2010-2022 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.gradle.kpm.idea

import java.io.Serializable

sealed interface IdeaKotlinModuleIdentifier : Serializable {
    val moduleClassifier: String?
}

interface IdeaKotlinLocalModuleIdentifier : IdeaKotlinModuleIdentifier {
    val buildId: String
    val projectId: String
}

interface IdeaKotlinMavenModuleIdentifier : IdeaKotlinModuleIdentifier {
    val group: String
    val name: String
}

data class IdeaKotlinLocalModuleIdentifierImpl @KotlinGradlePluginApi constructor(
    override val moduleClassifier: String?,
    override val buildId: String,
    override val projectId: String
) : IdeaKotlinLocalModuleIdentifier

data class IdeaKotlinMavenModuleIdentifierImpl @KotlinGradlePluginApi constructor(
    override val moduleClassifier: String?,
    override val group: String,
    override val name: String
) : IdeaKotlinMavenModuleIdentifier
