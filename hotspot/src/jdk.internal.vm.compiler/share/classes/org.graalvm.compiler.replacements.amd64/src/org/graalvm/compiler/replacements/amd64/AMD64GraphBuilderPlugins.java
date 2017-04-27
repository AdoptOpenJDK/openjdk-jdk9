/*
 * Copyright (c) 2015, 2016, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.compiler.replacements.amd64;

import static org.graalvm.compiler.core.common.util.Util.Java8OrEarlier;
import static org.graalvm.compiler.replacements.nodes.BinaryMathIntrinsicNode.BinaryOperation.POW;
import static org.graalvm.compiler.replacements.nodes.UnaryMathIntrinsicNode.UnaryOperation.COS;
import static org.graalvm.compiler.replacements.nodes.UnaryMathIntrinsicNode.UnaryOperation.EXP;
import static org.graalvm.compiler.replacements.nodes.UnaryMathIntrinsicNode.UnaryOperation.LOG;
import static org.graalvm.compiler.replacements.nodes.UnaryMathIntrinsicNode.UnaryOperation.LOG10;
import static org.graalvm.compiler.replacements.nodes.UnaryMathIntrinsicNode.UnaryOperation.SIN;
import static org.graalvm.compiler.replacements.nodes.UnaryMathIntrinsicNode.UnaryOperation.TAN;

import org.graalvm.compiler.bytecode.BytecodeProvider;
import org.graalvm.compiler.core.common.LocationIdentity;
import org.graalvm.compiler.lir.amd64.AMD64ArithmeticLIRGeneratorTool.RoundingMode;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.compiler.nodes.graphbuilderconf.GraphBuilderConfiguration.Plugins;
import org.graalvm.compiler.nodes.graphbuilderconf.GraphBuilderContext;
import org.graalvm.compiler.nodes.graphbuilderconf.InvocationPlugin;
import org.graalvm.compiler.nodes.graphbuilderconf.InvocationPlugin.Receiver;
import org.graalvm.compiler.nodes.graphbuilderconf.InvocationPlugins;
import org.graalvm.compiler.nodes.graphbuilderconf.InvocationPlugins.Registration;
import org.graalvm.compiler.nodes.java.AtomicReadAndAddNode;
import org.graalvm.compiler.nodes.java.AtomicReadAndWriteNode;
import org.graalvm.compiler.nodes.memory.address.AddressNode;
import org.graalvm.compiler.nodes.memory.address.OffsetAddressNode;
import org.graalvm.compiler.replacements.IntegerSubstitutions;
import org.graalvm.compiler.replacements.LongSubstitutions;
import org.graalvm.compiler.replacements.StandardGraphBuilderPlugins.UnsafeGetPlugin;
import org.graalvm.compiler.replacements.StandardGraphBuilderPlugins.UnsafePutPlugin;
import org.graalvm.compiler.replacements.nodes.BinaryMathIntrinsicNode;
import org.graalvm.compiler.replacements.nodes.BinaryMathIntrinsicNode.BinaryOperation;
import org.graalvm.compiler.replacements.nodes.BitCountNode;
import org.graalvm.compiler.replacements.nodes.UnaryMathIntrinsicNode;
import org.graalvm.compiler.replacements.nodes.UnaryMathIntrinsicNode.UnaryOperation;

import jdk.vm.ci.amd64.AMD64;
import jdk.vm.ci.amd64.AMD64.CPUFeature;
import jdk.vm.ci.meta.JavaKind;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import sun.misc.Unsafe;

public class AMD64GraphBuilderPlugins {

    public static void register(Plugins plugins, BytecodeProvider replacementsBytecodeProvider, AMD64 arch, boolean arithmeticStubs) {
        InvocationPlugins invocationPlugins = plugins.getInvocationPlugins();
        invocationPlugins.defer(new Runnable() {
            @Override
            public void run() {
                registerIntegerLongPlugins(invocationPlugins, IntegerSubstitutions.class, JavaKind.Int, arch, replacementsBytecodeProvider);
                registerIntegerLongPlugins(invocationPlugins, LongSubstitutions.class, JavaKind.Long, arch, replacementsBytecodeProvider);
                registerUnsafePlugins(invocationPlugins, replacementsBytecodeProvider);
                registerMathPlugins(invocationPlugins, arch, arithmeticStubs, replacementsBytecodeProvider);
            }
        });
    }

    private static void registerIntegerLongPlugins(InvocationPlugins plugins, Class<?> substituteDeclaringClass, JavaKind kind, AMD64 arch, BytecodeProvider bytecodeProvider) {
        Class<?> declaringClass = kind.toBoxedJavaClass();
        Class<?> type = kind.toJavaClass();
        Registration r = new Registration(plugins, declaringClass, bytecodeProvider);
        if (arch.getFeatures().contains(AMD64.CPUFeature.LZCNT) && arch.getFlags().contains(AMD64.Flag.UseCountLeadingZerosInstruction)) {
            r.register1("numberOfLeadingZeros", type, new InvocationPlugin() {
                @Override
                public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode value) {
                    ValueNode folded = AMD64CountLeadingZerosNode.tryFold(value);
                    if (folded != null) {
                        b.addPush(JavaKind.Int, folded);
                    } else {
                        b.addPush(JavaKind.Int, new AMD64CountLeadingZerosNode(value));
                    }
                    return true;
                }
            });
        } else {
            r.registerMethodSubstitution(substituteDeclaringClass, "numberOfLeadingZeros", type);
        }
        if (arch.getFeatures().contains(AMD64.CPUFeature.BMI1) && arch.getFlags().contains(AMD64.Flag.UseCountTrailingZerosInstruction)) {
            r.register1("numberOfTrailingZeros", type, new InvocationPlugin() {
                @Override
                public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode value) {
                    ValueNode folded = AMD64CountTrailingZerosNode.tryFold(value);
                    if (folded != null) {
                        b.addPush(JavaKind.Int, folded);
                    } else {
                        b.addPush(JavaKind.Int, new AMD64CountTrailingZerosNode(value));
                    }
                    return true;
                }
            });
        } else {
            r.registerMethodSubstitution(substituteDeclaringClass, "numberOfTrailingZeros", type);
        }

        if (arch.getFeatures().contains(AMD64.CPUFeature.POPCNT)) {
            r.register1("bitCount", type, new InvocationPlugin() {
                @Override
                public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode value) {
                    b.push(JavaKind.Int, b.recursiveAppend(new BitCountNode(value).canonical(null)));
                    return true;
                }
            });
        }
    }

    private static void registerMathPlugins(InvocationPlugins plugins, AMD64 arch, boolean arithmeticStubs, BytecodeProvider bytecodeProvider) {
        Registration r = new Registration(plugins, Math.class, bytecodeProvider);
        registerUnaryMath(r, "log", LOG);
        registerUnaryMath(r, "log10", LOG10);
        registerUnaryMath(r, "exp", EXP);
        registerBinaryMath(r, "pow", POW);
        if (arithmeticStubs) {
            registerUnaryMath(r, "sin", SIN);
            registerUnaryMath(r, "cos", COS);
            registerUnaryMath(r, "tan", TAN);
        } else {
            r.registerMethodSubstitution(AMD64MathSubstitutions.class, "sin", double.class);
            r.registerMethodSubstitution(AMD64MathSubstitutions.class, "cos", double.class);
            r.registerMethodSubstitution(AMD64MathSubstitutions.class, "tan", double.class);
        }

        if (arch.getFeatures().contains(CPUFeature.SSE4_1)) {
            registerRound(r, "rint", RoundingMode.NEAREST);
            registerRound(r, "ceil", RoundingMode.UP);
            registerRound(r, "floor", RoundingMode.DOWN);
        }
    }

    private static void registerUnaryMath(Registration r, String name, UnaryOperation operation) {
        r.register1(name, Double.TYPE, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode value) {
                b.push(JavaKind.Double, b.recursiveAppend(UnaryMathIntrinsicNode.create(value, operation)));
                return true;
            }
        });
    }

    private static void registerBinaryMath(Registration r, String name, BinaryOperation operation) {
        r.register2(name, Double.TYPE, Double.TYPE, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode x, ValueNode y) {
                b.push(JavaKind.Double, b.recursiveAppend(BinaryMathIntrinsicNode.create(x, y, operation)));
                return true;
            }
        });
    }

    private static void registerRound(Registration r, String name, RoundingMode mode) {
        r.register1(name, Double.TYPE, new InvocationPlugin() {
            @Override
            public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver receiver, ValueNode arg) {
                b.push(JavaKind.Double, b.append(new AMD64RoundNode(arg, mode)));
                return true;
            }
        });
    }

    private static void registerUnsafePlugins(InvocationPlugins plugins, BytecodeProvider replacementsBytecodeProvider) {
        Registration r;
        if (Java8OrEarlier) {
            r = new Registration(plugins, Unsafe.class);
        } else {
            r = new Registration(plugins, "jdk.internal.misc.Unsafe", replacementsBytecodeProvider);
        }
        for (JavaKind kind : new JavaKind[]{JavaKind.Int, JavaKind.Long, JavaKind.Object}) {
            Class<?> javaClass = kind == JavaKind.Object ? Object.class : kind.toJavaClass();

            r.register4("getAndSet" + kind.name(), Receiver.class, Object.class, long.class, javaClass, new InvocationPlugin() {
                @Override
                public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver unsafe, ValueNode object, ValueNode offset, ValueNode value) {
                    // Emits a null-check for the otherwise unused receiver
                    unsafe.get();
                    b.addPush(kind, new AtomicReadAndWriteNode(object, offset, value, kind, LocationIdentity.any()));
                    b.getGraph().markUnsafeAccess();
                    return true;
                }
            });
            if (kind != JavaKind.Object) {
                r.register4("getAndAdd" + kind.name(), Receiver.class, Object.class, long.class, javaClass, new InvocationPlugin() {
                    @Override
                    public boolean apply(GraphBuilderContext b, ResolvedJavaMethod targetMethod, Receiver unsafe, ValueNode object, ValueNode offset, ValueNode delta) {
                        // Emits a null-check for the otherwise unused receiver
                        unsafe.get();
                        AddressNode address = b.add(new OffsetAddressNode(object, offset));
                        b.addPush(kind, new AtomicReadAndAddNode(address, delta, LocationIdentity.any()));
                        b.getGraph().markUnsafeAccess();
                        return true;
                    }
                });
            }
        }

        for (JavaKind kind : new JavaKind[]{JavaKind.Char, JavaKind.Short, JavaKind.Int, JavaKind.Long}) {
            Class<?> javaClass = kind.toJavaClass();
            r.registerOptional3("get" + kind.name() + "Unaligned", Receiver.class, Object.class, long.class, new UnsafeGetPlugin(kind, false));
            r.registerOptional4("put" + kind.name() + "Unaligned", Receiver.class, Object.class, long.class, javaClass, new UnsafePutPlugin(kind, false));
        }
    }
}
