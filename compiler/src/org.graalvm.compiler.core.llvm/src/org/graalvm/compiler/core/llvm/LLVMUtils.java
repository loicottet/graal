/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package org.graalvm.compiler.core.llvm;

import static org.bytedeco.javacpp.LLVM.LLVMDoubleTypeInContext;
import static org.bytedeco.javacpp.LLVM.LLVMDoubleTypeKind;
import static org.bytedeco.javacpp.LLVM.LLVMFloatTypeInContext;
import static org.bytedeco.javacpp.LLVM.LLVMFloatTypeKind;
import static org.bytedeco.javacpp.LLVM.LLVMGetIntTypeWidth;
import static org.bytedeco.javacpp.LLVM.LLVMGetTypeKind;
import static org.bytedeco.javacpp.LLVM.LLVMInt64TypeInContext;
import static org.bytedeco.javacpp.LLVM.LLVMInt8TypeInContext;
import static org.bytedeco.javacpp.LLVM.LLVMIntEQ;
import static org.bytedeco.javacpp.LLVM.LLVMIntNE;
import static org.bytedeco.javacpp.LLVM.LLVMIntSGE;
import static org.bytedeco.javacpp.LLVM.LLVMIntSGT;
import static org.bytedeco.javacpp.LLVM.LLVMIntSLE;
import static org.bytedeco.javacpp.LLVM.LLVMIntSLT;
import static org.bytedeco.javacpp.LLVM.LLVMIntTypeInContext;
import static org.bytedeco.javacpp.LLVM.LLVMIntUGE;
import static org.bytedeco.javacpp.LLVM.LLVMIntUGT;
import static org.bytedeco.javacpp.LLVM.LLVMIntULE;
import static org.bytedeco.javacpp.LLVM.LLVMIntULT;
import static org.bytedeco.javacpp.LLVM.LLVMIntegerTypeKind;
import static org.bytedeco.javacpp.LLVM.LLVMIsUndef;
import static org.bytedeco.javacpp.LLVM.LLVMPointerType;
import static org.bytedeco.javacpp.LLVM.LLVMPointerTypeKind;
import static org.bytedeco.javacpp.LLVM.LLVMPrintValueToString;
import static org.bytedeco.javacpp.LLVM.LLVMRealOEQ;
import static org.bytedeco.javacpp.LLVM.LLVMRealOGE;
import static org.bytedeco.javacpp.LLVM.LLVMRealOGT;
import static org.bytedeco.javacpp.LLVM.LLVMRealOLE;
import static org.bytedeco.javacpp.LLVM.LLVMRealOLT;
import static org.bytedeco.javacpp.LLVM.LLVMRealONE;
import static org.bytedeco.javacpp.LLVM.LLVMRealUEQ;
import static org.bytedeco.javacpp.LLVM.LLVMRealUGE;
import static org.bytedeco.javacpp.LLVM.LLVMRealUGT;
import static org.bytedeco.javacpp.LLVM.LLVMRealULE;
import static org.bytedeco.javacpp.LLVM.LLVMRealULT;
import static org.bytedeco.javacpp.LLVM.LLVMRealUNE;
import static org.bytedeco.javacpp.LLVM.LLVMTypeOf;
import static org.graalvm.compiler.debug.GraalError.shouldNotReachHere;
import static org.graalvm.compiler.debug.GraalError.unimplemented;

import java.util.BitSet;

import org.bytedeco.javacpp.LLVM.LLVMContextRef;
import org.bytedeco.javacpp.LLVM.LLVMTypeRef;
import org.bytedeco.javacpp.LLVM.LLVMValueRef;
import org.bytedeco.javacpp.Pointer;
import org.graalvm.compiler.core.common.LIRKind;
import org.graalvm.compiler.core.common.NumUtil;
import org.graalvm.compiler.core.common.calc.Condition;
import org.graalvm.compiler.core.common.spi.LIRKindTool;
import org.graalvm.compiler.lir.ConstantValue;
import org.graalvm.compiler.lir.Variable;
import org.graalvm.compiler.lir.VirtualStackSlot;

import jdk.vm.ci.meta.AllocatableValue;
import jdk.vm.ci.meta.Constant;
import jdk.vm.ci.meta.PlatformKind;
import jdk.vm.ci.meta.Value;
import jdk.vm.ci.meta.ValueKind;

public class LLVMUtils {
    public static final int FALSE = 0;
    public static final int TRUE = 1;
    public static final Pointer NULL = null;

    public static final class DebugLevel {
        public static final int NONE = 0;
        public static final int FUNCTION = 1;
        public static final int BLOCK = 2;
        public static final int NODE = 3;
    }

    static int getLLVMIntCond(Condition cond) {
        switch (cond) {
            case EQ:
                return LLVMIntEQ;
            case NE:
                return LLVMIntNE;
            case LT:
                return LLVMIntSLT;
            case LE:
                return LLVMIntSLE;
            case GT:
                return LLVMIntSGT;
            case GE:
                return LLVMIntSGE;
            case AE:
                return LLVMIntUGE;
            case BE:
                return LLVMIntULE;
            case AT:
                return LLVMIntUGT;
            case BT:
                return LLVMIntULT;
            default:
                throw shouldNotReachHere("invalid condition");
        }
    }

    static int getLLVMRealCond(Condition cond, boolean unordered) {
        switch (cond) {
            case EQ:
                return (unordered) ? LLVMRealUEQ : LLVMRealOEQ;
            case NE:
                return (unordered) ? LLVMRealUNE : LLVMRealONE;
            case LT:
                return (unordered) ? LLVMRealULT : LLVMRealOLT;
            case LE:
                return (unordered) ? LLVMRealULE : LLVMRealOLE;
            case GT:
                return (unordered) ? LLVMRealUGT : LLVMRealOGT;
            case GE:
                return (unordered) ? LLVMRealUGE : LLVMRealOGE;
            default:
                throw shouldNotReachHere("invalid condition");
        }
    }

