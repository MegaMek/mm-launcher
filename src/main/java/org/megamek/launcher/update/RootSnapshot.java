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

import org.megamek.launcher.manifest.ManifestException;
import org.megamek.launcher.manifest.ManifestReader;
import org.megamek.launcher.operation.OperationContext;
import org.megamek.launcher.operation.OperationPhase;
import org.megamek.launcher.operation.ProgressUnit;
import org.megamek.launcher.release.SafeTarExtractor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable evidence from one bounded, stable, non-link-following root scan.
 *
 * <p>Package ownership and full-tree comparison can consume the same file hashes rather than
 * reopening the files independently.</p>
 */
record RootSnapshot(String canonicalRoot, RootIdentity rootIdentity,
                    List<EntryEvidence> entries) {
    RootSnapshot {
        Objects.requireNonNull(canonicalRoot);
        Objects.requireNonNull(rootIdentity);
        entries = List.copyOf(entries);
    }

    static RootSnapshot capture(Path requested, OperationContext context,
                                OperationPhase phase, Scan scan)
            throws IOException, InterruptedException, ManifestException {
        Objects.requireNonNull(context);
        Objects.requireNonNull(phase);
        Objects.requireNonNull(scan);
        context.phase(phase, scan.progressDetail);
        Path root = StrictPathSafety.requireDirectory(requested, scan.rootLabel);
        BasicFileAttributes rootBefore = attributes(root);
        List<Path> paths;
        try (var walk = Files.walk(root)) {
            paths = walk.skip(1).limit((long) SafeTarExtractor.MAX_ENTRIES + 1).toList();
        }
        if (paths.size() > SafeTarExtractor.MAX_ENTRIES) {
            throw new IOException(scan.subject
                    + " contains too many entries to verify safely");
        }
        paths = paths.stream().sorted(Comparator
                .comparing((Path path) -> portable(root.relativize(path))
                        .toLowerCase(Locale.ROOT))
                .thenComparing(path -> portable(root.relativize(path))))
                .toList();
        Set<String> aliases = new HashSet<>();
        List<EntryEvidence> entries = new ArrayList<>();
        int completed = 0;
        long totalBytes = 0;
        for (Path path : paths) {
            context.checkpoint();
            BasicFileAttributes before = attributes(path);
            if (before.isSymbolicLink() || before.isOther()
                    || !before.isDirectory() && !before.isRegularFile()) {
                throw new IOException(scan.subject + " contains a link or special entry");
            }
            String relative = portable(root.relativize(path));
            ManifestReader.validatePortablePath(relative, scan.pathLabel, scan.subject);
            if (!aliases.add(key(relative))) {
                throw new IOException(scan.subject
                        + " contains a case or Unicode path collision");
            }
            if (before.isRegularFile()
                    && (before.size() < 0 || before.size() > SafeTarExtractor.MAX_FILE_SIZE
                    || totalBytes > SafeTarExtractor.MAX_EXPANDED_SIZE - before.size())) {
                throw new IOException(scan.subject + " exceeds safe verification size limits");
            }
            if (before.isRegularFile()) totalBytes += before.size();
            String hash = before.isRegularFile() ? sha256(path, context) : null;
            BasicFileAttributes after = attributes(path);
            if (before.size() != after.size()
                    || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || before.fileKey() != null && !before.fileKey().equals(after.fileKey())) {
                throw new IOException(scan.subject + " changed while it was being verified");
            }
            entries.add(new EntryEvidence(relative, before.isRegularFile(),
                    before.isDirectory(), before.size(), before.lastModifiedTime().toMillis(),
                    before.fileKey() == null ? null : before.fileKey().toString(), hash));
            context.progress(phase, ++completed, paths.size(), ProgressUnit.FILES,
                    scan.progressDetail + " — " + completed + " of " + paths.size()
                            + " files and folders");
        }
        BasicFileAttributes rootAfter = attributes(root);
        RootIdentity identity = new RootIdentity(
                rootBefore.fileKey() == null ? null : rootBefore.fileKey().toString(),
                rootBefore.creationTime().toMillis(), rootBefore.lastModifiedTime().toMillis());
        RootIdentity finalIdentity = new RootIdentity(
                rootAfter.fileKey() == null ? null : rootAfter.fileKey().toString(),
                rootAfter.creationTime().toMillis(), rootAfter.lastModifiedTime().toMillis());
        if (!identity.equals(finalIdentity)) {
            throw new IOException(scan.subject + " changed while it was being verified");
        }
        return new RootSnapshot(root.toString(), identity, entries);
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
    }

    private static String sha256(Path path, OperationContext context)
            throws IOException, InterruptedException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
                byte[] buffer = new byte[128 * 1024];
                while (true) {
                    context.checkpoint();
                    int read = input.read(buffer);
                    if (read < 0) break;
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java runtime lacks SHA-256", impossible);
        }
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String key(String path) {
        return Normalizer.normalize(path, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    enum Scan {
        INITIAL_IMPORTED_COPY(
                "imported application root",
                "imported copy",
                "local path",
                "Checking existing installation"),
        OFFICIAL_PACKAGE(
                "official package root",
                "official package",
                "package path",
                "Checking official package"),
        FINAL_IMPORTED_COPY(
                "imported application root",
                "imported copy",
                "local path",
                "Confirming installation has not changed");

        private final String rootLabel;
        private final String subject;
        private final String pathLabel;
        private final String progressDetail;

        Scan(String rootLabel, String subject, String pathLabel, String progressDetail) {
            this.rootLabel = rootLabel;
            this.subject = subject;
            this.pathLabel = pathLabel;
            this.progressDetail = progressDetail;
        }
    }
}

record RootIdentity(String fileKey, long creationMillis, long modifiedMillis) {
}

record EntryEvidence(String path, boolean regularFile, boolean directory, long size,
                     long modifiedMillis, String fileKey, String sha256) {
}
