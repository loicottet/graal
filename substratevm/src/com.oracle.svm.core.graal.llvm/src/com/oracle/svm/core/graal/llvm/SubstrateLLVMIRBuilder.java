/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.svm.core.graal.llvm;

import static org.bytedeco.javacpp.LLVM.LLVMAddAlias;
import static org.bytedeco.javacpp.LLVM.LLVMTypeOf;

import org.bytedeco.javacpp.LLVM.LLVMContextRef;
import org.bytedeco.javacpp.LLVM.LLVMTypeRef;

import com.oracle.svm.core.SubstrateUtil;
import org.graalvm.compiler.core.llvm.LLVMIRBuilder;

public class SubstrateLLVMIRBuilder extends LLVMIRBuilder {
    SubstrateLLVMIRBuilder(String functionName, LLVMContextRef context, boolean shouldTrackPointers) {
        super(functionName, context, shouldTrackPointers);
    }

    public void addMainFunction(LLVMTypeRef type) {
        super.addMainFunction(type);
        LLVMAddAlias(getModule(), LLVMTypeOf(getMainFunction()), getMainFunction(), SubstrateUtil.mangleName(getFunctionName()));
    }
}
