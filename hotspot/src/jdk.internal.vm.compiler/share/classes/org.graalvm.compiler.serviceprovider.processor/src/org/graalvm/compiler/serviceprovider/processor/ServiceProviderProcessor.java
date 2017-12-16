/*
 * Copyright (c) 2013, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.compiler.serviceprovider.processor;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.HashSet;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.FilerException;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic.Kind;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

import org.graalvm.compiler.serviceprovider.ServiceProvider;

/**
 * Processes classes annotated with {@link ServiceProvider}. For a service defined by {@code S} and
 * a class {@code P} implementing the service, this processor generates the file
 * {@code META-INF/providers/P} whose contents are a single line containing the fully qualified name
 * of {@code S}.
 */
@SupportedAnnotationTypes("org.graalvm.compiler.serviceprovider.ServiceProvider")
public class ServiceProviderProcessor extends AbstractProcessor {

    private final Set<TypeElement> processed = new HashSet<>();

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latest();
    }

    private boolean verifyAnnotation(TypeMirror serviceInterface, TypeElement serviceProvider) {
        if (!processingEnv.getTypeUtils().isSubtype(serviceProvider.asType(), serviceInterface)) {
            String msg = String.format("Service provider class %s must implement service interface %s", serviceProvider.getSimpleName(), serviceInterface);
            processingEnv.getMessager().printMessage(Kind.ERROR, msg, serviceProvider);
            return false;
        }

        return true;
    }

    private void processElement(TypeElement serviceProvider) {
        if (processed.contains(serviceProvider)) {
            return;
        }

        processed.add(serviceProvider);
        ServiceProvider annotation = serviceProvider.getAnnotation(ServiceProvider.class);
        if (annotation != null) {
            try {
                annotation.value();
            } catch (MirroredTypeException ex) {
                TypeMirror serviceInterface = ex.getTypeMirror();
                if (verifyAnnotation(serviceInterface, serviceProvider)) {
                    String interfaceName = ex.getTypeMirror().toString();
                    createProviderFile(serviceProvider, interfaceName);
                }
            }
        }
    }

    private void createProviderFile(TypeElement serviceProvider, String interfaceName) {
        if (serviceProvider.getNestingKind().isNested()) {
            // This is a simplifying constraint that means we don't have to
            // processed the qualified name to insert '$' characters at
            // the relevant positions.
            String msg = String.format("Service provider class %s must be a top level class", serviceProvider.getSimpleName());
            processingEnv.getMessager().printMessage(Kind.ERROR, msg, serviceProvider);
            return;
        }

        String filename = "META-INF/providers/" + serviceProvider.getQualifiedName();
        try {
            FileObject file = processingEnv.getFiler().createResource(StandardLocation.CLASS_OUTPUT, "", filename, serviceProvider);
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(file.openOutputStream(), "UTF-8"));
            writer.println(interfaceName);
            writer.close();
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(isBug367599(e) ? Kind.NOTE : Kind.ERROR, e.getMessage(), serviceProvider);
        }
    }

    /**
     * Determines if a given exception is (most likely) caused by
     * <a href="https://bugs.eclipse.org/bugs/show_bug.cgi?id=367599">Bug 367599</a>.
     */
    public static boolean isBug367599(Throwable t) {
        if (t instanceof FilerException) {
            for (StackTraceElement ste : t.getStackTrace()) {
                if (ste.toString().contains("org.eclipse.jdt.internal.apt.pluggable.core.filer.IdeFilerImpl.create")) {
                    // See: https://bugs.eclipse.org/bugs/show_bug.cgi?id=367599
                    return true;
                }
            }
        }
        if (t.getCause() != null) {
            return isBug367599(t.getCause());
        }
        return false;
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (roundEnv.processingOver()) {
            return true;
        }

        for (Element element : roundEnv.getElementsAnnotatedWith(ServiceProvider.class)) {
            assert element.getKind().isClass();
            processElement((TypeElement) element);
        }

        return true;
    }
}
