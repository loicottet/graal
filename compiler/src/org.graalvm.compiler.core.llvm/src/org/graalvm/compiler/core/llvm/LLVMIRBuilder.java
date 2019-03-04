/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package org.graalvm.compiler.core.llvm;

import static org.bytedeco.javacpp.LLVM.LLVMConstInlineAsm;
import static org.graalvm.compiler.core.llvm.LLVMUtils.FALSE;
import static org.graalvm.compiler.core.llvm.LLVMUtils.TRUE;
import static org.bytedeco.javacpp.LLVM.LLVMAddAttributeAtIndex;
import static org.bytedeco.javacpp.LLVM.LLVMAddCallSiteAttribute;
import static org.bytedeco.javacpp.LLVM.LLVMAddCase;
import static org.bytedeco.javacpp.LLVM.LLVMAddFunction;
import static org.bytedeco.javacpp.LLVM.LLVMAddGlobalInAddressSpace;
import static org.bytedeco.javacpp.LLVM.LLVMAddIncoming;
import static org.bytedeco.javacpp.LLVM.LLVMAppendBasicBlockInContext;
import static org.bytedeco.javacpp.LLVM.LLVMArrayType;
import static org.bytedeco.javacpp.LLVM.LLVMAtomicOrderingSequentiallyConsistent;
import static org.bytedeco.javacpp.LLVM.LLVMAtomicRMWBinOpAdd;
import static org.bytedeco.javacpp.LLVM.LLVMAtomicRMWBinOpXchg;
import static org.bytedeco.javacpp.LLVM.LLVMAttributeFunctionIndex;
import static org.bytedeco.javacpp.LLVM.LLVMBuildArrayAlloca;
import static org.bytedeco.javacpp.LLVM.LLVMBuildAtomicCmpXchg;
import static org.bytedeco.javacpp.LLVM.LLVMBuildAtomicRMW;
import static org.bytedeco.javacpp.LLVM.LLVMBuildBr;
import static org.bytedeco.javacpp.LLVM.LLVMBuildCall;
import static org.bytedeco.javacpp.LLVM.LLVMBuildCondBr;
import static org.bytedeco.javacpp.LLVM.LLVMBuildExtractValue;
import static org.bytedeco.javacpp.LLVM.LLVMBuildFCmp;
import static org.bytedeco.javacpp.LLVM.LLVMBuildFPCast;
import static org.bytedeco.javacpp.LLVM.LLVMBuildFPToSI;
import static org.bytedeco.javacpp.LLVM.LLVMBuildFence;
import static org.bytedeco.javacpp.LLVM.LLVMBuildGEP;
import static org.bytedeco.javacpp.LLVM.LLVMBuildGlobalString;
import static org.bytedeco.javacpp.LLVM.LLVMBuildICmp;
import static org.bytedeco.javacpp.LLVM.LLVMBuildIntToPtr;
import static org.bytedeco.javacpp.LLVM.LLVMBuildIsNull;
import static org.bytedeco.javacpp.LLVM.LLVMBuildLoad;
import static org.bytedeco.javacpp.LLVM.LLVMBuildNot;
import static org.bytedeco.javacpp.LLVM.LLVMBuildPhi;
import static org.bytedeco.javacpp.LLVM.LLVMBuildPtrToInt;
import static org.bytedeco.javacpp.LLVM.LLVMBuildRet;
import static org.bytedeco.javacpp.LLVM.LLVMBuildRetVoid;
import static org.bytedeco.javacpp.LLVM.LLVMBuildSExt;
import static org.bytedeco.javacpp.LLVM.LLVMBuildSIToFP;
import static org.bytedeco.javacpp.LLVM.LLVMBuildSelect;
import static org.bytedeco.javacpp.LLVM.LLVMBuildStore;
import static org.bytedeco.javacpp.LLVM.LLVMBuildSwitch;
import static org.bytedeco.javacpp.LLVM.LLVMBuildTrunc;
import static org.bytedeco.javacpp.LLVM.LLVMBuildUnreachable;
import static org.bytedeco.javacpp.LLVM.LLVMBuildZExt;
import static org.bytedeco.javacpp.LLVM.LLVMConstInt;
import static org.bytedeco.javacpp.LLVM.LLVMConstNull;
import static org.bytedeco.javacpp.LLVM.LLVMConstReal;
import static org.bytedeco.javacpp.LLVM.LLVMCountParamTypes;
import static org.bytedeco.javacpp.LLVM.LLVMCreateBuilderInContext;
import static org.bytedeco.javacpp.LLVM.LLVMCreateEnumAttribute;
import static org.bytedeco.javacpp.LLVM.LLVMCreateStringAttribute;
import static org.bytedeco.javacpp.LLVM.LLVMDoubleTypeInContext;
import static org.bytedeco.javacpp.LLVM.LLVMDoubleTypeKind;
import static org.bytedeco.javacpp.LLVM.LLVMExternalLinkage;
import static org.bytedeco.javacpp.LLVM.LLVMFloatTypeInContext;
import static org.bytedeco.javacpp.LLVM.LLVMFloatTypeKind;
import static org.bytedeco.javacpp.LLVM.LLVMFunctionType;
import static org.bytedeco.javacpp.LLVM.LLVMFunctionTypeKind;
import static org.bytedeco.javacpp.LLVM.LLVMGetBasicBlockTerminator;
import static org.bytedeco.javacpp.LLVM.LLVMGetElementType;
import static org.bytedeco.javacpp.LLVM.LLVMGetEnumAttributeKindForName;
import static org.bytedeco.javacpp.LLVM.LLVMGetFirstBasicBlock;
import static org.bytedeco.javacpp.LLVM.LLVMGetFirstInstruction;
import static org.bytedeco.javacpp.LLVM.LLVMGetIntTypeWidth;
import static org.bytedeco.javacpp.LLVM.LLVMGetMDKindIDInContext;
import static org.bytedeco.javacpp.LLVM.LLVMGetNamedFunction;
import static org.bytedeco.javacpp.LLVM.LLVMGetNamedGlobal;
import static org.bytedeco.javacpp.LLVM.LLVMGetParam;
import static org.bytedeco.javacpp.LLVM.LLVMGetParamTypes;
import static org.bytedeco.javacpp.LLVM.LLVMGetPointerAddressSpace;
import static org.bytedeco.javacpp.LLVM.LLVMGetReturnType;
import static org.bytedeco.javacpp.LLVM.LLVMGetTypeKind;
import static org.bytedeco.javacpp.LLVM.LLVMGetUndef;
import static org.bytedeco.javacpp.LLVM.LLVMIntTypeInContext;
import static org.bytedeco.javacpp.LLVM.LLVMIntegerTypeKind;
import static org.bytedeco.javacpp.LLVM.LLVMLinkOnceAnyLinkage;
import static org.bytedeco.javacpp.LLVM.LLVMLinkOnceODRLinkage;
import static org.bytedeco.javacpp.LLVM.LLVMMDNodeInContext;
import static org.bytedeco.javacpp.LLVM.LLVMMDStringInContext;
import static org.bytedeco.javacpp.LLVM.LLVMMetadataTypeInContext;
import static org.bytedeco.javacpp.LLVM.LLVMModuleCreateWithNameInContext;
import static org.bytedeco.javacpp.LLVM.LLVMPointerType;
import static org.bytedeco.javacpp.LLVM.LLVMPointerTypeKind;
import static org.bytedeco.javacpp.LLVM.LLVMPositionBuilderAtEnd;
import static org.bytedeco.javacpp.LLVM.LLVMPositionBuilderBefore;
import static org.bytedeco.javacpp.LLVM.LLVMSetGC;
import static org.bytedeco.javacpp.LLVM.LLVMSetInitializer;
import static org.bytedeco.javacpp.LLVM.LLVMSetLinkage;
import static org.bytedeco.javacpp.LLVM.LLVMSetMetadata;
import static org.bytedeco.javacpp.LLVM.LLVMStructTypeInContext;
import static org.bytedeco.javacpp.LLVM.LLVMTypeOf;
import static org.bytedeco.javacpp.LLVM.LLVMVoidTypeInContext;
import static org.bytedeco.javacpp.LLVM.LLVMVoidTypeKind;
import static org.graalvm.compiler.debug.GraalError.shouldNotReachHere;
import static org.graalvm.compiler.debug.GraalError.unimplemented;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.bytedeco.javacpp.LLVM;
import org.bytedeco.javacpp.LLVM.LLVMAttributeRef;
import org.bytedeco.javacpp.LLVM.LLVMBasicBlockRef;
import org.bytedeco.javacpp.LLVM.LLVMBuilderRef;
import org.bytedeco.javacpp.LLVM.LLVMContextRef;
import org.bytedeco.javacpp.LLVM.LLVMModuleRef;
import org.bytedeco.javacpp.LLVM.LLVMTypeRef;
import org.bytedeco.javacpp.LLVM.LLVMValueRef;
import org.bytedeco.javacpp.PointerPointer;
import org.graalvm.compiler.core.common.calc.Condition;

