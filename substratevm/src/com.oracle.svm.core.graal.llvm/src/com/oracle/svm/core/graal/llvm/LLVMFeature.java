/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.svm.core.graal.llvm;

import static com.oracle.svm.core.SubstrateOptions.CompilerBackend;

import org.graalvm.compiler.options.Option;
import org.graalvm.compiler.phases.util.Providers;
import org.graalvm.compiler.replacements.Snippets;
import org.graalvm.nativeimage.Feature;
import org.graalvm.nativeimage.ImageSingletons;

import com.oracle.svm.core.annotate.AutomaticFeature;
import com.oracle.svm.core.graal.GraalFeature;
import com.oracle.svm.core.graal.code.SubstrateBackend;
import com.oracle.svm.core.graal.code.SubstrateBackendFactory;
import com.oracle.svm.core.option.HostedOptionKey;
import com.oracle.svm.hosted.code.CompileQueue;
import com.oracle.svm.hosted.image.NativeImageCodeCache;
import com.oracle.svm.hosted.image.NativeImageCodeCacheFactory;
import com.oracle.svm.hosted.image.NativeImageHeap;

@AutomaticFeature
public class LLVMFeature implements Feature, GraalFeature, Snippets {

    public static class Options {
        @Option(help = "Include debugging info in the generated image (for LLVM backend).")//
        public static final HostedOptionKey<Integer> IncludeLLVMDebugInfo = new HostedOptionKey<>(0);
    }

    @Override
    public boolean isInConfiguration(IsInConfigurationAccess access) {
        return CompilerBackend.getValue().equals("llvm");
    }

    @Override
    public void afterRegistration(AfterRegistrationAccess access) {
        ImageSingletons.add(SubstrateBackendFactory.class, new SubstrateBackendFactory() {
            @Override
            public SubstrateBackend newBackend(Providers newProviders) {
                return new SubstrateLLVMBackend(newProviders);
            }
        });
        ImageSingletons.add(NativeImageCodeCacheFactory.class, new NativeImageCodeCacheFactory() {
            @Override
            public NativeImageCodeCache newCodeCache(CompileQueue compileQueue, NativeImageHeap heap) {
                return new LLVMNativeImageCodeCache(compileQueue.getCompilations(), heap);
            }
        });
    }
}
