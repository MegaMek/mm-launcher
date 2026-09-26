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

package org.megamek.launcher.sandbox;

import org.megamek.launcher.manifest.ManifestException;
import org.megamek.launcher.manifest.ManifestReader;

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
import java.util.HexFormat;

final class SafeFiles {
    private SafeFiles() {
    }

    static Path realDirectory(Path path, String label) throws IOException, ManifestException {
        Path absolute = path.toAbsolutePath().normalize();
        Path cursor = absolute.getRoot();
        for (Path component : absolute) {
            cursor = cursor.resolve(component);
            if (Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes componentAttributes = Files.readAttributes(
                        cursor, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (componentAttributes.isSymbolicLink() || componentAttributes.isOther()) {
                    throw new ManifestException(label + " has a link/reparse ancestor: " + cursor);
                }
            }
        }
        BasicFileAttributes attributes = Files.readAttributes(
                absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()) {
            throw new ManifestException(label + " must be a real directory, not a link/reparse point: " + absolute);
        }
        Path real = absolute.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!real.equals(absolute)) {
            throw new ManifestException(label + " uses a case/path alias: " + absolute);
        }
        return real;
    }

    static Path resolve(Path root, String portable) throws IOException, ManifestException {
        ManifestReader.validatePortablePath(portable, "mutation path", "operation");
        Path current = root;
        String[] segments = portable.split("/");
        for (int i = 0; i < segments.length; i++) {
            rejectCaseAlias(current, segments[i], portable);
            current = current.resolve(segments[i]).normalize();
            if (!current.startsWith(root)) {
                throw new ManifestException("path escapes sandbox: " + portable);
            }
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes attributes = Files.readAttributes(
                        current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()) {
                    throw new ManifestException("link/reparse point refused at: " + portable);
                }
                if (i < segments.length - 1 && !attributes.isDirectory()) {
                    throw new ManifestException("non-directory parent at: " + portable);
                }
            }
        }
        return current;
    }

    static String hashStable(Path file) throws IOException, ManifestException {
        BasicFileAttributes before = Files.readAttributes(
                file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.isSymbolicLink() || before.isOther()) {
            throw new ManifestException("expected regular file: " + file);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            BasicFileAttributes after = Files.readAttributes(
                    file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (before.size() != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || !java.util.Objects.equals(before.fileKey(), after.fileKey())) {
                throw new ManifestException("file changed while validating: " + file);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
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

    private static void rejectCaseAlias(Path parent, String expected, String portable)
            throws IOException, ManifestException {
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
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
