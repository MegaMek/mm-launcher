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

import org.megamek.launcher.registry.InstallationRecord;

import java.util.List;

/**
 * Durable authority for one uninstall.  The registry removal is the commit barrier: a
 * COMMIT_AUTHORIZED journal plus an absent bound registration is committed, while every journal
 * whose registration still exists is rolled back.
 * The complete intent precedes atomic moves; recovery uses the original/backup locations.
 * Per-file states remain readable for journals produced by older launcher versions.
 */
record UninstallJournal(
        int schemaVersion,
        String transactionId,
        String phase,
        String canonicalRegistry,
        InstallationRecord record,
        CurrentUpdateState current,
        String rootBackup,
        List<FileMove> files,
        List<MetadataMove> metadata,
        List<DirectoryState> removedDirectories,
        boolean rootRemovalStarted) {

    UninstallJournal {
        files = files == null ? null : List.copyOf(files);
        metadata = metadata == null ? null : List.copyOf(metadata);
        removedDirectories = removedDirectories == null
                ? null : List.copyOf(removedDirectories);
    }

    record FileMove(String relativePath, String expectedSha256, long expectedSize,
                    String state) {
    }

    record MetadataMove(String fileName, String sha256, long size, String state) {
    }

    record DirectoryState(String relativePath, long modifiedMillis) {
    }
}
