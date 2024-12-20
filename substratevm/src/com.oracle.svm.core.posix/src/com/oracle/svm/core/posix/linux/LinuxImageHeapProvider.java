/*
 * Copyright (c) 2019, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.svm.core.posix.linux;

import static com.oracle.svm.core.Isolates.IMAGE_HEAP_A_RELOCATABLE_POINTER;
import static com.oracle.svm.core.Isolates.IMAGE_HEAP_BEGIN;
import static com.oracle.svm.core.Isolates.IMAGE_HEAP_END;
import static com.oracle.svm.core.Isolates.IMAGE_HEAP_RELOCATABLE_BEGIN;
import static com.oracle.svm.core.Isolates.IMAGE_HEAP_RELOCATABLE_END;
import static com.oracle.svm.core.Isolates.IMAGE_HEAP_WRITABLE_BEGIN;
import static com.oracle.svm.core.Isolates.IMAGE_HEAP_WRITABLE_END;
import static com.oracle.svm.core.posix.linux.ProcFSSupport.findMapping;
import static com.oracle.svm.core.util.PointerUtils.roundUp;
import static com.oracle.svm.core.util.PointerUtils.roundDown;
import static org.graalvm.word.WordFactory.signed;

import java.util.concurrent.ThreadLocalRandom;

import org.graalvm.compiler.nodes.PauseNode;
import org.graalvm.compiler.word.Word;
import org.graalvm.nativeimage.StackValue;
import org.graalvm.nativeimage.c.type.CCharPointer;
import org.graalvm.nativeimage.c.type.WordPointer;
import org.graalvm.word.ComparableWord;
import org.graalvm.word.LocationIdentity;
import org.graalvm.word.Pointer;
import org.graalvm.word.PointerBase;
import org.graalvm.word.SignedWord;
import org.graalvm.word.UnsignedWord;
import org.graalvm.word.WordFactory;

import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.Uninterruptible;
import com.oracle.svm.core.c.CGlobalData;
import com.oracle.svm.core.c.CGlobalDataFactory;
import com.oracle.svm.core.c.function.CEntryPointErrors;
import com.oracle.svm.core.code.DynamicMethodAddressResolutionHeapSupport;
import com.oracle.svm.core.config.ConfigurationValues;
import com.oracle.svm.core.headers.LibC;
import com.oracle.svm.core.heap.Heap;
import com.oracle.svm.core.os.AbstractImageHeapProvider;
import com.oracle.svm.core.os.CopyingImageHeapProvider;
import com.oracle.svm.core.os.VirtualMemoryProvider;
import com.oracle.svm.core.os.VirtualMemoryProvider.Access;
import com.oracle.svm.core.posix.PosixUtils;
import com.oracle.svm.core.posix.headers.Errno;
import com.oracle.svm.core.posix.headers.Fcntl;
import com.oracle.svm.core.posix.headers.Unistd;
import com.oracle.svm.core.util.PointerUtils;

/**
 * An optimal image heap provider for Linux which creates isolate image heaps that retain the
 * copy-on-write, lazy loading and reclamation semantics provided by the original heap's backing
 * resource.
 *
 * This is accomplished by discovering the backing executable or shared object file the kernel has
 * mmapped to the original heap image virtual address, as well as the location in the file storing
 * the original heap. A new memory map is created to a new virtual range pointing to this same
 * location. This allows the kernel to share the same physical pages between multiple heaps that
 * have not been modified, as well as lazily load them only when needed.
 *
 * The implementation avoids dirtying the pages of the original, and only referencing what is
 * strictly required.
 */
public class LinuxImageHeapProvider extends AbstractImageHeapProvider {
    /** Magic value to verify that a located image file matches our loaded image. */
    public static final CGlobalData<Pointer> MAGIC = CGlobalDataFactory.createWord(WordFactory.<Word> signed(ThreadLocalRandom.current().nextLong()));

    private static final CGlobalData<CCharPointer> PROC_SELF_MAPS = CGlobalDataFactory.createCString("/proc/self/maps");

    private static final SignedWord FIRST_ISOLATE_FD = signed(-1);
    private static final SignedWord UNASSIGNED_FD = signed(-2);
    private static final SignedWord CANNOT_OPEN_FD = signed(-3);

    private static final SignedWord COPY_RELOCATIONS_IN_PROGRESS = signed(-1);

