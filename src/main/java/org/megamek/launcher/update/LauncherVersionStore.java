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
import org.megamek.launcher.release.ReleaseNotes;
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
 * The launcher version that ran before the running one, which is where the launcher's own release
 * notes start. Each start records the running version, so any way of updating the launcher (its
 * self-update, an installer, a new portable copy) is noticed the next time it starts.
 */
public final class LauncherVersionStore {
    static final String FILE = "launcher-version.json";
    private static final int SCHEMA = 1;
    private static final int MAX_BYTES = 4096;
    private final ReceiptStore receipts = new ReceiptStore();
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    Path path(Path registry) {
        return receipts.metadataDirectory(registry).resolve(FILE);
    }

    /**
     * Records that {@code running} is the launcher version now running, and returns the older
     * version that ran before it, or null when that is unknown (a first start, a downgrade, or a
     * development build without a version).
     */
    public String remember(Path registry, String running) {
        if (VersionIdentity.fromExact(running).isEmpty()) return null;
        String current = running.trim();
        Entry entry = read(registry);
        if (entry != null && VersionIdentity.fromExact(entry.current()).equals(VersionIdentity.fromExact(current))) {
            return ReleaseNotes.combines(entry.previous(), current) ? entry.previous() : null;
        }
        String previous = entry != null && ReleaseNotes.combines(entry.current(), current) ? entry.current() : "";
        try {
            write(registry, new Entry(SCHEMA, previous, current));
        } catch (IOException | RuntimeException unwritable) {
            // The notes then start at the previous launcher release on GitHub.
        }
        return previous.isEmpty() ? null : previous;
    }

    private Entry read(Path registry) {
        try {
            Path file = path(registry);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
            StrictPathSafety.requireFile(file, "launcher version");
            if (Files.size(file) <= 0 || Files.size(file) > MAX_BYTES) return null;
            Entry entry = mapper.readValue(Files.readAllBytes(file), Entry.class);
            return entry.schemaVersion() == SCHEMA ? entry : null;
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private void write(Path registry, Entry entry) throws IOException {
        Path file = path(registry);
        Path directory = file.getParent();
        Files.createDirectories(directory);
        StrictPathSafety.requireDirectory(directory, "launcher metadata");
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            StrictPathSafety.requireFile(file, "launcher version");
        }
        byte[] bytes = mapper.writeValueAsBytes(entry);
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

    /** {@code previous} is empty when no older version is known. */
    record Entry(int schemaVersion, String previous, String current) {
    }
}