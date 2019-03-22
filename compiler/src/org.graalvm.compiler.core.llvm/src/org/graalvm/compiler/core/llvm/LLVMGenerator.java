/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package org.graalvm.compiler.core.llvm;

import static org.graalvm.compiler.core.llvm.LLVMUtils.getVal;
import static org.graalvm.compiler.core.llvm.LLVMUtils.typeOf;
import static org.bytedeco.javacpp.LLVM.LLVMBasicBlockRef;
import static org.bytedeco.javacpp.LLVM.LLVMTypeRef;
import static org.bytedeco.javacpp.LLVM.LLVMValueRef;
import static org.graalvm.compiler.debug.GraalError.shouldNotReachHere;
import static org.graalvm.compiler.debug.GraalError.unimplemented;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.IntStream;

import org.graalvm.compiler.core.common.CompressEncoding;
import org.graalvm.compiler.core.common.LIRKind;
import org.graalvm.compiler.core.common.calc.Condition;
import org.graalvm.compiler.core.common.calc.FloatConvert;
import org.graalvm.compiler.core.common.cfg.AbstractBlockBase;
import org.graalvm.compiler.core.common.spi.CodeGenProviders;
import org.graalvm.compiler.core.common.spi.ForeignCallDescriptor;
import org.graalvm.compiler.core.common.spi.ForeignCallLinkage;
import org.graalvm.compiler.core.common.spi.ForeignCallsProvider;
import org.graalvm.compiler.core.common.spi.LIRKindTool;
import org.graalvm.compiler.core.common.type.Stamp;
import org.graalvm.compiler.core.common.type.StampFactory;
import org.graalvm.compiler.graph.NodeSourcePosition;
import org.graalvm.compiler.lir.LIRFrameState;
import org.graalvm.compiler.lir.LIRInstruction;
import org.graalvm.compiler.lir.LabelRef;
import org.graalvm.compiler.lir.StandardOp;
import org.graalvm.compiler.lir.SwitchStrategy;
import org.graalvm.compiler.lir.Variable;
import org.graalvm.compiler.lir.VirtualStackSlot;
import org.graalvm.compiler.lir.gen.ArithmeticLIRGeneratorTool;
import org.graalvm.compiler.lir.gen.LIRGenerationResult;
import org.graalvm.compiler.lir.gen.LIRGeneratorTool;
import org.graalvm.compiler.nodes.AbstractBeginNode;
import org.graalvm.compiler.nodes.cfg.Block;
import org.graalvm.compiler.phases.util.Providers;
import org.graalvm.word.WordBase;

import org.graalvm.compiler.core.llvm.LLVMUtils.DebugLevel;
import org.graalvm.compiler.core.llvm.LLVMUtils.LLVMConstant;
import org.graalvm.compiler.core.llvm.LLVMUtils.LLVMKind;
import org.graalvm.compiler.core.llvm.LLVMUtils.LLVMStackSlot;
import org.graalvm.compiler.core.llvm.LLVMUtils.LLVMValueWrapper;
import org.graalvm.compiler.core.llvm.LLVMUtils.LLVMVariable;

import jdk.vm.ci.code.CodeCacheProvider;
import jdk.vm.ci.code.Register;
import jdk.vm.ci.code.RegisterAttributes;
import jdk.vm.ci.code.RegisterConfig;
import jdk.vm.ci.code.RegisterValue;
import jdk.vm.ci.code.StackSlot;
import jdk.vm.ci.code.TargetDescription;
import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.JavaConstant;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.MetaAccessProvider;
import jdk.vm.ci.meta.PlatformKind;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;
import jdk.vm.ci.meta.Signature;
import jdk.vm.ci.meta.Value;
import jdk.vm.ci.meta.ValueKind;

public class LLVMGenerator implements LIRGeneratorTool {
    private final ArithmeticLLVMGenerator arithmetic;
    protected final LLVMIRBuilder builder;
    private final LIRKindTool lirKindTool;
    private final Providers providers;
    private final LLVMGenerationResult generationResult;

    private Block currentBlock;

    private Map<AbstractBeginNode, LLVMBasicBlockRef> basicBlockMap = new HashMap<>();
    Map<Block, LLVMBasicBlockRef> splitBlockEndMap = new HashMap<>();

    private final int debugLevel;

    public LLVMGenerator(Providers providers, LLVMGenerationResult generationResult, LLVMIRBuilder builder, LIRKindTool lirKindTool, int debugLevel) {
        this.providers = providers;
        this.generationResult = generationResult;

        this.builder = builder;
        this.arithmetic = new ArithmeticLLVMGenerator(builder);
        this.lirKindTool = lirKindTool;

        this.debugLevel = debugLevel;
    }

    public LLVMIRBuilder getBuilder() {
        return builder;
    }

    void appendBasicBlock(Block block) {
        LLVMBasicBlockRef basicBlock = builder.appendBasicBlock(block.toString());
        basicBlockMap.put(block.getBeginNode(), basicBlock);
    }

    LLVMBasicBlockRef getBlock(Block block) {
        return getBlock(block.getBeginNode());
    }

    LLVMBasicBlockRef getBlock(AbstractBeginNode begin) {
        return basicBlockMap.get(begin);
    }

    LLVMBasicBlockRef getBlockEnd(Block block) {
        return (splitBlockEndMap.containsKey(block)) ? splitBlockEndMap.get(block) : getBlock(block);
    }

    void beginBlock(Block block) {
        currentBlock = block;
        builder.positionAtEnd(getBlock(block));
    }

    protected JavaKind getTypeKind(ResolvedJavaType type) {
        if (getMetaAccess().lookupJavaType(WordBase.class).isAssignableFrom(type)) {
            return JavaKind.Long;
        } else {
            return type.getJavaKind();
        }
    }

    LLVMValueRef getFunction(ResolvedJavaMethod method) {
        return builder.getFunction(getFunctionName(method), getLLVMFunctionType(method));
    }

    public String getFunctionName(ResolvedJavaMethod method) {
        return method.getName();
    }

    LLVMTypeRef getLLVMFunctionType(ResolvedJavaMethod method, int numArguments) {
        Signature signature = method.getSignature();
        if (signature.getParameterCount(true) == numArguments) {
            return getLLVMFunctionType(method, method.getDeclaringClass());
        } else if (signature.getParameterCount(false) == numArguments) {
            return getLLVMFunctionType(method, null);
        } else {
            throw shouldNotReachHere();
        }
    }

