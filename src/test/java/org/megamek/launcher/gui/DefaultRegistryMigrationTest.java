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
import org.megamek.launcher.registry.RegistryStore;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.*;

class DefaultRegistryMigrationTest {
    @TempDir Path temp;

    @Test
    void defaultDesktopRegistryHasItsOwnLauncherNamespace() {
        Path registry = GuiLauncher.defaultRegistry();
        assertEquals("launcher-registry.json", registry.getFileName().toString());
        String folder = registry.getParent().getFileName().toString();
        assertTrue(folder.equals("MegaMek Launcher") || folder.equals(".megamek-launcher"));
        assertNotEquals(registry, GuiLauncher.legacyRegistry());
    }

    private Path legacy() throws IOException {
        Path dir = Files.createDirectory(temp.resolve("MegaMek"));
        return dir.resolve("launcher-registry.json");
    }

    private Path target() {
        return temp.resolve("MegaMek Launcher").resolve("launcher-registry.json");
    }

    @Test
    void copiesValidSchemaThreeAndSettingsButLeavesLegacyBackupUntouched() throws Exception {
        Path source = legacy();
        String content = """
                {"schemaVersion":3,"defaultInstallationId":null,
                 "preferredInstallationIds":{},"installations":[]}""";
        Files.writeString(source, content);
        Path settings = source.resolveSibling(source.getFileName() + ".settings.json");
        String prefs = """
                {"schemaVersion":3,"defaultJavaExecutable":null,"defaultJavaFeature":null}""";
        Files.writeString(settings, prefs);
        Path metadata = Files.createDirectory(source.resolveSibling("launcher-registry.json.metadata"));
        Files.writeString(metadata.resolve("kept.json"), "opaque receipt");
        Path logs = Files.createDirectory(source.resolveSibling("launcher-registry.json.launcher-logs"));
        Files.writeString(logs.resolve("old.log"), "kept log");
        DefaultRegistryMigration.migrate(target(), source);
        assertEquals(content, Files.readString(target()));
        assertEquals(prefs, Files.readString(target().resolveSibling(settings.getFileName())));
        assertEquals(content, Files.readString(source));
        assertEquals(prefs, Files.readString(settings));
        assertEquals("opaque receipt", Files.readString(target().resolveSibling(
                "launcher-registry.json.metadata").resolve("kept.json")));
        assertEquals("kept log", Files.readString(target().resolveSibling(
                "launcher-registry.json.launcher-logs").resolve("old.log")));
        assertEquals(3, new RegistryStore().read(target()).schemaVersion());
        DefaultRegistryMigration.migrate(target(), source); // idempotent
    }

    @Test
    void unsupportedLegacySchemaDoesNotBlockNewInstallOrModifyLegacy() throws Exception {
        Path source = legacy();
        Files.writeString(source, "{\"schemaVersion\":1}");
        DefaultRegistryMigration.migrate(target(), source);
        assertFalse(Files.exists(target().getParent()));
        assertEquals("{\"schemaVersion\":1}", Files.readString(source));
    }

    @Test
    void unknownFutureSchemaFailsClosedRatherThanStrandingState() throws Exception {
        Path source = legacy();
        Files.writeString(source, "{\"schemaVersion\":4}");
        assertThrows(IOException.class, () -> DefaultRegistryMigration.migrate(target(), source));
        assertFalse(Files.exists(target().getParent()));
        assertEquals("{\"schemaVersion\":4}", Files.readString(source));
    }

    @Test
    void collisionFailsClosedWithoutReplacingEitherRegistry() throws Exception {
        Path source = legacy();
        String valid = """
                {"schemaVersion":3,"defaultInstallationId":null,
                 "preferredInstallationIds":{},"installations":[]}""";
        Files.writeString(source, valid);
        Files.createDirectory(target().getParent());
        Files.writeString(target(), "not a registry");
        assertThrows(IOException.class, () -> DefaultRegistryMigration.migrate(target(), source));
        assertEquals(valid, Files.readString(source));
        assertEquals("not a registry", Files.readString(target()));
    }

    @Test
    void existingValidNewRegistryWinsEvenWhenLegacySidecarIsMissingAtTarget() throws Exception {
        Path source = legacy();
        String valid = """
                {"schemaVersion":3,"defaultInstallationId":null,
                 "preferredInstallationIds":{},"installations":[]}""";
        Files.writeString(source, valid);
        Files.createDirectory(source.resolveSibling("launcher-registry.json.metadata"));
        Files.createDirectory(target().getParent());
        Files.writeString(target(), valid);
        DefaultRegistryMigration.migrate(target(), source);
        assertTrue(Files.isDirectory(source.resolveSibling("launcher-registry.json.metadata")));
        assertEquals(valid, Files.readString(target()));
    }

    @Test
    void occupiedDestinationWithoutRegistryIsNotMergedOrErased() throws Exception {
        Path source = legacy();
        Files.writeString(source, """
                {"schemaVersion":3,"defaultInstallationId":null,
                 "preferredInstallationIds":{},"installations":[]}""");
        Path occupied = Files.createDirectory(target().getParent());
        Files.writeString(occupied.resolve("unrelated.txt"), "keep");
        assertThrows(IOException.class, () -> DefaultRegistryMigration.migrate(target(), source));
        assertEquals("keep", Files.readString(occupied.resolve("unrelated.txt")));
        assertFalse(Files.exists(target()));
    }

    @Test
    void lockedLegacySettingsCannotBeCopiedOrPublished() throws Exception {
        Path source = legacy();
        String valid = """
                {"schemaVersion":3,"defaultInstallationId":null,
                 "preferredInstallationIds":{},"installations":[]}""";
        Files.writeString(source, valid);
        Path settings = source.resolveSibling("launcher-registry.json.settings.json");
        String prefs = """
                {"schemaVersion":3,"defaultJavaExecutable":null,"defaultJavaFeature":null}""";
        Files.writeString(settings, prefs);
        Path lock = settings.resolveSibling(settings.getFileName() + ".lock");
        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE); FileLock ignored = channel.lock()) {
            assertThrows(IOException.class, () -> DefaultRegistryMigration.migrate(target(), source));
            assertFalse(Files.exists(target().getParent()));
        }
        assertEquals(prefs, Files.readString(settings));
        DefaultRegistryMigration.migrate(target(), source);
        assertEquals(prefs, Files.readString(target().resolveSibling(settings.getFileName())));
    }

    @Test
    void unfinishedWriteFailsWithoutPromotingOldState() throws Exception {
        Path source = legacy();
        Files.writeString(source, """
                {"schemaVersion":3,"defaultInstallationId":null,
                 "preferredInstallationIds":{},"installations":[]}""");
        Files.writeString(source.resolveSibling("launcher-registry.json.new"), "unfinished");
        assertThrows(IOException.class, () -> DefaultRegistryMigration.migrate(target(), source));
        assertFalse(Files.exists(target().getParent()));
    }
}