    private static final CGlobalData<WordPointer> CACHED_IMAGE_FD = CGlobalDataFactory.createWord(FIRST_ISOLATE_FD);
    private static final CGlobalData<WordPointer> CACHED_IMAGE_HEAP_OFFSET = CGlobalDataFactory.createWord();
    private static final CGlobalData<WordPointer> CACHED_IMAGE_HEAP_RELOCATIONS = CGlobalDataFactory.createWord();

    private static final int MAX_PATHLEN = 4096;

    private static final CopyingImageHeapProvider fallbackCopyingProvider = new CopyingImageHeapProvider();

    @Override
    public boolean guaranteesHeapPreferredAddressSpaceAlignment() {
        return true;
    }

    @Override
    @Uninterruptible(reason = "Called during isolate initialization.")
    public int initialize(Pointer reservedAddressSpace, UnsignedWord reservedSize, WordPointer basePointer, WordPointer endPointer) {
        // If we are the first isolate, we might be able to use the existing image heap (see below)
        SignedWord fd = CACHED_IMAGE_FD.get().read();
        boolean firstIsolate = false;
        if (fd.equal(FIRST_ISOLATE_FD)) {
            SignedWord previous = ((Pointer) CACHED_IMAGE_FD.get()).compareAndSwapWord(0, FIRST_ISOLATE_FD, UNASSIGNED_FD, LocationIdentity.ANY_LOCATION);
            firstIsolate = previous.equal(FIRST_ISOLATE_FD);
            fd = firstIsolate ? UNASSIGNED_FD : previous;
        }

        /*
         * If we haven't already, find and open the image file. Even if we are the first isolate,
         * this is necessary because if we fail, we need to leave the loaded image heap in pristine
         * condition so we can use it to spawn isolates by copying it.
         *
         * We cache the file descriptor and the determined offset in the file for subsequent isolate
         * initializations. We intentionally allow racing in this step to avoid stalling threads.
         */
        if (fd.equal(UNASSIGNED_FD) || firstIsolate) {
            int opened = openImageFile();
            /*
             * Pointer cas operations are volatile accesses and prevent code reorderings.
             */
            SignedWord previous = ((Pointer) CACHED_IMAGE_FD.get()).compareAndSwapWord(0, fd, signed(opened), LocationIdentity.ANY_LOCATION);
            if (previous.equal(fd)) {
                fd = signed(opened);
            } else {
                if (opened >= 0) {
                    Unistd.NoTransitions.close(opened);
                }
                fd = previous;
            }
        }

        UnsignedWord pageSize = VirtualMemoryProvider.get().getGranularity();
        Word heapBeginSym = IMAGE_HEAP_BEGIN.get();
        UnsignedWord imageHeapSizeInFile = getImageHeapSizeInFile();
        Word heapRelocsSym = IMAGE_HEAP_RELOCATABLE_BEGIN.get();
        Word heapAnyRelocPointer = IMAGE_HEAP_A_RELOCATABLE_POINTER.get();
        Word heapRelocsEndSym = IMAGE_HEAP_RELOCATABLE_END.get();

        // If we cannot find or open the image file, fall back to mremap or copy it from memory.
        if (fd.equal(CANNOT_OPEN_FD)) {

            int result = initializeImageHeapWithMremap(reservedAddressSpace, reservedSize, imageHeapSizeInFile,
                            pageSize, CACHED_IMAGE_HEAP_RELOCATIONS.get(), heapBeginSym, heapRelocsSym, heapAnyRelocPointer,
                            heapRelocsEndSym, basePointer, endPointer);
            if (result == CEntryPointErrors.MREMAP_NOT_SUPPORTED) {
                /*
                 * MREMAP_DONTUNMAP is not supported, fall back to copying it from memory (the image
                 * heap must be in pristine condition for that).
                 */
                return fallbackCopyingProvider.initialize(reservedAddressSpace, reservedSize, basePointer, endPointer);
            }
            return result;
        }

        boolean haveDynamicMethodResolution = DynamicMethodAddressResolutionHeapSupport.isEnabled();
        if (haveDynamicMethodResolution) {
            int res = DynamicMethodAddressResolutionHeapSupport.get().initialize();
            if (res != CEntryPointErrors.NO_ERROR) {
                return res;
            }
        }

        // If we are the first isolate and can use the existing image heap, do it.
        Word imageHeapBegin = IMAGE_HEAP_BEGIN.get();
        int imageHeapOffsetInAddressSpace = Heap.getHeap().getImageHeapOffsetInAddressSpace();
        UnsignedWord alignment = WordFactory.unsigned(Heap.getHeap().getPreferredAddressSpaceAlignment());
        if (firstIsolate && reservedAddressSpace.isNull() && PointerUtils.isAMultiple(imageHeapBegin, alignment) && imageHeapOffsetInAddressSpace == 0 && !haveDynamicMethodResolution) {
            // Mark the whole image heap as read only.
            if (VirtualMemoryProvider.get().protect(imageHeapBegin, imageHeapSizeInFile, Access.READ) != 0) {
                return CEntryPointErrors.PROTECT_HEAP_FAILED;
            }

            // Unprotect writable pages.
            unprotectWritablePages(IMAGE_HEAP_BEGIN.get());

            // Protect the null region.
            int nullRegionSize = Heap.getHeap().getImageHeapNullRegionSize();
            if (nullRegionSize > 0) {
                if (VirtualMemoryProvider.get().protect(imageHeapBegin, WordFactory.unsigned(nullRegionSize), Access.NONE) != 0) {
                    return CEntryPointErrors.PROTECT_HEAP_FAILED;
                }
            }

            basePointer.write(imageHeapBegin);
            if (endPointer.isNonNull()) {
                endPointer.write(IMAGE_HEAP_END.get());
            }
            return CEntryPointErrors.NO_ERROR;
        }

        WordPointer heapBaseOut = StackValue.get(WordPointer.class);
        int result = reserveHeapBase(reservedAddressSpace, reservedSize, alignment, heapBaseOut);
        if (result != CEntryPointErrors.NO_ERROR) {
            return result;
        }
        Pointer heapBase = heapBaseOut.read();
        Pointer allocatedMemory = reservedAddressSpace.isNull() ? heapBase : WordFactory.nullPointer();
        Pointer imageHeap = heapBase.add(imageHeapOffsetInAddressSpace);

        // Create memory mappings from the image file.
        UnsignedWord fileOffset = CACHED_IMAGE_HEAP_OFFSET.get().read();
        imageHeap = VirtualMemoryProvider.get().mapFile(imageHeap, imageHeapSizeInFile, fd, fileOffset, Access.READ);
        if (imageHeap.isNull()) {
            freeImageHeap(allocatedMemory);
            return CEntryPointErrors.MAP_HEAP_FAILED;
        }

        result = copyRelocations(imageHeap, pageSize, heapBeginSym, heapRelocsSym, heapAnyRelocPointer, heapRelocsEndSym, WordFactory.nullPointer());
        if (result != CEntryPointErrors.NO_ERROR) {
            freeImageHeap(allocatedMemory);
            return result;
        }

        result = unprotectWritablePages(imageHeap);
        if (result != CEntryPointErrors.NO_ERROR) {
            freeImageHeap(allocatedMemory);
            return result;
        }

        basePointer.write(heapBase);
        if (endPointer.isNonNull()) {
            endPointer.write(roundUp(imageHeap.add(imageHeapSizeInFile), pageSize));
        }
        return CEntryPointErrors.NO_ERROR;

    }

