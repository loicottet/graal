/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.svm.core.graal.llvm;

import static org.graalvm.compiler.core.llvm.LLVMUtils.FALSE;
import static org.graalvm.compiler.core.llvm.LLVMUtils.TRUE;
import static com.oracle.svm.hosted.image.NativeBootImage.RWDATA_CGLOBALS_PARTITION_OFFSET;
import static org.bytedeco.javacpp.LLVM.LLVMCreateMemoryBufferWithMemoryRange;
import static org.bytedeco.javacpp.LLVM.LLVMCreateObjectFile;
import static org.bytedeco.javacpp.LLVM.LLVMDisposeSectionIterator;
import static org.bytedeco.javacpp.LLVM.LLVMDisposeSymbolIterator;
import static org.bytedeco.javacpp.LLVM.LLVMGetSectionAddress;
import static org.bytedeco.javacpp.LLVM.LLVMGetSectionContainsSymbol;
import static org.bytedeco.javacpp.LLVM.LLVMGetSectionContents;
import static org.bytedeco.javacpp.LLVM.LLVMGetSectionName;
import static org.bytedeco.javacpp.LLVM.LLVMGetSectionSize;
import static org.bytedeco.javacpp.LLVM.LLVMGetSections;
import static org.bytedeco.javacpp.LLVM.LLVMGetSymbolAddress;
import static org.bytedeco.javacpp.LLVM.LLVMGetSymbolName;
import static org.bytedeco.javacpp.LLVM.LLVMGetSymbols;
import static org.bytedeco.javacpp.LLVM.LLVMIsSectionIteratorAtEnd;
import static org.bytedeco.javacpp.LLVM.LLVMIsSymbolIteratorAtEnd;
import static org.bytedeco.javacpp.LLVM.LLVMMoveToNextSection;
import static org.bytedeco.javacpp.LLVM.LLVMMoveToNextSymbol;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.oracle.svm.hosted.image.NativeBootImage;
import org.bytedeco.javacpp.BytePointer;
import org.bytedeco.javacpp.LLVM.LLVMMemoryBufferRef;
import org.bytedeco.javacpp.LLVM.LLVMObjectFileRef;
import org.bytedeco.javacpp.LLVM.LLVMSectionIteratorRef;
import org.bytedeco.javacpp.LLVM.LLVMSymbolIteratorRef;
import org.bytedeco.javacpp.Pointer;
import org.graalvm.compiler.code.CompilationResult;
import org.graalvm.compiler.core.common.NumUtil;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.debug.GraalError;
import org.graalvm.compiler.debug.Indent;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;
import org.graalvm.word.WordFactory;

import com.oracle.graal.pointsto.util.Timer;
import com.oracle.graal.pointsto.util.Timer.StopTimer;
import com.oracle.objectfile.ObjectFile;
import com.oracle.objectfile.ObjectFile.Element;
import com.oracle.objectfile.SectionName;
import com.oracle.svm.core.FrameAccess;
import com.oracle.svm.core.SubstrateUtil;
import com.oracle.svm.core.graal.code.CGlobalDataReference;
import com.oracle.svm.core.heap.SubstrateReferenceMap;
import com.oracle.svm.core.jdk.UninterruptibleUtils.AtomicInteger;
import com.oracle.svm.core.option.HostedOptionValues;
import com.oracle.svm.hosted.NativeImageOptions;
import com.oracle.svm.hosted.c.util.FileUtils;
import com.oracle.svm.hosted.image.NativeBootImage.NativeTextSectionImpl;
import com.oracle.svm.hosted.image.NativeImageCodeCache;
import com.oracle.svm.hosted.image.NativeImageHeap;
import com.oracle.svm.hosted.image.RelocatableBuffer;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.meta.MethodPointer;