    public interface LLVMValueWrapper {
        LLVMValueRef get();
    }

    interface LLVMTypeWrapper {
        LLVMTypeRef get();
    }

    public static LLVMValueRef getVal(Value value) {
        return ((LLVMValueWrapper) value).get();
    }

    static LLVMTypeRef getType(ValueKind<?> kind) {
        return ((LLVMTypeWrapper) kind.getPlatformKind()).get();
    }

    public static LLVMTypeRef typeOf(LLVMValueRef value) {
        return LLVMTypeOf(value);
    }

    public static class LLVMVariable extends Variable implements LLVMValueWrapper {
        private static int id = 0;

        private LLVMValueRef value;

        LLVMVariable(ValueKind<?> kind) {
            super(kind, id++);
        }

        public LLVMVariable(LLVMValueRef value) {
            this(LLVMKind.toLIRKind(LLVMTypeOf(value)));

            this.value = value;
        }

        public void set(LLVMValueRef value) {
            assert this.value == null || LLVMIsUndef(this.value) == TRUE;

            this.value = value;
        }

        @Override
        public LLVMValueRef get() {
            return value;
        }

        @Override
        public String toString() {
            return LLVMPrintValueToString(value).getString();
        }
    }

    static class LLVMConstant extends ConstantValue implements LLVMValueWrapper {
        private final LLVMValueRef value;

        LLVMConstant(LLVMValueRef value, Constant constant) {
            super(LLVMKind.toLIRKind(typeOf(value)), constant);
            this.value = value;
        }

        @Override
        public LLVMValueRef get() {
            return value;
        }

        @Override
        public String toString() {
            return LLVMPrintValueToString(value).getString();
        }
    }

    public static class LLVMStackSlot extends VirtualStackSlot implements LLVMValueWrapper {
        private static int id = 0;

        private LLVMValueRef value;
        private final LLVMVariable address;
        private final BitSet objects;

        LLVMStackSlot(LLVMValueRef value, BitSet objects) {
            super(id++, LLVMKind.toLIRKind(LLVMTypeOf(value)));

            this.value = value;
            this.address = new LLVMVariable(value);
            this.objects = objects;
        }

        @Override
        public LLVMValueRef get() {
            return value;
        }

        public LLVMVariable address() {
            return address;
        }

        public BitSet objects() {
            return objects;
        }

        @Override
        public String toString() {
            return LLVMPrintValueToString(value).getString();
        }
    }

    public static class LLVMKindTool implements LIRKindTool {
        private LLVMContextRef context;
        private boolean shouldTrackPointers;

        public LLVMKindTool(LLVMContextRef context, boolean shouldTrackPointers) {
            this.context = context;
            this.shouldTrackPointers = shouldTrackPointers;
        }

        @Override
        public LIRKind getIntegerKind(int bits) {
            return LIRKind.value(new LLVMKind(LLVMIntTypeInContext(context, bits)));
        }

        @Override
        public LIRKind getFloatingKind(int bits) {
            switch (bits) {
                case 32:
                    return LIRKind.value(new LLVMKind(LLVMFloatTypeInContext(context)));
                case 64:
                    return LIRKind.value(new LLVMKind(LLVMDoubleTypeInContext(context)));
                default:
                    throw shouldNotReachHere("invalid float type");
            }
        }

        @Override
        public LIRKind getObjectKind() {
            return LIRKind.reference(new LLVMKind(LLVMPointerType(LLVMInt8TypeInContext(context), shouldTrackPointers ? 1 : 0)));
        }

        @Override
        public LIRKind getWordKind() {
            return LIRKind.value(new LLVMKind(LLVMInt64TypeInContext(context)));
        }

        @Override
        public LIRKind getNarrowOopKind() {
            throw unimplemented();
        }

        @Override
        public LIRKind getNarrowPointerKind() {
            throw unimplemented();
        }
    }

    static final class LLVMKind implements PlatformKind, LLVMTypeWrapper {
        private final LLVMTypeRef type;

        private LLVMKind(LLVMTypeRef type) {
            this.type = type;
        }

        static LIRKind toLIRKind(LLVMTypeRef type) {
            if (LLVMGetTypeKind(type) == LLVMPointerTypeKind) {
                return LIRKind.reference(new LLVMKind(type));
            } else {
                return LIRKind.value(new LLVMKind(type));
            }
        }

        @Override
        public LLVMTypeRef get() {
            return type;
        }

        @Override
        public String name() {
            throw unimplemented();
        }

        @Override
        public Key getKey() {
            throw unimplemented();
        }

        @Override
        public int getSizeInBytes() {
            switch (LLVMGetTypeKind(type)) {
                case LLVMIntegerTypeKind:
                    return NumUtil.roundUp(LLVMGetIntTypeWidth(type), 8) / 8;
                case LLVMFloatTypeKind:
                    return 4;
                case LLVMDoubleTypeKind:
                    return 8;
                case LLVMPointerTypeKind:
                    return 8;
                default:
                    throw shouldNotReachHere("invalid kind");
            }
        }

        @Override
        public int getVectorLength() {
            return 1;
        }

        @Override
        public char getTypeChar() {
            throw unimplemented();
        }
    }

    public static class LLVMAddressValue extends Value {

        private final Value base;
        private final Value index;

        public LLVMAddressValue(ValueKind<?> kind, Value base, Value index) {
            super(kind);
            this.base = base;
            this.index = index;
        }

        public Value getBase() {
            return base;
        }

        public Value getIndex() {
            return index;
        }
    }
}
