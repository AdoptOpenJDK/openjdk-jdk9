/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
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

package jdk.tools.jaotc;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.graalvm.compiler.code.CompilationResult;
import org.graalvm.compiler.core.GraalCompilerOptions;
import org.graalvm.compiler.debug.Debug;
import org.graalvm.compiler.debug.DebugEnvironment;
import org.graalvm.compiler.debug.Management;
import org.graalvm.compiler.debug.TTY;
import org.graalvm.compiler.debug.internal.DebugScope;

import jdk.vm.ci.hotspot.HotSpotJVMCIRuntime;
import jdk.vm.ci.hotspot.HotSpotResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.runtime.JVMCICompiler;

/**
 * Represents a task in the compile queue.
 *
 * This class encapsulates all Graal-specific information that is used during offline AOT
 * compilation of classes. It also defines methods that parse compilation result of Graal to create
 * target-independent representation {@code BinaryContainer} of the intended target binary.
 */
public class AOTCompilationTask implements Runnable, Comparable<Object> {

    private static final AtomicInteger ids = new AtomicInteger();

    private static final com.sun.management.ThreadMXBean threadMXBean = (com.sun.management.ThreadMXBean) Management.getThreadMXBean();

    private final Main main;

    /**
     * The compilation id of this task.
     */
    private final int id;

    private final AOTCompiledClass holder;

    /**
     * Method this task represents.
     */
    private final ResolvedJavaMethod method;

    private final AOTBackend aotBackend;

    /**
     * The result of this compilation task.
     */
    private CompiledMethodInfo result;

    public AOTCompilationTask(Main main, AOTCompiledClass holder, ResolvedJavaMethod method, AOTBackend aotBackend) {
        this.main = main;
        this.id = ids.getAndIncrement();
        this.holder = holder;
        this.method = method;
        this.aotBackend = aotBackend;
    }

    /**
     * Compile a method or a constructor.
     */
    public void run() {
        // Ensure a JVMCI runtime is initialized prior to Debug being initialized as the former
        // may include processing command line options used by the latter.
        HotSpotJVMCIRuntime.runtime();

        // Ensure a debug configuration for this thread is initialized
        if (Debug.isEnabled() && DebugScope.getConfig() == null) {
            DebugEnvironment.initialize(TTY.out);
        }
        AOTCompiler.logCompilation(MiscUtils.uniqueMethodName(method), "Compiling");

        final long threadId = Thread.currentThread().getId();

        final boolean printCompilation = GraalCompilerOptions.PrintCompilation.getValue() && !TTY.isSuppressed();
        final boolean printAfterCompilation = GraalCompilerOptions.PrintAfterCompilation.getValue() && !TTY.isSuppressed();
        if (printCompilation) {
            TTY.println(getMethodDescription() + "...");
        }

        final long start;
        final long allocatedBytesBefore;
        if (printAfterCompilation || printCompilation) {
            start = System.currentTimeMillis();
            allocatedBytesBefore = printAfterCompilation || printCompilation ? threadMXBean.getThreadAllocatedBytes(threadId) : 0L;
        } else {
            start = 0L;
            allocatedBytesBefore = 0L;
        }

        final long startTime = System.currentTimeMillis();
        CompilationResult compResult = aotBackend.compileMethod(method);
        final long endTime = System.currentTimeMillis();

        if (printAfterCompilation || printCompilation) {
            final long stop = System.currentTimeMillis();
            final int targetCodeSize = compResult != null ? compResult.getTargetCodeSize() : -1;
            final long allocatedBytesAfter = threadMXBean.getThreadAllocatedBytes(threadId);
            final long allocatedBytes = (allocatedBytesAfter - allocatedBytesBefore) / 1024;

            if (printAfterCompilation) {
                TTY.println(getMethodDescription() + String.format(" | %4dms %5dB %5dkB", stop - start, targetCodeSize, allocatedBytes));
            } else if (printCompilation) {
                TTY.println(String.format("%-6d JVMCI %-70s %-45s %-50s | %4dms %5dB %5dkB", getId(), "", "", "", stop - start, targetCodeSize, allocatedBytes));
            }
        }

        if (compResult == null) {
            result = null;
            return;
        }

        // For now precision to the nearest second is sufficient.
        Main.writeLog("    Compile Time: " + TimeUnit.MILLISECONDS.toSeconds(endTime - startTime) + "secs");
        if (main.options.debug) {
            aotBackend.printCompiledMethod((HotSpotResolvedJavaMethod) method, compResult);
        }

        result = new CompiledMethodInfo(compResult, new AOTHotSpotResolvedJavaMethod((HotSpotResolvedJavaMethod) method));
    }

    private String getMethodDescription() {
        return String.format("%-6d JVMCI %-70s %-45s %-50s %s", getId(), method.getDeclaringClass().getName(), method.getName(), method.getSignature().toMethodDescriptor(),
                        getEntryBCI() == JVMCICompiler.INVOCATION_ENTRY_BCI ? "" : "(OSR@" + getEntryBCI() + ") ");
    }

    private int getId() {
        return id;
    }

    public int getEntryBCI() {
        return JVMCICompiler.INVOCATION_ENTRY_BCI;
    }

    public ResolvedJavaMethod getMethod() {
        return method;
    }

    /**
     * Returns the holder of this method as a {@link AOTCompiledClass}.
     *
     * @return the holder of this method
     */
    public AOTCompiledClass getHolder() {
        return holder;
    }

    /**
     * Returns the result of this compilation task.
     *
     * @return result of this compilation task
     */
    public CompiledMethodInfo getResult() {
        return result;
    }

    @Override
    public int compareTo(Object obj) {
        AOTCompilationTask other = (AOTCompilationTask) obj;
        return this.id - other.id;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        AOTCompilationTask other = (AOTCompilationTask) obj;
        return (this.id == other.id);
    }

    @Override
    public int hashCode() {
        return 31 + id;
    }

}
