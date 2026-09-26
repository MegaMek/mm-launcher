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

package org.megamek.launcher.onboarding;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;

final class SafePath {
    private SafePath() {}

    static Path directory(Path requested, String label) throws IOException {
        Path absolute = requested.toAbsolutePath().normalize();
        if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " is not a real directory: " + absolute);
        }
        rejectLinks(absolute);
        return absolute.toRealPath();
    }

    static Path existing(Path root, String relative, String label) throws IOException {
        return existingOrMissing(root, relative, label)
                .orElseThrow(() -> new IOException(label + " is missing: " + relative));
    }

    static Optional<Path> existingOrMissing(Path root, String relative, String label)
            throws IOException {
        Path result = root.resolve(relative.replace('/', java.io.File.separatorChar)).normalize();
        if (!result.startsWith(root)) {
            throw new IOException(label + " escapes installation root: " + relative);
        }
        BasicFileAttributes attributes;
        try {
            rejectLinks(result);
            attributes = Files.readAttributes(result, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }
        if (!attributes.isRegularFile()) {
            throw new IOException(label + " is not a regular file: " + relative);
        }
        Path real = result.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!real.startsWith(root)) {
            throw new IOException(label + " resolves outside installation root: " + relative);
        }
        return Optional.of(real);
    }

    static void rejectLinks(Path path) throws IOException {
        Path current = path.getRoot();
        for (Path part : path) {
            current = current == null ? part : current.resolve(part);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            BasicFileAttributes attrs = Files.readAttributes(current, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (attrs.isSymbolicLink() || attrs.isOther()
                    || isWindowsReparsePoint(current)) {
                throw new IOException("links, reparse points, and special paths are not allowed: "
                        + current);
            }
        }
    }

    private static boolean isWindowsReparsePoint(Path path) throws IOException {
        try {
            Object value = Files.getAttribute(path, "dos:attributes",
                    LinkOption.NOFOLLOW_LINKS);
            return value instanceof Integer attributes && (attributes & 0x400) != 0;
        } catch (UnsupportedOperationException | IllegalArgumentException ignored) {
            return false;
        }
    }
}
