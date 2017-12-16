/*
 * Copyright (c) 2011, 2016, Oracle and/or its affiliates. All rights reserved.
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
package org.graalvm.compiler.core.common;

import java.util.IdentityHashMap;

// JaCoCo Exclude

/**
 * Marker interface for location identities. A different location identity of two memory accesses
 * guarantees that the two accesses do not interfere.
 *
 * Clients of {@link LocationIdentity} must use {@link #equals(Object)}, not {@code ==}, when
 * comparing two {@link LocationIdentity} values for equality. Likewise, they must not use
 * {@link IdentityHashMap}s with {@link LocationIdentity} values as keys.
 */
public abstract class LocationIdentity {

    private static final class AnyLocationIdentity extends LocationIdentity {
        @Override
        public boolean isImmutable() {
            return false;
        }

        @Override
        public String toString() {
            return "ANY_LOCATION";
        }
    }

    public static final LocationIdentity ANY_LOCATION = new AnyLocationIdentity();

    public static LocationIdentity any() {
        return ANY_LOCATION;
    }

    /**
     * Denotes a location is unchanging in all cases. Not that this is different than the Java
     * notion of final which only requires definite assignment.
     */
    public abstract boolean isImmutable();

    public final boolean isMutable() {
        return !isImmutable();
    }

    public final boolean isAny() {
        return this == ANY_LOCATION;
    }

    public final boolean isSingle() {
        return this != ANY_LOCATION;
    }

    public final boolean overlaps(LocationIdentity other) {
        return isAny() || other.isAny() || this.equals(other);
    }
}