import jdk.vm.ci.meta.JavaKind;
import org.graalvm.nativeimage.Platform;

public class LLVMIRBuilder {
    private static final String DEFAULT_INSTR_NAME = "";
    private static final int UNTRACKED_POINTER_ADDRESS_SPACE = 0;
    private static final int TRACKED_POINTER_ADDRESS_SPACE = 1;

    private LLVMContextRef context;
    private LLVMModuleRef module;
    private LLVMValueRef function;
    private LLVMBuilderRef builder;

    private String functionName;
    private final boolean trackPointers;
    private LLVMValueRef gcRegisterFunction;

    public LLVMIRBuilder(String functionName, LLVMContextRef context, boolean trackPointers) {
        this.context = context;
        this.functionName = functionName;
        this.trackPointers = trackPointers;

        this.module = LLVMModuleCreateWithNameInContext(functionName, context);
        this.builder = LLVMCreateBuilderInContext(context);

        /*
         * This function declares a GC-tracked pointer from an untracked value. This is needed as
         * the statepoint emission pass, which tracks live references in the function, doesn't
         * recognize an address space cast (see referenceType()) as declaring a new reference, but
         * it does a function return value.
         */
        gcRegisterFunction = addFunction("__svm_gc_register", functionType(referenceType(), longType()));
        LLVMSetLinkage(gcRegisterFunction, LLVMLinkOnceAnyLinkage);
        setAttribute(gcRegisterFunction, "alwaysinline");

        LLVMBasicBlockRef block = appendBasicBlock("main", gcRegisterFunction);
        positionAtEnd(block);
        LLVMValueRef arg = getParam(gcRegisterFunction, 0);
        LLVMValueRef ref = buildIntToPtr(arg, referenceType());
        buildRet(ref, gcRegisterFunction);
    }

    public LLVMModuleRef getModule() {
        return module;
    }

    public void addMainFunction(LLVMTypeRef type) {
        this.function = LLVMAddFunction(module, functionName, type);
//        LLVMSetGC(function, "statepoint-example");
        setAttribute(function, "noinline");
    }

    LLVMValueRef addFunction(String name, LLVMTypeRef type) {
        return LLVMAddFunction(module, name, type);
    }

    public LLVMValueRef getMainFunction() {
        return function;
    }

    void setAttribute(LLVMValueRef func, String attribute) {
//        int kind = LLVMGetEnumAttributeKindForName(attribute, attribute.length());
        LLVMAttributeRef attr;
//        if (kind != 0) {
//            attr = LLVMCreateEnumAttribute(context, kind, TRUE);
//        } else {
            String value = "true";
            attr = LLVMCreateStringAttribute(context, attribute, attribute.length(), value, value.length());
//        }
        LLVMAddAttributeAtIndex(func, (int) LLVMAttributeFunctionIndex, attr);
    }

    void setCallSiteAttribute(LLVMValueRef call, long index, String attribute) {
//        int kind = LLVMGetEnumAttributeKindForName(attribute, attribute.length());
        LLVMAttributeRef attr;
//        if (kind != 0) {
//            attr = LLVMCreateEnumAttribute(context, kind, TRUE);
//        } else {
            String value = "true";
            attr = LLVMCreateStringAttribute(context, attribute, attribute.length(), value, value.length());
//        }
        LLVMAddCallSiteAttribute(call, (int) index, attr);
    }

    public String getFunctionName() {
        return functionName;
    }

    LLVMBasicBlockRef appendBasicBlock(String name, LLVMValueRef func) {
        return LLVMAppendBasicBlockInContext(context, func, name);
    }

    public LLVMBasicBlockRef appendBasicBlock(String name) {
        return appendBasicBlock(name, function);
    }

    void positionAtStart() {
        LLVMValueRef firstInstruction = LLVMGetFirstInstruction(LLVMGetFirstBasicBlock(function));
        if (firstInstruction != null) {
            positionBefore(firstInstruction);
        }
    }

    void positionBefore(LLVMValueRef value) {
        LLVMPositionBuilderBefore(builder, value);
    }

    public void positionAtEnd(LLVMBasicBlockRef block) {
        LLVMPositionBuilderAtEnd(builder, block);
    }

    LLVMValueRef blockTerminator(LLVMBasicBlockRef block) {
        return LLVMGetBasicBlockTerminator(block);
    }

    /* Types */

    public LLVMTypeRef getLLVMType(JavaKind kind) {
        return getLLVMType(kind, false, false);
    }

    LLVMTypeRef getLLVMTypeForceRef(JavaKind kind) {
        return getLLVMType(kind, true, false);
    }

