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

package org.megamek.launcher.update;

import org.junit.jupiter.api.Test;
import org.megamek.launcher.manifest.FileEntry;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnershipPolicyTest {
    @Test
    void policyTwoOwnsRootPackageFilesAndBinButNeverUserDataOrUnknownFolders() {
        for (String owned : List.of("MegaMek.jar", "README.md", "LICENSE.code",
                "MekHQ.l4j.ini", "sentry.properties", "bin/MegaMek.sh", "lib/a.jar",
                "data/mekfiles/a.mtf", "docs/history.txt", "licenses/a.txt")) {
            assertTrue(OwnershipPolicy.managed(owned, 2), owned);
        }
        for (String excluded : List.of("mmconf/clientsettings.xml", "logs/megamek.log",
                "campaigns/The Learning Ropes/a.cpnx", "userdata/a.txt",
                "data/custom/a.mtf", "extras/settings.properties")) {
            assertFalse(OwnershipPolicy.managed(excluded, 2), excluded);
        }
    }

    @Test
    void policyOneKeepsItsOriginalNarrowRule() {
        assertTrue(OwnershipPolicy.managed("MegaMek.jar", 1));
        assertTrue(OwnershipPolicy.managed("data/a.txt", 1));
        assertFalse(OwnershipPolicy.managed("README.md", 1));
        assertFalse(OwnershipPolicy.managed("bin/MegaMek.sh", 1));
    }

    @Test
    void protectedDefaultHashesAccumulateAcrossReleases() {
        String first = "1".repeat(64);
        String second = "2".repeat(64);
        List<ProtectedPackageFile> merged = ProtectedPackageFile.merge(
                List.of(new ProtectedPackageFile("mmconf/a.xml", List.of(first))),
                List.of(new FileEntry("mmconf/a.xml", second),
                        new FileEntry("campaigns/b.cpnx", first)));
        assertEquals(List.of(new ProtectedPackageFile("campaigns/b.cpnx", List.of(first)),
                new ProtectedPackageFile("mmconf/a.xml", List.of(first, second))), merged);
    }
}