    public LLVMTypeRef getLLVMFunctionType(ResolvedJavaMethod method) {
        ResolvedJavaType accessingClass = method.hasReceiver() ? method.getDeclaringClass() : null;
        return getLLVMFunctionType(method, accessingClass);
    }

    private LLVMTypeRef getLLVMFunctionType(ResolvedJavaMethod method, ResolvedJavaType accessingClass) {
        Signature signature = method.getSignature();
        boolean uninterruptible = isMethodUninterruptible(method);

        LLVMTypeRef returnType = builder.getLLVMType(getTypeKind(signature.getReturnType(accessingClass).resolve(null)));
        LLVMTypeRef[] argTypes = Arrays.stream(signature.toParameterTypes(accessingClass)).map(type -> type.resolve(null))
                        .map(this::getTypeKind).map(uninterruptible ? builder::getLLVMTypeNoRef : builder::getLLVMTypeForceRef).toArray(LLVMTypeRef[]::new);
        return builder.functionType(returnType, argTypes);
    }

    LLVMValueRef[] getLLVMFunctionArgs(ResolvedJavaMethod targetMethod, Function<Integer, LLVMValueRef> arguments, int numArguments) {
        if (targetMethod == null) {
            /* C function invocation, no need to enforce parameter types */
            return IntStream.range(0, numArguments).mapToObj(arguments::apply).toArray(LLVMValueRef[]::new);
        }
        boolean uninterruptible = isMethodUninterruptible(targetMethod);
        boolean hasReceiver = targetMethod.getSignature().getParameterCount(true) == numArguments;
        return IntStream.range(0, numArguments).mapToObj(i -> {
            ResolvedJavaType expectedType;
            if (hasReceiver && i == 0) {
                expectedType = targetMethod.getDeclaringClass();
            } else {
                int paramId = (hasReceiver) ? i - 1 : i;
                expectedType = targetMethod.getSignature().getParameterType(paramId, targetMethod.getDeclaringClass()).resolve(targetMethod.getDeclaringClass());
            }

            LLVMValueRef value = arguments.apply(i);
            LLVMTypeRef type;
            if (uninterruptible) {
                type = builder.getLLVMTypeNoRef(getTypeKind(expectedType));
            } else {
                type = builder.getLLVMTypeForceRef(getTypeKind(expectedType));
            }
            return builder.buildConvert(value, type);
        }).toArray(LLVMValueRef[]::new);
    }

    protected boolean isMethodUninterruptible(ResolvedJavaMethod method) {
        throw unimplemented();
    }

    private long nextConstantId = 0L;

    private LLVMValueRef getLLVMPlaceholderForConstant(Constant constant) {
        String symbolName = generationResult.getSymbolNameForConstant(constant);
        if (symbolName == null) {
            symbolName = "constant_" + builder.getFunctionName() + "#" + nextConstantId++;
            generationResult.recordConstant(constant, symbolName);
        }
        return builder.getExternalGlobal(symbolName, true);
    }

    protected void emitPrintf(String base) {
        emitPrintf(base, new JavaKind[0], new LLVMValueRef[0]);
    }

    protected void emitPrintf(String base, JavaKind[] types, LLVMValueRef[] values) {
        StringBuilder introString = new StringBuilder(base);
        List<LLVMValueRef> printfArgs = new ArrayList<>();

        assert types.length == values.length;

        for (int i = 0; i < types.length; ++i) {
            switch (types[i]) {
                case Boolean:
                case Byte:
                    introString.append(" %hhd ");
                    break;
                case Short:
                    introString.append(" %hd ");
                    break;
                case Char:
                    introString.append(" %c ");
                    break;
                case Int:
                    introString.append(" %ld ");
                    break;
                case Float:
                case Double:
                    introString.append(" %f ");
                    break;
                case Long:
                    introString.append(" %lld ");
                    break;
                case Object:
                    introString.append(" %p ");
                    break;
                case Void:
                case Illegal:
                default:
                    throw shouldNotReachHere();
            }

            printfArgs.add(values[i]);
        }

        LLVMValueRef header = builder.buildGlobalString(introString.toString());
        LLVMValueRef printf = builder.getFunction("printf", builder.functionType(builder.intType(), true, builder.objectType(false)));
        printfArgs.add(0, builder.buildConvert(header, builder.objectType(false)));
        builder.buildCall(printf, printfArgs.toArray(new LLVMValueRef[0]));
    }

    @Override
    public ArithmeticLIRGeneratorTool getArithmetic() {
        return arithmetic;
    }

    @Override
    public CodeGenProviders getProviders() {
        return providers;
    }

    @Override
    public TargetDescription target() {
        return getCodeCache().getTarget();
    }

    @Override
    public MetaAccessProvider getMetaAccess() {
        return providers.getMetaAccess();
    }

    @Override
    public CodeCacheProvider getCodeCache() {
        return providers.getCodeCache();
    }

    @Override
    public ForeignCallsProvider getForeignCalls() {
        return providers.getForeignCalls();
    }

    @Override
    public AbstractBlockBase<?> getCurrentBlock() {
        return currentBlock;
    }

    @Override
    public LIRGenerationResult getResult() {
        throw unimplemented();
    }

    @Override
    public RegisterConfig getRegisterConfig() {
        return getCodeCache().getRegisterConfig();
    }

    @Override
    public boolean hasBlockEnd(AbstractBlockBase<?> block) {
        throw unimplemented();
    }

    @Override
    public MoveFactory getMoveFactory() {
        throw unimplemented();
    }

    @Override
    public MoveFactory getSpillMoveFactory() {
        return null;
    }

    @Override
    public BlockScope getBlockScope(AbstractBlockBase<?> block) {
        throw unimplemented();
    }

    @Override
    public Value emitConstant(LIRKind kind, Constant constant) {
        return emitJavaConstant((JavaConstant) constant);
    }

