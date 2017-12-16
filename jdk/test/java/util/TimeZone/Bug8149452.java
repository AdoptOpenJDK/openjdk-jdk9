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
/*
 * @test
 * @bug 8149452 8151876
 * @summary Check the missing time zone names.
 */
import java.text.DateFormatSymbols;
import java.util.ArrayList;
import java.util.TimeZone;
import java.util.Arrays;
import java.util.List;

public class Bug8149452 {

    public static void main(String[] args) {

        List<String> listNotFound = new ArrayList<>();
        String[][] zoneStrings = DateFormatSymbols.getInstance()
                .getZoneStrings();
        for (String tzID : TimeZone.getAvailableIDs()) {
            if (!Arrays.stream(zoneStrings)
                    .anyMatch(zone -> tzID.equalsIgnoreCase(zone[0]))) {
                // to ignore names for Etc/GMT[+-][0-9]+ which are not supported
                // Also ignore the TimeZone DisplayNames with GMT[+-]:hh:mm
                if (!tzID.startsWith("Etc/GMT")
                        && !tzID.startsWith("GMT")
                        && !TimeZone.getTimeZone(tzID).getDisplayName().startsWith("GMT")) {
                    listNotFound.add(tzID);
                }
            }
        }

        if (!listNotFound.isEmpty()) {
            throw new RuntimeException("Test Failed: Time Zone Strings for "
                    + listNotFound + " not found");
        }

    }

}
