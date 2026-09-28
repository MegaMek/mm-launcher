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

package org.megamek.launcher.gui;

import org.megamek.launcher.launch.RootCoordinator;
import org.megamek.launcher.onboarding.Inspection;
import org.megamek.launcher.onboarding.InstallationInspector;
import org.megamek.launcher.onboarding.Product;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.release.OfficialRepository;
import org.megamek.launcher.update.ReceiptStore;
import org.megamek.launcher.update.StrictPathSafety;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.UUID;

/** Moves only known installation-local settings to a restorable, per-root backup. */
public final class PreferencesResetService {
    private static final String BACKUPS = ".mm-launcher-preferences-backups";
    private final Path registry;
    private final RegistryStore store;
    private final InstallationInspector inspector;
    private final RootCoordinator coordinator;
    private final Mover mover;

    @FunctionalInterface
    interface Mover {
        void move(Path source, Path target) throws IOException;
    }

    public PreferencesResetService(Path registry, RegistryStore store,
                                   InstallationInspector inspector, RootCoordinator coordinator) {
        this(registry, store, inspector, coordinator, Files::move);
    }

    PreferencesResetService(Path registry, RegistryStore store,
                            InstallationInspector inspector, RootCoordinator coordinator,
                            Mover mover) {
        this.registry = registry;
        this.store = store;
        this.inspector = inspector;
        this.coordinator = coordinator;
        this.mover = mover;
    }

    public record Fingerprint(String fileKey, long size, String sha256) {
    }

    public record Plan(InstallationRecord record, String product, List<String> files, Path backup,
                       Map<String, Fingerprint> fingerprints) {
        public Plan {
            files = List.copyOf(files);
            fingerprints = Map.copyOf(fingerprints);
        }
    }

    public record Result(List<String> moved, Path backup) {
        public Result {
            moved = List.copyOf(moved);
        }
    }

    public Plan plan(InstallationRecord record) throws IOException {
        InstallationRecord current = store.resolve(store.read(registry), record.id());
        if (!current.equals(record)) throw new IOException("selected installation changed");
        Path root = StrictPathSafety.requireDirectory(Path.of(record.canonicalRoot()),
                "preferences root");
        Inspection observed = inspector.inspect(root);
        if (!observed.canonicalRoot().equals(record.canonicalRoot())
                || !observed.observedBuild().equals(record.observedBuild())
                || !observed.products().equals(record.products())) {
            throw new IOException("registered installation inventory changed");
        }
        String product = product(record);
        Path conf = StrictPathSafety.requireDirectory(root.resolve("mmconf"), "settings directory");
        List<String> candidates = new ArrayList<>();
        if (product.equals("megamek") || product.equals("mekhq")) {
            Collections.addAll(candidates, "clientsettings.xml", "mm.preferences");
        }
        if (product.equals("mekhq")) candidates.add("mhq.preferences");
        if (product.equals("mekhq") || product.equals("lab")) {
            Collections.addAll(candidates, "mml.preferences", "megameklab.properties",
                    "megameklab.properties.bak");
        }
        List<String> present = new ArrayList<>();
        Map<String, Fingerprint> fingerprints = new LinkedHashMap<>();
        for (String candidate : candidates) {
            Path file = conf.resolve(candidate);
            if (exists(file)) {
                fingerprints.put(candidate, fingerprint(file));
                present.add(candidate);
            }
        }
        Path backups = root.resolve(BACKUPS);
        if (exists(backups)) {
            StrictPathSafety.requireDirectory(backups, "preference backup directory");
        }
        Path backup = backups.resolve(UUID.randomUUID().toString());
        return new Plan(record, product, present, backup, fingerprints);
    }

