/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package org.graalvm.compiler.core.llvm;

import static org.graalvm.compiler.core.llvm.LLVMUtils.typeOf;
import static org.bytedeco.javacpp.LLVM.LLVMBasicBlockRef;
import static org.bytedeco.javacpp.LLVM.LLVMTypeRef;
import static org.bytedeco.javacpp.LLVM.LLVMValueRef;
import static org.graalvm.compiler.debug.GraalError.shouldNotReachHere;
import static org.graalvm.compiler.debug.GraalError.unimplemented;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import org.graalvm.compiler.core.common.calc.Condition;
import org.graalvm.compiler.core.common.cfg.BlockMap;
import org.graalvm.compiler.core.common.type.Stamp;
import org.graalvm.compiler.core.gen.DebugInfoBuilder;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.graph.GraalGraphError;
import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.graph.NodeInputList;
import org.graalvm.compiler.graph.NodeSourcePosition;
import org.graalvm.compiler.graph.iterators.NodeIterable;
import org.graalvm.compiler.lir.ConstantValue;
import org.graalvm.compiler.lir.LIRFrameState;
import org.graalvm.compiler.lir.Variable;
import org.graalvm.compiler.nodes.AbstractBeginNode;
import org.graalvm.compiler.nodes.AbstractEndNode;
import org.graalvm.compiler.nodes.AbstractMergeNode;
import org.graalvm.compiler.nodes.BreakpointNode;
import org.graalvm.compiler.nodes.DeoptimizingNode;
import org.graalvm.compiler.nodes.DirectCallTargetNode;
import org.graalvm.compiler.nodes.FrameState;
import org.graalvm.compiler.nodes.FullInfopointNode;
import org.graalvm.compiler.nodes.IfNode;
import org.graalvm.compiler.nodes.IndirectCallTargetNode;
import org.graalvm.compiler.nodes.Invoke;
import org.graalvm.compiler.nodes.InvokeWithExceptionNode;
import org.graalvm.compiler.nodes.LogicConstantNode;
import org.graalvm.compiler.nodes.LogicNode;
import org.graalvm.compiler.nodes.LoopEndNode;
import org.graalvm.compiler.nodes.LoweredCallTargetNode;
import org.graalvm.compiler.nodes.NodeView;
import org.graalvm.compiler.nodes.ParameterNode;
import org.graalvm.compiler.nodes.SafepointNode;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.ValuePhiNode;
import org.graalvm.compiler.nodes.calc.CompareNode;
import org.graalvm.compiler.nodes.calc.ConditionalNode;
import org.graalvm.compiler.nodes.calc.IntegerTestNode;
import org.graalvm.compiler.nodes.calc.IsNullNode;
import org.graalvm.compiler.nodes.cfg.Block;
import org.graalvm.compiler.nodes.extended.SwitchNode;
import org.graalvm.compiler.nodes.spi.LIRLowerable;
import org.graalvm.compiler.nodes.spi.NodeLIRBuilderTool;
import org.graalvm.compiler.nodes.spi.NodeValueMap;

import org.graalvm.compiler.core.llvm.LLVMUtils.DebugLevel;
import org.graalvm.compiler.core.llvm.LLVMUtils.LLVMAddressValue;
import org.graalvm.compiler.core.llvm.LLVMUtils.LLVMConstant;
import org.graalvm.compiler.core.llvm.LLVMUtils.LLVMValueWrapper;
import org.graalvm.compiler.core.llvm.LLVMUtils.LLVMVariable;

import jdk.vm.ci.code.CallingConvention;
import jdk.vm.ci.code.DebugInfo;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.PlatformKind;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.Value;

public class NodeLLVMBuilder implements NodeLIRBuilderTool {
    private final LLVMGenerator gen;
    private final DebugInfoBuilder debugInfoBuilder;

    private Map<Node, LLVMValueWrapper> valueMap = new HashMap<>();

    private Map<ValuePhiNode, LLVMValueRef> backwardsPhi = new HashMap<>();

    protected NodeLLVMBuilder(StructuredGraph graph, LLVMGenerator gen, BiFunction<NodeValueMap, DebugContext, DebugInfoBuilder> debugInfoProvider) {
        this.gen = gen;
        this.debugInfoBuilder = debugInfoProvider.apply(this, graph.getDebug());

        gen.getBuilder().addMainFunction(gen.getLLVMFunctionType(graph.method()));

        for (Block block : graph.getLastSchedule().getCFG().getBlocks()) {
            gen.appendBasicBlock(block);
        }
    }

