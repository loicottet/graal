/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package org.graalvm.compiler.core.llvm;

import jdk.vm.ci.meta.ResolvedJavaMethod;
import org.graalvm.compiler.nodes.StructuredGraph;

/**
 * Provides compiler backend-specific generation helpers for the {@link LLVMCompilerBackend}.
 */
public interface LLVMGenerationProvider {
    LLVMGenerator newLLVMGenerator(LLVMGenerationResult result);

    NodeLLVMBuilder newNodeLLVMBuilder(StructuredGraph graph, LLVMGenerator generator);

    LLVMGenerationResult newLLVMGenerationResult(ResolvedJavaMethod method);
}