import jdk.vm.ci.code.site.Call;
import jdk.vm.ci.code.site.DataPatch;
import jdk.vm.ci.code.site.DataSectionReference;
import jdk.vm.ci.code.site.ExceptionHandler;
import jdk.vm.ci.code.site.Infopoint;
import jdk.vm.ci.code.site.InfopointReason;

@Platforms(Platform.HOSTED_ONLY.class)
public class LLVMNativeImageCodeCache extends NativeImageCodeCache {
    private static final int BATCH_SIZE = 1000;

    private String bitcodeFileName;
    private long codeSize = 0L;
    private Map<String, Integer> textSymbolOffsets = new HashMap<>();
    private Map<Integer, String> offsetToSymbolMap = new TreeMap<>();
    private LLVMStackMapInfo info;
    private HostedMethod firstMethod;

    public LLVMNativeImageCodeCache(Map<HostedMethod, CompilationResult> compilations, NativeImageHeap imageHeap) {
        super(compilations, imageHeap);
    }

    @Override
    public int getCodeCacheSize() {
        return NumUtil.safeToInt(codeSize);
    }

    @Override
    @SuppressWarnings("try")
    public void layoutMethods(DebugContext debug, String imageName) {
        try (Indent indent = debug.logAndIndent("layout methods")) {

            // Compile all methods.
            byte[] bytes;
            try {
                Path basePath = Files.createTempDirectory("native-image-llvm");
                Path outputPath = writeBitcode(debug, basePath, imageName);
                bitcodeFileName = outputPath.toString();
                bytes = Files.readAllBytes(outputPath);
            } catch (IOException e) {
                throw new GraalError(e);
            }

            try (StopTimer t = new Timer(imageName, "(stackmap)").start()) {
                LLVMMemoryBufferRef buffer = LLVMCreateMemoryBufferWithMemoryRange(new BytePointer(bytes), bytes.length, new BytePointer(""), FALSE);
                LLVMObjectFileRef objectFile = LLVMCreateObjectFile(buffer);

                LLVMSectionIteratorRef sectionIterator;
                for (sectionIterator = LLVMGetSections(objectFile); LLVMIsSectionIteratorAtEnd(objectFile, sectionIterator) == FALSE; LLVMMoveToNextSection(sectionIterator)) {
                    BytePointer sectionNamePointer = LLVMGetSectionName(sectionIterator);
                    String sectionName = (sectionNamePointer != null) ? sectionNamePointer.getString() : "";
                    if (sectionName.startsWith(SectionName.TEXT.getFormatDependentName(ObjectFile.getNativeFormat()))) {
                        readTextSection(sectionIterator, objectFile);
                    } else if (sectionName.startsWith(SectionName.LLVM_STACKMAPS.getFormatDependentName(ObjectFile.getNativeFormat()))) {
                        readStackMapSection(sectionIterator);
                    }
                }
                assert codeSize > 0L;
                assert info != null;

                LLVMDisposeSectionIterator(sectionIterator);

                readStackMap();

                buildRuntimeMetadata(MethodPointer.factory(firstMethod), WordFactory.signed(codeSize));
            }
        }
    }

    private void readTextSection(LLVMSectionIteratorRef sectionIterator, LLVMObjectFileRef objectFile) {
        codeSize = LLVMGetSectionSize(sectionIterator);
        long sectionAddress = LLVMGetSectionAddress(sectionIterator);

        LLVMSymbolIteratorRef symbolIterator;
        for (symbolIterator = LLVMGetSymbols(objectFile); LLVMIsSymbolIteratorAtEnd(objectFile, symbolIterator) == FALSE; LLVMMoveToNextSymbol(symbolIterator)) {
            if (LLVMGetSectionContainsSymbol(sectionIterator, symbolIterator) == TRUE) {
                int offset = NumUtil.safeToInt(LLVMGetSymbolAddress(symbolIterator) - sectionAddress);
                String symbolName = LLVMGetSymbolName(symbolIterator).getString();
                textSymbolOffsets.put(symbolName, offset);
                offsetToSymbolMap.put(offset, symbolName);
            }
        }
        LLVMDisposeSymbolIterator(symbolIterator);
    }