    @Override
    public LLVMGenerator getLIRGeneratorTool() {
        return gen;
    }

    @Override
    public void doBlock(Block block, StructuredGraph graph, BlockMap<List<Node>> blockMap) {
        gen.beginBlock(block);
        if (block == graph.getLastSchedule().getCFG().getStartBlock()) {
            assert block.getPredecessorCount() == 0;

            long startPatchpointID = LLVMIRBuilder.nextPatchpointId.getAndIncrement();
            gen.getBuilder().buildStackmap(gen.getBuilder().constantLong(startPatchpointID));
            gen.getLLVMResult().setStartPatchpointID(startPatchpointID);

            for (ParameterNode param : graph.getNodes(ParameterNode.TYPE)) {
                LLVMValueRef paramValue = gen.getBuilder().getParam(param.index());
                LLVMValueRef castedParam = gen.getBuilder().buildConvert(paramValue, gen.getBuilder().getLLVMType(param.getStackKind()));
                setResult(param, castedParam);
            }

            if (gen.getDebugLevel() >= DebugLevel.FUNCTION) {
                List<JavaKind> printfTypes = new ArrayList<>();
                List<LLVMValueRef> printfArgs = new ArrayList<>();

                for (ParameterNode param : graph.getNodes(ParameterNode.TYPE)) {
                    printfTypes.add(param.getStackKind());
                    printfArgs.add(llvmOperand(param));
                }

                String functionName = gen.getBuilder().getFunctionName();
                gen.emitPrintf("In " + functionName, printfTypes.toArray(new JavaKind[0]), printfArgs.toArray(new LLVMValueRef[0]));
            }
        } else {
            assert block.getPredecessorCount() > 0;
            // create phi-in value array
            AbstractBeginNode begin = block.getBeginNode();
            if (begin instanceof AbstractMergeNode) {
                AbstractMergeNode merge = (AbstractMergeNode) begin;
                for (ValuePhiNode phiNode : merge.valuePhis()) {
                    List<LLVMValueRef> forwardPhis = new ArrayList<>();
                    List<LLVMBasicBlockRef> forwardBlocks = new ArrayList<>();
                    LLVMTypeRef phiType = gen.getBuilder().getLLVMType(gen.getTypeKind(phiNode.stamp(NodeView.DEFAULT).javaType(gen.getMetaAccess())));

                    boolean hasBackwardIncomingEdges = false;
                    for (Block predecessor : block.getPredecessors()) {
                        if (gen.getLLVMResult().isProcessed(predecessor)) {
                            ValueNode phiValue = phiNode.valueAt((AbstractEndNode) predecessor.getEndNode());
                            assert valueMap.containsKey(phiValue);
                            LLVMValueRef value = llvmOperand(phiValue);
                            LLVMBasicBlockRef parentBlock = gen.getBlockEnd(predecessor);

                            if (gen.getBuilder().compatibleTypes(typeOf(value), phiType)) {
                                forwardPhis.add(value);
                            } else {
                                gen.getBuilder().positionBefore(gen.getBuilder().blockTerminator(parentBlock));
                                LLVMValueRef castedValue = gen.getBuilder().buildConvert(value, phiType);
                                gen.getBuilder().positionAtEnd(gen.getBlockEnd((Block) gen.getCurrentBlock()));
                                forwardPhis.add(castedValue);
                            }
                            forwardBlocks.add(parentBlock);
                        } else {
                            hasBackwardIncomingEdges = true;
                        }
                    }

                    LLVMValueRef[] incomingValues = forwardPhis.toArray(new LLVMValueRef[0]);
                    LLVMBasicBlockRef[] incomingBlocks = forwardBlocks.toArray(new LLVMBasicBlockRef[0]);
                    LLVMValueRef phi = gen.getBuilder().buildPhi(phiType, incomingValues, incomingBlocks);

                    if (hasBackwardIncomingEdges) {
                        backwardsPhi.put(phiNode, phi);
                    }

                    setResult(phiNode, phi);
                }
            }
        }

        if (gen.getDebugLevel() >= DebugLevel.BLOCK) {
            gen.emitPrintf("In block " + block.toString());
        }

        for (Node node : blockMap.get(block)) {
            if (node instanceof ValueNode) {
                if (!valueMap.containsKey(node)) {
                    ValueNode valueNode = (ValueNode) node;
                    try {
                        if (gen.getDebugLevel() >= DebugLevel.NODE) {
                            gen.emitPrintf(valueNode.toString());
                        }
                        doRoot(valueNode);
                    } catch (GraalError e) {
                        throw GraalGraphError.transformAndAddContext(e, valueNode);
                    } catch (Throwable e) {
                        throw new GraalGraphError(e).addContext(valueNode);
                    }
                } else {
                    // There can be cases in which the result of an instruction is already set
                    // before by other instructions.
                }
            }
        }

        if (gen.getBuilder().blockTerminator(gen.getBlockEnd(block)) == null) {
            NodeIterable<Node> successors = block.getEndNode().successors();
            assert successors.count() == block.getSuccessorCount();
            if (block.getSuccessorCount() != 1) {
                /*
                 * If we have more than one successor, we cannot just use the first one. Since
                 * successors are unordered, this would be a random choice.
                 */
                throw new GraalError("Block without BlockEndOp: " + block.getEndNode());
            }
            gen.getBuilder().buildBranch(gen.getBlock(block.getFirstSuccessor()));
        }

        gen.getLLVMResult().setProcessed(block);
    }

