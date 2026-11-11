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

import org.megamek.launcher.channel.ChannelPreference;
import org.megamek.launcher.channel.ChannelPreferenceStore;
import org.megamek.launcher.launch.RootCoordinator;
import org.megamek.launcher.manifest.FileEntry;
import org.megamek.launcher.manifest.Manifest;
import org.megamek.launcher.manifest.ManifestException;
import org.megamek.launcher.onboarding.Inspection;
import org.megamek.launcher.onboarding.InstallationInspector;
import org.megamek.launcher.operation.OperationContext;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryData;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.sandbox.OverrideEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Re-attaches a registered installation whose folder was renamed or moved to the folder the
 * player points at. The folder must hold the same build and applications; for a launcher-managed
 * copy every official file must be present and every official jar must still match its official
 * hash, so update history is never carried to an unrelated folder.
 *
 * <p>The copy gets a new installation id. Its launcher metadata is first published create-new
 * under that id, then one atomic registry mutation swaps the records; that write is the only
 * commit point. A failure before it leaves at most unreferenced new-id metadata, which is removed
 * when possible, and the leftover old-id metadata after it is never read again.
 */
public final class InstallationRelocationService {
    private final Path registry;
    private final RegistryStore registries;
    private final RootCoordinator coordinator;
    private final InstallationInspector inspector;
    private final ReceiptStore receipts = new ReceiptStore();
    private final CurrentStateStore currentStates = new CurrentStateStore(receipts);
    private final AdoptionStateStore adoptions = new AdoptionStateStore(receipts);
    private final PreviousBuildStore previousBuilds = new PreviousBuildStore();
    private final ChannelPreferenceStore channels = new ChannelPreferenceStore();

    public InstallationRelocationService(Path registry, RegistryStore registries,
                                         RootCoordinator coordinator,
                                         InstallationInspector inspector) {
        this.registry = registry.toAbsolutePath().normalize();
        this.registries = registries;
        this.coordinator = coordinator;
        this.inspector = inspector;
    }

    /** Points {@code missing} at {@code folder} and returns the record that replaced it. */
    public InstallationRecord relocate(InstallationRecord missing, Path folder,
                                       OperationContext context)
            throws IOException, InterruptedException {
        if (java.awt.EventQueue.isDispatchThread()) {
            throw new IOException("locating an installation must run off the UI thread");
        }
        if (missing == null || folder == null) {
            throw new IOException("installation and folder are required");
        }
        Path oldRoot = Path.of(missing.canonicalRoot()).toAbsolutePath().normalize();
        if (!InstallationDirectory.missing(oldRoot)) {
            throw new IOException("The installation folder exists again; nothing to locate.");
        }
        Inspection inspection = inspector.inspect(folder);
        if (!inspection.observedBuild().equals(missing.observedBuild())
                || !inspection.products().equals(missing.products())) {
            throw new IOException("That folder holds " + describe(inspection.products(),
                    inspection.observedBuild()) + ", not " + describe(missing.products(),
                    missing.observedBuild()) + ". Choose the folder this installation was "
                    + "renamed or moved to.");
        }
        Path newRoot = Path.of(inspection.canonicalRoot());
        try (RootCoordinator.Lease oldLease = coordinator.acquireForRecovery(oldRoot, false);
             RootCoordinator.Lease newLease = coordinator.acquire(newRoot, false)) {
            oldLease.requireNoPendingUpdate();
            newLease.requireNoPendingUpdate();
            RegistryData data = registries.read(registry);
            InstallationRecord registered = registries.resolve(data, missing.id());
            if (!registered.equals(missing)) {
                throw new IOException("installation changed; reopen Installations");
            }
            if (UninstallService.hasPending(registry, missing.id())) {
                throw new IOException("Finish the interrupted uninstall or removal of this "
                        + "installation first.");
            }
            for (InstallationRecord other : data.installations()) {
                Path otherRoot = Path.of(other.canonicalRoot());
                if (other.id().equals(missing.id())) continue;
                if (newRoot.equals(otherRoot)) {
                    throw new IOException("That folder is already in the launcher as \""
                            + other.name() + "\".");
                }
                if (newRoot.startsWith(otherRoot) || otherRoot.startsWith(newRoot)) {
                    throw new IOException("That folder overlaps \"" + other.name()
                            + "\", which is already in the launcher.");
                }
            }
            InstallationRecord moved = new InstallationRecord(UUID.randomUUID().toString(),
                    missing.name(), inspection.canonicalRoot(), missing.observedBuild(),
                    missing.products(), missing.pin(), missing.updateEligible(),
                    missing.registeredAt());
            List<Runnable> rollback = new ArrayList<>();
            try {
                publishMetadata(data, missing, moved, newRoot, context, rollback);
                if (!InstallationDirectory.missing(oldRoot)) {
                    throw new IOException("The installation folder exists again; nothing to "
                            + "locate.");
                }
                InstallationRecord committed = registries.relocate(registry, missing, inspection,
                        moved.id());
                rollback.clear();
                forgetMetadata(missing.id());
                return committed;
            } catch (IOException | InterruptedException | RuntimeException failure) {
                for (int i = rollback.size() - 1; i >= 0; i--) rollback.get(i).run();
                throw failure;
            }
        }
    }

