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
import org.megamek.launcher.operation.OperationType;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;

/** Narrow real-update filesystem primitives; no sandbox marker or acknowledgement is bypassed. */
final class RealUpdateFiles {
    private RealUpdateFiles() {
    }

    static Path root(Path root) throws IOException {
        return StrictPathSafety.requireDirectory(root, "real update root");
    }

    static Path resolve(Path root, String portable, boolean allowMissing)
            throws IOException, ManifestException {
        ManifestReader.validatePortablePath(portable, "real update path", "transaction");
        Path current = root;
        String[] segments = portable.split("/");
        for (int i = 0; i < segments.length; i++) {
            rejectCaseAlias(current, segments[i], portable);
            current = current.resolve(segments[i]).normalize();
            if (!current.startsWith(root)) {
                throw new ManifestException("transaction path escapes application root: " + portable);
            }
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (allowMissing) continue;
                throw new IOException("required transaction path is absent: " + portable);
            }
            BasicFileAttributes attrs = Files.readAttributes(current, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (attrs.isSymbolicLink() || attrs.isOther()) {
                throw new ManifestException("link/reparse point refused at: " + portable);
            }
            if (i < segments.length - 1 && !attrs.isDirectory()) {
                throw new ManifestException("non-directory parent at: " + portable);
            }
        }
        return current;
    }

    static String hash(Path file) throws IOException, ManifestException {
        try {
            return hash(file, OperationContext.none(OperationType.UPDATE_APPLY));
        } catch (InterruptedException impossible) {
            Thread.currentThread().interrupt();
            throw new IOException("update file hash was interrupted", impossible);
        }
    }

    static String hash(Path file, OperationContext context)
            throws IOException, ManifestException, InterruptedException {
        BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.isSymbolicLink() || before.isOther()) {
            throw new ManifestException("expected a regular update file: " + file);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
                byte[] buffer = new byte[128 * 1024];
                while (true) {
                    context.checkpoint();
                    int read = input.read(buffer);
                    if (read < 0) break;
                }
            }
            BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (before.size() != after.size()
                    || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || !java.util.Objects.equals(before.fileKey(), after.fileKey())) {
                throw new ManifestException("file changed while validating: " + file);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hashIfRegular(Path file) throws IOException, ManifestException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        return hash(file);
    }

    static void copyDurable(Path source, Path destination) throws IOException {
        Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
        try (FileChannel channel = FileChannel.open(destination, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    static void atomicReplace(Path staged, Path destination) throws IOException {
        try {
            Files.move(staged, destination, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            throw new IOException("atomic same-volume application replacement is unsupported", e);
        }
    }

    static void deleteOwnedTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (attrs.isSymbolicLink() || attrs.isOther()) {
                    throw new IOException("refusing cleanup through unexpected update link: " + path);
                }
                Files.delete(path);
            }
        }
    }

    static boolean emptyDirectory(Path path) throws IOException {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(path)) return false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
            return !stream.iterator().hasNext();
        }
    }

    private static void rejectCaseAlias(Path parent, String expected, String portable)
            throws IOException, ManifestException {
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent)) {
            for (Path entry : stream) {
                String actual = entry.getFileName().toString();
                if (actual.equalsIgnoreCase(expected) && !actual.equals(expected)) {
                    throw new ManifestException("local case alias collision for " + portable);
                }
            }
        }
    }
}