    LLVMTypeRef getLLVMTypeNoRef(JavaKind kind) {
        return getLLVMType(kind, false, true);
    }

    private LLVMTypeRef getLLVMType(JavaKind kind, boolean forceReference, boolean forceNoReference) {
        assert !(forceReference && forceNoReference);
        switch (kind) {
            case Boolean:
                return booleanType();
            case Byte:
                return byteType();
            case Short:
                return shortType();
            case Char:
                return charType();
            case Int:
                return intType();
            case Float:
                return floatType();
            case Long:
                return longType();
            case Double:
                return doubleType();
            case Object:
                if (forceReference) {
                    return referenceType();
                } else if (forceNoReference) {
                    return objectType(false);
                } else {
                    return objectType(true);
                }
            case Void:
                return voidType();
            case Illegal:
            default:
                throw shouldNotReachHere("Illegal type");
        }
    }

    private LLVMTypeRef[] getLLVMFunctionParamTypes(LLVMTypeRef functionType) {
        int numParams = LLVMCountParamTypes(functionType);
        PointerPointer<LLVMTypeRef> argTypesPointer = new PointerPointer<>(numParams);
        LLVMGetParamTypes(functionType, argTypesPointer);
        return IntStream.range(0, numParams).mapToObj(i -> argTypesPointer.get(LLVMTypeRef.class, i)).toArray(LLVMTypeRef[]::new);
    }

    boolean compatibleTypes(LLVMTypeRef a, LLVMTypeRef b) {
        if (LLVMGetTypeKind(a) != LLVMGetTypeKind(b)) {
            return false;
        }
        if (LLVMGetTypeKind(a) == LLVMIntegerTypeKind) {
            return LLVMGetIntTypeWidth(a) == LLVMGetIntTypeWidth(b);
        }
        if (LLVMGetTypeKind(a) == LLVMPointerTypeKind) {
            return LLVMGetPointerAddressSpace(a) == LLVMGetPointerAddressSpace(b);
        }
        return true;
    }

    int integerTypeWidth(LLVMTypeRef intType) {
        assert LLVMGetTypeKind(intType) == LLVMIntegerTypeKind;
        return LLVMGetIntTypeWidth(intType);
    }

    LLVMTypeRef getWiderType(LLVMTypeRef a, LLVMTypeRef b) {
        if (compatibleTypes(a, b)) {
            return a;
        }

        int kindA = LLVMGetTypeKind(a);
        int kindB = LLVMGetTypeKind(b);
        if (kindA == LLVMIntegerTypeKind && kindB == LLVMIntegerTypeKind) {
            return integerType(Math.max(integerTypeWidth(a), integerTypeWidth(b)));
        }
        throw shouldNotReachHere();
    }

    private String intrinsicType(LLVMTypeRef type) {
        switch (LLVMGetTypeKind(type)) {
            case LLVMIntegerTypeKind:
                return "i" + integerTypeWidth(type);
            case LLVMFloatTypeKind:
                return "f32";
            case LLVMDoubleTypeKind:
                return "f64";
            case LLVMVoidTypeKind:
                return "isVoid";
            case LLVMPointerTypeKind:
                return "p" + LLVMGetPointerAddressSpace(type) + intrinsicType(LLVMGetElementType(type));
            case LLVMFunctionTypeKind:
                String args = Arrays.stream(getLLVMFunctionParamTypes(type)).map(this::intrinsicType).collect(Collectors.joining(""));
                return "f_" + intrinsicType(LLVMGetReturnType(type)) + args + "f";
            default:
                throw shouldNotReachHere();
        }
    }

    LLVMTypeRef booleanType() {
        return integerType(1);
    }

    LLVMTypeRef byteType() {
        return integerType(8);
    }

    private LLVMTypeRef shortType() {
        return integerType(16);
    }

    private LLVMTypeRef charType() {
        return integerType(16);
    }

    LLVMTypeRef intType() {
        return integerType(32);
    }

    public LLVMTypeRef longType() {
        return integerType(64);
    }

    LLVMTypeRef integerType(int bits) {
        return LLVMIntTypeInContext(context, bits);
    }

    LLVMTypeRef floatType() {
        return LLVMFloatTypeInContext(context);
    }

    LLVMTypeRef doubleType() {
        return LLVMDoubleTypeInContext(context);
    }

    /*
     * Pointer types can be of two types: references and regular pointers. References are pointers
     * to Java objects which are tracked by the GC statepoint emission pass to create reference maps
     * at call sites. Regular pointers are not tracked and represent a non-java pointer or a Java
     * pointer in an uninterruptible method (which doesn't need statepoints). They are distinguished
     * by the pointer address space they live in (1, resp. 0).
     */

    public LLVMTypeRef pointerType(LLVMTypeRef type, boolean isReference) {
        return LLVMPointerType(type, pointerAddressSpace(isReference && trackPointers));
    }

    private int pointerAddressSpace(boolean tracked) {
        return tracked ? TRACKED_POINTER_ADDRESS_SPACE : UNTRACKED_POINTER_ADDRESS_SPACE;
    }

    boolean isReference(LLVMValueRef pointer) {
        return isReference(LLVMTypeOf(pointer));
    }

    private boolean isReference(LLVMTypeRef pointerType) {
        return LLVMGetTypeKind(pointerType) == LLVMPointerTypeKind && LLVMGetPointerAddressSpace(pointerType) == pointerAddressSpace(true);
    }

    /*
     * To allow an uninterruptible method to call an interruptible method, object parameters to the
     * call are transformed into tracked pointers just before the call, and therefore are tracked
     * pointers when trackPointers is false. referenceType() represents such a
     * "tracked in an untracked environment" pointer.
     */
    public LLVMTypeRef referenceType() {
        return LLVMPointerType(byteType(), pointerAddressSpace(true));
    }

    public LLVMTypeRef objectType(boolean isReference) {
        return pointerType(byteType(), isReference);
    }

    public LLVMTypeRef arrayType(LLVMTypeRef type, int length) {
        return LLVMArrayType(type, length);
    }

    public LLVMTypeRef structType(LLVMTypeRef... types) {
        return LLVMStructTypeInContext(context, new PointerPointer<>(types), types.length, FALSE);
    }

    public LLVMTypeRef functionType(LLVMTypeRef returnType, LLVMTypeRef... argTypes) {
        return functionType(returnType, false, argTypes);
    }

    LLVMTypeRef functionType(LLVMTypeRef returnType, boolean varargs, LLVMTypeRef... argTypes) {
        return LLVMFunctionType(returnType, new PointerPointer<>(argTypes), argTypes.length, varargs ? TRUE : FALSE);
    }

    private LLVMTypeRef voidType() {
        return LLVMVoidTypeInContext(context);
    }

    private LLVMTypeRef metadataType() {
        return LLVMMetadataTypeInContext(context);
    }

