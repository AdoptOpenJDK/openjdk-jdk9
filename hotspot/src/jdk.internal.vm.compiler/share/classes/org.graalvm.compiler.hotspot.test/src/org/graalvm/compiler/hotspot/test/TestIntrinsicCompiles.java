/*
 * Copyright (c) 2014, 2016, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
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
package org.graalvm.compiler.hotspot.test;

import java.util.List;
import java.util.Set;

import org.junit.Test;

import org.graalvm.compiler.api.test.Graal;
import org.graalvm.compiler.core.common.CompilationIdentifier;
import org.graalvm.compiler.core.test.GraalCompilerTest;
import org.graalvm.compiler.hotspot.HotSpotGraalCompiler;
import org.graalvm.compiler.hotspot.HotSpotGraalRuntimeProvider;
import org.graalvm.compiler.hotspot.meta.HotSpotProviders;
import org.graalvm.compiler.nodes.StructuredGraph;
import org.graalvm.compiler.nodes.graphbuilderconf.GraphBuilderConfiguration.Plugins;
import org.graalvm.compiler.nodes.graphbuilderconf.InvocationPlugin;
import org.graalvm.compiler.nodes.graphbuilderconf.InvocationPlugins;
import org.graalvm.compiler.nodes.graphbuilderconf.MethodSubstitutionPlugin;
import org.graalvm.compiler.runtime.RuntimeProvider;

import jdk.vm.ci.hotspot.HotSpotVMConfigStore;
import jdk.vm.ci.hotspot.VMIntrinsicMethod;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.runtime.JVMCI;

/**
 * Exercise the compilation of intrinsic method substitutions.
 */
public class TestIntrinsicCompiles extends GraalCompilerTest {

    private static boolean match(ResolvedJavaMethod method, VMIntrinsicMethod intrinsic) {
        if (intrinsic.name.equals(method.getName())) {
            if (intrinsic.descriptor.equals(method.getSignature().toMethodDescriptor())) {
                String declaringClass = method.getDeclaringClass().toClassName().replace('.', '/');
                if (declaringClass.equals(intrinsic.declaringClass)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static ResolvedJavaMethod findMethod(Set<ResolvedJavaMethod> methods, VMIntrinsicMethod intrinsic) {
        for (ResolvedJavaMethod method : methods) {
            if (match(method, intrinsic)) {
                return method;
            }
        }
        return null;
    }

    @Test
    @SuppressWarnings("try")
    public void test() {
        HotSpotGraalCompiler compiler = (HotSpotGraalCompiler) JVMCI.getRuntime().getCompiler();
        HotSpotGraalRuntimeProvider rt = (HotSpotGraalRuntimeProvider) Graal.getRequiredCapability(RuntimeProvider.class);
        HotSpotProviders providers = rt.getHostBackend().getProviders();
        Plugins graphBuilderPlugins = providers.getGraphBuilderPlugins();
        InvocationPlugins invocationPlugins = graphBuilderPlugins.getInvocationPlugins();

        Set<ResolvedJavaMethod> pluginMethods = invocationPlugins.getMethods();
        HotSpotVMConfigStore store = rt.getVMConfig().getStore();
        List<VMIntrinsicMethod> intrinsics = store.getIntrinsics();
        for (VMIntrinsicMethod intrinsic : intrinsics) {
            ResolvedJavaMethod method = findMethod(pluginMethods, intrinsic);
            if (method != null) {
                InvocationPlugin plugin = invocationPlugins.lookupInvocation(method);
                if (plugin instanceof MethodSubstitutionPlugin && !method.isNative()) {
                    StructuredGraph graph = compiler.getIntrinsicGraph(method, providers, CompilationIdentifier.INVALID_COMPILATION_ID);
                    getCode(method, graph);
                }
            }
        }
    }
}
