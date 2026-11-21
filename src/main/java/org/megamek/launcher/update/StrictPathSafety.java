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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/** Shared boundary check which rejects links/special objects in every existing path component. */
public final class StrictPathSafety {
    private StrictPathSafety() {
    }

    /**
     * Resolves links in a platform-provided base directory ({@code user.home}, an XDG base
     * directory, or {@code LOCALAPPDATA}) once, where it is read. Fedora Atomic systems such as
     * Bazzite and Silverblue make {@code /home} a system link to {@code /var/home}, which the
     * strict checks would otherwise reject for every path below the home folder.
     *
     * <p>Only components that are symbolic links are replaced by their targets; every other
     * component keeps its exact spelling, so paths without links (including Windows short names,
     * letter case, and junctions, which the strict checks already handle) are returned unchanged.
     * Missing trailing components are re-appended as given. Everything below the returned base
     * still gets the strict no-link checks. Never use this for user-chosen, registry-override,
     * or installation paths. A link that cannot be resolved is kept so the strict checks report
     * it.
     */
    public static Path canonicalPlatformBase(Path requested) {
        Path absolute = requested.toAbsolutePath().normalize();
        Path resolved = absolute.getRoot();
        if (resolved == null) return absolute;
        for (Path part : absolute) {
            Path next = resolved.resolve(part);
            if (Files.isSymbolicLink(next)) {
                try {
                    next = next.toRealPath();
                } catch (IOException | SecurityException unresolved) {
                    // Keep the dangling or unreadable link for the strict checks to reject.
                }
            }
            resolved = next;
        }
        return resolved;
    }

    /**
     * Rebases a path that starts with the lexical {@code user.home} onto its
     * {@link #canonicalPlatformBase resolved form}, trusting only the home folder's own
     * ancestor links (for example Fedora Atomic's {@code /home -> var/home}). Links below the
     * home folder are left for the strict checks. File choosers return lexical paths under
     * {@code user.home}; other paths are returned absolute and normalized.
     */
    public static Path underCanonicalHome(Path path, String userHome) {
        Path absolute = path.toAbsolutePath().normalize();
        if (userHome == null || userHome.isBlank()) return absolute;
        try {
            Path home = Path.of(userHome).toAbsolutePath().normalize();
            if (!absolute.startsWith(home)) return absolute;
            Path canonical = canonicalPlatformBase(home);
            return canonical.equals(home) ? absolute : canonical.resolve(home.relativize(absolute));
        } catch (java.nio.file.InvalidPathException invalid) {
            return absolute;
        }
    }

    public static Path requireDirectory(Path requested, String label) throws IOException {
        return require(requested, true, label);
    }

    public static Path requireFile(Path requested, String label) throws IOException {
        return require(requested, false, label);
    }

    private static Path require(Path requested, boolean directory, String label) throws IOException {
        Path absolute = requested.toAbsolutePath().normalize();
        Path cursor = absolute.getRoot();
        for (Path part : absolute) {
            cursor = cursor == null ? part : cursor.resolve(part);
            BasicFileAttributes attrs = Files.readAttributes(cursor, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (attrs.isSymbolicLink() || attrs.isOther()) {
                throw new IOException(label + " has a linked, reparse, or special ancestor: " + cursor);
            }
        }
        BasicFileAttributes attrs = Files.readAttributes(absolute, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (directory ? !attrs.isDirectory() : !attrs.isRegularFile()) {
            throw new IOException(label + " has an unexpected type: " + absolute);
        }
        Path followed = absolute.toRealPath();
        if (!absolute.equals(followed)) {
            throw new IOException(label + " is noncanonical or traverses a link: " + absolute);
        }
        return absolute;
    }
}
