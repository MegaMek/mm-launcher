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

package org.megamek.launcher.release;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.megamek.launcher.operation.OperationContext;
import org.megamek.launcher.operation.OperationPhase;
import org.megamek.launcher.operation.OperationType;
import org.megamek.launcher.operation.ProgressUnit;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public final class SafeTarExtractor {
    public static final int MAX_ENTRIES = 100_000;
    public static final long MAX_FILE_SIZE = 2L * 1024 * 1024 * 1024;
    public static final long MAX_EXPANDED_SIZE = 6L * 1024 * 1024 * 1024;
    public static final int MAX_NAME_LENGTH = 512;
    private static final Pattern DRIVE = Pattern.compile("^[A-Za-z]:.*");
    private static final Pattern DEVICE = Pattern.compile(
            "(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?$");

    public Path extract(Path archive, Path extractionDirectory) throws IOException {
        try {
            return extract(archive, extractionDirectory,
                    OperationContext.none(OperationType.UPDATE_PREVIEW),
                    OperationPhase.EXTRACT);
        } catch (InterruptedException impossible) {
            Thread.currentThread().interrupt();
            throw new IOException("archive extraction was interrupted", impossible);
        }
    }

    public Path extract(Path archive, Path extractionDirectory, OperationContext context,
                        OperationPhase phase) throws IOException, InterruptedException {
        Files.createDirectory(extractionDirectory);
        String root = null;
        int entries = 0;
        long total = 0;
        Map<String, Item> paths = new HashMap<>();
        try (InputStream file = Files.newInputStream(archive);
             GzipCompressorInputStream gzip = new GzipCompressorInputStream(file);
             TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextTarEntry()) != null) {
                 context.checkpoint();
                 if (++entries > MAX_ENTRIES) throw new IOException("archive entry count exceeds limit");
                String name = normalizeName(entry.getName());
                if (name == null) continue; // conventional "." or "./"
                String first = name.contains("/") ? name.substring(0, name.indexOf('/')) : name;
                if (root == null) root = first;
                if (!root.equals(first)) {
                    throw new IOException("archive must contain exactly one top-level distribution directory");
                }
                if (name.equals(root) && !entry.isDirectory()) {
                    throw new IOException("archive top-level item must be a directory");
                }
                if (!(entry.isDirectory() || entry.isFile()) || entry.isSymbolicLink()
                        || entry.isLink() || entry.isSparse() || entry.isCharacterDevice()
                        || entry.isBlockDevice() || entry.isFIFO()) {
                    throw new IOException("unsupported archive entry type: " + name);
                }
                long size = entry.getSize();
                if (size < 0 || size > MAX_FILE_SIZE || total > MAX_EXPANDED_SIZE - size) {
                    throw new IOException("archive expanded size exceeds limit");
                }
                total += size;
                String key = key(name);
                Item prior = paths.get(key);
                Item item = new Item(name, entry.isDirectory());
                if (prior != null) {
                    if (!prior.name.equals(name) || !prior.directory || !entry.isDirectory()
                            || prior.explicit) {
                        throw new IOException("duplicate/case/normalization archive path: "
                                + prior.name + " and " + name);
                    }
                }
                checkParents(paths, name, entry.isDirectory());
                paths.put(key, new Item(item.name, item.directory, true));
                Path target = extractionDirectory.resolve(name.replace('/',
                        java.io.File.separatorChar)).normalize();
                if (!target.startsWith(extractionDirectory)) {
                    throw new IOException("archive path escapes extraction directory");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                } else {
                    Path parent = target.getParent();
                    if (parent != null) Files.createDirectories(parent);
                    try (var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE)) {
                        byte[] buffer = new byte[64 * 1024];
                        long remaining = size;
                        while (remaining > 0) {
                            context.checkpoint();
                            int count = tar.read(buffer, 0, (int) Math.min(buffer.length, remaining));
                            if (count < 0) throw new IOException("truncated tar entry: " + name);
                            if (count == 0) continue;
                            output.write(buffer, 0, count);
                            remaining -= count;
                        }
                    }
                }
                context.progress(phase, entries, -1, ProgressUnit.FILES,
                        "Extracted " + entries + " archive entries");
            }
        }
        if (root == null) throw new IOException("archive is empty");
        Path result = extractionDirectory.resolve(root);
        if (!Files.isDirectory(result, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("archive has no distribution root directory");
        }
        return result;
    }

    private static String normalizeName(String raw) throws IOException {
        if (raw == null || raw.isEmpty() || raw.indexOf('\0') >= 0 || raw.length() > MAX_NAME_LENGTH) {
            throw new IOException("invalid or overlong archive entry name");
        }
        String name = raw;
        while (name.startsWith("./")) name = name.substring(2);
        if (name.equals(".") || name.isEmpty()) return null;
        while (name.endsWith("/")) name = name.substring(0, name.length() - 1);
        if (name.isEmpty() || name.startsWith("/") || name.startsWith("\\")
                || name.startsWith("//") || DRIVE.matcher(name).matches() || name.contains("\\")) {
            throw new IOException("absolute, drive, UNC, and backslash archive paths are forbidden: " + raw);
        }
        for (String component : name.split("/", -1)) {
            if (component.isEmpty() || component.equals(".") || component.equals("..")
                    || component.contains(":") || component.endsWith(".")
                    || component.endsWith(" ") || DEVICE.matcher(component).matches()) {
                throw new IOException("non-portable archive path: " + raw);
            }
            if (component.equalsIgnoreCase(".mm-launcher")) {
                throw new IOException("archive contains reserved .mm-launcher state");
            }
        }
        return name;
    }

    private static void checkParents(Map<String, Item> paths, String name, boolean directory)
            throws IOException {
        int slash = name.indexOf('/');
        while (slash >= 0) {
            String parent = name.substring(0, slash);
            Item prior = paths.get(key(parent));
            if (prior != null && !prior.directory) {
                throw new IOException("archive file/parent collision: " + prior.name + " and " + name);
            }
            if (prior != null && !prior.name.equals(parent)) {
                throw new IOException("case/normalization archive parent alias: "
                        + prior.name + " and " + parent);
            }
            if (prior == null) paths.put(key(parent), new Item(parent, true, false));
            slash = name.indexOf('/', slash + 1);
        }
        if (!directory) {
            String prefix = key(name) + "/";
            if (paths.keySet().stream().anyMatch(existing -> existing.startsWith(prefix))) {
                throw new IOException("archive file/parent collision at " + name);
            }
        }
    }

    private static String key(String path) {
        return Normalizer.normalize(path, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    private record Item(String name, boolean directory, boolean explicit) {
        Item(String name, boolean directory) { this(name, directory, false); }
    }
}