    private void readStackMapSection(LLVMSectionIteratorRef sectionIterator) {
        Pointer stackMap = LLVMGetSectionContents(sectionIterator).limit(LLVMGetSectionSize(sectionIterator));
        info = new LLVMStackMapInfo(stackMap.asByteBuffer());
    }

    private void readStackMap() {
        List<Integer> sortedMethodOffsets = textSymbolOffsets.values().stream().filter(offset -> !offsetToSymbolMap.get(offset).contains("_get_setjmp_buffer_")).distinct().sorted()
                        .collect(Collectors.toList());

        compilations.entrySet().parallelStream().forEach(entry -> {
            HostedMethod method = entry.getKey();
            String methodSymbolName = ((ObjectFile.getNativeFormat() == ObjectFile.Format.MACH_O) ? "_" : "") + SubstrateUtil.uniqueShortName(method);
            assert (textSymbolOffsets.containsKey(methodSymbolName));

            int offset = textSymbolOffsets.get(methodSymbolName);

            CompilationResult compilation = entry.getValue();
            long startPatchpointID = compilation.getInfopoints().stream().filter(ip -> ip.reason == InfopointReason.METHOD_START).findFirst()
                            .orElseThrow(() -> new GraalError("no method start infopoint: " + methodSymbolName)).pcOffset;
            compilation.setTotalFrameSize(NumUtil.safeToInt(info.getFunctionStackSize(startPatchpointID) + FrameAccess.returnAddressSize()));

            int nextFunctionStartOffset = sortedMethodOffsets.stream().filter(x -> x > offset).findFirst().orElse(NumUtil.safeToInt(codeSize));
            int functionSize = nextFunctionStartOffset - offset;
            compilation.setTargetCode(null, functionSize);
            method.setCodeAddressOffset(offset);

            List<Infopoint> newInfopoints = new ArrayList<>();
            for (Infopoint infopoint : compilation.getInfopoints()) {
                if (infopoint instanceof Call) {
                    Call call = (Call) infopoint;

                    /* Optimizations might have duplicated some calls. */
                    for (int actualPcOffset : info.getPatchpointOffsets(call.pcOffset)) {
                        SubstrateReferenceMap referenceMap = new SubstrateReferenceMap();
                        info.forEachStatepointOffset(call.pcOffset, actualPcOffset, o -> referenceMap.markReferenceAtOffset(o, false));
                        call.debugInfo.setReferenceMap(referenceMap);
                        newInfopoints.add(new Call(call.target, actualPcOffset, call.size, call.direct, call.debugInfo));
                    }
                }
            }

            compilation.clearInfopoints();

            newInfopoints.forEach(compilation::addInfopoint);

            Map<Integer, Integer> newExceptionHandlers = new HashMap<>();
            for (ExceptionHandler handler : compilation.getExceptionHandlers()) {
                for (int actualPCOffset : info.getPatchpointOffsets(handler.pcOffset)) {
                    int actualHandlerPos = textSymbolOffsets.get(methodSymbolName + "_get_setjmp_buffer_" + handler.handlerPos) - offset;

                    /* handlerPos is offset to function to get setjmp buffer */
                    newExceptionHandlers.put(actualPCOffset, actualHandlerPos);
                }
            }

            compilation.clearExceptionHandlers();

            newExceptionHandlers.forEach(compilation::recordExceptionHandler);
        });

        compilations.forEach((method, compilation) -> compilationsByStart.put(method.getCodeAddressOffset(), compilation));

        for (int i = 0; i < sortedMethodOffsets.size(); ++i) {
            int startOffset = sortedMethodOffsets.get(i);
            int endOffset = (i + 1 == sortedMethodOffsets.size()) ? NumUtil.safeToInt(codeSize) : sortedMethodOffsets.get(i + 1);
            CompilationResult compilationResult = compilationsByStart.get(startOffset);
            assert compilationResult == null || startOffset + compilationResult.getTargetCodeSize() == endOffset : compilationResult.getName();
        }

        firstMethod = (HostedMethod) getFirstCompilation().getMethods()[0];
    }

