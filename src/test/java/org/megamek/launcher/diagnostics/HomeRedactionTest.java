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

package org.megamek.launcher.diagnostics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class HomeRedactionTest {
    @TempDir
    Path temp;

    @Test
    void resolvedHomeIsRedactedBeforeTheLexicalHomeItContains() throws Exception {
        Path root = temp.toRealPath();
        Path realHome = Files.createDirectories(root.resolve("var").resolve("home").resolve("goose"));
        try {
            Files.createSymbolicLink(root.resolve("home"), root.resolve("var").resolve("home"));
        } catch (UnsupportedOperationException | IOException unavailable) {
            assumeTrue(false, "symbolic links are unavailable: " + unavailable.getMessage());
        }
        String lexicalHome = root.resolve("home").resolve("goose").toString();

        assertEquals("open ~" + java.io.File.separator + "logs failed",
                SanitizedErrors.redactHome("open " + realHome.resolve("logs") + " failed", lexicalHome));
        assertEquals("open ~" + java.io.File.separator + "logs failed",
                SanitizedErrors.redactHome("open " + Path.of(lexicalHome, "logs") + " failed", lexicalHome));
    }

    @Test
    void siblingFoldersSharingTheHomePrefixAreNotRedacted() throws Exception {
        Path home = Files.createDirectories(temp.toRealPath().resolve("al"));
        Path sibling = temp.toRealPath().resolve("alice").resolve("MegaMek");

        assertEquals("see " + sibling, SanitizedErrors.redactHome("see " + sibling, home.toString()));
        assertEquals("see ~", SanitizedErrors.redactHome("see " + home, home.toString()));
    }
}
