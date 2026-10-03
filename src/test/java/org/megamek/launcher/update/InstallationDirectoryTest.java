/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.megamek.launcher.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.FileSystemException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class InstallationDirectoryTest {
    @TempDir Path temp;

    @Test
    void onlyAnAbsentEntryInAnAccessibleCanonicalParentIsMissing() throws Exception {
        Path root = temp.resolve("installation");
        assertTrue(InstallationDirectory.missing(root));
        Files.createDirectory(root);
        assertFalse(InstallationDirectory.missing(root));
        Files.delete(root);
        Files.writeString(root, "not a directory");
        assertThrows(IOException.class, () -> InstallationDirectory.missing(root));
    }

    @Test
    void missingParentDirectoriesAreMissingWithoutRecreatingThem() throws Exception {
        Path parent = temp.resolve("deleted").resolve("release");
        Path root = Files.createDirectories(parent.resolve("installation"));
        Files.delete(root);
        Files.delete(parent);
        Files.delete(parent.getParent());
        assertTrue(InstallationDirectory.missing(root));
        assertFalse(Files.exists(parent.getParent()));
    }

    @Test
    void unavailableFilesystemAndIndeterminateAccessAreNotReportedAsDeleted() throws Exception {
        Path accessibleParentRoot = temp.resolve("installation");
        for (IOException failure : new IOException[] {
                new AccessDeniedException(accessibleParentRoot.toString()),
                new FileSystemException(accessibleParentRoot.toString(), null, "device unavailable")
        }) {
            assertSame(failure, assertThrows(IOException.class,
                    () -> InstallationDirectory.missing(accessibleParentRoot, path -> {
                        if (path.equals(accessibleParentRoot)) throw failure;
                        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    })));
        }
        NoSuchFileException unavailable = new NoSuchFileException(temp.getRoot().toString());
        assertSame(unavailable, assertThrows(IOException.class,
                () -> InstallationDirectory.missing(accessibleParentRoot, path -> {
                    if (path.equals(temp.getRoot())) throw unavailable;
                    return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                })));
        Path parent = Files.writeString(temp.resolve("not-a-directory"), "keep");
        assertThrows(IOException.class,
                () -> InstallationDirectory.missing(parent.resolve("installation")));
    }

    @Test
    void linkedRootsOrParentsAndFilesystemRootsCannotBeForgotten() throws Exception {
        assertThrows(IOException.class, () -> InstallationDirectory.missing(temp.getRoot()));
        Path target = Files.createDirectory(temp.resolve("target"));
        Path link = temp.resolve("link");
        boolean supported;
        try {
            Files.createSymbolicLink(link, target);
            supported = true;
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            supported = false;
        }
        assumeTrue(supported, "directory symlinks are unavailable");
        assertThrows(IOException.class, () -> InstallationDirectory.missing(link));
        assertThrows(IOException.class, () -> InstallationDirectory.missing(link.resolve("absent")));
        assertThrows(IOException.class,
                () -> InstallationDirectory.missing(link.resolve("absent").resolve("installation")));
    }
}