    private void doRoot(ValueNode instr) {
        DebugContext debug = instr.getDebug();
        debug.log("Visiting %s", instr);
        emitNode(instr);
        debug.log("Operand for %s = %s", instr, operand(instr));
    }

    private void emitNode(ValueNode node) {
        if (node.getDebug().isLogEnabled() && node.stamp(NodeView.DEFAULT).isEmpty()) {
            node.getDebug().log("This node has an empty stamp, we are emitting dead code(?): %s", node);
        }
        setSourcePosition(node.getNodeSourcePosition());
        if (node instanceof LIRLowerable) {
            ((LIRLowerable) node).generate(this);
        } else {
            throw shouldNotReachHere("node is not LIRLowerable: " + node);
        }
    }

    void finish() {
        gen.getLLVMResult().setModule(gen.getBuilder().getModule());
    }

    @Override
    public void emitIf(IfNode i) {
        LLVMValueRef condition = emitCondition(i.condition());
        LLVMBasicBlockRef thenBlock = gen.getBlock(i.trueSuccessor());
        LLVMBasicBlockRef elseBlock = gen.getBlock(i.falseSuccessor());
        LLVMValueRef instr = gen.getBuilder().buildIf(condition, thenBlock, elseBlock);

        int trueProbability = expandProbability(i.getTrueSuccessorProbability());
        int falseProbability = expandProbability(1 - i.getTrueSuccessorProbability());
        LLVMValueRef branchWeights = gen.getBuilder().buildBranchWeightsMetadata(gen.getBuilder().constantInt(trueProbability), gen.getBuilder().constantInt(falseProbability));
        gen.getBuilder().setMetadata(instr, "prof", branchWeights);
    }

    private int expandProbability(double probability) {
        return (int) (probability * Integer.MAX_VALUE);
    }

    private LLVMValueRef emitCondition(LogicNode condition) {
        if (condition instanceof IsNullNode) {
            return gen.getBuilder().buildIsNull(llvmOperand(((IsNullNode) condition).getValue()));
        }
        if (condition instanceof LogicConstantNode) {
            return gen.getBuilder().constantBoolean(((LogicConstantNode) condition).getValue());
        }
        if (condition instanceof CompareNode) {
            CompareNode compareNode = (CompareNode) condition;
            return gen.getBuilder().buildCompare(compareNode.condition().asCondition(), llvmOperand(compareNode.getX()), llvmOperand(compareNode.getY()), false);
        }
        if (condition instanceof IntegerTestNode) {
            IntegerTestNode integerTestNode = (IntegerTestNode) condition;
            LLVMValueRef and = gen.getBuilder().buildAnd(llvmOperand(integerTestNode.getX()), llvmOperand(integerTestNode.getY()));
            return gen.getBuilder().buildIsNull(and);
        }
        throw shouldNotReachHere("logic node: " + condition.getClass().getName());
    }

    @Override
    public void emitConditional(ConditionalNode conditional) {
        Value tVal = operand(conditional.trueValue());
        Value fVal = operand(conditional.falseValue());
        setResult(conditional, emitConditional(conditional.condition(), tVal, fVal));
    }

