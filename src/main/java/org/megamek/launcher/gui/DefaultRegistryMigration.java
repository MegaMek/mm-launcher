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

import org.megamek.launcher.registry.RegistryStore;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;

/**
 * Copies only known schema-3 launcher state. The old directory is the durable backup:
 * neither it nor any game installation is modified. Unsupported old schemas are ignored,
 * never reset or upgraded speculatively.
 */
final class DefaultRegistryMigration {
    private DefaultRegistryMigration() {
    }

    static void migrate(Path target, Path legacy) throws IOException {
        Path source = legacy.toAbsolutePath().normalize();
        Path registryTarget = target.toAbsolutePath().normalize();
        if (source.equals(registryTarget)) return;
        Path destination = registryTarget.getParent();
        if (destination == null) throw new IOException("missing destination directory");
        RegistryStore store = new RegistryStore();
        // A validated new registry is authoritative, even if a legacy backup's
        // logs or metadata were subsequently cleaned up in the new location.
        if (Files.exists(registryTarget, LinkOption.NOFOLLOW_LINKS)) {
            requireRealDirectory(destination);
            requireRegular(registryTarget);
            store.read(registryTarget);
            return;
        }
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) return;
        requireRealDirectory(source.getParent());
        requireRegular(source);
        // A previous installation may contain a schema that this release cannot read.
        // Do not let that obsolete state prevent a fresh install from opening.
        int oldSchema = schema(source);
        if (oldSchema == 1) return; // Obsolete pre-release state cannot be safely imported.
        if (oldSchema != RegistryStore.SCHEMA) {
            throw new IOException("unsupported legacy registry schema " + oldSchema
                    + "; no migration was attempted");
        }
        store.read(source);
        Path oldSettings = settings(source);
        Path oldMetadata = sidecar(source, ".metadata");
        Path oldLogs = sidecar(source, ".launcher-logs");
        Path parent = destination.getParent();
        if (parent == null) throw new IOException("missing destination parent");
        if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(parent);
        requireRealDirectory(parent);
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            requireRealDirectory(destination);
            throw new IOException("destination state directory already exists: " + destination);
        }
        Path oldLock = source.resolveSibling(source.getFileName() + ".lock");
        Path settingsLock = oldSettings.resolveSibling(oldSettings.getFileName() + ".lock");
        if (Files.exists(oldLock, LinkOption.NOFOLLOW_LINKS)) requireRegular(oldLock);
        if (Files.exists(settingsLock, LinkOption.NOFOLLOW_LINKS)) requireRegular(settingsLock);
        try (FileChannel channel = FileChannel.open(oldLock, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE); FileLock lock = lock(channel);
             FileChannel settingsChannel = FileChannel.open(settingsLock, StandardOpenOption.CREATE,
                     StandardOpenOption.WRITE); FileLock settingsFileLock = lock(settingsChannel)) {
            if (schema(source) != RegistryStore.SCHEMA) {
                throw new IOException("legacy registry changed during migration");
            }
            store.read(source);
            if (Files.exists(oldSettings, LinkOption.NOFOLLOW_LINKS)) {
                requireRegular(oldSettings);
                new LauncherSettingsStore(source).read();
            }
            for (Path transientPath : new Path[] {
                    source.resolveSibling(source.getFileName() + ".new"),
                    oldSettings.resolveSibling(oldSettings.getFileName() + ".new")
            }) {
                if (Files.exists(transientPath, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("unfinished legacy state write: " + transientPath);
                }
            }
            Path stage = Files.createTempDirectory(parent, ".megamek-launcher-migration-");
            try {
                Files.copy(source, stage.resolve(source.getFileName()),
                        LinkOption.NOFOLLOW_LINKS);
                if (Files.mismatch(source, stage.resolve(source.getFileName())) != -1) {
                    throw new IOException("legacy registry changed during migration");
                }
                if (Files.exists(oldSettings, LinkOption.NOFOLLOW_LINKS)) {
                    new LauncherSettingsStore(source).read();
                    Files.copy(oldSettings, stage.resolve(oldSettings.getFileName()),
                            LinkOption.NOFOLLOW_LINKS);
                    if (Files.mismatch(oldSettings, stage.resolve(oldSettings.getFileName())) != -1) {
                        throw new IOException("legacy settings changed during migration");
                    }
                }
                copyTreeIfPresent(oldMetadata, stage.resolve(oldMetadata.getFileName()));
                copyTreeIfPresent(oldLogs, stage.resolve(oldLogs.getFileName()));
                store.read(stage.resolve(source.getFileName()));
                try {
                    Files.move(stage, destination, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    throw new IOException("atomic migration is unavailable; legacy state retained", e);
                }
            } finally {
                if (Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) {
                    try (var paths = Files.walk(stage)) {
                        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                            Files.delete(path);
                        }
                    }
                }
            }
        } catch (OverlappingFileLockException e) {
            throw new IOException("legacy registry or settings are locked", e);
        }
    }

    private static int schema(Path file) throws IOException {
        // Only the schema number is inspected here; strict validation follows for schema 3.
        var tree = new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readAllBytes(file));
        if (tree == null || !tree.path("schemaVersion").canConvertToInt()) {
            throw new IOException("legacy registry schema is invalid; no migration was attempted");
        }
        return tree.path("schemaVersion").intValue();
    }

    private static Path settings(Path registry) {
        return sidecar(registry, ".settings.json");
    }

    private static Path sidecar(Path registry, String suffix) {
        return registry.resolveSibling(registry.getFileName() + suffix);
    }

    private static void copyTreeIfPresent(Path source, Path target) throws IOException {
        if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) return;
        requireRealDirectory(source);
        // Reject links and non-regular entries; never follow or rewrite unknown future sidecars.
        try (var paths = Files.walk(source)) {
            for (Path item : paths.toList()) {
                Path output = target.resolve(source.relativize(item));
                if (Files.isDirectory(item, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectory(output);
                } else {
                    requireRegular(item);
                    Files.copy(item, output, LinkOption.NOFOLLOW_LINKS);
                    if (Files.mismatch(item, output) != -1) {
                        throw new IOException("legacy sidecar changed during migration: " + item);
                    }
                }
            }
        }
    }

    private static void requireRegular(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("launcher state is not a regular file: " + file);
    }

    private static void requireRealDirectory(Path directory) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("launcher state parent is not a real directory: " + directory);
    }

    private static FileLock lock(FileChannel channel) throws IOException {
        FileLock lock = channel.tryLock();
        if (lock == null) throw new IOException("legacy registry is locked");
        return lock;
    }
}