    private void publishMetadata(RegistryData data, InstallationRecord missing,
                                 InstallationRecord moved, Path newRoot,
                                 OperationContext context, List<Runnable> rollback)
            throws IOException, InterruptedException {
        ChannelPreference channel = null;
        if (Files.exists(receipts.receiptPath(registry, missing.id()),
                LinkOption.NOFOLLOW_LINKS)) {
            OwnershipReceipt oldReceipt = receipts.readForRelocation(registry, data, missing);
            CurrentUpdateState oldCurrent = currentStates.readForRelocation(registry,
                    missing.id(), oldReceipt);
            AdoptionStateStore.Marker oldMarker = adoptions.readForRelocation(registry, missing);
            OwnershipReceipt receipt = rebind(oldReceipt, moved);
            CurrentUpdateState current = oldCurrent == null ? null : rebind(oldCurrent, moved);
            CurrentUpdateState latest = current == null
                    ? CurrentUpdateState.initial(receipt) : current;
            verifyOfficialFiles(newRoot, latest, context);

            receipts.publish(registry, data, moved, receipt);
            rollback.add(() -> quietly(() -> receipts.rollbackPublication(registry, moved,
                    receipt)));
            if (current != null) {
                currentStates.write(registry, moved, receipt, current);
                rollback.add(() -> quietly(() -> currentStates.rollbackPublication(registry,
                        moved.id(), false, current)));
            }
            channel = channels.publishRelocated(registry, data, missing, moved);
            if (channel != null) {
                ChannelPreference published = channel;
                rollback.add(() -> quietly(() -> channels.rollbackInitialization(registry,
                        moved, published)));
            }
            if (oldMarker != null) {
                if (channel == null) {
                    throw new IOException("adopted installation has no readable release type");
                }
                AdoptionStateStore.Marker marker = adoptions.write(registry, moved, receipt,
                        latest, channel, oldMarker.attemptId());
                rollback.add(() -> quietly(() -> adoptions.rollback(registry, moved.id(),
                        marker)));
            }
        } else {
            ChannelPreference published = channels.publishRelocated(registry, data, missing,
                    moved);
            if (published != null) {
                rollback.add(() -> quietly(() -> channels.rollbackInitialization(registry,
                        moved, published)));
            }
        }
        String previous = previousBuilds.read(registry, missing);
        if (previous != null) {
            previousBuilds.write(registry, moved.id(), previous, missing.observedBuild());
            Path file = previousBuilds.path(registry, moved.id());
            rollback.add(() -> quietly(() -> Files.deleteIfExists(file)));
        }
    }

