/*
 * Copyright (c) 2016, Oracle and/or its affiliates. All rights reserved.
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

module jdk.internal.vm.compiler {
    requires java.instrument;
    requires java.management;
    requires jdk.management;
    requires jdk.internal.vm.ci;

    // sun.misc.Unsafe is used
    requires jdk.unsupported;

    uses org.graalvm.compiler.code.DisassemblerProvider;
    uses org.graalvm.compiler.core.match.MatchStatementSet;
    uses org.graalvm.compiler.debug.DebugConfigCustomizer;
    uses org.graalvm.compiler.debug.DebugInitializationParticipant;
    uses org.graalvm.compiler.debug.TTYStreamProvider;
    uses org.graalvm.compiler.hotspot.CompilerConfigurationFactory;
    uses org.graalvm.compiler.hotspot.HotSpotBackendFactory;
    uses org.graalvm.compiler.nodes.graphbuilderconf.NodeIntrinsicPluginFactory;

    exports org.graalvm.compiler.api.directives         to jdk.aot;
    exports org.graalvm.compiler.api.runtime            to jdk.aot;
    exports org.graalvm.compiler.api.replacements       to jdk.aot;
    exports org.graalvm.compiler.asm.amd64              to jdk.aot;
    exports org.graalvm.compiler.bytecode               to jdk.aot;
    exports org.graalvm.compiler.code                   to jdk.aot;
    exports org.graalvm.compiler.core                   to jdk.aot;
    exports org.graalvm.compiler.core.common            to jdk.aot;
    exports org.graalvm.compiler.core.target            to jdk.aot;
    exports org.graalvm.compiler.debug                  to jdk.aot;
    exports org.graalvm.compiler.debug.internal         to jdk.aot;
    exports org.graalvm.compiler.graph                  to jdk.aot;
    exports org.graalvm.compiler.hotspot                to jdk.aot;
    exports org.graalvm.compiler.hotspot.meta           to jdk.aot;
    exports org.graalvm.compiler.hotspot.replacements   to jdk.aot;
    exports org.graalvm.compiler.hotspot.stubs          to jdk.aot;
    exports org.graalvm.compiler.hotspot.word           to jdk.aot;
    exports org.graalvm.compiler.java                   to jdk.aot;
    exports org.graalvm.compiler.lir.asm                to jdk.aot;
    exports org.graalvm.compiler.lir.phases             to jdk.aot;
    exports org.graalvm.compiler.nodes                  to jdk.aot;
    exports org.graalvm.compiler.nodes.graphbuilderconf to jdk.aot;
    exports org.graalvm.compiler.options                to jdk.aot;
    exports org.graalvm.compiler.phases                 to jdk.aot;
    exports org.graalvm.compiler.phases.tiers           to jdk.aot;
    exports org.graalvm.compiler.runtime                to jdk.aot;
    exports org.graalvm.compiler.replacements           to jdk.aot;
    exports org.graalvm.compiler.word                   to jdk.aot;
}