    /* Constants */

    LLVMValueRef constantBoolean(boolean x) {
        return constantInteger(x ? TRUE : FALSE, 1);
    }

    LLVMValueRef constantByte(byte x) {
        return constantInteger(x, 8);
    }

    LLVMValueRef constantShort(short x) {
        return constantInteger(x, 16);
    }

    LLVMValueRef constantChar(char x) {
        return constantInteger(x, 16);
    }

    public LLVMValueRef constantInt(int x) {
        return constantInteger(x, 32);
    }

    public LLVMValueRef constantLong(long x) {
        return constantInteger(x, 64);
    }

    LLVMValueRef constantInteger(long value, int bits) {
        return LLVMConstInt(integerType(bits), value, FALSE);
    }

    LLVMValueRef constantFloat(float x) {
        return LLVMConstReal(floatType(), x);
    }

    LLVMValueRef constantDouble(double x) {
        return LLVMConstReal(doubleType(), x);
    }

    public LLVMValueRef constantNull(LLVMTypeRef type) {
        return LLVMConstNull(type);
    }

    LLVMValueRef undef(LLVMTypeRef type) {
        return LLVMGetUndef(type);
    }

    LLVMValueRef buildGlobalString(String name) {
        return LLVMBuildGlobalString(builder, name + "\n", DEFAULT_INSTR_NAME);
    }

    /* Values */

    private LLVMValueRef getParam(LLVMValueRef func, int index) {
        return LLVMGetParam(func, index);
    }

    LLVMValueRef getParam(int index) {
        return getParam(function, index);
    }

    LLVMValueRef buildPhi(LLVMTypeRef phiType, LLVMValueRef[] incomingValues, LLVMBasicBlockRef[] incomingBlocks) {
        LLVMValueRef phi = LLVMBuildPhi(builder, phiType, DEFAULT_INSTR_NAME);
        LLVMAddIncoming(phi, new PointerPointer<>(incomingValues), new PointerPointer<>(incomingBlocks), incomingValues.length);
        return phi;
    }

    void addIncoming(LLVMValueRef phi, LLVMValueRef[] values, LLVMBasicBlockRef[] blocks) {
        assert values.length == blocks.length;
        LLVMAddIncoming(phi, new PointerPointer<>(values), new PointerPointer<>(blocks), blocks.length);
    }

    public LLVMValueRef getExternalGlobal(String name, boolean isReference) {
        LLVMValueRef val = getGlobal(name);
        if (val == null) {
            val = LLVMAddGlobalInAddressSpace(module, objectType(isReference), name, pointerAddressSpace(trackPointers && isReference));
            LLVMSetLinkage(val, LLVMExternalLinkage);
        }
        return val;
    }

    public LLVMValueRef getUniqueGlobal(String name, LLVMTypeRef type) {
        LLVMValueRef global = getGlobal(name);
        if (global == null) {
            global = LLVMAddGlobalInAddressSpace(module, type, name, pointerAddressSpace(isReference(type)));
            LLVMSetInitializer(global, LLVMConstNull(type));
            LLVMSetLinkage(global, LLVMLinkOnceODRLinkage);
        }
        return global;
    }

    private LLVMValueRef getGlobal(String name) {
        return LLVMGetNamedGlobal(module, name);
    }

    LLVMValueRef getFunction(String name, LLVMTypeRef type) {
        LLVMValueRef func = LLVMGetNamedFunction(module, name);
        if (func == null) {
            func = LLVMAddFunction(module, name, type);
            LLVMSetLinkage(func, LLVMExternalLinkage);
        }

        return func;
    }

    LLVMValueRef register(String name) {
        String nameEncoding = name + "\00";
        LLVMValueRef[] vals = new LLVMValueRef[]{LLVMMDStringInContext(context, nameEncoding, nameEncoding.length())};
        return LLVMMDNodeInContext(context, new PointerPointer<>(vals), vals.length);
    }

    LLVMValueRef buildReadRegister(LLVMValueRef register) {
        LLVMTypeRef readRegisterType = functionType(longType(), metadataType());
        return buildIntrinsicCall("llvm.read_register.i64", readRegisterType, register);
    }

    LLVMValueRef buildExtractValue(LLVMValueRef struct, int i) {
        return LLVMBuildExtractValue(builder, struct, i, DEFAULT_INSTR_NAME);
    }

    public void setMetadata(LLVMValueRef instr, String kind, LLVMValueRef metadata) {
        LLVMSetMetadata(instr, LLVMGetMDKindIDInContext(context, kind, kind.length()), metadata);
    }

    /* Control flow */
    public static final AtomicLong nextPatchpointId = new AtomicLong(0);

    public LLVMValueRef buildCall(LLVMValueRef callee, LLVMValueRef... args) {
        return LLVMBuildCall(builder, callee, new PointerPointer<>(args), args.length, DEFAULT_INSTR_NAME);
    }

    LLVMValueRef buildCall(LLVMValueRef callee, long statepointId, LLVMValueRef... args) {
        LLVMValueRef call;
        if (Platform.includedIn(Platform.AMD64.class)) {
            call = buildCall(callee, args);

            String key = "statepoint-id";
            String value = Long.toString(statepointId);
            LLVMAttributeRef attribute = LLVMCreateStringAttribute(context, key, key.length(), value, value.length());
            LLVMAddCallSiteAttribute(call, (int) LLVMAttributeFunctionIndex, attribute);
        } else {
            call = buildCall(callee, args);
            buildStackmap(constantLong(statepointId));
//            /* The platform doesn't support statepoints */
//            LLVMTypeRef returnType = LLVMGetReturnType(LLVMGetElementType(LLVMTypeOf(callee)));
//            LLVMTypeRef patchpointType = functionType(returnType, true, longType(), intType(), objectType(false), intType());
//
//            LLVMValueRef[] patchpointArgs = new LLVMValueRef[4 + args.length];
//            patchpointArgs[0] = constantLong(statepointId);
//            patchpointArgs[1] = constantInt(0); /* Patch bytes */
//            patchpointArgs[2] = buildBitcast(callee, objectType(false));
//            patchpointArgs[3] = constantInt(args.length);
//            System.arraycopy(args, 0, patchpointArgs, 4, args.length);
//
//            call = buildIntrinsicCall("llvm.experimental.patchpoint." + intrinsicType(returnType), patchpointType, patchpointArgs);
        }
        return call;
    }

    public void buildStackmap(LLVMValueRef patchpointId, LLVMValueRef... liveValues) {
        LLVMTypeRef stackmapType = functionType(voidType(), true, longType(), intType());

        LLVMValueRef[] allArgs = new LLVMValueRef[2 + liveValues.length];
        allArgs[0] = patchpointId;
        allArgs[1] = constantInt(0);
        System.arraycopy(liveValues, 0, allArgs, 2, liveValues.length);

        buildIntrinsicCall("llvm.experimental.stackmap", stackmapType, allArgs);
    }

