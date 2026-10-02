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
import java.nio.file.Path;

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
    void unavailableParentAndIndeterminateAccessAreNotReportedAsDeleted() throws Exception {
        Path root = temp.resolve("unavailable").resolve("installation");
        assertThrows(IOException.class, () -> InstallationDirectory.missing(root));
        Path accessibleParentRoot = temp.resolve("installation");
        for (IOException failure : new IOException[] {
                new AccessDeniedException(accessibleParentRoot.toString()),
                new FileSystemException(accessibleParentRoot.toString(), null, "device unavailable")
        }) {
            assertSame(failure, assertThrows(IOException.class,
                    () -> InstallationDirectory.missing(accessibleParentRoot, path -> { throw failure; })));
        }
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
    }
}
