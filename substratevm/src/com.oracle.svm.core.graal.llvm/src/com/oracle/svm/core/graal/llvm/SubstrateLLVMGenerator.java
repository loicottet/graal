/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.svm.core.graal.llvm;

import static com.oracle.svm.core.util.VMError.unimplemented;
import static org.graalvm.compiler.core.llvm.LLVMUtils.getVal;
import static org.graalvm.compiler.core.llvm.LLVMUtils.typeOf;

import org.bytedeco.javacpp.LLVM.LLVMContextRef;
import org.bytedeco.javacpp.LLVM.LLVMValueRef;
import org.graalvm.compiler.core.common.spi.ForeignCallDescriptor;
import org.graalvm.compiler.core.llvm.LLVMGenerationResult;
import org.graalvm.compiler.core.llvm.LLVMGenerator;
import org.graalvm.compiler.phases.util.Providers;

import com.oracle.svm.core.SubstrateUtil;
import com.oracle.svm.core.annotate.Uninterruptible;
import com.oracle.svm.core.graal.code.SubstrateLIRGenerator;
import com.oracle.svm.core.snippets.SnippetRuntime;
import org.graalvm.compiler.core.llvm.LLVMUtils.LLVMKindTool;

import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.Value;

public class SubstrateLLVMGenerator extends LLVMGenerator implements SubstrateLIRGenerator {
    SubstrateLLVMGenerator(Providers providers, LLVMGenerationResult generationResult, ResolvedJavaMethod method, LLVMContextRef context, int debugLevel) {
        super(providers, generationResult, new SubstrateLLVMIRBuilder(SubstrateUtil.uniqueShortName(method), context, shouldTrackPointers(method)),
                        new LLVMKindTool(context, shouldTrackPointers(method)), debugLevel);
    }

    private static boolean shouldTrackPointers(ResolvedJavaMethod method) {
        return !method.isAnnotationPresent(Uninterruptible.class);
    }

    @Override
    public Value emitReadInstructionPointer() {
        throw unimplemented();
    }

    @Override
    public void emitFarReturn(AllocatableValue result, Value sp, Value ip) {
        LLVMValueRef exceptionHolder = builder.getUniqueGlobal("__svm_exception_object", builder.referenceType());
        LLVMValueRef exceptionObject = builder.buildBitcast(getVal(result), builder.objectType(true));
        getBuilder().buildStore(exceptionObject, exceptionHolder);

        LLVMValueRef getSetJmpBuffer = builder.buildBitcast(getVal(ip), builder.pointerType(builder.functionType(builder.objectType(false)), false));
        LLVMValueRef buffer = builder.buildCall(getSetJmpBuffer);
        buffer = builder.buildBitcast(buffer, builder.pointerType(builder.arrayType(builder.longType(), 5), false));

        LLVMValueRef spAddr = builder.buildGEP(buffer, typeOf(buffer), builder.constantInt(0), builder.constantInt(2));
        builder.buildStore(getVal(sp), spAddr);

        getBuilder().buildLongjmp(builder.buildBitcast(buffer, builder.objectType(false)));
        getBuilder().buildUnreachable();
    }

    @Override
    public void emitDeadEnd() {
        emitPrintf("Dead end");
        getBuilder().buildUnreachable();
    }

    @Override
    protected ResolvedJavaMethod findForeignCallTarget(ForeignCallDescriptor descriptor) {
        SnippetRuntime.SubstrateForeignCallDescriptor substrateDesctiptor = (SnippetRuntime.SubstrateForeignCallDescriptor) descriptor;
        return substrateDesctiptor.findMethod(getMetaAccess());
    }

    @Override
    public String getFunctionName(ResolvedJavaMethod method) {
        return SubstrateUtil.uniqueShortName(method);
    }

    @Override
    protected boolean isMethodUninterruptible(ResolvedJavaMethod method) {
        return method.isAnnotationPresent(Uninterruptible.class);
    }
}