    LLVMValueRef buildSetjmp(LLVMValueRef buffer) {
        LLVMTypeRef setjmpType = functionType(intType(), objectType(false));
//        return buildIntrinsicCall("llvm.eh.sjlj.setjmp", setjmpType, buffer);
        LLVMValueRef setjmp = getExternalGlobal("setjmp", false);
        return buildCall(buildBitcast(setjmp, pointerType(setjmpType, false)), buffer);
    }

    public void buildLongjmp(LLVMValueRef buffer) {
        LLVMTypeRef longjmpType = functionType(voidType(), objectType(false), intType());
//        buildIntrinsicCall("llvm.eh.sjlj.longjmp", longjmpType, buffer);
        LLVMValueRef longjmp = getExternalGlobal("longjmp", false);
        buildCall(buildBitcast(longjmp, pointerType(longjmpType, false)), buffer, constantInt(0));
    }

    private LLVMValueRef buildIntrinsicCall(String name, LLVMTypeRef type, LLVMValueRef... args) {
        LLVMValueRef intrinsic = getFunction(name, type);
        return buildCall(intrinsic, args);
    }

    public void buildRetVoid() {
        LLVMBuildRetVoid(builder);
    }

    void buildRet(LLVMValueRef value, LLVMValueRef func) {
        LLVMValueRef castedReturn = buildConvert(value, LLVMGetReturnType(LLVMGetElementType(LLVMTypeOf(func))));
        LLVMBuildRet(builder, castedReturn);
    }

    public void buildRet(LLVMValueRef value) {
        buildRet(value, function);
    }

    void buildBranch(LLVMBasicBlockRef block) {
        LLVMBuildBr(builder, block);
    }

    LLVMValueRef buildIf(LLVMValueRef condition, LLVMBasicBlockRef thenBlock, LLVMBasicBlockRef elseBlock) {
        return LLVMBuildCondBr(builder, condition, thenBlock, elseBlock);
    }

    LLVMValueRef buildSwitch(LLVMValueRef value, LLVMBasicBlockRef defaultBlock, LLVMValueRef[] switchValues, LLVMBasicBlockRef[] switchBlocks) {
        assert switchValues.length == switchBlocks.length;

        LLVMValueRef switchVal = LLVMBuildSwitch(builder, value, defaultBlock, switchBlocks.length);
        for (int i = 0; i < switchBlocks.length; ++i) {
            LLVMValueRef castedValue = buildConvert(switchValues[i], LLVMTypeOf(value));
            LLVMAddCase(switchVal, castedValue, switchBlocks[i]);
        }
        return switchVal;
    }

    public void buildUnreachable() {
        LLVMBuildUnreachable(builder);
    }

    void buildDebugtrap() {
        buildIntrinsicCall("llvm.debugtrap", functionType(voidType()));
    }

    private LLVMValueRef buildInlineAsm(LLVMTypeRef functionType, String asm, String constraints, boolean hasSideEffects, boolean alignStack) {
        return LLVMConstInlineAsm(functionType, asm, constraints, hasSideEffects ? TRUE : FALSE, alignStack ? TRUE : FALSE);
    }

    public LLVMValueRef buildFunctionEntryCountMetadata(LLVMValueRef count) {
        String functionEntryCountName = "function_entry_count";
        LLVMValueRef[] values = new LLVMValueRef[2];
        values[0] = LLVMMDStringInContext(context, functionEntryCountName, functionEntryCountName.length());
        values[1] = count;
        return LLVMMDNodeInContext(context, new PointerPointer<>(values), values.length);
    }

    LLVMValueRef buildBranchWeightsMetadata(LLVMValueRef... weights) {
        String branchWeightsName = "branch_weights";
        LLVMValueRef[] values = new LLVMValueRef[weights.length + 1];
        values[0] = LLVMMDStringInContext(context, branchWeightsName, branchWeightsName.length());
        System.arraycopy(weights, 0, values, 1, weights.length);
        return LLVMMDNodeInContext(context, new PointerPointer<>(values), values.length);
    }

    /* Comparisons */

    LLVMValueRef buildIsNull(LLVMValueRef value) {
        return LLVMBuildIsNull(builder, value, DEFAULT_INSTR_NAME);
    }

    LLVMValueRef buildCompare(Condition cond, LLVMValueRef x, LLVMValueRef y, boolean unordered) {
        int leftTypeKind = LLVMGetTypeKind(LLVMTypeOf(x));
        if (leftTypeKind == LLVMIntegerTypeKind) {
            return buildICmp(LLVMUtils.getLLVMIntCond(cond), x, y);
        }
        if (leftTypeKind == LLVMPointerTypeKind) {
            LLVMValueRef castedX = buildConvert(x, longType());
            return buildICmp(LLVMUtils.getLLVMIntCond(cond), castedX, y);
        }
        if (leftTypeKind == LLVMFloatTypeKind || leftTypeKind == LLVMDoubleTypeKind) {
            return buildFCmp(LLVMUtils.getLLVMRealCond(cond, unordered), x, y);
        }
        throw unimplemented();
    }

    private LLVMValueRef buildICmp(int type, LLVMValueRef x, LLVMValueRef y) {
        LLVMValueRef castedY = buildConvert(y, LLVMTypeOf(x));
        return LLVMBuildICmp(builder, type, x, castedY, DEFAULT_INSTR_NAME);
    }

    private LLVMValueRef buildFCmp(int type, LLVMValueRef x, LLVMValueRef y) {
        LLVMValueRef castedY = buildConvert(y, LLVMTypeOf(x));
        return LLVMBuildFCmp(builder, type, x, castedY, DEFAULT_INSTR_NAME);
    }

    LLVMValueRef buildSelect(LLVMValueRef condition, LLVMValueRef trueVal, LLVMValueRef falseVal) {
        return LLVMBuildSelect(builder, condition, trueVal, falseVal, DEFAULT_INSTR_NAME);
    }

    /* Arithmetic */

    private interface UnaryBuilder {
        LLVMValueRef build(LLVMBuilderRef builder, LLVMValueRef a, String str);
    }

    private interface BinaryBuilder {
        LLVMValueRef build(LLVMBuilderRef builder, LLVMValueRef a, LLVMValueRef b, String str);
    }

    LLVMValueRef buildNeg(LLVMValueRef arg) {
        int type = LLVMGetTypeKind(LLVMTypeOf(arg));

        UnaryBuilder unaryBuilder;
        if (type == LLVMIntegerTypeKind) {
            unaryBuilder = LLVM::LLVMBuildNeg;
        } else if (type == LLVMFloatTypeKind || type == LLVMDoubleTypeKind) {
            unaryBuilder = LLVM::LLVMBuildFNeg;
        } else {
            throw shouldNotReachHere();
        }

        return unaryBuilder.build(builder, arg, DEFAULT_INSTR_NAME);
    }