    @Override
    public Value emitJavaConstant(JavaConstant constant) {
        LLVMValueRef value;
        JavaKind kind = constant.getJavaKind();
        if (kind.isPrimitive()) {
            switch (kind) {
                case Boolean:
                    value = builder.constantBoolean(constant.asBoolean());
                    break;
                case Byte:
                    value = builder.constantByte((byte) constant.asBoxedPrimitive());
                    break;
                case Short:
                    value = builder.constantShort((short) constant.asBoxedPrimitive());
                    break;
                case Char:
                    value = builder.constantChar((char) constant.asBoxedPrimitive());
                    break;
                case Int:
                    value = builder.constantInt(constant.asInt());
                    break;
                case Float:
                    value = builder.constantFloat(constant.asFloat());
                    break;
                case Long:
                    value = builder.constantLong(constant.asLong());
                    break;
                case Double:
                    value = builder.constantDouble(constant.asDouble());
                    break;
                case Object:
                case Void:
                case Illegal:
                default:
                    throw shouldNotReachHere("Illegal type");
            }
        } else {
            if (constant.isNull()) {
                value = builder.constantNull(builder.getLLVMType(kind));
            } else {
                value = builder.buildLoad(getLLVMPlaceholderForConstant(constant), builder.getLLVMType(kind));
            }
        }

        return new LLVMConstant(value, constant);
    }

    @Override
    public <K extends ValueKind<K>> K toRegisterKind(K kind) {
        /* Registers are handled by LLVM. */
        throw unimplemented();
    }

    @Override
    public AllocatableValue emitLoadConstant(ValueKind<?> kind, Constant constant) {
        LLVMValueRef value = builder.buildLoad(getLLVMPlaceholderForConstant(constant), ((LLVMKind) kind.getPlatformKind()).get());
        return new LLVMVariable(value);
    }

    @Override
    public void emitNullCheck(Value address, LIRFrameState state) {
        throw unimplemented();
    }

    @Override
    public Value emitAtomicReadAndWrite(Value address, ValueKind<?> valueKind, Value newValue) {
        LLVMValueRef atomicRMW = builder.buildAtomicXchg(getVal(address), getVal(newValue));
        return new LLVMVariable(atomicRMW);
    }

    @Override
    public Value emitAtomicReadAndAdd(Value address, ValueKind<?> valueKind, Value delta) {
        LLVMValueRef castedDelta = builder.buildConvert(getVal(delta), LLVMUtils.getType(valueKind));
        LLVMValueRef atomicRMW = builder.buildAtomicAdd(getVal(address), castedDelta);
        return new LLVMVariable(atomicRMW);
    }

    @Override
    public Variable emitLogicCompareAndSwap(LIRKind accessKind, Value address, Value expectedValue, Value newValue, Value trueValue, Value falseValue) {
        LLVMValueRef cas = builder.buildCmpxchg(getVal(address), getVal(expectedValue), getVal(newValue));
        /* builder.buildExtractValue(cas, 1); */
        /*
         * Hack for singlethreaded programs, as structures containing tracked pointers cause
         * statepoint generation to fail
         */
        LLVMValueRef success = builder.constantBoolean(true);
        LLVMValueRef result = builder.buildSelect(success, getVal(trueValue), getVal(falseValue));
        return new LLVMVariable(result);
    }

    @Override
    public Value emitValueCompareAndSwap(LIRKind accessKind, Value address, Value expectedValue, Value newValue) {
        throw unimplemented();
    }

    @Override
    public void emitDeoptimize(Value actionAndReason, Value failedSpeculation, LIRFrameState state) {
        throw unimplemented();
    }

    @Override
    public Variable emitForeignCall(ForeignCallLinkage linkage, LIRFrameState state, Value... args) {
        ForeignCallDescriptor descriptor = linkage.getDescriptor();

        ResolvedJavaMethod targetMethod = findForeignCallTarget(descriptor);

        state.initDebugInfo(null, false);
        long patchpointId = LLVMIRBuilder.nextPatchpointId.getAndIncrement();
        generationResult.recordDirectCall(targetMethod, patchpointId, state.debugInfo());

        LLVMValueRef callee = getFunction(targetMethod);
        LLVMValueRef[] arguments = getLLVMFunctionArgs(targetMethod, i -> getVal(args[i]), args.length);

        LLVMValueRef call = builder.buildCall(callee, patchpointId, arguments);
        return new LLVMVariable(call);
    }

    protected ResolvedJavaMethod findForeignCallTarget(@SuppressWarnings("unused") ForeignCallDescriptor descriptor) {
        throw unimplemented();
    }

    @Override
    public RegisterAttributes attributes(Register register) {
        throw unimplemented();
    }

    @Override
    public Variable newVariable(ValueKind<?> kind) {
        return new LLVMVariable(kind);
    }

    @Override
    public Variable emitMove(Value input) {
        if (input instanceof LLVMVariable) {
            return (LLVMVariable) input;
        } else if (input instanceof LLVMValueWrapper) {
            return new LLVMVariable(getVal(input));
        } else if (input instanceof RegisterValue) {
            RegisterValue reg = (RegisterValue) input;
            assert reg.getRegister().equals(getRegisterConfig().getFrameRegister());
            LLVMValueRef stackPointer = builder.buildReadRegister(builder.register(getRegisterConfig().getFrameRegister().name));
            return new LLVMVariable(stackPointer);
        }
        throw shouldNotReachHere("Unknown move input");
    }

    @Override
    public void emitMove(AllocatableValue dst, Value src) {
        LLVMValueRef source = getVal(src);
        LLVMTypeRef destType = ((LLVMKind) dst.getPlatformKind()).get();
        source = builder.buildConvert(source, destType);
        ((LLVMVariable) dst).set(source);
    }

    @Override
    public void emitMoveConstant(AllocatableValue dst, Constant src) {
        throw unimplemented();
    }

    @Override
    public Variable emitAddress(AllocatableValue stackslot) {
        if (stackslot instanceof LLVMStackSlot) {
            LLVMStackSlot llvmStackSlot = (LLVMStackSlot) stackslot;
            return llvmStackSlot.address();
        }
        throw shouldNotReachHere("Unknown address type");
    }

    @Override
    public void emitMembar(int barriers) {
        builder.buildFence();
    }

    @Override
    public void emitUnwind(Value operand) {
        throw unimplemented();
    }

    @Override
    public void beforeRegisterAllocation() {
        throw unimplemented();
    }

