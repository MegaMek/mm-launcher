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

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.release.VersionIdentity;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * The version an installation ran before its last update through the launcher, which is where
 * What's new starts. It only counts while the installation still runs the version that update
 * installed; anything else (a manual update, a rescan) falls back to the release's own notes.
 */
public final class PreviousBuildStore {
    static final String SUFFIX = ".previous.json";
    private static final int SCHEMA = 1;
    private static final int MAX_BYTES = 4096;
    private final ReceiptStore receipts = new ReceiptStore();
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    Path path(Path registry, String id) throws IOException {
        // receiptPath performs the strict UUID check.
        receipts.receiptPath(registry, id);
        return receipts.metadataDirectory(registry).resolve(id + SUFFIX);
    }

    /** Remembers that the installation went from {@code previous} to {@code current}. */
    public void write(Path registry, String id, String previous, String current) throws IOException {
        if (VersionIdentity.fromExact(previous).isEmpty() || VersionIdentity.fromExact(current).isEmpty()) {
            throw new IOException("previous and current builds must be versions");
        }
        Path file = path(registry, id);
        Path directory = file.getParent();
        Files.createDirectories(directory);
        StrictPathSafety.requireDirectory(directory, "launcher metadata");
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            StrictPathSafety.requireFile(file, "previous build");
        }
        byte[] bytes = mapper.writeValueAsBytes(new Entry(SCHEMA, previous.trim(), current.trim()));
        Path staging = file.resolveSibling(file.getFileName() + ".new");
        try {
            Files.deleteIfExists(staging);
            try (FileChannel output = FileChannel.open(staging, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                output.write(ByteBuffer.wrap(bytes));
                output.force(true);
            }
            Files.move(staging, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) {
            Files.deleteIfExists(staging);
            throw error;
        }
    }

    /**
     * The version the installation ran before its last launcher update, or null when unknown or
     * when the installation no longer runs the version that update installed.
     */
    public String read(Path registry, InstallationRecord record) {
        try {
            Path file = path(registry, record.id());
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
            StrictPathSafety.requireFile(file, "previous build");
            if (Files.size(file) <= 0 || Files.size(file) > MAX_BYTES) return null;
            Entry entry = mapper.readValue(Files.readAllBytes(file), Entry.class);
            if (entry.schemaVersion() != SCHEMA) return null;
            var current = VersionIdentity.fromExact(entry.current());
            if (current.isEmpty() || !current.equals(VersionIdentity.fromExact(record.observedBuild()))
                    || VersionIdentity.fromExact(entry.previous()).isEmpty()) {
                return null;
            }
            return entry.previous();
        } catch (IOException | RuntimeException unreadable) {
            return null; // What's new then starts at the previous release on GitHub.
        }
    }

    record Entry(int schemaVersion, String previous, String current) {
    }
}