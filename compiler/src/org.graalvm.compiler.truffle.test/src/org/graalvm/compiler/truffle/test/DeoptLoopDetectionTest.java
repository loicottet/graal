/*
 * Copyright (c) 2025, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.compiler.truffle.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.graalvm.compiler.truffle.common.TruffleCompilerListener;
import org.graalvm.compiler.truffle.runtime.AbstractGraalTruffleRuntimeListener;
import org.graalvm.compiler.truffle.runtime.GraalTruffleRuntime;
import org.graalvm.compiler.truffle.runtime.OptimizedCallTarget;
import org.graalvm.compiler.truffle.runtime.TruffleInlining;
import org.graalvm.polyglot.Context;
import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import com.oracle.truffle.api.Assumption;
import com.oracle.truffle.api.CallTarget;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.Truffle;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.test.polyglot.AbstractPolyglotTest;

public class DeoptLoopDetectionTest {

    private final AtomicReference<CallTarget> callTargetFilter = new AtomicReference<>();
    private final AtomicInteger compilationCounter = new AtomicInteger();
    private final AtomicReference<String> compilationFailedReason = new AtomicReference<>();
    private Context context;
    private final AbstractGraalTruffleRuntimeListener listener = new AbstractGraalTruffleRuntimeListener(GraalTruffleRuntime.getRuntime()) {
        @Override
        public void onCompilationSuccess(OptimizedCallTarget target, TruffleInlining inliningDecision, TruffleCompilerListener.GraphInfo graph, TruffleCompilerListener.CompilationResultInfo result,
                        int tier) {
            super.onCompilationSuccess(target, inliningDecision, graph, result, tier);
            if (target == callTargetFilter.get()) {
                compilationCounter.incrementAndGet();
            }
        }
    };

    @Before
    public void setup() {
        context = Context.newBuilder().//
                        option("engine.CompilationFailureAction", "Silent").//
                        option("engine.BackgroundCompilation", "false").//
                        option("engine.CompileImmediately", "true").build();
        context.enter();
        Assume.assumeTrue(Truffle.getRuntime() instanceof GraalTruffleRuntime);
        ((GraalTruffleRuntime) Truffle.getRuntime()).addListener(listener);

    }

    @After
    public void tearDown() {
        context.close();
        ((GraalTruffleRuntime) Truffle.getRuntime()).removeListener(listener);
    }

    @Test
    public void testAlwaysDeopt() {
        assertDeoptLoop(new BaseRootNode() {
            @Override
            public Object execute(VirtualFrame frame) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                return null;
            }
        }, "alwaysDeopt", CallTarget::call, 1);
    }

    @Test
    public void testLocalDeopt() {
        assertDeoptLoop(new BaseRootNode() {

            @CompilationFinal boolean cachedValue;

            @Override
            public Object execute(VirtualFrame frame) {
                boolean arg = (boolean) frame.getArguments()[0];
                if (this.cachedValue != arg) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    this.cachedValue = arg;
                }
                return this.cachedValue;
            }

        }, "localDeopt", (target) -> {
            target.call(true);
            target.call(false);
        }, 2);
    }

    @Test
    public void testGlobalDeopt() {
        assertDeoptLoop(new BaseRootNode() {

            @CompilationFinal Assumption assumption = Assumption.create();

            @Override
            public Object execute(VirtualFrame frame) {
                boolean arg = (boolean) frame.getArguments()[0];
                if (arg && assumption.isValid()) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    assumption.invalidate();
                    assumption = Assumption.create();
                }
                return arg;
            }

        }, "globalDeopt", (target) -> {
            target.call(true);
        }, 1);
    }

    static class StaticAssumptionRootNode extends BaseRootNode {
        @CompilationFinal static volatile Assumption assumption = Assumption.create();

        @Override
        public Object execute(VirtualFrame frame) {
            if (!assumption.isValid()) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
            }
            return 0;
        }
    }

    @Test
    public void testStaticAssumptionNoDeopt() {
        boolean[] firstExecution = new boolean[]{true};
        AbstractPolyglotTest.assertFails(() -> assertDeoptLoop(new StaticAssumptionRootNode(), "staticAssumptionNoDeopt", (target) -> {
            target.call();
            if (firstExecution[0]) {
                StaticAssumptionRootNode.assumption.invalidate();
                StaticAssumptionRootNode.assumption = Assumption.create();
                firstExecution[0] = false;
            }
        }, 1), AssertionError.class, new Consumer<AssertionError>() {
            @Override
            public void accept(AssertionError expectedError) {
                Assert.assertEquals("No deopt loop detected after " + MAX_EXECUTIONS + " executions", expectedError.getMessage());
            }
        });
    }

    @Test
    public void testLocalDeoptWithChangedCode() {
        assertDeoptLoop(new BaseRootNode() {

            @CompilationFinal boolean cachedValue;

            @Override
            public Object execute(VirtualFrame frame) {
                int arg = (int) frame.getArguments()[0];
                if (arg > 0) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    this.cachedValue = !this.cachedValue;
                }
                int result = arg;
                if (cachedValue) {
                    result--;
                } else {
                    result++;
                }
                return result;
            }

        }, "localLoopDeoptwithChangedCode", (target) -> {
            target.call(1);
        }, 1);
    }

    private static final int MAX_EXECUTIONS = 100;

    private void assertDeoptLoop(BaseRootNode root, String name, Consumer<CallTarget> callStrategy, int compilationsPerIteration) {
        root.name = name;
        CallTarget callTarget = root.getCallTarget();
        callTargetFilter.set(callTarget);
        compilationCounter.set(0);
        compilationFailedReason.set(null);

        callStrategy.accept(callTarget);

        assertEquals(compilationsPerIteration, compilationCounter.get());
        int iterationCounter = 0;
        while (iterationCounter < MAX_EXECUTIONS) {
            callStrategy.accept(callTarget);
            iterationCounter++;
        }
        if (compilationCounter.get() < iterationCounter) {
            throw new AssertionError("No deopt loop detected after " + MAX_EXECUTIONS + " executions");
        }

        assertTrue(iterationCounter * compilationsPerIteration >= MAX_EXECUTIONS);
    }

    abstract static class BaseRootNode extends RootNode {

        private String name = this.getClass().getSimpleName();

        BaseRootNode() {
            super(null);
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String toString() {
            return getName();
        }
    }
}