    @Override
    public void emitIncomingValues(Value[] params) {
        throw unimplemented();
    }

    @Override
    public void emitReturn(JavaKind javaKind, Value input) {
        if (javaKind == JavaKind.Void) {
            if (debugLevel >= DebugLevel.FUNCTION) {
                emitPrintf("Return");
            }
            builder.buildRetVoid();
        } else {
            if (debugLevel >= DebugLevel.FUNCTION) {
                emitPrintf("Return", new JavaKind[]{javaKind}, new LLVMValueRef[]{getVal(input)});
            }
            builder.buildRet(getVal(input));
        }
    }

    @Override
    public AllocatableValue asAllocatable(Value value) {
        return (AllocatableValue) value;
    }

    @Override
    public Variable load(Value value) {
        if (value instanceof LLVMVariable) {
            return (Variable) value;
        }
        return new LLVMVariable(getVal(value));
    }

    @Override
    public Value loadNonConst(Value value) {
        throw unimplemented();
    }

    @Override
    public boolean needOnlyOopMaps() {
        return false;
    }

    @Override
    public AllocatableValue resultOperandFor(JavaKind javaKind, ValueKind<?> valueKind) {
        return new LLVMVariable(builder.undef(builder.getLLVMType(javaKind)));
    }

    @Override
    public <I extends LIRInstruction> I append(I op) {
        throw unimplemented();
    }

    @Override
    public void setSourcePosition(NodeSourcePosition position) {
        throw unimplemented();
    }

    @Override
    public void emitJump(LabelRef label) {
        builder.buildBranch(getBlock((Block) label.getTargetBlock()));
    }

    @Override
    public void emitCompareBranch(PlatformKind cmpKind, Value left, Value right, Condition cond, boolean unorderedIsTrue, LabelRef trueDestination, LabelRef falseDestination,
                    double trueDestinationProbability) {
        throw unimplemented();
    }

    @Override
    public void emitOverflowCheckBranch(LabelRef overflow, LabelRef noOverflow, LIRKind cmpKind, double overflowProbability) {
        throw unimplemented();
    }

    @Override
    public void emitIntegerTestBranch(Value left, Value right, LabelRef trueDestination, LabelRef falseDestination, double trueSuccessorProbability) {
        throw unimplemented();
    }

    @Override
    public Variable emitConditionalMove(PlatformKind cmpKind, Value leftVal, Value rightVal, Condition cond, boolean unorderedIsTrue, Value trueValue, Value falseValue) {
        LLVMTypeRef llvmCmpType = ((LLVMKind) cmpKind).get();
        LLVMValueRef compLeft = builder.buildConvert(getVal(leftVal), llvmCmpType);
        LLVMValueRef compRight = builder.buildConvert(getVal(rightVal), llvmCmpType);
        LLVMValueRef condition = builder.buildCompare(cond, compLeft, compRight, unorderedIsTrue);

        LLVMValueRef select = buildSelect(condition, getVal(trueValue), getVal(falseValue));
        return new LLVMVariable(select);
    }

    Variable emitIsNullMove(Value value, Value trueValue, Value falseValue) {
        LLVMValueRef isNull = builder.buildIsNull(getVal(value));
        LLVMValueRef select = buildSelect(isNull, getVal(trueValue), getVal(falseValue));
        return new LLVMVariable(select);
    }

    @Override
    public Variable emitIntegerTestMove(Value left, Value right, Value trueValue, Value falseValue) {
        LLVMValueRef and = builder.buildAnd(getVal(left), getVal(right));
        LLVMValueRef isNull = builder.buildIsNull(and);
        LLVMValueRef select = buildSelect(isNull, getVal(trueValue), getVal(falseValue));
        return new LLVMVariable(select);
    }

    /*
     * Select has to be manually created because of a bug in LLVM which makes it incompatible with
     * statepoint emission in rare cases.
     */
    private LLVMValueRef buildSelect(LLVMValueRef condition, LLVMValueRef trueVal, LLVMValueRef falseVal) {
        LLVMBasicBlockRef trueBlock = builder.appendBasicBlock(currentBlock.toString() + "_select_true");
        LLVMBasicBlockRef falseBlock = builder.appendBasicBlock(currentBlock.toString() + "_select_false");
        LLVMBasicBlockRef mergeBlock = builder.appendBasicBlock(currentBlock.toString() + "_select_end");
        splitBlockEndMap.put(currentBlock, mergeBlock);

        LLVMTypeRef biggerType = builder.getWiderType(typeOf(trueVal), typeOf(falseVal));
        builder.buildIf(condition, trueBlock, falseBlock);

        builder.positionAtEnd(trueBlock);
        LLVMValueRef castedTrueVal = builder.buildConvert(trueVal, biggerType);
        builder.buildBranch(mergeBlock);

        builder.positionAtEnd(falseBlock);
        LLVMValueRef castedFalseVal = builder.buildConvert(falseVal, biggerType);
        builder.buildBranch(mergeBlock);

        builder.positionAtEnd(mergeBlock);
        LLVMValueRef[] incomingValues = new LLVMValueRef[]{castedTrueVal, castedFalseVal};
        LLVMBasicBlockRef[] incomingBlocks = new LLVMBasicBlockRef[]{trueBlock, falseBlock};
        return builder.buildPhi(typeOf(trueVal), incomingValues, incomingBlocks);
    }

    @Override
    public void emitStrategySwitch(JavaConstant[] keyConstants, double[] keyProbabilities, LabelRef[] keyTargets, LabelRef defaultTarget, Variable value) {
        throw unimplemented();
    }

    @Override
    public void emitStrategySwitch(SwitchStrategy strategy, Variable key, LabelRef[] keyTargets, LabelRef defaultTarget) {
        throw unimplemented();
    }

    @Override
    public Variable emitByteSwap(Value operand) {
        LLVMValueRef byteSwap = builder.buildBswap(getVal(operand));
        return new LLVMVariable(byteSwap);
    }