    @Uninterruptible(reason = "Called during isolate initialization.")
    private int reserveHeapBase(Pointer reservedAddressSpace, UnsignedWord reservedSize, UnsignedWord alignment, WordPointer heapBaseOut) {
        boolean haveDynamicMethodResolution = DynamicMethodAddressResolutionHeapSupport.isEnabled();
        UnsignedWord preHeapRequiredBytes = WordFactory.zero();
        if (haveDynamicMethodResolution) {
            preHeapRequiredBytes = DynamicMethodAddressResolutionHeapSupport.get().getDynamicMethodAddressResolverPreHeapMemoryBytes();
        }

        // Reserve an address space for the image heap if necessary.
        UnsignedWord imageHeapAddressSpaceSize = getImageHeapAddressSpaceSize();
        UnsignedWord totalAddressSpaceSize = imageHeapAddressSpaceSize.add(preHeapRequiredBytes);

        Pointer heapBase;
        Pointer allocatedMemory = WordFactory.nullPointer();
        if (reservedAddressSpace.isNull()) {
            heapBase = allocatedMemory = VirtualMemoryProvider.get().reserve(totalAddressSpaceSize, alignment, false);
            if (allocatedMemory.isNull()) {
                return CEntryPointErrors.RESERVE_ADDRESS_SPACE_FAILED;
            }
        } else {
            if (reservedSize.belowThan(totalAddressSpaceSize)) {
                return CEntryPointErrors.INSUFFICIENT_ADDRESS_SPACE;
            }
            heapBase = reservedAddressSpace;
        }

        if (haveDynamicMethodResolution) {
            heapBase = heapBase.add(preHeapRequiredBytes);
            if (allocatedMemory.isNonNull()) {
                allocatedMemory = allocatedMemory.add(preHeapRequiredBytes);
            }
            Pointer installOffset = heapBase.subtract(DynamicMethodAddressResolutionHeapSupport.get().getRequiredPreHeapMemoryInBytes());
            int error = DynamicMethodAddressResolutionHeapSupport.get().install(installOffset);

            if (error != CEntryPointErrors.NO_ERROR) {
                freeImageHeap(allocatedMemory);
                return error;
            }
        }

        heapBaseOut.write(heapBase);
        return CEntryPointErrors.NO_ERROR;
    }