    @Override
    public void patchMethods(DebugContext debug, RelocatableBuffer relocs, ObjectFile objectFile) {
        Element rodataSection = objectFile.elementForName(SectionName.RODATA.getFormatDependentName(objectFile.getFormat()));
        Element dataSection = objectFile.elementForName(SectionName.DATA.getFormatDependentName(objectFile.getFormat()));
        for (CompilationResult result : getCompilations().values()) {
            for (DataPatch dataPatch : result.getDataPatches()) {
                if (dataPatch.reference instanceof CGlobalDataReference) {
                    CGlobalDataReference reference = (CGlobalDataReference) dataPatch.reference;

                    if (reference.getDataInfo().isSymbolReference()) {
                        objectFile.createUndefinedSymbol(reference.getDataInfo().getData().symbolName, 0, true);
                    }

                    int offset = reference.getDataInfo().getOffset();

                    String symbolName = (String) dataPatch.note;
                    if (objectFile.getOrCreateSymbolTable().getSymbol(symbolName) == null) {
                        objectFile.createDefinedSymbol(symbolName, dataSection, offset + NativeBootImage.RWDATA_CGLOBALS_PARTITION_OFFSET, 0, false, true);
                    }
                } else if (dataPatch.reference instanceof DataSectionReference) {
                    DataSectionReference reference = (DataSectionReference) dataPatch.reference;

                    int offset = reference.getOffset();

                    String symbolName = (String) dataPatch.note;
                    if (objectFile.getOrCreateSymbolTable().getSymbol(symbolName) == null) {
                        objectFile.createDefinedSymbol(symbolName, rodataSection, offset, 0, false, true);
                    }
                }
            }
        }
    }

    @Override
    public void writeCode(RelocatableBuffer buffer) {
        /* Do nothing, code is written at link stage */
    }

    @SuppressWarnings("try")
    private Path writeBitcode(DebugContext debug, Path basePath, String imageName) {
        List<String> paths;
        try (StopTimer t = new Timer(imageName, "(bitcode)").start()) {
            AtomicInteger num = new AtomicInteger(-1);
            paths = getCompilations().values().parallelStream().map(compilationResult -> {
                int id = num.incrementAndGet();
                String bitcodePath = basePath.resolve("llvm_" + id + ".bc").toString();

                try (FileOutputStream fos = new FileOutputStream(bitcodePath)) {
                    fos.write(compilationResult.getTargetCode());
                } catch (Exception e) {
                    throw new GraalError(e);
                }

                return bitcodePath;
            }).collect(Collectors.toList());
        }

        /* Compile LLVM */
        Path linkedBitcodePath = basePath.resolve("llvm.bc");
        try (StopTimer t = new Timer(imageName, "(link)").start()) {
            int maxThreads = NativeImageOptions.getMaximumNumberOfConcurrentThreads(ImageSingletons.lookup(HostedOptionValues.class));
            int numBatches = Math.max(maxThreads, paths.size() / BATCH_SIZE + ((paths.size() % BATCH_SIZE == 0) ? 0 : 1));
            int batchSize = paths.size() / numBatches + ((paths.size() % numBatches == 0) ? 0 : 1);
            List<List<String>> batchInputLists = IntStream.range(0, numBatches).mapToObj(i -> paths.stream()
                            .skip(i * batchSize)
                            .limit(batchSize)
                            .collect(Collectors.toList())).collect(Collectors.toList());

            AtomicInteger batchNum = new AtomicInteger(-1);
            List<String> batchPaths = batchInputLists.parallelStream()
                            .filter(inputList -> !inputList.isEmpty())
                            .map(batchInputs -> {
                                String batchOutputPath = basePath.resolve("llvm_batch" + batchNum.incrementAndGet() + ".bc").toString();

                                llvmLink(debug, batchOutputPath, batchInputs);

                                return batchOutputPath;
                            }).collect(Collectors.toList());

            llvmLink(debug, linkedBitcodePath.toString(), batchPaths);
        }

        Path optimizedBitcodePath = basePath.resolve("llvm_opt.bc");
        try (StopTimer t = new Timer(imageName, "(gc)").start()) {
            llvmOptimize(debug, optimizedBitcodePath.toString(), linkedBitcodePath.toString());
        }

        Path outputPath = basePath.resolve("llvm.o");
        try (StopTimer t = new Timer(imageName, "(llvm)").start()) {
            llvmCompile(debug, outputPath.toString(), optimizedBitcodePath.toString());
        }

        return outputPath;
    }

