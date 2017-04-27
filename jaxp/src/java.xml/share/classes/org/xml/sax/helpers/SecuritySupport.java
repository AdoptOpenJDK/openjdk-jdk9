/*
 * Copyright (c) 2004, 2016, Oracle and/or its affiliates. All rights reserved.
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

package org.xml.sax.helpers;

import java.io.*;
import java.security.*;

/**
 * This class is duplicated for each JAXP subpackage so keep it in sync.
 * It is package private and therefore is not exposed as part of the JAXP
 * API.
 *
 * Security related methods that only work on J2SE 1.2 and newer.
 */
class SecuritySupport  {

    /**
     * Returns the current thread's context class loader, or the system class loader
     * if the context class loader is null.
     * @return the current thread's context class loader, or the system class loader
     * @throws SecurityException
     */
    ClassLoader getClassLoader() throws SecurityException{
        return AccessController.doPrivileged((PrivilegedAction<ClassLoader>)() -> {
            ClassLoader cl = Thread.currentThread().getContextClassLoader();
            if (cl == null) {
                cl = ClassLoader.getSystemClassLoader();
            }

            return cl;
        });
    }

    String getSystemProperty(final String propName) {
        return AccessController.doPrivileged((PrivilegedAction<String>)()
                -> System.getProperty(propName));
    }

    FileInputStream getFileInputStream(final File file)
        throws FileNotFoundException
    {
        try {
            return AccessController.doPrivileged((PrivilegedExceptionAction<FileInputStream>)() ->
                    new FileInputStream(file));
        } catch (PrivilegedActionException e) {
            throw (FileNotFoundException)e.getException();
        }
    }


    InputStream getResourceAsStream(final ClassLoader cl, final String name)
    {
        return AccessController.doPrivileged((PrivilegedAction<InputStream>) () -> {
            InputStream ris;
            if (cl == null) {
                ris = SecuritySupport.class.getResourceAsStream(name);
            } else {
                ris = cl.getResourceAsStream(name);
            }
            return ris;
        });
    }

    boolean doesFileExist(final File f) {
        return (AccessController.doPrivileged((PrivilegedAction<Boolean>)() ->
                new Boolean(f.exists())));
    }

}
