/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package org.graalvm.compiler.core.llvm;

import org.graalvm.compiler.code.CompilationResult;
import org.graalvm.compiler.debug.DebugCloseable;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.debug.TimerKey;
import org.graalvm.compiler.nodes.StructuredGraph;

public class LLVMCompilerBackend {
    private static final TimerKey EmitLLVM = DebugContext.timer("EmitLLVM").doc("Time spent generating LLVM from HIR.");
    private static final TimerKey Populate = DebugContext.timer("EmitCode").doc("Time spent populating the compilation result.");
    private static final TimerKey BackEnd = DebugContext.timer("BackEnd").doc("Time spent in EmitLLVM and Populate.");

    @SuppressWarnings("try")
    public static void emitBackEnd(LLVMGenerationProvider backend, StructuredGraph graph, CompilationResult result) {
        DebugContext debug = graph.getDebug();
        try (DebugContext.Scope s = debug.scope("BackEnd", graph.getLastSchedule()); DebugCloseable a = BackEnd.start(debug)) {
            LLVMGenerationResult genRes = emitLLVM(backend, graph);
            result.setHasUnsafeAccess(graph.hasUnsafeAccess());
            try (DebugCloseable p = Populate.start(debug)) {
                genRes.populate(result, graph);
            }
        } catch (Throwable e) {
            throw debug.handle(e);
        } finally {
            graph.checkCancellation();
        }
    }

    @SuppressWarnings("try")
    private static LLVMGenerationResult emitLLVM(LLVMGenerationProvider backend, StructuredGraph graph) {
        DebugContext debug = graph.getDebug();
        try (DebugContext.Scope ds = debug.scope("EmitLLVM"); DebugCloseable a = EmitLLVM.start(debug)) {
            assert !graph.hasValueProxies();

            LLVMGenerationResult genResult = backend.newLLVMGenerationResult(graph.method());
            LLVMGenerator generator = backend.newLLVMGenerator(genResult);
            NodeLLVMBuilder nodeBuilder = backend.newNodeLLVMBuilder(graph, generator);

            /* LIR generation */
            LLVMGenerationPhase.run(nodeBuilder, graph);

            try (DebugContext.Scope s = debug.scope("LIRStages", nodeBuilder, genResult, null)) {
                /* Dump LIR along with HIR (the LIR is looked up from context) */
                debug.dump(DebugContext.BASIC_LEVEL, graph.getLastSchedule(), "After LIR generation");
                return genResult;
            } catch (Throwable e) {
                throw debug.handle(e);
            }
        } catch (Throwable e) {
            throw debug.handle(e);
        } finally {
            graph.checkCancellation();
        }
    }
}