    @Override
    public Variable emitArrayEquals(JavaKind kind, Value array1, Value array2, Value length, int constantLength, boolean directPointers) {
        int arrayBaseOffset = directPointers ? 0 : getProviders().getMetaAccess().getArrayBaseOffset(kind);
        int arrayIndexScale = getProviders().getMetaAccess().getArrayIndexScale(kind);

        LLVMValueRef arrayVal1 = builder.buildGEP(getVal(array1), builder.objectType(!directPointers), builder.constantInt(arrayBaseOffset));
        LLVMValueRef arrayVal2 = builder.buildGEP(getVal(array2), builder.objectType(!directPointers), builder.constantInt(arrayBaseOffset));
        LLVMValueRef len = builder.buildMul(getVal(length), builder.constantInt(arrayIndexScale));

        LLVMBasicBlockRef currBlock = getBlock((Block) getCurrentBlock());
        LLVMBasicBlockRef loop = builder.appendBasicBlock("array_equals");
        LLVMBasicBlockRef endLoop = builder.appendBasicBlock("array_equals_end");
        splitBlockEndMap.put((Block) getCurrentBlock(), endLoop);

        LLVMValueRef zeroLength = builder.buildIsNull(len);
        builder.buildIf(zeroLength, endLoop, loop);

        builder.positionAtEnd(loop);

        LLVMBasicBlockRef[] incomingBlocks = {currBlock};
        LLVMValueRef[] incomingEqualsValues = {builder.constantBoolean(true)};
        LLVMValueRef[] incomingIValues = {builder.constantInt(0)};
        LLVMValueRef equals = builder.buildPhi(builder.booleanType(), incomingEqualsValues, incomingBlocks);
        LLVMValueRef i = builder.buildPhi(builder.intType(), incomingIValues, incomingBlocks);

        LLVMValueRef elem1 = builder.buildLoad(builder.buildGEP(arrayVal1, typeOf(arrayVal1), i), builder.byteType());
        LLVMValueRef elem2 = builder.buildLoad(builder.buildGEP(arrayVal2, typeOf(arrayVal2), i), builder.byteType());
        LLVMValueRef compare = builder.buildCompare(Condition.EQ, elem1, elem2, false);

        LLVMValueRef newEquals = builder.buildAnd(compare, equals);
        LLVMValueRef newI = builder.buildAdd(i, builder.constantInt(1));

        LLVMValueRef[] newIncomingEqualsValues = new LLVMValueRef[]{newEquals};
        LLVMValueRef[] newIncomingIValues = new LLVMValueRef[]{newI};
        LLVMBasicBlockRef[] newIncomingBlocks = new LLVMBasicBlockRef[]{loop};
        builder.addIncoming(equals, newIncomingEqualsValues, newIncomingBlocks);
        builder.addIncoming(i, newIncomingIValues, newIncomingBlocks);

        LLVMValueRef atEnd = builder.buildCompare(Condition.EQ, newI, len, false);
        LLVMValueRef exitLoop = builder.buildOr(atEnd, builder.buildNot(newEquals));
        builder.buildIf(exitLoop, endLoop, loop);

        builder.positionAtEnd(endLoop);

        LLVMBasicBlockRef[] incomingArrayEqualsBlocks = new LLVMBasicBlockRef[]{currBlock, loop};
        LLVMValueRef[] incomingArrayEqualsValues = new LLVMValueRef[]{builder.constantBoolean(true), newEquals};
        LLVMValueRef arrayEquals = builder.buildPhi(builder.booleanType(), incomingArrayEqualsValues, incomingArrayEqualsBlocks);

        return new LLVMVariable(arrayEquals);
    }

    @Override
    public Variable emitArrayCompareTo(JavaKind kind1, JavaKind kind2, Value array1, Value array2, Value length1, Value length2) {
        int arrayBaseOffset1 = getProviders().getMetaAccess().getArrayBaseOffset(kind1);
        int arrayBaseOffset2 = getProviders().getMetaAccess().getArrayBaseOffset(kind2);

        LLVMValueRef arrayVal1 = builder.buildGEP(getVal(array1), builder.objectType(true), builder.constantInt(arrayBaseOffset1));
        LLVMValueRef arrayVal2 = builder.buildGEP(getVal(array2), builder.objectType(true), builder.constantInt(arrayBaseOffset2));
        LLVMValueRef len1 = builder.buildMul(getVal(length1), builder.constantInt(1));
        LLVMValueRef len2 = builder.buildMul(getVal(length2), builder.constantInt(1));

        LLVMBasicBlockRef currBlock = getBlock((Block) getCurrentBlock());
        LLVMBasicBlockRef loop = builder.appendBasicBlock("array_compare");
        LLVMBasicBlockRef endLoop = builder.appendBasicBlock("array_compare_end");
        splitBlockEndMap.put((Block) getCurrentBlock(), endLoop);

        LLVMValueRef zeroLength1 = builder.buildIsNull(len1);
        LLVMValueRef zeroLength2 = builder.buildIsNull(len2);
        LLVMValueRef eitherZeroLength = builder.buildOr(zeroLength1, zeroLength2);
        builder.buildIf(eitherZeroLength, endLoop, loop);

        builder.positionAtEnd(loop);

        LLVMBasicBlockRef[] incomingBlocks = {currBlock};
        LLVMValueRef[] incomingIValues = {builder.constantInt(0)};
        LLVMValueRef i = builder.buildPhi(builder.intType(), incomingIValues, incomingBlocks);

        LLVMValueRef elem1 = builder.buildLoad(builder.buildGEP(arrayVal1, typeOf(arrayVal1), i), builder.byteType());
        LLVMValueRef elem2 = builder.buildLoad(builder.buildGEP(arrayVal2, typeOf(arrayVal2), i), builder.byteType());
        LLVMValueRef different = builder.buildCompare(Condition.NE, elem1, elem2, false);
        LLVMValueRef compare = builder.buildCompare(Condition.LT, elem1, elem2, false);

        LLVMValueRef newI = builder.buildAdd(i, builder.constantInt(1));

        LLVMValueRef[] newIncomingIValues = new LLVMValueRef[]{newI};
        LLVMBasicBlockRef[] newIncomingBlocks = new LLVMBasicBlockRef[]{loop};
        builder.addIncoming(i, newIncomingIValues, newIncomingBlocks);

        LLVMValueRef atEnd1 = builder.buildCompare(Condition.EQ, newI, len1, false);
        LLVMValueRef atEnd2 = builder.buildCompare(Condition.EQ, newI, len2, false);
        LLVMValueRef atEnd = builder.buildOr(atEnd1, atEnd2);
        LLVMValueRef exitLoop = builder.buildOr(atEnd, different);
        builder.buildIf(exitLoop, endLoop, loop);

        builder.positionAtEnd(endLoop);

        LLVMBasicBlockRef[] incomingEndBlocks = new LLVMBasicBlockRef[]{currBlock, loop};
        LLVMValueRef[] incomingDifferentValues = new LLVMValueRef[]{builder.constantBoolean(false), different};
        LLVMValueRef differentPhi = builder.buildPhi(builder.booleanType(), incomingDifferentValues, incomingEndBlocks);
        LLVMValueRef[] incomingCompareValues = new LLVMValueRef[]{builder.constantBoolean(false), compare};
        LLVMValueRef comparePhi = builder.buildPhi(builder.booleanType(), incomingCompareValues, incomingEndBlocks);
        LLVMValueRef[] incomingAtEnd1Values = new LLVMValueRef[]{zeroLength1, atEnd1};
        LLVMValueRef atEndPhi1 = builder.buildPhi(builder.booleanType(), incomingAtEnd1Values, incomingEndBlocks);
        LLVMValueRef[] incomingAtEnd2Values = new LLVMValueRef[]{zeroLength2, atEnd2};
        LLVMValueRef atEndPhi2 = builder.buildPhi(builder.booleanType(), incomingAtEnd2Values, incomingEndBlocks);

        LLVMValueRef valCmp = builder.buildSelect(comparePhi, builder.constantInt(-1), builder.constantInt(1));
        LLVMValueRef lenCmp = builder.buildSelect(atEndPhi1, builder.buildSelect(atEndPhi2, builder.constantInt(0), builder.constantInt(-1)), builder.constantInt(1));
        LLVMValueRef arrayCompare = builder.buildSelect(differentPhi, valCmp, lenCmp);

        return new LLVMVariable(arrayCompare);
    }