    private static Fingerprint fingerprint(Path file) throws IOException {
        StrictPathSafety.requireFile(file, "preference file");
        BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StrictPathSafety.requireFile(file, "preference file");
        BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.size() != after.size()
                || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !java.util.Objects.equals(before.fileKey(), after.fileKey())) {
            throw new IOException("preference file changed while reading: " + file);
        }
        return new Fingerprint(before.fileKey() == null ? null : before.fileKey().toString(),
                before.size(), java.util.HexFormat.of().formatHex(digest.digest()));
    }

    private String product(InstallationRecord record) throws IOException {
        Path receipt = new ReceiptStore().receiptPath(registry, record.id());
        if (exists(receipt)) {
            // An invalid receipt is not a reason to guess from the jar inventory.
            String source = new ReceiptStore().read(registry, store.read(registry), record)
                    .repository();
            OfficialRepository repository = OfficialRepository.parse(source);
            if (record.products().stream().noneMatch(p -> p.key().equals(repository.key()))) {
                throw new IOException("official source disagrees with product inventory");
            }
            return repository.key();
        }
        Set<String> keys = record.products().stream().map(Product::key)
                .collect(java.util.stream.Collectors.toSet());
        if (keys.equals(Set.of("megamek", "mekhq", "lab"))
                || keys.equals(Set.of("megamek", "mekhq"))) return "mekhq";
        if (keys.size() == 1 && Set.of("megamek", "mekhq", "lab").containsAll(keys)) {
            return keys.iterator().next();
        }
        throw new IOException("imported package product inventory is ambiguous; preferences "
                + "were not reset");
    }

    public Result reset(Plan requested) throws IOException {
        Path root = Path.of(requested.record().canonicalRoot());
        try (RootCoordinator.Lease lease = coordinator.acquire(root, true)) {
            lease.requireNoPendingUpdate();
            Plan current = plan(requested.record());
            if (!current.product().equals(requested.product())
                    || !current.files().equals(requested.files())
                    || !current.fingerprints().equals(requested.fingerprints())) {
                throw new IOException("preferences changed since confirmation; nothing was moved");
            }
            Path backups = root.resolve(BACKUPS);
            if (!requested.backup().getParent().equals(backups)
                    || exists(requested.backup())) {
                throw new IOException("preference backup destination changed");
            }
            if (current.files().isEmpty()) return new Result(List.of(), requested.backup());
            if (!exists(backups)) Files.createDirectory(backups);
            StrictPathSafety.requireDirectory(backups, "preference backup directory");
            Files.createDirectory(requested.backup());
            StrictPathSafety.requireDirectory(requested.backup(), "preference backup");
            List<String> moved = new ArrayList<>();
            try {
                for (String name : current.files()) {
                    Path source = root.resolve("mmconf").resolve(name);
                    StrictPathSafety.requireDirectory(root.resolve("mmconf"), "settings directory");
                    StrictPathSafety.requireDirectory(requested.backup(), "preference backup");
                    if (!fingerprint(source).equals(requested.fingerprints().get(name))) {
                        throw new IOException("preference changed since confirmation: " + name);
                    }
                    Path target = requested.backup().resolve(name);
                    if (exists(target)) {
                        throw new IOException("backup file already exists: " + target);
                    }
                    mover.move(source, target);
                    moved.add(name);
                }
            } catch (IOException failure) {
                Collections.reverse(moved);
                for (String name : moved) {
                    try {
                        Path source = root.resolve("mmconf").resolve(name);
                        if (exists(source)) {
                            throw new IOException("original preference path is occupied");
                        }
                        StrictPathSafety.requireDirectory(root.resolve("mmconf"),
                                "settings directory");
                        StrictPathSafety.requireFile(requested.backup().resolve(name),
                                "backup preference file");
                        Files.move(requested.backup().resolve(name), source);
                    } catch (IOException rollback) {
                        failure.addSuppressed(rollback);
                    }
                }
                throw new IOException("Preference reset failed; verify original files and recover "
                        + "any remaining files from " + requested.backup(), failure);
            }
            return new Result(current.files(), requested.backup());
        }
    }

    private static boolean exists(Path path) throws IOException {
        try {
            Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return true;
        } catch (NoSuchFileException missing) {
            return false;
        }
    }
}
