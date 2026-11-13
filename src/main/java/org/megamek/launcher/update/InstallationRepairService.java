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

import org.megamek.launcher.internationalization.LocalizedText;
import org.megamek.launcher.launch.RootCoordinator;
import org.megamek.launcher.manifest.FileEntry;
import org.megamek.launcher.manifest.ManifestException;
import org.megamek.launcher.onboarding.Inspection;
import org.megamek.launcher.onboarding.InstallationInspector;
import org.megamek.launcher.operation.OperationContext;
import org.megamek.launcher.operation.OperationPhase;
import org.megamek.launcher.operation.OperationType;
import org.megamek.launcher.operation.ProgressUnit;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The offline half of Repair: clears caches the applications rebuild on their next start,
 * re-reads the folder of a copy the launcher does not own, and checks the official files of a
 * copy it installed against their recorded SHA-256 without downloading anything.
 */
public final class InstallationRepairService {
    private static final String BUNDLE = "org.megamek.launcher.services";
    private final Path registry;
    private final RegistryStore store;
    private final InstallationInspector inspector;
    private final RootCoordinator coordinator;

    public InstallationRepairService(Path registry, RegistryStore store,
                                     InstallationInspector inspector, RootCoordinator coordinator) {
        this.registry = registry.toAbsolutePath().normalize();
        this.store = store;
        this.inspector = inspector;
        this.coordinator = coordinator;
    }

    /**
     * @param record        the installation after the repair, refreshed when the rescan updated it
     * @param clearedCaches the cache files that were deleted, as portable paths
     * @param previousBuild the version the launcher had recorded when the rescan changed it,
     *                      otherwise {@code null}
     */
    public record Tidy(InstallationRecord record, List<String> clearedCaches, String previousBuild) {
        public Tidy {
            clearedCaches = List.copyOf(clearedCaches);
        }

        public boolean rescanned() {
            return previousBuild != null;
        }
    }

    /**
     * @param missing  official files that are absent
     * @param modified official files whose content differs from the installed release
     */
    public record FileCheck(List<String> missing, List<String> modified) {
        public FileCheck {
            missing = List.copyOf(missing);
            modified = List.copyOf(modified);
        }

        public int damaged() {
            return missing.size() + modified.size();
        }
    }

    /**
     * Clears regenerable caches and, for a copy without launcher provenance, publishes the build
     * and programs its folder holds now. Copies the launcher owns keep their record: a changed
     * folder there is damage for {@link #checkFiles} to find, not a new version.
     */
    public Tidy tidy(InstallationRecord expected) throws IOException {
        InstallationRecord record = store.resolve(store.read(registry), expected.id());
        if (!record.equals(expected)) {
            throw new IOException("selected installation changed; reopen Installations");
        }
        UninstallService.requirePresent(record);
        if (UninstallService.hasPending(registry, record.id())) {
            throw new IOException("uninstall recovery required before repair");
        }
        Path root = StrictPathSafety.requireDirectory(Path.of(record.canonicalRoot()),
                "installation root");
        try (RootCoordinator.Lease lease = coordinator.acquire(root, true)) {
            lease.requireNoPendingUpdate();
            Inspection observed = owned(record) ? null : inspector.inspect(root);
            List<String> cleared = new ArrayList<>();
            for (String cache : UninstallService.REGENERABLE_CACHES) {
                Path file;
                try {
                    file = RealUpdateFiles.resolve(root, cache, true);
                } catch (ManifestException unsafe) {
                    throw new IOException("cache path is unsafe: " + cache, unsafe);
                }
                if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) continue;
                StrictPathSafety.requireFile(file, "cache file");
                Files.delete(file);
                cleared.add(cache);
            }
            if (observed == null || observed.observedBuild().equals(record.observedBuild())
                    && observed.products().equals(record.products())) {
                return new Tidy(record, cleared, null);
            }
            if (!observed.canonicalRoot().equals(record.canonicalRoot())) {
                throw new IOException("installation folder moved; remove it and add it again");
            }
            InstallationRecord refreshed = store.refreshAfterUpdate(registry, record,
                    observed.observedBuild(), observed.products());
            return new Tidy(refreshed, cleared, record.observedBuild());
        }
    }

    /**
     * Hashes every official file of the recorded release, except caches the applications
     * regenerate. Nothing is downloaded or changed.
     */
    public static FileCheck checkFiles(RealUpdateService.Snapshot snapshot)
            throws IOException, InterruptedException {
        return checkFiles(snapshot, OperationContext.none(OperationType.REPAIR));
    }

    public static FileCheck checkFiles(RealUpdateService.Snapshot snapshot, OperationContext context)
            throws IOException, InterruptedException {
        List<String> missing = new ArrayList<>();
        List<String> modified = new ArrayList<>();
        List<FileEntry> files = snapshot.current().officialManifest().files();
        try {
            for (int i = 0; i < files.size(); i++) {
                FileEntry entry = files.get(i);
                context.progress(OperationPhase.PLAN, i, files.size(), ProgressUnit.FILES,
                        LocalizedText.of(BUNDLE, "InstallationRepairService.checkingGameFiles"));
                if (UninstallService.REGENERABLE_CACHES.stream()
                        .anyMatch(cache -> cache.equalsIgnoreCase(entry.path()))) {
                    continue;
                }
                Path file = RealUpdateFiles.resolve(snapshot.root(), entry.path(), true);
                if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                    missing.add(entry.path());
                } else if (!RealUpdateFiles.hash(file, context).equals(entry.sha256())) {
                    modified.add(entry.path());
                }
            }
        } catch (ManifestException unsafe) {
            throw new IOException(unsafe.getMessage(), unsafe);
        }
        return new FileCheck(missing, modified);
    }

    private boolean owned(InstallationRecord record) throws IOException {
        ReceiptStore receipts = new ReceiptStore();
        return Files.exists(receipts.receiptPath(registry, record.id()), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(receipts.metadataDirectory(registry)
                        .resolve(record.id() + ".adoption.json"), LinkOption.NOFOLLOW_LINKS);
    }
}
