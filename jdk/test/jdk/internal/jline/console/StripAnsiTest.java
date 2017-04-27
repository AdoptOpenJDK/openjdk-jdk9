/*
 * Copyright (c) 2015, Oracle and/or its affiliates. All rights reserved.
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

/**
 * @test
 * @bug 8080679 8131913
 * @modules jdk.internal.le/jdk.internal.jline
 *          jdk.internal.le/jdk.internal.jline.console:+open
 * @summary Verify ConsoleReader.stripAnsi strips escape sequences from its input correctly.
 */

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import jdk.internal.jline.UnsupportedTerminal;
import jdk.internal.jline.console.ConsoleReader;

public class StripAnsiTest {
    public static void main(String... args) throws Exception {
        new StripAnsiTest().run();
    }

    void run() throws Exception {
        ByteArrayInputStream in = new ByteArrayInputStream(new byte[0]);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ConsoleReader reader = new ConsoleReader(in, out, new UnsupportedTerminal());

        String withAnsi = "0\033[s1\033[2J2\033[37;4m3";
        String expected = "0123";

        Method stripAnsi = ConsoleReader.class.getDeclaredMethod("stripAnsi", String.class);
        stripAnsi.setAccessible(true);
        String actual = (String) stripAnsi.invoke(reader, withAnsi);

        if (!expected.equals(actual)) {
            throw new IllegalStateException("Did not correctly strip escape sequences: " + actual);
        }
    }
}