    private Variable emitConditional(LogicNode node, Value trueValue, Value falseValue) {
        if (node instanceof IsNullNode) {
            IsNullNode isNullNode = (IsNullNode) node;
            return gen.emitIsNullMove(operand(isNullNode.getValue()), trueValue, falseValue);
        } else if (node instanceof CompareNode) {
            CompareNode compare = (CompareNode) node;
            PlatformKind kind = gen.getLIRKind(compare.getX().stamp(NodeView.DEFAULT)).getPlatformKind();
            return gen.emitConditionalMove(kind, operand(compare.getX()), operand(compare.getY()), compare.condition().asCondition(), compare.unorderedIsTrue(), trueValue, falseValue);
        } else if (node instanceof LogicConstantNode) {
            return gen.emitMove(((LogicConstantNode) node).getValue() ? trueValue : falseValue);
        } else if (node instanceof IntegerTestNode) {
            IntegerTestNode test = (IntegerTestNode) node;
            return gen.emitIntegerTestMove(operand(test.getX()), operand(test.getY()), trueValue, falseValue);
        } else {
            throw unimplemented(node.toString());
        }
    }

    @Override
    public void emitSwitch(SwitchNode switchNode) {
        int numCases = switchNode.keyCount();
        LLVMValueRef[] values = new LLVMValueRef[numCases];
        LLVMBasicBlockRef[] blocks = new LLVMBasicBlockRef[numCases];
        LLVMValueRef[] weights = new LLVMValueRef[numCases + 1];
        int defaultProbability = expandProbability(switchNode.probability(switchNode.defaultSuccessor()));
        weights[0] = gen.getBuilder().constantInt(defaultProbability);

        for (int i = 0; i < numCases; ++i) {
            JavaConstant key = (JavaConstant) switchNode.keyAt(i);
            values[i] = gen.getBuilder().constantInt(key.asInt());
            blocks[i] = gen.getBlock(switchNode.keySuccessor(i));
            int keyProbability = expandProbability(switchNode.probability(switchNode.keySuccessor(i)));
            weights[i + 1] = gen.getBuilder().constantInt(keyProbability);
        }

        LLVMValueRef switchInstr = gen.getBuilder().buildSwitch(llvmOperand(switchNode.value()), gen.getBlock(switchNode.defaultSuccessor()), values, blocks);

        LLVMValueRef branchWeights = gen.getBuilder().buildBranchWeightsMetadata(weights);
        gen.getBuilder().setMetadata(switchInstr, "prof", branchWeights);
    }