    LLVMValueRef buildAdd(LLVMValueRef a, LLVMValueRef b) {
        return buildBinaryNumberOp(a, b, LLVM::LLVMBuildAdd, LLVM::LLVMBuildFAdd);
    }

    LLVMValueRef buildSub(LLVMValueRef a, LLVMValueRef b) {
        return buildBinaryNumberOp(a, b, LLVM::LLVMBuildSub, LLVM::LLVMBuildFSub);
    }

    LLVMValueRef buildMul(LLVMValueRef a, LLVMValueRef b) {
        return buildBinaryNumberOp(a, b, LLVM::LLVMBuildMul, LLVM::LLVMBuildFMul);
    }

    LLVMValueRef buildDiv(LLVMValueRef a, LLVMValueRef b) {
        return buildBinaryNumberOp(a, b, LLVM::LLVMBuildSDiv, LLVM::LLVMBuildFDiv);
    }

    LLVMValueRef buildRem(LLVMValueRef a, LLVMValueRef b) {
        return buildBinaryNumberOp(a, b, LLVM::LLVMBuildSRem, LLVM::LLVMBuildFRem);
    }

    LLVMValueRef buildUDiv(LLVMValueRef a, LLVMValueRef b) {
        return buildBinaryNumberOp(a, b, LLVM::LLVMBuildUDiv, null);
    }

    LLVMValueRef buildURem(LLVMValueRef a, LLVMValueRef b) {
        return buildBinaryNumberOp(a, b, LLVM::LLVMBuildURem, null);
    }

    private LLVMValueRef buildBinaryNumberOp(LLVMValueRef rawA, LLVMValueRef rawB, BinaryBuilder integerBuilder, BinaryBuilder realBuilder) {
        LLVMValueRef a = (LLVMGetTypeKind(LLVMTypeOf(rawA)) == LLVMPointerTypeKind) ? buildBitcast(rawA, longType()) : rawA;
        LLVMValueRef b = (LLVMGetTypeKind(LLVMTypeOf(rawB)) == LLVMPointerTypeKind) ? buildBitcast(rawB, longType()) : rawB;

        int kindA = LLVMGetTypeKind(LLVMTypeOf(a));
        int kindB = LLVMGetTypeKind(LLVMTypeOf(b));
        assert kindA == kindB;

        BinaryBuilder binaryBuilder;
        if (integerBuilder != null && kindA == LLVMIntegerTypeKind) {
            int aWidth = LLVMGetIntTypeWidth(LLVMTypeOf(a));
            int bWidth = LLVMGetIntTypeWidth(LLVMTypeOf(b));
            if (aWidth > bWidth) {
                b = buildConvert(b, LLVMTypeOf(a));
            } else if (bWidth > aWidth) {
                a = buildConvert(a, LLVMTypeOf(b));
            }
            binaryBuilder = integerBuilder;
        } else if (realBuilder != null && (kindA == LLVMFloatTypeKind || kindA == LLVMDoubleTypeKind)) {
            binaryBuilder = realBuilder;
        } else {
            throw shouldNotReachHere();
        }

        return binaryBuilder.build(builder, a, b, DEFAULT_INSTR_NAME);
    }

    LLVMValueRef buildAbs(LLVMValueRef value) {
        LLVMTypeRef valueType = LLVMTypeOf(value);
        int valueKind = LLVMGetTypeKind(valueType);
        assert valueKind == LLVMFloatTypeKind || valueKind == LLVMDoubleTypeKind;

        String absName = "llvm.fabs." + intrinsicType(valueType);
        LLVMTypeRef absType = functionType(valueType, valueType);

        return buildIntrinsicCall(absName, absType, value);
    }

    LLVMValueRef buildLog(LLVMValueRef value) {
        LLVMTypeRef valueType = LLVMTypeOf(value);
        int valueKind = LLVMGetTypeKind(valueType);
        assert valueKind == LLVMFloatTypeKind || valueKind == LLVMDoubleTypeKind;

        String logName = "llvm.log." + intrinsicType(valueType);
        LLVMTypeRef logType = functionType(valueType, valueType);

        return buildIntrinsicCall(logName, logType, value);
    }

    LLVMValueRef buildLog10(LLVMValueRef value) {
        LLVMTypeRef valueType = LLVMTypeOf(value);
        int valueKind = LLVMGetTypeKind(valueType);
        assert valueKind == LLVMFloatTypeKind || valueKind == LLVMDoubleTypeKind;

        String log10Name = "llvm.log10." + intrinsicType(valueType);
        LLVMTypeRef log10Type = functionType(valueType, valueType);

        return buildIntrinsicCall(log10Name, log10Type, value);
    }

    LLVMValueRef buildSqrt(LLVMValueRef value) {
        LLVMTypeRef valueType = LLVMTypeOf(value);
        int valueKind = LLVMGetTypeKind(valueType);
        assert valueKind == LLVMFloatTypeKind || valueKind == LLVMDoubleTypeKind;

        String sqrtName = "llvm.sqrt." + intrinsicType(valueType);
        LLVMTypeRef sqrtType = functionType(valueType, valueType);

        return buildIntrinsicCall(sqrtName, sqrtType, value);
    }

    LLVMValueRef buildCos(LLVMValueRef value) {
        LLVMTypeRef valueType = LLVMTypeOf(value);
        int valueKind = LLVMGetTypeKind(valueType);
        assert valueKind == LLVMFloatTypeKind || valueKind == LLVMDoubleTypeKind;

        String cosName = "llvm.cos." + intrinsicType(valueType);
        LLVMTypeRef cosType = functionType(valueType, valueType);

        return buildIntrinsicCall(cosName, cosType, value);
    }

    LLVMValueRef buildSin(LLVMValueRef value) {
        LLVMTypeRef valueType = LLVMTypeOf(value);
        int valueKind = LLVMGetTypeKind(valueType);
        assert valueKind == LLVMFloatTypeKind || valueKind == LLVMDoubleTypeKind;

        String sinName = "llvm.sin." + intrinsicType(valueType);
        LLVMTypeRef sinType = functionType(valueType, valueType);

        return buildIntrinsicCall(sinName, sinType, value);
    }

    LLVMValueRef buildExp(LLVMValueRef value) {
        LLVMTypeRef valueType = LLVMTypeOf(value);
        int valueKind = LLVMGetTypeKind(valueType);
        assert valueKind == LLVMFloatTypeKind || valueKind == LLVMDoubleTypeKind;

        String expName = "llvm.exp." + intrinsicType(valueType);
        LLVMTypeRef expType = functionType(valueType, valueType);

        return buildIntrinsicCall(expName, expType, value);
    }

