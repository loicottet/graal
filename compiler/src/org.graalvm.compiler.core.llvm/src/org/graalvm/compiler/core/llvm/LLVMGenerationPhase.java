/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package org.graalvm.compiler.core.llvm;

import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.StructuredGraph.ScheduleResult;
import org.graalvm.compiler.nodes.cfg.Block;

public class LLVMGenerationPhase {
    protected static void run(NodeLLVMBuilder nodeBuilder, StructuredGraph graph) {
        ScheduleResult schedule = graph.getLastSchedule();
        LLVMGenerationResult genResult = nodeBuilder.getLIRGeneratorTool().getLLVMResult();
        for (Block b : schedule.getCFG().getBlocks()) {
            assert !genResult.isProcessed(b) : "Block already processed " + b;
            assert verifyPredecessors(genResult, b);
            nodeBuilder.doBlock(b, graph, schedule.getBlockToNodesMap());
        }
        nodeBuilder.finish();
    }

    private static boolean verifyPredecessors(LLVMGenerationResult genResult, Block block) {
        for (Block pred : block.getPredecessors()) {
            if (!block.isLoopHeader() || !pred.isLoopEnd()) {
                assert genResult.isProcessed(pred) : "Predecessor not yet processed " + pred;
            }
        }
        return true;
    }

}
