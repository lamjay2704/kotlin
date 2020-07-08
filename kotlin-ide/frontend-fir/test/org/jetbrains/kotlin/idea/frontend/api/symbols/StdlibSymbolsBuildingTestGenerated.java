/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.frontend.api.symbols;

import com.intellij.testFramework.TestDataPath;
import org.jetbrains.kotlin.test.JUnit3RunnerWithInners;
import org.jetbrains.kotlin.test.KotlinTestUtils;
import org.jetbrains.kotlin.test.TestMetadata;
import org.jetbrains.kotlin.test.TestRoot;
import org.junit.runner.RunWith;

/*
 * This class is generated by {@link org.jetbrains.kotlin.generators.tests.TestsPackage}.
 * DO NOT MODIFY MANUALLY.
 */
@SuppressWarnings("all")
@TestRoot("frontend-fir")
@TestDataPath("$CONTENT_ROOT")
@RunWith(JUnit3RunnerWithInners.class)
@TestMetadata("testData/stdLibSymbols")
public class StdlibSymbolsBuildingTestGenerated extends AbstractStdlibSymbolsBuildingTest {
    private void runTest(String testDataFilePath) throws Exception {
        KotlinTestUtils.runTest(this::doTest, this, testDataFilePath);
    }

    @TestMetadata("fileWalkDirectionEnum.txt")
    public void testFileWalkDirectionEnum() throws Exception {
        runTest("testData/stdLibSymbols/fileWalkDirectionEnum.txt");
    }

    @TestMetadata("iterator.txt")
    public void testIterator() throws Exception {
        runTest("testData/stdLibSymbols/iterator.txt");
    }

    @TestMetadata("listOf.txt")
    public void testListOf() throws Exception {
        runTest("testData/stdLibSymbols/listOf.txt");
    }
}