    @Uninterruptible(reason = "Called during isolate initialization.")
    private static int copyRelocations(Pointer imageHeap, UnsignedWord pageSize, Word heapBeginSym, Word heapRelocsSym, Pointer heapAnyRelocPointer, Word heapRelocsEndSym,
                    Pointer cachedRelocsBoundary) {
        if (heapAnyRelocPointer.isNonNull()) {
            Pointer linkedRelocsBoundary = roundDown(heapRelocsSym, pageSize);
            Pointer sourceRelocsBoundary = cachedRelocsBoundary;
            if (sourceRelocsBoundary.isNull()) {
                sourceRelocsBoundary = linkedRelocsBoundary;
            }
            ComparableWord relocatedValue = sourceRelocsBoundary.readWord(heapAnyRelocPointer.subtract(linkedRelocsBoundary));
            ComparableWord mappedValue = imageHeap.readWord(heapAnyRelocPointer.subtract(heapBeginSym));
            if (relocatedValue.notEqual(mappedValue)) {
                UnsignedWord relocsAlignedSize = roundUp(heapRelocsEndSym.subtract(linkedRelocsBoundary), pageSize);
                Pointer relocsBoundary = imageHeap.add(linkedRelocsBoundary.subtract(heapBeginSym));
                /*
                 * Addresses were relocated by the dynamic linker, so copy them, but first remap the
                 * pages to avoid swapping them in from disk. We need to round to page boundaries,
                 * and so we copy some extra data.
                 *
                 * NOTE: while objects with relocations are considered read-only, some of them might
                 * be part of a chunk with writable objects, in which case the chunk header must
                 * also be writable, and all the chunk's pages will be unprotected below.
                 */
                Pointer committedRelocsBegin = VirtualMemoryProvider.get().commit(relocsBoundary, relocsAlignedSize, Access.READ | Access.WRITE);
                if (committedRelocsBegin.isNull() || committedRelocsBegin != relocsBoundary) {
                    return CEntryPointErrors.PROTECT_HEAP_FAILED;
                }
                LibC.memcpy(relocsBoundary, sourceRelocsBoundary, relocsAlignedSize);
                if (VirtualMemoryProvider.get().protect(relocsBoundary, relocsAlignedSize, Access.READ) != 0) {
                    return CEntryPointErrors.PROTECT_HEAP_FAILED;
                }
            }
        }

        return CEntryPointErrors.NO_ERROR;
    }

    @Uninterruptible(reason = "Called during isolate initialization.")
    private static int unprotectWritablePages(Pointer imageHeap) {
        // Unprotect writable pages.
        Pointer writableBegin = imageHeap.add(IMAGE_HEAP_WRITABLE_BEGIN.get().subtract(IMAGE_HEAP_BEGIN.get()));
        UnsignedWord writableSize = IMAGE_HEAP_WRITABLE_END.get().subtract(IMAGE_HEAP_WRITABLE_BEGIN.get());
        if (VirtualMemoryProvider.get().protect(writableBegin, writableSize, Access.READ | Access.WRITE) != 0) {
            return CEntryPointErrors.PROTECT_HEAP_FAILED;
        }
        return CEntryPointErrors.NO_ERROR;
    }

