/*
 * Copyright (c) 2012, 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.oracle.truffle.sl.nodes;

import com.oracle.truffle.api.frame.FrameInstance;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.RootNode;
import com.oracle.truffle.api.source.SourceSection;
import com.oracle.truffle.api.strings.TruffleString;
import com.oracle.truffle.sl.SLException;
import com.oracle.truffle.sl.SLLanguage;
import com.oracle.truffle.sl.runtime.SLFunction;

/**
 * The initial {@link RootNode} of {@link SLFunction functions} when they are created, i.e., when
 * they are still undefined. Executing it throws an {@link SLException#undefinedFunction exception}.
 */
public final class SLUndefinedFunctionRootNode extends SLRootNode {

    private static final Object[] EMPTY_OBJECT_ARRAY = new Object[0];

    private final TruffleString name;

    public SLUndefinedFunctionRootNode(SLLanguage language, TruffleString name) {
        super(language, null);
        this.name = name;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        throw SLException.undefinedFunction(null, name);
    }

    @Override
    public SourceSection getSourceSection() {
        return null;
    }

    @Override
    public SourceSection ensureSourceSection() {
        return null;
    }

    @Override
    public SLExpressionNode getBodyNode() {
        return null;
    }

    @Override
    public Object[] getLocalNames(FrameInstance frame) {
        return EMPTY_OBJECT_ARRAY;
    }

    @Override
    public Object[] getLocalValues(FrameInstance frame) {
        return EMPTY_OBJECT_ARRAY;
    }

    @Override
    public void setLocalValues(FrameInstance frame, Object[] args) {
    }

    @Override
    public TruffleString getTSName() {
        return name;
    }
}
