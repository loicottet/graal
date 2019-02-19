/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.svm.core.graal.llvm;

import java.util.HashMap;
import java.util.Map;

import org.graalvm.compiler.code.CompilationResult;
import org.graalvm.compiler.core.llvm.LLVMGenerationResult;
import org.graalvm.compiler.nodes.StructuredGraph;

import com.oracle.svm.core.graal.code.CGlobalDataReference;
import com.oracle.svm.core.graal.code.SubstrateDataBuilder;

import jdk.vm.ci.code.site.DataSectionReference;
import jdk.vm.ci.meta.ResolvedJavaMethod;

public class SubstrateLLVMGenerationResult extends LLVMGenerationResult {
    private Map<CGlobalDataReference, String> cGlobals = new HashMap<>();

    public SubstrateLLVMGenerationResult(ResolvedJavaMethod method) {
        super(method);
    }

    public void recordCGlobal(CGlobalDataReference reference, String symbolName) {
        cGlobals.put(reference, symbolName);
    }

    @Override
    public void populate(CompilationResult compilationResult, StructuredGraph graph) {
        super.populate(compilationResult, graph);

        cGlobals.forEach((reference, symbolName) -> compilationResult.recordDataPatchWithNote(0, reference, symbolName));
        SubstrateDataBuilder dataBuilder = new SubstrateDataBuilder();
        getConstants().forEach((constant, symbolName) -> {
            DataSectionReference reference = compilationResult.getDataSection().insertData(dataBuilder.createDataItem(constant));
            compilationResult.recordDataPatchWithNote(0, reference, symbolName);
        });
    }
}