    @Uninterruptible(reason = "Called during isolate initialization.")
    private int initializeImageHeapWithMremap(Pointer reservedAddressSpace, UnsignedWord reservedSize, UnsignedWord imageHeapSizeInFile, UnsignedWord pageSize,
                    WordPointer cachedImageHeapRelocationsPtr, Word heapBeginSym,
                    Word heapRelocsSym, Pointer heapAnyRelocPointer, Word heapRelocsEndSym, WordPointer basePointer, WordPointer endPointer) {
        if (!SubstrateOptions.MremapImageHeap.getValue()) {
            return CEntryPointErrors.MREMAP_NOT_SUPPORTED;
        }

        Pointer cachedImageHeapRelocations = getCachedImageHeapRelocations((Pointer) cachedImageHeapRelocationsPtr, pageSize, heapRelocsSym, heapRelocsEndSym);
        assert cachedImageHeapRelocations.notEqual(0);
        if (cachedImageHeapRelocations.rawValue() < 0) {
            return (int) -cachedImageHeapRelocations.rawValue(); // value is a negated error code
        }

        UnsignedWord alignment = WordFactory.unsigned(Heap.getHeap().getPreferredAddressSpaceAlignment());
        WordPointer heapBaseOut = StackValue.get(WordPointer.class);
        int result = reserveHeapBase(reservedAddressSpace, reservedSize, alignment, heapBaseOut);
        if (result != CEntryPointErrors.NO_ERROR) {
            return result;
        }
        Pointer heapBase = heapBaseOut.read();

        Pointer allocatedMemory = reservedAddressSpace.isNull() ? heapBase : WordFactory.nullPointer();
        int imageHeapOffsetInAddressSpace = Heap.getHeap().getImageHeapOffsetInAddressSpace();
        Pointer imageHeap = heapBase.add(imageHeapOffsetInAddressSpace);

        // Map the image heap for the new isolate from the template
        int mremapFlags = LinuxLibCHelper.MREMAP_FIXED() | LinuxLibCHelper.MREMAP_MAYMOVE() | LinuxLibCHelper.MREMAP_DONTUNMAP();
        PointerBase res = LinuxLibCHelper.NoTransitions.mremapP(heapBeginSym, imageHeapSizeInFile, imageHeapSizeInFile, mremapFlags, imageHeap);
        if (res.notEqual(imageHeap)) {
            freeImageHeap(allocatedMemory);
            return CEntryPointErrors.MAP_HEAP_FAILED;
        }

        result = copyRelocations(imageHeap, pageSize, heapBeginSym, heapRelocsSym, heapAnyRelocPointer, heapRelocsEndSym, cachedImageHeapRelocations);
        if (result != CEntryPointErrors.NO_ERROR) {
            freeImageHeap(allocatedMemory);
            return result;
        }

        result = unprotectWritablePages(imageHeap);
        if (result != CEntryPointErrors.NO_ERROR) {
            freeImageHeap(allocatedMemory);
            return result;
        }

        basePointer.write(heapBase);
        if (endPointer.isNonNull()) {
            endPointer.write(roundUp(imageHeap.add(imageHeapSizeInFile), pageSize));
        }
        return CEntryPointErrors.NO_ERROR;
    }

