/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.svm.core.graal.llvm;

import static com.oracle.svm.core.util.VMError.shouldNotReachHere;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.graalvm.compiler.core.common.NumUtil;

import com.oracle.svm.core.FrameAccess;
import com.oracle.svm.core.util.VMError;

public class LLVMStackMapInfo {
    private static final long DEFAULT_PATCHPOINT_ID = 0xABCDEF00L;
    private StackMap stackMap;

    static class StackMap {
        byte version;
        Function[] functions;
        long[] constants;
    }

    static class Function {
        long address;
        long stackSize;
        Record[] records;
    }

    static class Record {
        long patchpointID;
        int instructionOffset;
        short flags;
        Location[] locations;
        LiveOut[] liveOuts;
    }

    static class Location {
        Type type;
        short size;
        short regNum;
        int offset;

        enum Type {
            Register(1),
            Direct(2),
            Indirect(3),
            Constant(4),
            ConstantIndex(5);

            private final byte encoding;

            Type(int encoding) {
                this.encoding = (byte) encoding;
            }

            static Type decode(byte encoding) {
                for (Type type : values()) {
                    if (type.encoding == encoding) {
                        return type;
                    }
                }
                return null;
            }
        }
    }

    static class LiveOut {
        short regNum;
        byte size;
    }

    /*
     * Stack map format specification available at
     * https://llvm.org/docs/StackMaps.html#stack-map-format
     */
    public LLVMStackMapInfo(ByteBuffer buffer) {
        stackMap = new StackMap();

        int offset = 0;

        stackMap.version = buffer.get(offset);
        offset += Byte.BYTES;

        // skip header
        offset += Byte.BYTES;
        offset += Short.BYTES;

        stackMap.functions = new Function[buffer.getInt(offset)];
        offset += Integer.BYTES;

        stackMap.constants = new long[buffer.getInt(offset)];
        offset += Integer.BYTES;

        int numRecords = buffer.getInt(offset);
        offset += Integer.BYTES;

        long totalNumRecords = 0L;
        for (int i = 0; i < stackMap.functions.length; i++) {
            Function function = new Function();

            function.address = buffer.getLong(offset); // always 0
            offset += Long.BYTES;

            function.stackSize = buffer.getLong(offset);
            offset += Long.BYTES;

            function.records = new Record[NumUtil.safeToInt(buffer.getLong(offset))];
            offset += Long.BYTES;

            stackMap.functions[i] = function;

            totalNumRecords += function.records.length;
        }

        for (int i = 0; i < stackMap.constants.length; ++i) {
            stackMap.constants[i] = buffer.getLong(offset);
            offset += Long.BYTES;
        }

        int fun = 0;
        int rec = 0;
        assert numRecords == totalNumRecords;
        for (int i = 0; i < numRecords; ++i, ++rec) {
            while (rec == stackMap.functions[fun].records.length) {
                fun++;
                rec = 0;
            }

            Function function = stackMap.functions[fun];
            Record record = new Record();

            record.patchpointID = buffer.getLong(offset);
            offset += Long.BYTES;

            record.instructionOffset = buffer.getInt(offset);
            offset += Integer.BYTES;

            record.flags = buffer.getShort(offset);
            offset += Short.BYTES;

            record.locations = new Location[buffer.getShort(offset)];
            offset += Short.BYTES;

            for (int j = 0; j < record.locations.length; j++) {
                Location location = new Location();

                location.type = Location.Type.decode(buffer.get(offset));
                offset += Byte.BYTES;
                offset += Byte.BYTES; // skip reserved bytes

                location.size = buffer.getShort(offset);
                offset += Short.BYTES;

                location.regNum = buffer.getShort(offset);
                offset += Short.BYTES;
                offset += Short.BYTES; // skip reserved bytes

                location.offset = buffer.getInt(offset);
                offset += Integer.BYTES;

                record.locations[j] = location;
            }
            if (offset % Long.BYTES != 0) {
                offset += Integer.BYTES; // skip alignment padding
            }
            offset += Short.BYTES; // skip padding

            record.liveOuts = new LiveOut[buffer.getShort(offset)];
            offset += Short.BYTES;

            for (int j = 0; j < record.liveOuts.length; j++) {
                LiveOut liveOut = new LiveOut();

                liveOut.regNum = buffer.getShort(offset);
                offset += Short.BYTES;
                offset += Byte.BYTES; // skip reserved bytes

                liveOut.size = buffer.get(offset);
                offset += Byte.BYTES;

                record.liveOuts[j] = liveOut;
            }
            if (offset % Long.BYTES != 0) {
                offset += Integer.BYTES; // skip alignment padding
            }

            function.records[rec] = record;

            if (patchpointToFunction.containsKey(record.patchpointID)) {
                assert record.patchpointID == DEFAULT_PATCHPOINT_ID || patchpointToFunction.get(record.patchpointID) == function;
            }
            patchpointToFunction.put(record.patchpointID, function);
            patchpointsByID.computeIfAbsent(record.patchpointID, v -> new HashSet<>()).add(record);
        }
    }

    private Map<Long, Function> patchpointToFunction = new HashMap<>();
    private Map<Long, Set<Record>> patchpointsByID = new HashMap<>();

    public long getFunctionStackSize(long startPatchpointID) {
        assert patchpointToFunction.containsKey(startPatchpointID);
        return patchpointToFunction.get(startPatchpointID).stackSize;
    }

    public int[] getPatchpointOffsets(long patchpointID) {
        if (patchpointsByID.containsKey(patchpointID)) {
            return patchpointsByID.get(patchpointID).stream().mapToInt(r -> r.instructionOffset).toArray();
        }
        return new int[0];
    }

    private static final int DWARF_AMD64_RSP = 7;
    private static final int DWARF_AMD64_RBP = 6;

    public void forEachStatepointOffset(long patchpointID, int instructionOffset, Consumer<Integer> callback) {
        Location[] locations = patchpointsByID.get(patchpointID).stream().filter(r -> r.instructionOffset == instructionOffset)
                        .findFirst().orElseThrow(VMError::shouldNotReachHere).locations;
        if (locations.length == 0) {
            return;
        }
        assert locations.length >= 3;
        Location numLiveVariables = locations[2];
        assert numLiveVariables.type == Location.Type.Constant && numLiveVariables.offset + 3 <= locations.length;
        Set<Integer> seenOffsets = new HashSet<>();
        for (int i = 3; i < locations.length; ++i) {
            if (locations[i].type == Location.Type.Indirect) { // spilled values
                int offset;
                if (locations[i].regNum == DWARF_AMD64_RSP) {
                    offset = locations[i].offset;
                } else if (locations[i].regNum == DWARF_AMD64_RBP) {
                    /*
                     * Convert frame-relative offset (negative) to a stack-relative offset
                     * (positive)
                     */
                    offset = locations[i].offset + NumUtil.safeToInt(getFunctionStackSize(patchpointID)) - FrameAccess.wordSize();
                } else {
                    throw shouldNotReachHere("found other register " + patchpointID + " " + locations[i].regNum);
                }
                if (!seenOffsets.contains(offset)) {
                    seenOffsets.add(offset);
                    callback.accept(offset);
                }
            }
        }
    }
}
