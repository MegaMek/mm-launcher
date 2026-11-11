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

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LauncherVersionStoreTest {
    @TempDir
    Path registry;

    private final LauncherVersionStore store = new LauncherVersionStore();

    @Test
    void aFirstStartKnowsNoPreviousVersion() {
        assertNull(store.remember(registry, "0.14.3"));
        assertNull(store.remember(registry, "0.14.3"), "starting the same version again changes nothing");
    }

    @Test
    void anUpdateRemembersTheVersionThatRanBeforeOnEveryStart() {
        store.remember(registry, "0.14.1");
        assertEquals("0.14.1", store.remember(registry, "0.14.3"));
        assertEquals("0.14.1", store.remember(registry, "0.14.3"));
        assertEquals("0.14.3", store.remember(registry, "0.14.4"));
    }

    @Test
    void aDowngradeForgetsThePreviousVersion() {
        store.remember(registry, "0.14.1");
        store.remember(registry, "0.14.3");
        assertNull(store.remember(registry, "0.14.2"));
        assertEquals("0.14.2", store.remember(registry, "0.14.3"));
    }

    @Test
    void ignoresDevelopmentBuildsAndUnreadableFiles() throws Exception {
        assertNull(store.remember(registry, ""));
        assertNull(store.remember(registry, "dev"));
        Path file = store.path(registry);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{not json");
        assertNull(store.remember(registry, "0.14.3"));
        assertEquals("0.14.3", store.remember(registry, "0.14.4"));
    }
}