    /**
     * Returns a valid pointer if successful; otherwise, returns a negated
     * {@linkplain CEntryPointErrors error code}.
     */
    @Uninterruptible(reason = "Called during isolate initialization.")
    private static Pointer getCachedImageHeapRelocations(Pointer cachedImageHeapRelocationsPtr, UnsignedWord pageSize, Word heapRelocsSym, Word heapRelocsEndSym) {
        Pointer imageHeapRelocations = cachedImageHeapRelocationsPtr.readWord(0, LocationIdentity.ANY_LOCATION);
        if (imageHeapRelocations.isNull() || imageHeapRelocations.equal(COPY_RELOCATIONS_IN_PROGRESS)) {
            if (!cachedImageHeapRelocationsPtr.logicCompareAndSwapWord(0, WordFactory.nullPointer(), COPY_RELOCATIONS_IN_PROGRESS, LocationIdentity.ANY_LOCATION)) {
                /* Wait for other thread to initialize heap relocations. */
                while ((imageHeapRelocations = cachedImageHeapRelocationsPtr.readWordVolatile(0, LocationIdentity.ANY_LOCATION)).equal(COPY_RELOCATIONS_IN_PROGRESS)) {
                    PauseNode.pause();
                }
            } else {
                /*
                 * This is the first time mapping the heap. Create a private copy of the relocated
                 * image heap symbols, as these may be reverted during subsequent mremaps.
                 */

                Pointer linkedRelocsBoundary = roundDown(heapRelocsSym, pageSize);
                UnsignedWord heapRelocsLength = roundUp(heapRelocsEndSym.subtract(linkedRelocsBoundary), pageSize);
                int mremapFlags = LinuxLibCHelper.MREMAP_MAYMOVE() | LinuxLibCHelper.MREMAP_DONTUNMAP();
                imageHeapRelocations = LinuxLibCHelper.NoTransitions.mremapP(linkedRelocsBoundary, heapRelocsLength, heapRelocsLength, mremapFlags, WordFactory.nullPointer());

                if (imageHeapRelocations.equal(-1)) {
                    if (LibC.errno() == Errno.EINVAL()) {
                        /*
                         * MREMAP_DONTUNMAP with non-anonymous mappings is only supported from
                         * kernel version 5.13 onwards, and fails with EINVAL otherwise.
                         *
                         * https://github.com/torvalds/linux/commit/
                         * a4609387859f0281951f5e476d9f76d7fb9ab321
                         */
                        imageHeapRelocations = WordFactory.pointer(-CEntryPointErrors.MREMAP_NOT_SUPPORTED);
                    } else {
                        imageHeapRelocations = WordFactory.pointer(-CEntryPointErrors.MAP_HEAP_FAILED);
                    }
                } else {
                    if (VirtualMemoryProvider.get().protect(imageHeapRelocations, heapRelocsLength, Access.READ) != 0) {
                        imageHeapRelocations = WordFactory.pointer(-CEntryPointErrors.PROTECT_HEAP_FAILED);
                    }
                }

                cachedImageHeapRelocationsPtr.writeWordVolatile(0, imageHeapRelocations);
            }
        }

        assert imageHeapRelocations.isNonNull() && imageHeapRelocations.notEqual(COPY_RELOCATIONS_IN_PROGRESS);
        return imageHeapRelocations;
    }

    /**
     * Locate our image file, containing the image heap. Unfortunately we must open it by its path.
     *
     * NOTE: we look for the relocatables partition of the linker-mapped heap because it always
     * stays mapped, while the rest of the linker-mapped heap can be unmapped after tearing down the
     * first isolate. We do not use /proc/self/exe because it breaks with some tools like Valgrind.
     */
    @Uninterruptible(reason = "Called during isolate initialization.")
    private static int openImageFile() {
        final int failfd = (int) CANNOT_OPEN_FD.rawValue();
        int mapfd = Fcntl.NoTransitions.open(PROC_SELF_MAPS.get(), Fcntl.O_RDONLY(), 0);
        if (mapfd == -1) {
            return failfd;
        }
        final int bufferSize = MAX_PATHLEN;
        CCharPointer buffer = StackValue.get(bufferSize);

        // Find the offset of the magic word in the image file. We cannot reliably compute it from
        // the image heap offset below because it might be in a different file segment.
        Pointer magicAddress = MAGIC.get();
        int wordSize = ConfigurationValues.getTarget().wordSize;
        WordPointer magicMappingStart = StackValue.get(WordPointer.class);
        WordPointer magicMappingFileOffset = StackValue.get(WordPointer.class);
        boolean found = findMapping(mapfd, buffer, bufferSize, magicAddress, magicAddress.add(wordSize), magicMappingStart, magicMappingFileOffset, false);
        if (!found) {
            Unistd.NoTransitions.close(mapfd);
            return failfd;
        }
        Word magicFileOffset = (Word) magicAddress.subtract(magicMappingStart.read()).add(magicMappingFileOffset.read());

        if (Unistd.NoTransitions.lseek(mapfd, signed(0), Unistd.SEEK_SET()).notEqual(0)) {
            Unistd.NoTransitions.close(mapfd);
            return failfd;
        }
        // The relocatables partition might stretch over two adjacent mappings due to permission
        // differences, so only locate the mapping for the first page of relocatables
        UnsignedWord pageSize = VirtualMemoryProvider.get().getGranularity();
        WordPointer relocsMappingStart = StackValue.get(WordPointer.class);
        WordPointer relocsMappingFileOffset = StackValue.get(WordPointer.class);
        found = findMapping(mapfd, buffer, bufferSize, IMAGE_HEAP_RELOCATABLE_BEGIN.get(),
                        IMAGE_HEAP_RELOCATABLE_BEGIN.get().add(pageSize), relocsMappingStart, relocsMappingFileOffset, true);
        Unistd.NoTransitions.close(mapfd);
        if (!found) {
            return failfd;
        }
        int opened = Fcntl.NoTransitions.open(buffer, Fcntl.O_RDONLY(), 0);
        if (opened < 0) {
            return failfd;
        }

        // Compare the magic word in memory with the magic word read from the file
        if (Unistd.NoTransitions.lseek(opened, magicFileOffset, Unistd.SEEK_SET()).notEqual(magicFileOffset)) {
            Unistd.NoTransitions.close(opened);
            return failfd;
        }
        if (PosixUtils.readBytes(opened, buffer, wordSize, 0) != wordSize) {
            Unistd.NoTransitions.close(opened);
            return failfd;
        }
        Word fileMagic = ((WordPointer) buffer).read();
        if (fileMagic.notEqual(magicAddress.readWord(0))) {
            return failfd; // magic number mismatch
        }

        Word imageHeapRelocsOffset = IMAGE_HEAP_RELOCATABLE_BEGIN.get().subtract(IMAGE_HEAP_BEGIN.get());
        Word imageHeapOffset = IMAGE_HEAP_RELOCATABLE_BEGIN.get().subtract(relocsMappingStart.read()).subtract(imageHeapRelocsOffset);
        UnsignedWord fileOffset = imageHeapOffset.add(relocsMappingFileOffset.read());
        CACHED_IMAGE_HEAP_OFFSET.get().write(fileOffset);
        return opened;
    }

