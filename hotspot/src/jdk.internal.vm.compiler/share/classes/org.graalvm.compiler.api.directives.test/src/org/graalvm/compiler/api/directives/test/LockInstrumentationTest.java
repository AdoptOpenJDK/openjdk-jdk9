/*
 * Copyright (c) 2016, 2016, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.compiler.api.directives.test;

import java.io.IOException;

import org.junit.Assert;
import org.junit.Test;

import org.graalvm.compiler.api.directives.GraalDirectives;
import org.graalvm.compiler.core.common.GraalOptions;
import org.graalvm.compiler.core.test.GraalCompilerTest;
import org.graalvm.compiler.options.OptionValue;
import org.graalvm.compiler.options.OptionValue.OverrideScope;

import jdk.internal.org.objectweb.asm.Opcodes;
import jdk.vm.ci.code.InstalledCode;
import jdk.vm.ci.hotspot.HotSpotResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaMethod;

@SuppressWarnings("try")
public class LockInstrumentationTest extends GraalCompilerTest {

    private TinyInstrumentor instrumentor;

    public LockInstrumentationTest() {
        HotSpotResolvedJavaMethod method = (HotSpotResolvedJavaMethod) getResolvedJavaMethod(ClassA.class, "notInlinedMethod");
        method.setNotInlineable();

        try {
            instrumentor = new TinyInstrumentor(LockInstrumentationTest.class, "instrumentation");
        } catch (IOException e) {
            Assert.fail("unable to initialize the instrumentor: " + e);
        }
    }

    public static class ClassA {

        // This method should be marked as not inlineable
        public void notInlinedMethod() {
        }

    }

    public static final Object lock = new Object();
    public static boolean lockAfterCheckPoint;
    public static boolean checkpoint;

    private static void resetFlags() {
        lockAfterCheckPoint = false;
        checkpoint = false;
    }

    static void instrumentation() {
        GraalDirectives.instrumentationBeginForPredecessor();
        lockAfterCheckPoint = checkpoint;
        GraalDirectives.instrumentationEnd();
    }

    public static void lockSnippet() {
        synchronized (lock) {
            checkpoint = true;
            ClassA a = new ClassA();
            a.notInlinedMethod();
        }
    }

    @Test
    public void testLock() {
        try (OverrideScope s = OptionValue.override(GraalOptions.UseGraalInstrumentation, true)) {
            Class<?> clazz = instrumentor.instrument(LockInstrumentationTest.class, "lockSnippet", Opcodes.MONITORENTER);
            ResolvedJavaMethod method = getResolvedJavaMethod(clazz, "lockSnippet");
            executeExpected(method, null); // ensure the method is fully resolved
            resetFlags();
            // The monitorenter anchors. We expect the instrumentation set the flag before passing
            // the checkpoint.
            InstalledCode code = getCode(method);
            code.executeVarargs();
            Assert.assertFalse("expected lock was performed before checkpoint", lockAfterCheckPoint);
        } catch (Throwable e) {
            throw new AssertionError(e);
        }
    }

    public static void postponeLockSnippet() {
        ClassA a = new ClassA();

        synchronized (a) {
            checkpoint = true;
            a.notInlinedMethod();
        }

    }

    @Test
    public void testNonEscapeLock() {
        try (OverrideScope s = OptionValue.override(GraalOptions.UseGraalInstrumentation, true)) {
            Class<?> clazz = instrumentor.instrument(LockInstrumentationTest.class, "postponeLockSnippet", Opcodes.MONITORENTER);
            ResolvedJavaMethod method = getResolvedJavaMethod(clazz, "postponeLockSnippet");
            executeExpected(method, null); // ensure the method is fully resolved
            resetFlags();
            // The lock in the snippet will be relocated before the invocation to
            // notInlinedMethod(), i.e., after the checkpoint. We expect the instrumentation follows
            // and flag will be set to true.
            InstalledCode code = getCode(method);
            code.executeVarargs();
            Assert.assertTrue("expected lock was performed after checkpoint", lockAfterCheckPoint);
        } catch (Throwable e) {
            throw new AssertionError(e);
        }
    }

}
