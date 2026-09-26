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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VersionIdentityTest {
    @Test
    void normalizesOnlyNumericComponentsAndOptionalTagPrefix() {
        VersionIdentity observed = VersionIdentity.fromExact("0.50.7").orElseThrow();
        assertEquals(observed, VersionIdentity.fromExact("0.50.07").orElseThrow());
        assertEquals(observed, VersionIdentity.fromExact("v0.50.07").orElseThrow());
        assertEquals(observed,
                VersionIdentity.fromDisplay("MegaMek version 0.50.007").orElseThrow());
    }

    @Test
    void preservesSuffixAndComponentIdentity() {
        assertNotEquals(VersionIdentity.fromExact("0.50.7").orElseThrow(),
                VersionIdentity.fromExact("v0.50.7-beta").orElseThrow());
        assertNotEquals(VersionIdentity.fromExact("0.50.7+build1").orElseThrow(),
                VersionIdentity.fromExact("0.50.7+build2").orElseThrow());
        assertNotEquals(VersionIdentity.fromExact("0.50.7").orElseThrow(),
                VersionIdentity.fromExact("0.50.7.0").orElseThrow());
        assertTrue(VersionIdentity.fromDisplay("0.50.7 and 0.50.8").isEmpty());
    }
}
