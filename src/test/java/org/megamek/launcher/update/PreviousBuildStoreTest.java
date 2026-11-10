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
import org.megamek.launcher.registry.InstallationRecord;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PreviousBuildStoreTest {
    @TempDir
    Path temp;

    private InstallationRecord record(String id, String build) {
        return new InstallationRecord(id, "Copy", temp.resolve("copy").toString(), build, List.of(),
                null, true, "2026-01-01T00:00:00Z");
    }

    @Test
    void remembersTheVersionBeforeTheLastUpdateWhileTheInstallationStillRunsIt() throws IOException {
        Path registry = temp.resolve("registry.json");
        String id = UUID.randomUUID().toString();
        PreviousBuildStore store = new PreviousBuildStore();
        assertNull(store.read(registry, record(id, "0.51.01.5")), "nothing remembered yet");

        store.write(registry, id, "0.51.01.3", "v0.51.01.5");
        assertEquals("0.51.01.3", store.read(registry, record(id, "0.51.1.5")));
        assertNull(store.read(registry, record(id, "0.51.01.6")), "updated some other way since");

        store.write(registry, id, "0.51.01.5", "v0.51.01.6");
        assertEquals("0.51.01.5", store.read(registry, record(id, "0.51.01.6")), "the latest update counts");
        assertFalse(Files.exists(store.path(registry, id).resolveSibling(id + ".previous.json.new")));
    }

    @Test
    void ignoresDamagedEntriesAndRefusesNonVersions() throws IOException {
        Path registry = temp.resolve("registry.json");
        String id = UUID.randomUUID().toString();
        PreviousBuildStore store = new PreviousBuildStore();
        assertThrows(IOException.class, () -> store.write(registry, id, "nightly", "v0.51.01.5"));
        assertThrows(IOException.class, () -> store.write(registry, "not-a-uuid", "0.51.01.3", "v0.51.01.5"));

        Path file = store.path(registry, id);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"schemaVersion\":1,\"previous\":\"0.51.01.3\"}");
        assertNull(store.read(registry, record(id, "0.51.01.5")));
        Files.writeString(file, "not json");
        assertNull(store.read(registry, record(id, "0.51.01.5")));
    }
}