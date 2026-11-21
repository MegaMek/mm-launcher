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
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class StrictPathSafetyTest {
    @TempDir
    Path temp;

    /** Fedora Atomic layout: home -> var/home, as on Bazzite and Silverblue. */
    private Path linkedHome() throws IOException {
        Path root = temp.toRealPath();
        Path realHome = Files.createDirectories(root.resolve("var").resolve("home").resolve("user"));
        Files.createDirectories(realHome.resolve(".local").resolve("state"));
        try {
            Files.createSymbolicLink(root.resolve("home"), root.resolve("var").resolve("home"));
        } catch (UnsupportedOperationException | IOException unavailable) {
            assumeTrue(false, "symbolic links are unavailable: " + unavailable.getMessage());
        }
        return root.resolve("home").resolve("user");
    }

    @Test
    void platformBaseBehindASystemLinkResolvesToTheRealDirectory() throws Exception {
        Path home = linkedHome();
        Path real = temp.toRealPath().resolve("var").resolve("home").resolve("user");

        assertEquals(real, StrictPathSafety.canonicalPlatformBase(home));
        assertThrows(IOException.class, () -> StrictPathSafety.requireDirectory(
                home.resolve(".local").resolve("state"), "state"));
        Path state = StrictPathSafety.canonicalPlatformBase(home.resolve(".local").resolve("state"));
        assertEquals(real.resolve(".local").resolve("state"),
                StrictPathSafety.requireDirectory(state, "state"));
    }

    @Test
    void missingTrailingComponentsAreKeptBelowTheResolvedAncestor() throws Exception {
        Path home = linkedHome();
        Path real = temp.toRealPath().resolve("var").resolve("home").resolve("user");

        assertEquals(real.resolve(".local").resolve("share").resolve("MegaMek"),
                StrictPathSafety.canonicalPlatformBase(
                        home.resolve(".local").resolve("share").resolve("MegaMek")));
    }

    @Test
    void danglingLinkBelowTheBaseIsLeftForTheStrictChecks() throws Exception {
        Path home = linkedHome();
        Path real = temp.toRealPath().resolve("var").resolve("home").resolve("user");
        Files.createSymbolicLink(real.resolve("state"), real.resolve("missing"));

        Path base = StrictPathSafety.canonicalPlatformBase(home.resolve("state"));
        assertEquals(real.resolve("state"), base);
        assertThrows(IOException.class, () -> StrictPathSafety.requireDirectory(base, "state"));
    }

    @Test
    void choosersRebaseOnlyPathsUnderTheLexicalHome() throws Exception {
        Path home = linkedHome();
        Path real = temp.toRealPath().resolve("var").resolve("home").resolve("user");
        Path picked = home.resolve(".local").resolve("share").resolve("MegaMek");

        assertEquals(real.resolve(".local").resolve("share").resolve("MegaMek"),
                StrictPathSafety.underCanonicalHome(picked, home.toString()));
        Path outside = temp.toRealPath().resolve("elsewhere");
        assertEquals(outside, StrictPathSafety.underCanonicalHome(outside, home.toString()));
    }

    @Test
    void linksBelowTheHomeAreNotTrustedWhenRebasing() throws Exception {
        Path home = linkedHome();
        Path real = temp.toRealPath().resolve("var").resolve("home").resolve("user");
        Files.createSymbolicLink(real.resolve("games"), Files.createDirectories(
                temp.toRealPath().resolve("games")));

        assertEquals(real.resolve("games"),
                StrictPathSafety.underCanonicalHome(home.resolve("games"), home.toString()));
        assertThrows(IOException.class, () -> StrictPathSafety.requireDirectory(
                StrictPathSafety.underCanonicalHome(home.resolve("games"), home.toString()), "games"));
    }

    @Test
    void pathWithoutLinksIsUnchanged() throws Exception {
        Path plain = Files.createDirectories(temp.toRealPath().resolve("plain"));

        assertEquals(plain, StrictPathSafety.canonicalPlatformBase(plain));
        assertEquals(plain.resolve("absent"), StrictPathSafety.canonicalPlatformBase(plain.resolve("absent")));
    }
}