    @Override
    public Variable emitArrayIndexOf(JavaKind kind, boolean findTwoConsecutive, Value sourcePointer, Value sourceCount, Value... searchValues) {
        LLVMValueRef arrayVal = builder.buildBitcast(getVal(sourcePointer), builder.pointerType(builder.getLLVMType(kind), false));
        LLVMValueRef len = getVal(sourceCount);

        LLVMBasicBlockRef currBlock = getBlock((Block) getCurrentBlock());
        LLVMBasicBlockRef loop = builder.appendBasicBlock("array_index_of");
        LLVMBasicBlockRef endLoop = builder.appendBasicBlock("array_index_of_end");
        splitBlockEndMap.put((Block) getCurrentBlock(), endLoop);

        LLVMValueRef zeroLength = builder.buildIsNull(len);
        builder.buildIf(zeroLength, endLoop, loop);

        builder.positionAtEnd(loop);

        LLVMBasicBlockRef[] incomingBlocks = {currBlock};
        LLVMValueRef[] incomingIValues = {builder.constantInt(0)};
        LLVMValueRef i = builder.buildPhi(builder.intType(), incomingIValues, incomingBlocks);
        LLVMValueRef[] incomingIndexValues = {builder.constantInt(-1)};
        LLVMValueRef index = builder.buildPhi(builder.intType(), incomingIndexValues, incomingBlocks);

        LLVMValueRef elem = builder.buildLoad(builder.buildGEP(arrayVal, typeOf(arrayVal), i), builder.byteType());
        LLVMValueRef equal = builder.constantBoolean(false);
        for (Value searchValue : searchValues) {
            LLVMValueRef thisCompare = builder.buildCompare(Condition.EQ, elem, getVal(searchValue), false);
            equal = builder.buildOr(equal, thisCompare);
        }

        LLVMValueRef newI = builder.buildAdd(i, builder.constantInt(1));
        LLVMValueRef newIndex = builder.buildSelect(equal, i, index);

        LLVMValueRef[] newIncomingIValues = new LLVMValueRef[]{newI};
        LLVMValueRef[] newIncomingIndexValues = new LLVMValueRef[]{newIndex};
        LLVMBasicBlockRef[] newIncomingBlocks = new LLVMBasicBlockRef[]{loop};
        builder.addIncoming(i, newIncomingIValues, newIncomingBlocks);
        builder.addIncoming(index, newIncomingIndexValues, newIncomingBlocks);

        LLVMValueRef atEnd = builder.buildCompare(Condition.EQ, newI, len, false);
        LLVMValueRef exitLoop = builder.buildOr(atEnd, equal);
        builder.buildIf(exitLoop, endLoop, loop);

        builder.positionAtEnd(endLoop);

        LLVMBasicBlockRef[] incomingEndBlocks = new LLVMBasicBlockRef[]{currBlock, loop};
        LLVMValueRef[] incomingReturnValues = new LLVMValueRef[]{builder.constantInt(-1), newIndex};
        LLVMValueRef indexPhi = builder.buildPhi(builder.intType(), incomingReturnValues, incomingEndBlocks);

        return new LLVMVariable(indexPhi);
    }

    @Override
    public void emitBlackhole(Value operand) {
        builder.buildInlineConsumeValue(getVal(operand));
    }

    @Override
    public LIRKind getLIRKind(Stamp stamp) {
        return stamp.getLIRKind(lirKindTool);
    }

    @Override
    public void emitPause() {
        throw unimplemented();
    }

    @Override
    public void emitPrefetchAllocate(Value address) {
        builder.buildPrefetch(getVal(address));
    }

    @Override
    public Value emitCompress(Value pointer, CompressEncoding encoding, boolean nonNull) {
        throw unimplemented();
    }

    @Override
    public Value emitUncompress(Value pointer, CompressEncoding encoding, boolean nonNull) {
        throw unimplemented();
    }

    @Override
    public void emitSpeculationFence() {
        throw unimplemented();
    }

    @Override
    public LIRKind getValueKind(JavaKind javaKind) {
        return getLIRKind(StampFactory.forKind(javaKind));
    }

    @Override
    public LIRInstruction createBenchmarkCounter(String name, String group, Value increment) {
        throw unimplemented();
    }

