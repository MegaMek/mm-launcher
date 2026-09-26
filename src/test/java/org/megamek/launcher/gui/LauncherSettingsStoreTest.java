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

package org.megamek.launcher.gui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LauncherSettingsStoreTest {
    @TempDir Path temp;

    @Test
    void absentSettingsContainOnlyTheUnsetDefaultJavaAndDoNotWrite() throws Exception {
        Path registry = temp.resolve("registry.json");
        LauncherSettingsStore store = new LauncherSettingsStore(registry);

        LauncherSettingsStore.Settings initial = store.read();

        assertEquals(LauncherSettingsStore.SCHEMA, initial.schemaVersion());
        assertNull(initial.defaultJavaExecutable());
        assertNull(initial.defaultJavaFeature());
        assertFalse(Files.exists(store.path()), "reading the default is side-effect free");
        assertFalse(Files.exists(registry));
    }

    @Test
    void defaultJavaRoundTripsInExactCurrentSchemaWithoutUpdateFields() throws Exception {
        Path registry = temp.resolve("registry.json");
        LauncherSettingsStore store = new LauncherSettingsStore(registry);
        Path java = temp.resolve("jdk").resolve("bin").resolve("java.exe")
                .toAbsolutePath().normalize();

        LauncherSettingsStore.Settings saved = store.writeDefaultJava(java, 21);
        String json = Files.readString(store.path());

        assertEquals(java.toString(), saved.defaultJavaExecutable());
        assertEquals(21, saved.defaultJavaFeature());
        assertEquals(saved, store.read());
        assertTrue(json.contains("\"schemaVersion\" : 3"));
        assertFalse(json.contains("checkInstalledVersionsOnOpen"));
        assertFalse(json.contains("checkNewInstallsOnOpen"));
    }

    @Test
    void oldGlobalUpdateSchemaIsRejectedWithoutMigrationOrRewrite() throws Exception {
        Path registry = temp.resolve("registry.json");
        LauncherSettingsStore store = new LauncherSettingsStore(registry);
        Files.writeString(store.path(), """
                {
                  "schemaVersion" : 2,
                  "checkInstalledVersionsOnOpen" : true,
                  "checkNewInstallsOnOpen" : false
                }
                """);
        String before = Files.readString(store.path());

        IOException error = assertThrows(IOException.class, store::read);

        assertTrue(error.getMessage().contains("unsupported launcher settings schema"));
        assertEquals(before, Files.readString(store.path()));
    }

    @Test
    void removedGlobalFieldIsRejectedEvenWhenClaimingCurrentSchema() throws Exception {
        LauncherSettingsStore store =
                new LauncherSettingsStore(temp.resolve("registry.json"));
        String malformed = """
                {
                  "schemaVersion" : 3,
                  "defaultJavaExecutable" : null,
                  "defaultJavaFeature" : null,
                  "checkInstalledVersionsOnOpen" : false
                }
                """;
        Files.writeString(store.path(), malformed);

        assertThrows(IOException.class, store::read);
        assertEquals(malformed, Files.readString(store.path()));
    }

    @Test
    void invalidDefaultJavaDoesNotReplacePreviousSettings() throws Exception {
        Path registry = temp.resolve("registry.json");
        LauncherSettingsStore store = new LauncherSettingsStore(registry);
        Path java = temp.resolve("jdk").resolve("bin").resolve("java.exe")
                .toAbsolutePath().normalize();
        store.writeDefaultJava(java, 21);
        byte[] before = Files.readAllBytes(store.path());

        assertThrows(IOException.class,
                () -> store.writeDefaultJava(Path.of("relative-java"), 21));
        assertArrayEquals(before, Files.readAllBytes(store.path()));
    }

    @Test
    void corruptExistingSettingIsNotReset() throws Exception {
        Path registry = temp.resolve("registry.json");
        LauncherSettingsStore store = new LauncherSettingsStore(registry);
        Path settings = Files.writeString(store.path(), "{broken");

        IOException read = assertThrows(IOException.class, store::read);
        assertTrue(read.getMessage().contains("not reset"));
        assertThrows(IOException.class, () -> store.writeDefaultJava(
                temp.resolve("java.exe").toAbsolutePath().normalize(), 21));
        assertEquals("{broken", Files.readString(settings));
    }

    @Test
    void wrongTypedDefaultJavaValueIsRejectedWithoutCoercionOrReset() throws Exception {
        LauncherSettingsStore store =
                new LauncherSettingsStore(temp.resolve("registry.json"));
        String malformed = """
                {
                  "schemaVersion" : 3,
                  "defaultJavaExecutable" : false,
                  "defaultJavaFeature" : 21
                }
                """;
        Files.writeString(store.path(), malformed);

        assertThrows(IOException.class, store::read);
        assertEquals(malformed, Files.readString(store.path()));
    }
}
