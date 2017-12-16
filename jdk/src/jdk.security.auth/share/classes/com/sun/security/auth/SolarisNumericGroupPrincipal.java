/*
 * Copyright (c) 1999, 2013, Oracle and/or its affiliates. All rights reserved.
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

package com.sun.security.auth;

import java.security.Principal;
import static sun.security.util.ResourcesMgr.getAuthResourceString;

/**
 * This class implements the {@code Principal} interface
 * and represents a user's Solaris group identification number (GID).
 *
 * <p> Principals such as this {@code SolarisNumericGroupPrincipal}
 * may be associated with a particular {@code Subject}
 * to augment that {@code Subject} with an additional
 * identity.  Refer to the {@code Subject} class for more information
 * on how to achieve this.  Authorization decisions can then be based upon
 * the Principals associated with a {@code Subject}.

 * @deprecated As of JDK&nbsp;1.4, replaced by
 *             {@link UnixNumericGroupPrincipal}.
 *             This class is entirely deprecated.
 * This class is subject to removal in a future version of Java SE.
 *
 * @see java.security.Principal
 * @see javax.security.auth.Subject
 */
@Deprecated(since="1.4", forRemoval=true)
public class SolarisNumericGroupPrincipal implements
                                        Principal,
                                        java.io.Serializable {

    private static final long serialVersionUID = 2345199581042573224L;

    /**
     * @serial
     */
    private String name;

    /**
     * @serial
     */
    private boolean primaryGroup;

    /**
     * Create a {@code SolarisNumericGroupPrincipal} using a
     * {@code String} representation of the user's
     * group identification number (GID).
     *
     * @param name the user's group identification number (GID)
     *                  for this user.
     *
     * @param primaryGroup true if the specified GID represents the
     *                  primary group to which this user belongs.
     *
     * @exception NullPointerException if the {@code name}
     *                  is {@code null}.
     */
    public SolarisNumericGroupPrincipal(String name, boolean primaryGroup) {
        if (name == null)
            throw new NullPointerException(getAuthResourceString("provided.null.name"));

        this.name = name;
        this.primaryGroup = primaryGroup;
    }

    /**
     * Create a {@code SolarisNumericGroupPrincipal} using a
     * long representation of the user's group identification number (GID).
     *
     * @param name the user's group identification number (GID) for this user
     *                  represented as a long.
     *
     * @param primaryGroup true if the specified GID represents the
     *                  primary group to which this user belongs.
     *
     */
    public SolarisNumericGroupPrincipal(long name, boolean primaryGroup) {
        this.name = Long.toString(name);
        this.primaryGroup = primaryGroup;
    }

    /**
     * Return the user's group identification number (GID) for this
     * {@code SolarisNumericGroupPrincipal}.
     *
     * @return the user's group identification number (GID) for this
     *          {@code SolarisNumericGroupPrincipal}
     */
    public String getName() {
        return name;
    }

    /**
     * Return the user's group identification number (GID) for this
     * {@code SolarisNumericGroupPrincipal} as a long.
     *
     * @return the user's group identification number (GID) for this
     *          {@code SolarisNumericGroupPrincipal} as a long.
     */
    public long longValue() {
        return Long.parseLong(name);
    }

    /**
     * Return whether this group identification number (GID) represents
     * the primary group to which this user belongs.
     *
     * @return true if this group identification number (GID) represents
     *          the primary group to which this user belongs,
     *          or false otherwise.
     */
    public boolean isPrimaryGroup() {
        return primaryGroup;
    }

    /**
     * Return a string representation of this
     * {@code SolarisNumericGroupPrincipal}.
     *
     * @return a string representation of this
     *          {@code SolarisNumericGroupPrincipal}.
     */
    public String toString() {
        return primaryGroup ?
            getAuthResourceString
            ("SolarisNumericGroupPrincipal.Primary.Group.") + name :
            getAuthResourceString
            ("SolarisNumericGroupPrincipal.Supplementary.Group.") + name;
    }

    /**
     * Compares the specified Object with this
     * {@code SolarisNumericGroupPrincipal}
     * for equality.  Returns true if the given object is also a
     * {@code SolarisNumericGroupPrincipal} and the two
     * SolarisNumericGroupPrincipals
     * have the same group identification number (GID).
     *
     * @param o Object to be compared for equality with this
     *          {@code SolarisNumericGroupPrincipal}.
     *
     * @return true if the specified Object is equal to this
     *          {@code SolarisNumericGroupPrincipal}.
     */
    public boolean equals(Object o) {
        if (o == null)
            return false;

        if (this == o)
            return true;

        if (!(o instanceof SolarisNumericGroupPrincipal))
            return false;
        SolarisNumericGroupPrincipal that = (SolarisNumericGroupPrincipal)o;

        if (this.getName().equals(that.getName()) &&
            this.isPrimaryGroup() == that.isPrimaryGroup())
            return true;
        return false;
    }

    /**
     * Return a hash code for this {@code SolarisNumericGroupPrincipal}.
     *
     * @return a hash code for this {@code SolarisNumericGroupPrincipal}.
     */
    public int hashCode() {
        return toString().hashCode();
    }
}