    @Override
    public LIRInstruction createMultiBenchmarkCounter(String[] names, String[] groups, Value[] increments) {
        throw unimplemented();
    }

    @Override
    public StandardOp.SaveRegistersOp createZapRegisters(Register[] zappedRegisters, JavaConstant[] zapValues) {
        throw unimplemented();
    }

    @Override
    public StandardOp.SaveRegistersOp createZapRegisters() {
        throw unimplemented();
    }

    @Override
    public LIRInstruction createZapArgumentSpace(StackSlot[] zappedStack, JavaConstant[] zapValues) {
        throw unimplemented();
    }

    @Override
    public LIRInstruction zapArgumentSpace() {
        throw unimplemented();
    }

    @Override
    public VirtualStackSlot allocateStackSlots(int slots, BitSet objects, List<VirtualStackSlot> outObjectStackSlots) {
        if (slots == 0) {
            return null;
        }

        builder.positionAtStart();
        LLVMValueRef alloca = builder.buildArrayAlloca(slots);
        builder.positionAtEnd(getBlock(currentBlock));

        BitSet bitSet = new BitSet();
        bitSet.set(0, slots);
        LLVMStackSlot stackSlot = new LLVMStackSlot(alloca, bitSet);
        if (outObjectStackSlots != null) {
            outObjectStackSlots.add(stackSlot);
        }
        return stackSlot;
    }

    @Override
    public Value emitReadCallerStackPointer(Stamp wordStamp) {
        LLVMValueRef basePointer = builder.buildFrameAddress(builder.constantInt(0));
        LLVMValueRef callerSP = builder.buildAdd(basePointer, builder.constantLong(16));
        return new LLVMVariable(callerSP);
    }

    @Override
    public Value emitReadReturnAddress(Stamp wordStamp, int returnAddressSize) {
        LLVMValueRef returnAddress = builder.buildReturnAddress(builder.constantInt(0));
        return new LLVMVariable(returnAddress);
    }

    public LLVMGenerationResult getLLVMResult() {
        return generationResult;
    }

    int getDebugLevel() {
        return debugLevel;
    }

    public static class ArithmeticLLVMGenerator implements ArithmeticLIRGeneratorTool {
        private final LLVMIRBuilder builder;

        ArithmeticLLVMGenerator(LLVMIRBuilder builder) {
            this.builder = builder;
        }

        @Override
        public Value emitNegate(Value input) {
            LLVMValueRef neg = builder.buildNeg(getVal(input));
            return new LLVMVariable(neg);
        }

        @Override
        public Value emitAdd(Value a, Value b, boolean setFlags) {
            LLVMValueRef add = builder.buildAdd(getVal(a), getVal(b));
            return new LLVMVariable(add);
        }

        @Override
        public Value emitSub(Value a, Value b, boolean setFlags) {
            LLVMValueRef sub = builder.buildSub(getVal(a), getVal(b));
            return new LLVMVariable(sub);
        }

        @Override
        public Value emitMul(Value a, Value b, boolean setFlags) {
            LLVMValueRef mul = builder.buildMul(getVal(a), getVal(b));
            return new LLVMVariable(mul);
        }

        @Override
        public Value emitMulHigh(Value a, Value b) {
            return emitMulHigh(a, b, true);
        }

        @Override
        public Value emitUMulHigh(Value a, Value b) {
            return emitMulHigh(a, b, false);
        }

        private LLVMVariable emitMulHigh(Value a, Value b, boolean signed) {
            LLVMValueRef valA = getVal(a);
            LLVMValueRef valB = getVal(b);
            assert builder.integerTypeWidth(LLVMUtils.typeOf(valA)) == builder.integerTypeWidth(LLVMUtils.typeOf(valB));

            int baseBits = builder.integerTypeWidth(LLVMUtils.typeOf(valA));
            int extendedBits = baseBits * 2;

            LLVMValueRef extendedA;
            LLVMValueRef extendedB;
            if (signed) {
                extendedA = builder.buildSExt(valA, extendedBits);
                extendedB = builder.buildSExt(valB, extendedBits);
            } else {
                extendedA = builder.buildZExt(valA, extendedBits);
                extendedB = builder.buildZExt(valB, extendedBits);
            }
            LLVMValueRef mul = builder.buildMul(extendedA, extendedB);

            LLVMValueRef shiftedMul;
            if (signed) {
                shiftedMul = builder.buildShr(mul, builder.constantInteger(baseBits, baseBits));
            } else {
                shiftedMul = builder.buildUShr(mul, builder.constantInteger(baseBits, baseBits));
            }
            LLVMValueRef truncatedMul = builder.buildTrunc(shiftedMul, baseBits);

            return new LLVMVariable(truncatedMul);
        }

        @Override
        public Value emitDiv(Value a, Value b, LIRFrameState state) {
            LLVMValueRef div = builder.buildDiv(getVal(a), getVal(b));
            return new LLVMVariable(div);
        }

        @Override
        public Value emitRem(Value a, Value b, LIRFrameState state) {
            LLVMValueRef rem = builder.buildRem(getVal(a), getVal(b));
            return new LLVMVariable(rem);
        }

        @Override
        public Value emitUDiv(Value a, Value b, LIRFrameState state) {
            LLVMValueRef uDiv = builder.buildUDiv(getVal(a), getVal(b));
            return new LLVMVariable(uDiv);
        }

        @Override
        public Value emitURem(Value a, Value b, LIRFrameState state) {
            LLVMValueRef uRem = builder.buildURem(getVal(a), getVal(b));
            return new LLVMVariable(uRem);
        }

        @Override
        public Value emitNot(Value input) {
            LLVMValueRef not = builder.buildNot(getVal(input));
            return new LLVMVariable(not);
        }

        @Override
        public Value emitAnd(Value a, Value b) {
            LLVMValueRef and = builder.buildAnd(getVal(a), getVal(b));
            return new LLVMVariable(and);
        }

        @Override
        public Value emitOr(Value a, Value b) {
            LLVMValueRef or = builder.buildOr(getVal(a), getVal(b));
            return new LLVMVariable(or);
        }

        @Override
        public Value emitXor(Value a, Value b) {
            LLVMValueRef xor = builder.buildXor(getVal(a), getVal(b));
            return new LLVMVariable(xor);
        }

