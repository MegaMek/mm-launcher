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

import org.megamek.launcher.manifest.FileEntry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * A file an official package shipped inside a protected user-data folder, with every official
 * SHA-256 it has had. Updates never touch these paths; uninstall removes a local copy only when
 * its bytes still equal one of these official defaults.
 */
public record ProtectedPackageFile(String path, List<String> officialHashes) {
    public ProtectedPackageFile {
        officialHashes = officialHashes == null ? null : List.copyOf(officialHashes);
    }

    /** Unions official hashes per path, keeping the first spelling and a stable order. */
    public static List<ProtectedPackageFile> merge(Collection<ProtectedPackageFile> previous,
                                                   Collection<FileEntry> added) {
        Map<String, String> spellings = new TreeMap<>();
        Map<String, TreeSet<String>> hashes = new TreeMap<>();
        List<ProtectedPackageFile> all = new ArrayList<>(previous == null ? List.of() : previous);
        if (added != null) {
            added.forEach(entry -> all.add(
                    new ProtectedPackageFile(entry.path(), List.of(entry.sha256()))));
        }
        for (ProtectedPackageFile file : all) {
            String key = file.path().toLowerCase(Locale.ROOT);
            spellings.putIfAbsent(key, file.path());
            hashes.computeIfAbsent(key, ignored -> new TreeSet<>())
                    .addAll(file.officialHashes());
        }
        return spellings.entrySet().stream()
                .map(entry -> new ProtectedPackageFile(entry.getValue(),
                        List.copyOf(hashes.get(entry.getKey()))))
                .sorted(Comparator.comparing(ProtectedPackageFile::path,
                        String.CASE_INSENSITIVE_ORDER))
                .toList();
    }
}
