/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.svm.core.graal.llvm;

import org.bytedeco.javacpp.LLVM.LLVMValueRef;
import org.graalvm.compiler.core.llvm.LLVMGenerator;
import org.graalvm.compiler.core.llvm.LLVMUtils;
import org.graalvm.compiler.core.llvm.NodeLLVMBuilder;
import org.graalvm.compiler.lir.Variable;
import org.graalvm.compiler.nodes.StructuredGraph;

import com.oracle.svm.core.graal.code.CGlobalDataInfo;
import com.oracle.svm.core.graal.code.CGlobalDataReference;
import com.oracle.svm.core.graal.code.SubstrateNodeLIRBuilder;
import com.oracle.svm.core.graal.nodes.CGlobalDataLoadAddressNode;

public class SubstrateNodeLLVMBuilder extends NodeLLVMBuilder implements SubstrateNodeLIRBuilder {
    private long nextCGlobalId = 0L;

    protected SubstrateNodeLLVMBuilder(StructuredGraph graph, LLVMGenerator gen) {
        super(graph, gen, SubstrateLLVMBackend.SubstrateDebugInfoBuilder::new);
    }

    @Override
    public void emitCGlobalDataLoadAddress(CGlobalDataLoadAddressNode node) {
        LLVMGenerator gen = getLIRGeneratorTool();
        CGlobalDataInfo dataInfo = node.getDataInfo();

        String symbolName = "global_" + gen.getBuilder().getFunctionName() + "#" + nextCGlobalId++;
        ((SubstrateLLVMGenerationResult) gen.getLLVMResult()).recordCGlobal(new CGlobalDataReference(dataInfo), symbolName);

        if (dataInfo.isSymbolReference()) {
            setResult(node, gen.getBuilder().getExternalGlobal(dataInfo.getData().symbolName, false));
        } else {
            LLVMValueRef placeholder = gen.getBuilder().getExternalGlobal(symbolName, true);
            setResult(node, placeholder);
        }
    }

    @Override
    public Variable emitReadReturnAddress() {
        LLVMValueRef returnAddress = getLIRGeneratorTool().getBuilder().buildReturnAddress(getLIRGeneratorTool().getBuilder().constantInt(0));
        return new LLVMUtils.LLVMVariable(returnAddress);
    }
}