    private static void llvmOptimize(DebugContext debug, String outputPath, String inputPath) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("opt");
            /*
             * Mem2reg has to be run before rewriting statepoints as it promotes allocas, which are
             * not supported for statepoints.
             */
            cmd.add("-mem2reg");
            cmd.add("-rewrite-statepoints-for-gc");
            cmd.add("-o");
            cmd.add(outputPath);
            cmd.add(inputPath);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();

            OutputStream output = new ByteArrayOutputStream();
            FileUtils.drainInputStream(p.getInputStream(), output);

            int status = p.waitFor();
            if (status != 0) {
                debug.log("%s", output.toString());
                throw new GraalError("LLVM optimization failed for " + inputPath + ": " + status);
            }
        } catch (IOException | InterruptedException e) {
            throw new GraalError(e);
        }
    }

    private static void llvmCompile(DebugContext debug, String outputPath, String inputPath) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("llc");
            /* X86 call frame optimization causes variable sized stack frames */
            cmd.add("-no-x86-call-frame-opt");
            cmd.add("-O2");
            cmd.add("-filetype=obj");
            cmd.add("-o");
            cmd.add(outputPath);
            cmd.add(inputPath);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();

            OutputStream output = new ByteArrayOutputStream();
            FileUtils.drainInputStream(p.getInputStream(), output);

            int status = p.waitFor();
            if (status != 0) {
                debug.log("%s", output.toString());
                throw new GraalError("LLVM compilation failed for " + inputPath + ": " + status);
            }
        } catch (IOException | InterruptedException e) {
            throw new GraalError(e);
        }
    }

    private static void llvmLink(DebugContext debug, String outputPath, List<String> inputPaths) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("llvm-link");
            cmd.add("-v");
            cmd.add("-o");
            cmd.add(outputPath);
            cmd.addAll(inputPaths);

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();

            OutputStream output = new ByteArrayOutputStream();
            FileUtils.drainInputStream(p.getInputStream(), output);

            int status = p.waitFor();
            if (status != 0) {
                debug.log("%s", output.toString());
                throw new GraalError("LLVM linking failed into " + outputPath + ": " + status);
            }
        } catch (IOException | InterruptedException e) {
            throw new GraalError(e);
        }
    }

    @Override
    public NativeTextSectionImpl getTextSectionImpl(RelocatableBuffer buffer, ObjectFile objectFile, NativeImageCodeCache codeCache) {
        return new NativeTextSectionImpl(buffer, objectFile, codeCache) {
            @Override
            protected void defineMethodSymbol(String name, Element section, HostedMethod method, CompilationResult result) {
                objectFile.createUndefinedSymbol(name, 0, true);
            }
        };
    }

    @Override
    public String[] getCCInputFiles(Path tempDirectory, String imageName) {
        String relocatableFileName = tempDirectory.resolve(imageName + ObjectFile.getFilenameSuffix()).toString();
        return new String[]{relocatableFileName, bitcodeFileName};
    }
}
