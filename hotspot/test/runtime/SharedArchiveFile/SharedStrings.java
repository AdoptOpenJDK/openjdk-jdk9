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

/*
 * @test
 * @summary Check to make sure that shared strings in the bootstrap CDS archive
 *          are actually shared
 * Feature support: G1GC only, compressed oops/kptrs, 64-bit os, not on windows
 * @requires (sun.arch.data.model != "32") & (os.family != "windows")
 * @requires (vm.opt.UseCompressedOops == null) | (vm.opt.UseCompressedOops == true)
 * @requires vm.gc.G1
 * @library /test/lib
 * @modules java.base/jdk.internal.misc
 *          java.management
 * @build SharedStringsWb sun.hotspot.WhiteBox
 * @run main ClassFileInstaller -jar whitebox.jar sun.hotspot.WhiteBox
 * @run main SharedStrings
 */
import jdk.test.lib.process.ProcessTools;
import jdk.test.lib.process.OutputAnalyzer;

public class SharedStrings {
    public static void main(String[] args) throws Exception {
        boolean test_runtime = true;
        ProcessBuilder pb = ProcessTools.createJavaProcessBuilder(
            "-XX:+UnlockDiagnosticVMOptions",
            "-XX:SharedArchiveFile=./SharedStrings.jsa",
            "-XX:+UseG1GC",
            "-XX:+UseCompressedOops",
            "-XX:+PrintSharedSpaces",
            // Needed for bootclasspath match, for CDS to work with WhiteBox API
            "-Xbootclasspath/a:" + ClassFileInstaller.getJarPath("whitebox.jar"),
            "-Xshare:dump");

        OutputAnalyzer dumpOutput = new OutputAnalyzer(pb.start());
        try {
            dumpOutput.shouldContain("Loading classes to share");
            dumpOutput.shouldContain("Shared string table stats");
            dumpOutput.shouldHaveExitValue(0);
        } catch (RuntimeException e) {
            if (dumpOutput.getOutput().indexOf("Shared strings are excluded") != -1 ||
                dumpOutput.getOutput().indexOf("Cannot dump shared archive") != -1) {
                test_runtime = false;
            } else {
                throw new RuntimeException("Unexpected failure");
            }
        }

        if (test_runtime) {
            pb = ProcessTools.createJavaProcessBuilder(
                "-XX:+UnlockDiagnosticVMOptions",
                "-XX:SharedArchiveFile=./SharedStrings.jsa",
                // these are required modes for shared strings
                "-XX:+UseCompressedOops", "-XX:+UseG1GC",
                // needed for access to white box test API
                "-Xbootclasspath/a:" + ClassFileInstaller.getJarPath("whitebox.jar"),
                "-XX:+UnlockDiagnosticVMOptions", "-XX:+WhiteBoxAPI",
                "-Xshare:on", "-showversion", "SharedStringsWb");

            OutputAnalyzer output = new OutputAnalyzer(pb.start());

            try {
                output.shouldContain("sharing");
                output.shouldHaveExitValue(0);
            } catch (RuntimeException e) {
                output.shouldContain("Unable to use shared archive");
                output.shouldHaveExitValue(1);
            }
        }
    }
}