    @Override
    public void emitInvoke(Invoke i) {
        LoweredCallTargetNode callTarget = (LoweredCallTargetNode) i.callTarget();
        ResolvedJavaMethod targetMethod = callTarget.targetMethod();
        NodeInputList<ValueNode> arguments = callTarget.arguments();

        LLVMValueRef callee;
        LLVMValueRef[] args;
        args = gen.getLLVMFunctionArgs(targetMethod, id -> llvmOperand(arguments.get(id)), arguments.size());

        LIRFrameState state = state(i);
        state.initDebugInfo(null, false);
        DebugInfo debugInfo = state.debugInfo();
        long patchpointId = LLVMIRBuilder.nextPatchpointId.getAndIncrement();
        if (callTarget instanceof DirectCallTargetNode) {
            callee = gen.getFunction(targetMethod);
            gen.getLLVMResult().recordDirectCall(targetMethod, patchpointId, debugInfo);
        } else if (callTarget instanceof IndirectCallTargetNode) {
            LLVMValueRef computedAddress = llvmOperand(((IndirectCallTargetNode) callTarget).computedAddress());
            if (targetMethod != null) {
                callee = gen.getBuilder().buildBitcast(computedAddress,
                                gen.getBuilder().pointerType(gen.getLLVMFunctionType(targetMethod, arguments.size()), gen.getBuilder().isReference(computedAddress)));
                gen.getLLVMResult().recordIndirectCall(targetMethod, patchpointId, debugInfo);
            } else {
                LLVMTypeRef returnType = gen.getBuilder().getLLVMType(callTarget.returnStamp().getTrustedStamp().getStackKind());
                LLVMTypeRef[] argTypes = Arrays.stream(callTarget.signature()).map(argType -> gen.getBuilder().getLLVMType(gen.getTypeKind(argType.resolve(null)))).toArray(LLVMTypeRef[]::new);
                callee = gen.getBuilder().buildBitcast(computedAddress,
                                gen.getBuilder().pointerType(gen.getBuilder().functionType(returnType, argTypes), gen.getBuilder().isReference(computedAddress)));

                assert args.length == argTypes.length;
                for (int j = 0; j < args.length; ++j) {
                    args[j] = gen.getBuilder().buildConvert(args[j], argTypes[j]);
                }
            }

            if (gen.getDebugLevel() >= DebugLevel.NODE) {
                gen.emitPrintf("Indirect call to " + ((targetMethod != null) ? targetMethod.getName() : "[unknown]"), new JavaKind[]{JavaKind.Object}, new LLVMValueRef[]{callee});
            }
        } else {
            throw shouldNotReachHere();
        }

        LLVMValueRef call;
        if (i instanceof InvokeWithExceptionNode) {
            InvokeWithExceptionNode invokeWithExceptionNode = (InvokeWithExceptionNode) i;
            LLVMBasicBlockRef successor = gen.getBlock(invokeWithExceptionNode.next());
            LLVMBasicBlockRef handler = gen.getBlock(invokeWithExceptionNode.exceptionEdge());

            Block currentBlock = (Block) gen.getCurrentBlock();
            LLVMBasicBlockRef callBlock = gen.getBuilder().appendBasicBlock(currentBlock.toString() + "_invoke");
            gen.splitBlockEndMap.put(currentBlock, callBlock);

            LLVMValueRef setjmpBuffer = gen.getBuilder().getUniqueGlobal(gen.getBuilder().getFunctionName() + "_setjmp_buffer_" + patchpointId,
                            gen.getBuilder().arrayType(gen.getBuilder().longType(), 5));
            LLVMValueRef fpAddr = gen.getBuilder().buildGEP(setjmpBuffer, typeOf(setjmpBuffer), gen.getBuilder().constantInt(0), gen.getBuilder().constantInt(0));
            LLVMValueRef framePointer = gen.getBuilder().buildFrameAddress(gen.getBuilder().constantInt(0));
            gen.getBuilder().buildStore(framePointer, fpAddr);

            LLVMValueRef status = gen.getBuilder().buildSetjmp(gen.getBuilder().buildBitcast(setjmpBuffer, gen.getBuilder().objectType(false)));
            LLVMValueRef zeroStatus = gen.getBuilder().buildCompare(Condition.EQ, status, gen.getBuilder().constantInt(0), false);
            gen.getBuilder().buildIf(zeroStatus, callBlock, handler);

            gen.getBuilder().positionAtEnd(callBlock);
            call = gen.getBuilder().buildCall(callee, patchpointId, args);
            gen.getBuilder().buildBranch(successor);

            gen.getLLVMResult().recordExceptionHandler(patchpointId, patchpointId);

            /* Get buffer function */
            LLVMValueRef getBufferFunc = gen.getBuilder().addFunction(gen.getBuilder().getFunctionName() + "_get_setjmp_buffer_" + patchpointId,
                            gen.getBuilder().functionType(gen.getBuilder().objectType(false)));
            gen.getBuilder().setAttribute(getBufferFunc, "noinline");

            LLVMBasicBlockRef getBufferMainBlock = gen.getBuilder().appendBasicBlock("main", getBufferFunc);
            gen.getBuilder().positionAtEnd(getBufferMainBlock);
            gen.getBuilder().buildRet(setjmpBuffer, getBufferFunc);
        } else {
            call = gen.getBuilder().buildCall(callee, patchpointId, args);
        }

        setResult(i.asNode(), call);
    }

    @Override
    public void emitReadExceptionObject(ValueNode node) {
        setResult(node, gen.getBuilder().buildLoad(gen.getBuilder().getUniqueGlobal("__svm_exception_object", gen.getBuilder().referenceType()), gen.getBuilder().objectType(true)));
    }

    @Override
    public void visitMerge(AbstractMergeNode i) {
        /* Handled in doBlock */
    }

    @Override
    public void visitEndNode(AbstractEndNode i) {
        LLVMBasicBlockRef nextBlock = gen.getBlock(i.merge());
        gen.getBuilder().buildBranch(nextBlock);
    }