    LLVMValueRef buildPow(LLVMValueRef x, LLVMValueRef y) {
        LLVMTypeRef valueType = LLVMTypeOf(x);
        assert compatibleTypes(LLVMTypeOf(y), valueType);

        int valueKind = LLVMGetTypeKind(valueType);
        assert valueKind == LLVMFloatTypeKind || valueKind == LLVMDoubleTypeKind;

        String powName = "llvm.pow." + intrinsicType(valueType);
        LLVMTypeRef powType = functionType(valueType, valueType, valueType);

        return buildIntrinsicCall(powName, powType, x, y);
    }

    LLVMValueRef buildBswap(LLVMValueRef value) {
        LLVMTypeRef valueType = LLVMTypeOf(value);
        int valueKind = LLVMGetTypeKind(valueType);
        assert valueKind == LLVMIntegerTypeKind;

        String bswapName = "llvm.bswap." + intrinsicType(valueType);
        LLVMTypeRef bswapType = functionType(valueType, valueType);

        return buildIntrinsicCall(bswapName, bswapType, value);
    }

    /* Bitwise */

    LLVMValueRef buildNot(LLVMValueRef input) {
        return LLVMBuildNot(builder, input, DEFAULT_INSTR_NAME);
    }

    LLVMValueRef buildAnd(LLVMValueRef a, LLVMValueRef b) {
        return buildBitwise(LLVM::LLVMBuildAnd, a, b);
    }

    LLVMValueRef buildOr(LLVMValueRef a, LLVMValueRef b) {
        return buildBitwise(LLVM::LLVMBuildOr, a, b);
    }

    LLVMValueRef buildXor(LLVMValueRef a, LLVMValueRef b) {
        return buildBitwise(LLVM::LLVMBuildXor, a, b);
    }

    LLVMValueRef buildShl(LLVMValueRef a, LLVMValueRef b) {
        return buildBitwise(LLVM::LLVMBuildShl, a, b);
    }

    LLVMValueRef buildShr(LLVMValueRef a, LLVMValueRef b) {
        return buildBitwise(LLVM::LLVMBuildAShr, a, b);
    }

    LLVMValueRef buildUShr(LLVMValueRef a, LLVMValueRef b) {
        return buildBitwise(LLVM::LLVMBuildLShr, a, b);
    }

    private LLVMValueRef buildBitwise(BinaryBuilder binaryBuilder, LLVMValueRef rawA, LLVMValueRef rawB) {
        LLVMValueRef a = (LLVMGetTypeKind(LLVMTypeOf(rawA)) == LLVMPointerTypeKind) ? buildBitcast(rawA, longType()) : rawA;
        LLVMValueRef b = (LLVMGetTypeKind(LLVMTypeOf(rawB)) == LLVMPointerTypeKind) ? buildBitcast(rawB, longType()) : rawB;

        int aWidth = LLVMGetIntTypeWidth(LLVMTypeOf(a));
        int bWidth = LLVMGetIntTypeWidth(LLVMTypeOf(b));

        if (aWidth > bWidth) {
            b = buildConvert(b, LLVMTypeOf(a));
        } else if (aWidth < bWidth) {
            a = buildConvert(a, LLVMTypeOf(b));
        }
        return binaryBuilder.build(builder, a, b, DEFAULT_INSTR_NAME);
    }

    LLVMValueRef buildCtlz(LLVMValueRef val) {
        int width = LLVMGetIntTypeWidth(LLVMTypeOf(val));
        LLVMTypeRef ctlzType = functionType(integerType(width), integerType(width), booleanType());

        return buildIntrinsicCall("llvm.ctlz.i" + width, ctlzType, val, constantBoolean(true));
    }

    LLVMValueRef buildCttz(LLVMValueRef val) {
        int width = LLVMGetIntTypeWidth(LLVMTypeOf(val));
        LLVMTypeRef cttzType = functionType(integerType(width), integerType(width), booleanType());

        return buildIntrinsicCall("llvm.cttz.i" + width, cttzType, val, constantBoolean(true));
    }

    /* Conversions */

    private interface ConversionBuilder {
        LLVMValueRef build(LLVMBuilderRef builder, LLVMValueRef input, LLVMTypeRef type, String str);
    }

    LLVMValueRef buildConvert(LLVMValueRef value, LLVMTypeRef type) {
        LLVMTypeRef valueType = LLVMTypeOf(value);
        int valueKind = LLVMGetTypeKind(valueType);
        int typeKind = LLVMGetTypeKind(type);

        ConversionBuilder converter;
        if (typeKind == LLVMPointerTypeKind || valueKind == LLVMPointerTypeKind) {
            return buildBitcast(value, type);
        } else if (valueKind == LLVMIntegerTypeKind && typeKind == LLVMIntegerTypeKind) {
            int valueSize = LLVMGetIntTypeWidth(valueType);
            int typeSize = LLVMGetIntTypeWidth(type);
            if (valueSize > typeSize) {
                converter = LLVM::LLVMBuildTrunc;
            } else if (valueSize < typeSize) {
                converter = (valueSize == 1) ? LLVM::LLVMBuildZExt : LLVM::LLVMBuildSExt;
            } else {
                return value;
            }
        } else if (typeKind == LLVMIntegerTypeKind && (valueKind == LLVMFloatTypeKind || valueKind == LLVMDoubleTypeKind)) {
            converter = LLVM::LLVMBuildFPToSI;
        } else if ((typeKind == LLVMFloatTypeKind || typeKind == LLVMDoubleTypeKind) && valueKind == LLVMIntegerTypeKind) {
            converter = LLVM::LLVMBuildSIToFP;
        } else if (typeKind == LLVMFloatTypeKind && valueKind == LLVMDoubleTypeKind || typeKind == LLVMDoubleTypeKind && valueKind == LLVMFloatTypeKind) {
            converter = LLVM::LLVMBuildFPCast;
        } else {
            if (typeKind == valueKind) {
                return value;
            }
            throw shouldNotReachHere("invalid cast " + valueKind + " " + typeKind);
        }

        return converter.build(builder, value, type, DEFAULT_INSTR_NAME);
    }

    public LLVMValueRef buildBitcast(LLVMValueRef value, LLVMTypeRef type) {
        LLVMTypeRef valueType = LLVMTypeOf(value);
        int valueKind = LLVMGetTypeKind(valueType);
        int typeKind = LLVMGetTypeKind(type);

        ConversionBuilder converter;
        if (valueKind == LLVMIntegerTypeKind && typeKind == LLVMPointerTypeKind) {
            if (isReference(type)) {
                value = buildCall(gcRegisterFunction, value);
                converter = LLVM::LLVMBuildBitCast;
            } else {
                converter = LLVM::LLVMBuildIntToPtr;
            }
        } else if (valueKind == LLVMPointerTypeKind && typeKind == LLVMIntegerTypeKind) {
            converter = LLVM::LLVMBuildPtrToInt;
        } else {
            if (isReference(type) && !isReference(valueType)) {
                value = buildCall(gcRegisterFunction, buildPtrToInt(value, longType()));
            }
            converter = (isReference(value) && !isReference(type)) ? LLVM::LLVMBuildAddrSpaceCast : LLVM::LLVMBuildBitCast;
        }

        return converter.build(builder, value, type, DEFAULT_INSTR_NAME);
    }

