/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.megamek.launcher.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/** Distinguishes a deleted installation from an inaccessible or unsafe location. */
public final class InstallationDirectory {
    private InstallationDirectory() {
    }

    public static boolean missing(Path root) throws IOException {
        return missing(root, path -> Files.readAttributes(
                path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS));
    }

    static boolean missing(Path root, PathProbe probe) throws IOException {
        Path absolute = root.toAbsolutePath().normalize();
        if (!root.equals(absolute) || absolute.getParent() == null) {
            throw new IOException("installation root must be a canonical non-root path");
        }
        Path ancestor = absolute.getRoot();
        BasicFileAttributes attributes = probe.read(ancestor);
        for (Path part : absolute) {
            if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()) {
                throw new IOException("installation folder has an unsafe ancestor: " + ancestor);
            }
            Path next = ancestor.resolve(part);
            try {
                attributes = probe.read(next);
            } catch (NoSuchFileException missing) {
                StrictPathSafety.requireDirectory(ancestor, "installation folder ancestor");
                return true;
            }
            ancestor = next;
        }
        StrictPathSafety.requireDirectory(absolute, "installation folder");
        return false;
    }

    @FunctionalInterface
    interface PathProbe {
        BasicFileAttributes read(Path path) throws IOException;
    }
}