    @Override
    @Uninterruptible(reason = "Called during isolate tear-down.")
    public int freeImageHeap(PointerBase heapBase) {
        if (heapBase.isNonNull()) {
            if (heapBase.equal(IMAGE_HEAP_BEGIN.get())) {
                assert Heap.getHeap().getImageHeapOffsetInAddressSpace() == 0;
                /*
                 * This isolate uses the image heap mapped by the loader. We shouldn't unmap it in
                 * case we are a dynamic library and dlclose() is called on us and tries to access
                 * the pages. However, the heap need not stay resident, so we remap it as an
                 * anonymous mapping. For future isolates, we still need the read-only heap
                 * partition with relocatable addresses that were adjusted by the loader, so we
                 * leave it. (We have already checked that that partition is page-aligned)
                 */
                assert Heap.getHeap().getImageHeapOffsetInAddressSpace() == 0;
                UnsignedWord beforeRelocSize = IMAGE_HEAP_RELOCATABLE_BEGIN.get().subtract((Pointer) heapBase);
                Pointer newHeapBase = VirtualMemoryProvider.get().commit(heapBase, beforeRelocSize, Access.READ);

                if (newHeapBase.isNull() || newHeapBase.notEqual(heapBase)) {
                    return CEntryPointErrors.MAP_HEAP_FAILED;
                }

                Word relocEnd = IMAGE_HEAP_RELOCATABLE_END.get();
                Word afterRelocSize = IMAGE_HEAP_END.get().subtract(relocEnd);
                Pointer newRelocEnd = VirtualMemoryProvider.get().commit(relocEnd, afterRelocSize, Access.READ);

                if (newRelocEnd.isNull() || newRelocEnd.notEqual(relocEnd)) {
                    return CEntryPointErrors.MAP_HEAP_FAILED;
                }
            } else {
                UnsignedWord totalAddressSpaceSize = getImageHeapAddressSpaceSize();
                Pointer addressSpaceStart = (Pointer) heapBase;
                if (DynamicMethodAddressResolutionHeapSupport.isEnabled()) {
                    UnsignedWord preHeapRequiredBytes = DynamicMethodAddressResolutionHeapSupport.get().getDynamicMethodAddressResolverPreHeapMemoryBytes();
                    totalAddressSpaceSize = totalAddressSpaceSize.add(preHeapRequiredBytes);
                    addressSpaceStart = addressSpaceStart.subtract(preHeapRequiredBytes);
                }

                if (VirtualMemoryProvider.get().free(addressSpaceStart, totalAddressSpaceSize) != 0) {
                    return CEntryPointErrors.FREE_IMAGE_HEAP_FAILED;
                }
            }
        }
        return CEntryPointErrors.NO_ERROR;
    }
}