    /**
     * Requires every official file except regenerable caches, and the official hash of every jar
     * the player has not knowingly overridden, so the receipt is not attached to another program.
     */
    private static void verifyOfficialFiles(Path root, CurrentUpdateState latest,
                                            OperationContext context)
            throws IOException, InterruptedException {
        Manifest manifest = latest.officialManifest();
        Set<String> overridden = latest.overrides().stream().map(OverrideEntry::path)
                .map(path -> path.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
        for (FileEntry entry : manifest.files()) {
            context.checkpoint();
            if (UninstallService.regenerableCache(entry.path())) continue;
            Path file;
            try {
                file = RealUpdateFiles.resolve(root, entry.path(), true);
            } catch (ManifestException error) {
                throw new IOException("That folder's files are not usable: "
                        + error.getMessage(), error);
            }
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("That folder is missing " + entry.path()
                        + " from this installation, so it can't be the same copy.");
            }
            if (!entry.path().toLowerCase(Locale.ROOT).endsWith(".jar")
                    || overridden.contains(entry.path().toLowerCase(Locale.ROOT))) {
                continue;
            }
            String hash;
            try {
                hash = RealUpdateFiles.hash(file, context);
            } catch (ManifestException error) {
                throw new IOException("That folder's files are not usable: "
                        + error.getMessage(), error);
            }
            if (!hash.equals(entry.sha256())) {
                throw new IOException(entry.path() + " in that folder is not the one this "
                        + "installation had, so it can't be the same copy.");
            }
        }
    }

    private void forgetMetadata(String id) {
        Path directory = receipts.metadataDirectory(registry);
        for (String suffix : List.of(".adoption.json", ".channel.json", ".current.json",
                PreviousBuildStore.SUFFIX, ".json", ".json.lock", ".channel.json.lock")) {
            // The registry no longer references the old id, so leftovers are harmless.
            quietly(() -> Files.deleteIfExists(directory.resolve(id + suffix)));
        }
    }

    private static OwnershipReceipt rebind(OwnershipReceipt receipt, InstallationRecord moved) {
        return new OwnershipReceipt(receipt.schemaVersion(), receipt.ownershipPolicyVersion(),
                moved.id(), moved.canonicalRoot(), moved.registeredAt(), receipt.repository(),
                receipt.tag(), receipt.assetName(), receipt.assetSize(), receipt.assetSha256(),
                receipt.officialManifest(), receipt.excludedOfficialPaths(),
                receipt.protectedPackageFiles());
    }

    private static CurrentUpdateState rebind(CurrentUpdateState state, InstallationRecord moved) {
        return new CurrentUpdateState(state.schemaVersion(), state.ownershipPolicyVersion(),
                moved.id(), moved.canonicalRoot(), moved.registeredAt(), state.repository(),
                state.tag(), state.assetName(), state.assetSize(), state.assetSha256(),
                state.officialManifest(), state.excludedOfficialPaths(), state.overrides(),
                state.caseOverrides(), state.lastTransactionId(), state.protectedPackageFiles());
    }

    private static String describe(List<org.megamek.launcher.onboarding.Product> products,
                                   String build) {
        String names = products.stream().map(product -> switch (product.key()) {
            case "mekhq" -> "MekHQ";
            case "lab" -> "MegaMekLab";
            case "megamek" -> "MegaMek";
            default -> product.key();
        }).collect(Collectors.joining(" + "));
        return (names.isEmpty() ? "no game" : names) + " " + build;
    }

    private static void quietly(IoAction action) {
        try {
            action.run();
        } catch (IOException | RuntimeException ignored) {
            // Best effort: unreferenced metadata is never read for a registered installation.
        }
    }

    @FunctionalInterface
    private interface IoAction {
        void run() throws IOException;
    }
}