    private LLVMValueRef buildIntToPtr(LLVMValueRef value, LLVMTypeRef type) {
        return LLVMBuildIntToPtr(builder, value, type, DEFAULT_INSTR_NAME);
    }

    private LLVMValueRef buildPtrToInt(LLVMValueRef value, LLVMTypeRef type) {
        return LLVMBuildPtrToInt(builder, value, type, DEFAULT_INSTR_NAME);
    }

    LLVMValueRef buildFPToSI(LLVMValueRef inputVal, LLVMTypeRef destType) {
        return LLVMBuildFPToSI(builder, inputVal, destType, DEFAULT_INSTR_NAME);
    }

    LLVMValueRef buildSIToFP(LLVMValueRef inputVal, LLVMTypeRef destType) {
        return LLVMBuildSIToFP(builder, inputVal, destType, DEFAULT_INSTR_NAME);
    }

    LLVMValueRef buildFPCast(LLVMValueRef inputVal, LLVMTypeRef destType) {
        return LLVMBuildFPCast(builder, inputVal, destType, DEFAULT_INSTR_NAME);
    }

    LLVMValueRef buildTrunc(LLVMValueRef inputVal, int toBits) {
        assert toBits <= LLVMGetIntTypeWidth(LLVMTypeOf(inputVal));
        return LLVMBuildTrunc(builder, inputVal, integerType(toBits), DEFAULT_INSTR_NAME);
    }

    LLVMValueRef buildSExt(LLVMValueRef inputVal, int toBits) {
        return LLVMBuildSExt(builder, inputVal, integerType(toBits), DEFAULT_INSTR_NAME);
    }

    LLVMValueRef buildZExt(LLVMValueRef inputVal, int toBits) {
        return LLVMBuildZExt(builder, inputVal, integerType(toBits), DEFAULT_INSTR_NAME);
    }

    /* Memory */

    public LLVMValueRef buildGEP(LLVMValueRef base, LLVMTypeRef type, LLVMValueRef... indices) {
        LLVMValueRef castedBase = buildBitcast(base, type);
        return LLVMBuildGEP(builder, castedBase, new PointerPointer<>(indices), indices.length, DEFAULT_INSTR_NAME);
    }

    LLVMValueRef buildLoad(LLVMValueRef address, LLVMTypeRef type) {
        LLVMValueRef castedAddress = buildBitcast(address, pointerType(type, isReference(type)));
        return LLVMBuildLoad(builder, castedAddress, DEFAULT_INSTR_NAME);
    }

    public void buildStore(LLVMValueRef value, LLVMValueRef address) {
        LLVMValueRef castedAddress = buildBitcast(address, pointerType(LLVMTypeOf(value), isReference(value)));
        LLVMBuildStore(builder, value, castedAddress);
    }

    LLVMValueRef buildArrayAlloca(int slots) {
        return LLVMBuildArrayAlloca(builder, objectType(false), constantInt(slots), DEFAULT_INSTR_NAME);
    }

    void buildPrefetch(LLVMValueRef address) {
        LLVMTypeRef prefetchType = functionType(voidType(), objectType(isReference(address)), intType(), intType(), intType());
        /* llvm.prefetch(address, WRITE, NO_LOCALITY, DATA) */
        buildIntrinsicCall("llvm.prefetch", prefetchType, address, constantInt(1), constantInt(0), constantInt(1));
    }

    public LLVMValueRef buildReturnAddress(LLVMValueRef level) {
        LLVMTypeRef returnAddressType = functionType(objectType(false), intType());
        return buildIntrinsicCall("llvm.returnaddress", returnAddressType, level);
    }

    LLVMValueRef buildFrameAddress(LLVMValueRef level) {
        LLVMTypeRef frameAddressType = functionType(objectType(false), intType());
        return buildIntrinsicCall("llvm.frameaddress", frameAddressType, level);
    }

    /* Atomic */

    void buildFence() {
        LLVMBuildFence(builder, LLVMAtomicOrderingSequentiallyConsistent, FALSE, DEFAULT_INSTR_NAME);
    }

    LLVMValueRef buildCmpxchg(LLVMValueRef address, LLVMValueRef expectedValue, LLVMValueRef newValue) {
        LLVMTypeRef valueType = LLVMTypeOf(expectedValue);
        LLVMValueRef castedAddress = buildBitcast(address, pointerType(valueType, isReference(address)));
        LLVMValueRef castedNewValue = buildConvert(newValue, valueType);
        return LLVMBuildAtomicCmpXchg(builder, castedAddress, expectedValue, castedNewValue, LLVMAtomicOrderingSequentiallyConsistent, LLVMAtomicOrderingSequentiallyConsistent, FALSE);
    }

    LLVMValueRef buildAtomicXchg(LLVMValueRef address, LLVMValueRef value) {
        return buildAtomicRMW(LLVMAtomicRMWBinOpXchg, address, value);
    }

    LLVMValueRef buildAtomicAdd(LLVMValueRef address, LLVMValueRef value) {
        return buildAtomicRMW(LLVMAtomicRMWBinOpAdd, address, value);
    }

    private LLVMValueRef buildAtomicRMW(int operation, LLVMValueRef address, LLVMValueRef value) {
        LLVMTypeRef originalValueType = LLVMTypeOf(value);
        boolean pointerOp = LLVMGetTypeKind(originalValueType) == LLVMPointerTypeKind;
        LLVMValueRef castedValue = (pointerOp) ? buildConvert(value, longType()) : value;
        LLVMValueRef castedAddress = buildBitcast(address, pointerType(LLVMTypeOf(castedValue), isReference(address)));
        LLVMValueRef atomicRMW = LLVMBuildAtomicRMW(builder, operation, castedAddress, castedValue, LLVMAtomicOrderingSequentiallyConsistent, FALSE);
        return (pointerOp) ? buildConvert(atomicRMW, originalValueType) : atomicRMW;
    }

    void buildInlineConsumeValue(LLVMValueRef value) {
        LLVMValueRef consumeValueSnippet = buildInlineAsm(functionType(voidType(), LLVMTypeOf(value)), "NOP", "r", false, false);
        LLVMValueRef call = buildCall(consumeValueSnippet, value);
        setCallSiteAttribute(call, LLVMAttributeFunctionIndex, "gc-leaf-function");
    }
}
