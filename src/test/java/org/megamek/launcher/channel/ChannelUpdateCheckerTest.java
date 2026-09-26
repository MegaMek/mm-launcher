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

package org.megamek.launcher.channel;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChannelUpdateCheckerTest {
    @Test
    void ordersThreeAndFourComponentVersionsNumericallyWithoutLexicalMistakes() {
        assertEquals(ChannelUpdateChecker.Status.UPDATE_AVAILABLE,
                ChannelUpdateChecker.compareTags("v0.50.10", "v0.51.0"));
        assertEquals(ChannelUpdateChecker.Status.INSTALLED_AHEAD,
                ChannelUpdateChecker.compareTags("v0.51.00.1", "v0.51.0"));
        assertEquals(ChannelUpdateChecker.Status.UPDATE_AVAILABLE,
                ChannelUpdateChecker.compareTags("v0.9.99", "v0.10.0"));
        assertEquals(ChannelUpdateChecker.Status.EXACT_CURRENT,
                ChannelUpdateChecker.compareTags("v0.051.0", "v0.051.0"));
    }

    @Test
    void equalNumericDifferentIdentityAndSuffixesAreNotAssumedCurrentOrDowngraded() {
        assertEquals(ChannelUpdateChecker.Status.NON_COMPARABLE,
                ChannelUpdateChecker.compareTags("v0.51.0", "v0.51.0.0"));
        assertEquals(ChannelUpdateChecker.Status.NON_COMPARABLE,
                ChannelUpdateChecker.compareTags("v0.051.0", "v0.51.0"));
        assertEquals(ChannelUpdateChecker.Status.NON_COMPARABLE,
                ChannelUpdateChecker.compareTags("v0.51.0-beta", "v0.51.0"));
        assertEquals(ChannelUpdateChecker.Status.NON_COMPARABLE,
                ChannelUpdateChecker.compareTags("release-0.51.0", "v0.51.0"));
    }
}
