/*
 * Copyright (c) 2021, 2021, Oracle and/or its affiliates. All rights reserved.
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
package com.oracle.svm.hosted;

import java.util.function.Supplier;

import org.graalvm.compiler.graph.Node;
import org.graalvm.compiler.nodes.ConstantNode;
import org.graalvm.compiler.nodes.DeoptimizeNode;
import org.graalvm.compiler.nodes.FixedNode;
import org.graalvm.compiler.nodes.Invoke;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.extended.ForeignCallNode;
import org.graalvm.compiler.nodes.spi.CoreProviders;
import org.graalvm.compiler.nodes.spi.SimplifierTool;

import com.oracle.graal.pointsto.PointsToAnalysis;
import com.oracle.graal.pointsto.infrastructure.Universe;
import com.oracle.graal.pointsto.meta.AnalysisType;
import com.oracle.graal.pointsto.results.StrengthenGraphs;
import com.oracle.svm.common.meta.MultiMethod;
import com.oracle.svm.core.SubstrateUtil;
import com.oracle.svm.core.Uninterruptible;
import com.oracle.svm.core.graal.nodes.InlinedInvokeArgumentsNode;
import com.oracle.svm.core.graal.nodes.LoweredDeadEndNode;
import com.oracle.svm.core.nodes.SubstrateMethodCallTargetNode;
import com.oracle.svm.core.snippets.SnippetRuntime;
import com.oracle.svm.core.util.HostedStringDeduplication;
import com.oracle.svm.core.util.VMError;
import com.oracle.svm.hosted.code.SubstrateCompilationDirectives;
import com.oracle.svm.hosted.meta.HostedType;

import jdk.vm.ci.meta.DeoptimizationAction;
import jdk.vm.ci.meta.DeoptimizationReason;
import jdk.vm.ci.meta.JavaMethodProfile;
import jdk.vm.ci.meta.JavaTypeProfile;

public class SubstrateStrengthenGraphs extends StrengthenGraphs {

    public SubstrateStrengthenGraphs(PointsToAnalysis bb, Universe converter) {
        super(bb, converter);
    }

    @Override
    protected AnalysisType getSingleImplementorType(AnalysisType originalType) {
        HostedType singleImplementorType = ((HostedType) converter.lookup(originalType)).getSingleImplementor();
        return singleImplementorType == null ? null : singleImplementorType.getWrapped();
    }

    @Override
    protected AnalysisType getStrengthenStampType(AnalysisType originalType) {
        HostedType strengthenStampType = ((HostedType) converter.lookup(originalType)).getStrengthenStampType();
        return strengthenStampType == null ? null : strengthenStampType.getWrapped();
    }

    @Override
    protected FixedNode createInvokeWithNullReceiverReplacement(StructuredGraph graph) {
        /*
         * Since this only should happen in a runtime compiled method, we can directly insert a
         * deoptimize node.
         */
        VMError.guarantee(SubstrateCompilationDirectives.isRuntimeCompiledMethod(graph.method()), "Creating null check deoptimize in non-runtime compiled method: %s", graph.method());
        DeoptimizeNode deopt = graph.add(new DeoptimizeNode(DeoptimizationAction.None, DeoptimizationReason.NullCheckException));
        return deopt;
    }

    @Override
    protected FixedNode createUnreachable(StructuredGraph graph, CoreProviders providers, Supplier<String> message) {
        FixedNode unreachableNode = graph.add(new LoweredDeadEndNode());

        /*
         * To aid debugging of static analysis problems, we can print details about why the place is
         * unreachable before failing fatally. But since these strings are long and not useful for
         * non-VM developers, we only do it when assertions are enabled for the image builder. And
         * Uninterruptible methods might not be able to access the heap yet for the error message
         * constant, so we skip it for such methods too.
         *
         * We also do not print out this message for runtime compiled methods and methods which can
         * deopt for testing because it would require us to preserve additional graph state.
         */
        boolean insertMessage = SubstrateUtil.assertionsEnabled() &&
                        !Uninterruptible.Utils.isUninterruptible(graph.method()) &&
                        !SubstrateCompilationDirectives.isRuntimeCompiledMethod(graph.method()) &&
                        !SubstrateCompilationDirectives.singleton().isRegisteredForDeoptTesting(graph.method());
        if (insertMessage) {
            ConstantNode messageNode = ConstantNode.forConstant(providers.getConstantReflection().forString(message.get()), providers.getMetaAccess(), graph);
            ForeignCallNode foreignCallNode = graph.add(new ForeignCallNode(SnippetRuntime.UNSUPPORTED_FEATURE, messageNode));
            foreignCallNode.setNext(unreachableNode);
            unreachableNode = foreignCallNode;
        }

        return unreachableNode;
    }

    @Override
    protected void setInvokeProfiles(Invoke invoke, JavaTypeProfile typeProfile, JavaMethodProfile methodProfile) {
        if (needsProfiles(invoke)) {
            ((SubstrateMethodCallTargetNode) invoke.callTarget()).setProfiles(typeProfile, methodProfile);
        }
    }

    protected void setInvokeProfiles(Invoke invoke, JavaTypeProfile typeProfile, JavaMethodProfile methodProfile, JavaTypeProfile staticTypeProfile) {
        if (needsProfiles(invoke)) {
            ((SubstrateMethodCallTargetNode) invoke.callTarget()).setProfiles(typeProfile, methodProfile, staticTypeProfile);
        }
    }

    private static boolean needsProfiles(Invoke invoke) {
        /* We do not need any profiles in methods for JIT compilation at image run time. */
        return ((MultiMethod) invoke.asNode().graph().method()).getMultiMethodKey() != SubstrateCompilationDirectives.RUNTIME_COMPILED_METHOD;
    }

    @Override
    protected String getTypeName(AnalysisType type) {
        return HostedStringDeduplication.singleton().deduplicate(type.toJavaName(true), false);
    }

    @Override
    protected boolean simplifyDelegate(Node n, SimplifierTool tool) {
        /*
         * This node is only necessary for analysis and can be removed once StrengthenGraphs is
         * reached.
         */
        if (n instanceof InlinedInvokeArgumentsNode nodeToRemove) {
            nodeToRemove.graph().removeFixed(nodeToRemove);
            return true;
        }
        return false;
    }
}
