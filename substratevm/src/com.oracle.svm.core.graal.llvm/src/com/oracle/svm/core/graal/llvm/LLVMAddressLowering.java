/*
 * Copyright (c) 2018, 2018, Oracle and/or its affiliates. All rights reserved.
 * ORACLE PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.oracle.svm.core.graal.llvm;

import static com.oracle.svm.core.SubstrateOptions.CompilerBackend;

import org.graalvm.compiler.core.common.CompressEncoding;
import org.graalvm.compiler.graph.NodeClass;
import org.graalvm.compiler.lir.gen.LIRGeneratorTool;
import org.graalvm.compiler.nodeinfo.NodeInfo;
import org.graalvm.compiler.nodes.NodeView;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.memory.address.AddressNode;
import org.graalvm.compiler.nodes.spi.LIRLowerable;
import org.graalvm.compiler.nodes.spi.NodeLIRBuilderTool;
import org.graalvm.compiler.phases.Phase;
import org.graalvm.compiler.phases.common.AddressLoweringPhase;
import org.graalvm.nativeimage.Feature;
import org.graalvm.nativeimage.ImageSingletons;
import org.graalvm.nativeimage.Platform;
import org.graalvm.nativeimage.Platforms;

import com.oracle.svm.core.annotate.AutomaticFeature;
import com.oracle.svm.core.graal.code.SubstrateAddressLoweringPhaseFactory;
import com.oracle.svm.core.graal.meta.SubstrateRegisterConfig;
import org.graalvm.compiler.core.llvm.LLVMUtils.LLVMAddressValue;

@AutomaticFeature
@Platforms(Platform.AMD64.class)
class SubstrateAMD64AddressLoweringPhaseFeature implements Feature {
    @Override
    public void afterRegistration(AfterRegistrationAccess access) {
        ImageSingletons.add(SubstrateAddressLoweringPhaseFactory.class, new SubstrateAddressLoweringPhaseFactory() {

            @Override
            public Phase newAddressLowering(CompressEncoding compressEncoding, SubstrateRegisterConfig registerConfig) {
                LLVMAddressLowering addressLowering = new LLVMAddressLowering();
                return new AddressLoweringPhase(addressLowering);
            }
        });
    }

    @Override
    public boolean isInConfiguration(IsInConfigurationAccess access) {
        return Platform.includedIn(Platform.AMD64.class) && CompilerBackend.getValue().equals("llvm");
    }
}

public class LLVMAddressLowering extends AddressLoweringPhase.AddressLowering {

    @Override
    public AddressNode lower(ValueNode base, ValueNode offset) {
        LLVMAddressNode ret = new LLVMAddressNode(base, offset);
        StructuredGraph graph = base.graph();
        return graph.unique(ret);
    }

    @NodeInfo
    public static class LLVMAddressNode extends AddressNode implements LIRLowerable {
        public static final NodeClass<LLVMAddressNode> TYPE = NodeClass.create(LLVMAddressNode.class);

        @Input private ValueNode base;
        @Input private ValueNode index;

        public LLVMAddressNode(ValueNode base, ValueNode offset) {
            super(TYPE);
            this.base = base;
            this.index = offset;
        }

        @Override
        public ValueNode getBase() {
            return base;
        }

        @Override
        public ValueNode getIndex() {
            return index;
        }

        @Override
        public long getMaxConstantDisplacement() {
            return Long.MAX_VALUE;
        }

        @Override
        public void generate(NodeLIRBuilderTool generator) {
            LIRGeneratorTool gen = generator.getLIRGeneratorTool();
            generator.setResult(this, new LLVMAddressValue(gen.getLIRKind(stamp(NodeView.DEFAULT)), generator.operand(base), generator.operand(index)));
        }
    }
}
