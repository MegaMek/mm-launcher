/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek Launcher.
 *
 * MegaMek Launcher is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek Launcher is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's "Game Content Usage Rules"
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */

package org.megamek.launcher.release;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A conservative identity for dotted MegaMek release versions.
 *
 * <p>Numeric components are compared numerically so build displays such as {@code 0.50.7} and
 * tags such as {@code v0.50.07} agree. Component count and any prerelease/build suffix remain
 * part of the identity; this deliberately does not turn suffixed builds into final releases.</p>
 */
public record VersionIdentity(List<BigInteger> components, String suffix) {
    private static final String VERSION =
            "v?([0-9]{1,18}(?:\\.[0-9]{1,18}){2,3})"
                    + "([-+][A-Za-z0-9][A-Za-z0-9.-]*)?";
    private static final Pattern EXACT = Pattern.compile("^" + VERSION + "$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DISPLAY = Pattern.compile(
            "(?<![A-Za-z0-9.+-])" + VERSION + "(?![A-Za-z0-9.+-])",
            Pattern.CASE_INSENSITIVE);

    public VersionIdentity {
        components = List.copyOf(components);
        if (components.size() < 3 || components.size() > 4) {
            throw new IllegalArgumentException("version must have three or four components");
        }
        suffix = suffix == null ? "" : suffix;
    }

    public static Optional<VersionIdentity> fromExact(String value) {
        if (value == null) return Optional.empty();
        Matcher matcher = EXACT.matcher(value.trim());
        return matcher.matches() ? Optional.of(fromMatcher(matcher)) : Optional.empty();
    }

    /**
     * Extracts one unambiguous version identity from human-readable release text.
     */
    public static Optional<VersionIdentity> fromDisplay(String value) {
        if (value == null) return Optional.empty();
        Matcher matcher = DISPLAY.matcher(value);
        VersionIdentity found = null;
        VersionIdentity ending = null;
        while (matcher.find()) {
            VersionIdentity candidate = fromMatcher(matcher);
            if (found != null && !found.equals(candidate)) return Optional.empty();
            found = candidate;
            if (value.substring(matcher.end()).matches("[\\s)\\]]*")) {
                ending = candidate;
            }
        }
        return Optional.ofNullable(ending);
    }

    private static VersionIdentity fromMatcher(Matcher matcher) {
        List<BigInteger> numbers = new ArrayList<>();
        for (String component : matcher.group(1).split("\\.")) {
            numbers.add(new BigInteger(component));
        }
        String suffix = matcher.group(2);
        return new VersionIdentity(numbers, suffix == null ? "" : suffix);
    }
}