        @Override
        public Value emitShl(Value a, Value b) {
            LLVMValueRef shl = builder.buildShl(getVal(a), getVal(b));
            return new LLVMVariable(shl);
        }

        @Override
        public Value emitShr(Value a, Value b) {
            LLVMValueRef shr = builder.buildShr(getVal(a), getVal(b));
            return new LLVMVariable(shr);
        }

        @Override
        public Value emitUShr(Value a, Value b) {
            LLVMValueRef ushr = builder.buildUShr(getVal(a), getVal(b));
            return new LLVMVariable(ushr);
        }

        @Override
        public Value emitFloatConvert(FloatConvert op, Value inputVal) {
            LLVMTypeRef destType;
            switch (op) {
                case F2I:
                case D2I:
                    destType = builder.intType();
                    break;
                case F2L:
                case D2L:
                    destType = builder.longType();
                    break;
                case I2F:
                case L2F:
                case D2F:
                    destType = builder.floatType();
                    break;
                case I2D:
                case L2D:
                case F2D:
                    destType = builder.doubleType();
                    break;
                default:
                    throw shouldNotReachHere("invalid FloatConvert type");
            }

            LLVMValueRef convert;
            switch (op.getCategory()) {
                case FloatingPointToInteger:
                    convert = builder.buildFPToSI(getVal(inputVal), destType);
                    break;
                case IntegerToFloatingPoint:
                    convert = builder.buildSIToFP(getVal(inputVal), destType);
                    break;
                case FloatingPointToFloatingPoint:
                    convert = builder.buildFPCast(getVal(inputVal), destType);
                    break;
                default:
                    throw shouldNotReachHere("invalid FloatConvert type");
            }
            return new LLVMVariable(convert);
        }

        @Override
        public Value emitReinterpret(LIRKind to, Value inputVal) {
            LLVMTypeRef type = LLVMUtils.getType(to);
            LLVMValueRef cast = builder.buildBitcast(getVal(inputVal), type);
            return new LLVMVariable(cast);
        }

        @Override
        public Value emitNarrow(Value inputVal, int bits) {
            LLVMValueRef narrow = builder.buildConvert(getVal(inputVal), builder.integerType(bits));
            return new LLVMVariable(narrow);
        }

        @Override
        public Value emitSignExtend(Value inputVal, int fromBits, int toBits) {
            LLVMValueRef signExtend = builder.buildSExt(getVal(inputVal), toBits);
            return new LLVMVariable(signExtend);
        }

        @Override
        public Value emitZeroExtend(Value inputVal, int fromBits, int toBits) {
            LLVMValueRef zeroExtend = builder.buildZExt(getVal(inputVal), toBits);
            return new LLVMVariable(zeroExtend);
        }

        @Override
        public Value emitMathAbs(Value input) {
            LLVMValueRef abs = builder.buildAbs(getVal(input));
            return new LLVMVariable(abs);
        }

        @Override
        public Value emitMathSqrt(Value input) {
            LLVMValueRef sqrt = builder.buildSqrt(getVal(input));
            return new LLVMVariable(sqrt);
        }

        @Override
        public Value emitMathLog(Value input, boolean base10) {
            LLVMValueRef value = getVal(input);
            LLVMValueRef log = base10 ? builder.buildLog10(value) : builder.buildLog(value);
            return new LLVMVariable(log);
        }

        @Override
        public Value emitMathCos(Value input) {
            LLVMValueRef cos = builder.buildCos(getVal(input));
            return new LLVMVariable(cos);
        }

        @Override
        public Value emitMathSin(Value input) {
            LLVMValueRef sin = builder.buildSin(getVal(input));
            return new LLVMVariable(sin);
        }

        @Override
        public Value emitMathTan(Value input) {
            LLVMValueRef value = getVal(input);
            LLVMValueRef sin = builder.buildSin(value);
            LLVMValueRef cos = builder.buildCos(value);
            LLVMValueRef tan = builder.buildDiv(sin, cos);
            return new LLVMVariable(tan);
        }

        @Override
        public Value emitMathExp(Value input) {
            LLVMValueRef exp = builder.buildExp(getVal(input));
            return new LLVMVariable(exp);
        }

        @Override
        public Value emitMathPow(Value x, Value y) {
            LLVMValueRef pow = builder.buildPow(getVal(x), getVal(y));
            return new LLVMVariable(pow);
        }

        @Override
        public Value emitBitCount(Value operand) {
            throw unimplemented();
        }

        @Override
        public Value emitBitScanForward(Value operand) {
            LLVMValueRef op = getVal(operand);
            LLVMValueRef trailingZeros = builder.buildCttz(op);
            LLVMValueRef castedResult = builder.buildConvert(trailingZeros, builder.intType());
            return new LLVMVariable(castedResult);
        }

        @Override
        public Value emitBitScanReverse(Value operand) {
            LLVMValueRef op = getVal(operand);
            int opWidth = builder.integerTypeWidth(LLVMUtils.typeOf(op));

            LLVMValueRef leadingZeros = builder.buildCtlz(op);
            LLVMValueRef result = builder.buildSub(builder.constantInteger(opWidth - 1, opWidth), leadingZeros);
            LLVMValueRef castedResult = builder.buildConvert(result, builder.intType());
            return new LLVMVariable(castedResult);
        }

        @Override
        public Variable emitLoad(LIRKind kind, Value address, LIRFrameState state) {
            LLVMValueRef load = builder.buildLoad(getVal(address), LLVMUtils.getType(kind));
            return new LLVMVariable(load);
        }

        @Override
        public void emitStore(ValueKind<?> kind, Value address, Value input, LIRFrameState state) {
            builder.buildStore(getVal(input), getVal(address));
        }

        @Override
        public Value emitCountLeadingZeros(Value value) {
            LLVMValueRef leadingZeros = builder.buildCtlz(getVal(value));
            leadingZeros = builder.buildConvert(leadingZeros, builder.intType());
            return new LLVMVariable(leadingZeros);
        }

        @Override
        public Value emitCountTrailingZeros(Value value) {
            LLVMValueRef trailingZeros = builder.buildCttz(getVal(value));
            trailingZeros = builder.buildConvert(trailingZeros, builder.intType());
            return new LLVMVariable(trailingZeros);
        }
    }
}