    @Override
    public void visitLoopEnd(LoopEndNode i) {
        LLVMBasicBlockRef[] basicBlocks = new LLVMBasicBlockRef[]{gen.getBlockEnd((Block) gen.getCurrentBlock())};
        for (ValuePhiNode phiNode : i.merge().valuePhis()) {
            LLVMValueRef phi = backwardsPhi.get(phiNode);

            LLVMValueRef value = llvmOperand(phiNode.valueAt(i));
            value = gen.getBuilder().buildConvert(value, typeOf(phi));

            LLVMValueRef[] values = new LLVMValueRef[]{value};
            gen.getBuilder().addIncoming(phi, values, basicBlocks);
        }
    }

    @Override
    public void visitSafepointNode(SafepointNode i) {
        throw unimplemented();
    }

    @Override
    public void visitBreakpointNode(BreakpointNode i) {
        if (gen.getDebugLevel() >= DebugLevel.FUNCTION) {
            gen.emitPrintf("breakpoint");
        }
        gen.getBuilder().buildDebugtrap();
    }

    @Override
    public void visitFullInfopointNode(FullInfopointNode i) {
        throw unimplemented();
    }

    @Override
    public void setSourcePosition(NodeSourcePosition position) {
    }

    @Override
    public LIRFrameState state(DeoptimizingNode deopt) {
        if (!deopt.canDeoptimize()) {
            return null;
        }

        if (gen.needOnlyOopMaps()) {
            return new LIRFrameState(null, null, null);
        }

        FrameState state;
        if (deopt instanceof DeoptimizingNode.DeoptBefore) {
            assert !(deopt instanceof DeoptimizingNode.DeoptDuring || deopt instanceof DeoptimizingNode.DeoptAfter);
            state = ((DeoptimizingNode.DeoptBefore) deopt).stateBefore();
        } else if (deopt instanceof DeoptimizingNode.DeoptDuring) {
            assert !(deopt instanceof DeoptimizingNode.DeoptAfter);
            state = ((DeoptimizingNode.DeoptDuring) deopt).stateDuring();
        } else {
            assert deopt instanceof DeoptimizingNode.DeoptAfter;
            state = ((DeoptimizingNode.DeoptAfter) deopt).stateAfter();
        }
        assert state != null;
        return debugInfoBuilder.build(state, null);
    }

    @Override
    public void emitOverflowCheckBranch(AbstractBeginNode overflowSuccessor, AbstractBeginNode next, Stamp compareStamp, double probability) {
        throw unimplemented();
    }

    @Override
    public Value[] visitInvokeArguments(CallingConvention cc, Collection<ValueNode> arguments) {
        throw unimplemented();
    }

    @Override
    public Value operand(Node node) {
        return (Value) valueMap.get(node);
    }

    private LLVMValueRef llvmOperand(Node node) {
        assert valueMap.containsKey(node);
        return valueMap.get(node).get();
    }

    @Override
    public boolean hasOperand(Node node) {
        return valueMap.containsKey(node);
    }

    protected void setResult(ValueNode node, LLVMValueRef operand) {
        setResult(node, new LLVMVariable(operand));
    }

    @Override
    public Value setResult(ValueNode node, Value operand) {
        LLVMValueWrapper llvmOperand;
        if (operand instanceof ConstantValue) {
            ConstantValue constantValue = (ConstantValue) operand;
            assert constantValue.isJavaConstant();
            JavaConstant constant = ((ConstantValue) operand).getJavaConstant();

            llvmOperand = (LLVMConstant) gen.emitJavaConstant(constant);
        } else if (operand instanceof LLVMAddressValue) {
            LLVMAddressValue addressValue = (LLVMAddressValue) operand;
            LLVMValueRef base = LLVMUtils.getVal(addressValue.getBase());
            Value index = addressValue.getIndex();

            LLVMValueRef intermediate;
            if (index == null || index == Value.ILLEGAL) {
                intermediate = base;
            } else {
                LLVMTypeRef resultType = gen.getBuilder().objectType((gen.getBuilder().isReference(base)));
                intermediate = gen.getBuilder().buildGEP(base, resultType, LLVMUtils.getVal(index));
            }

            llvmOperand = new LLVMVariable(intermediate);
        } else {
            llvmOperand = (LLVMValueWrapper) operand;
        }
        valueMap.put(node, llvmOperand);
        return operand;
    }

    @Override
    public ValueNode valueForOperand(Value value) {
        throw unimplemented();
    }
}
