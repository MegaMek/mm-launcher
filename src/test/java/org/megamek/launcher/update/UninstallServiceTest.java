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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.megamek.launcher.channel.ChannelPreferenceStore;
import org.megamek.launcher.channel.FollowChannel;
import org.megamek.launcher.launch.RootCoordinator;
import org.megamek.launcher.onboarding.InstallationInspector;
import org.megamek.launcher.operation.OperationContext;
import org.megamek.launcher.operation.OperationCancelledException;
import org.megamek.launcher.operation.OperationType;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.release.OfficialRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UninstallServiceTest {
    @TempDir Path temp;

    @Test
    void uninstallDeletesOnlyExactCurrentOfficialFilesAndKeepsCustomAndModified()
            throws Exception {
        Fixture fixture = managed("selective");
        Path modified = fixture.root.resolve("docs/guide.txt");
        Files.writeString(modified, "locally changed");
        Path custom = Files.writeString(fixture.root.resolve("custom/user.txt"), "keep");

        UninstallService service = fixture.service(point -> { });
        UninstallService.Plan plan = service.plan(fixture.record, context());
        assertTrue(plan.entries().stream().anyMatch(entry ->
                entry.relativePath().equals("docs/guide.txt")
                        && entry.action() == UninstallService.Action.RETAIN));

        UninstallService.Result result = service.uninstall(
                plan, UninstallService.CONFIRMATION, context());

        assertTrue(result.committed());
        assertTrue(result.retainedFiles());
        assertEquals("locally changed", Files.readString(modified));
        assertEquals("keep", Files.readString(custom));
        assertFalse(Files.exists(fixture.root.resolve("MegaMek.jar")));
        assertTrue(fixture.store.read(fixture.registry).installations().isEmpty());
        assertFalse(Files.exists(new ReceiptStore().receiptPath(
                fixture.registry, fixture.record.id())));
    }

    @Test
    void pristineManagedRootIsRemovedButImportedCopyCannotUninstall() throws Exception {
        Fixture managed = managed("pristine");
        Files.delete(managed.root.resolve("custom/user.txt"));
        Files.delete(managed.root.resolve("custom"));
        UninstallService service = managed.service(point -> { });
        UninstallService.Result result = service.uninstall(
                service.plan(managed.record, context()),
                UninstallService.CONFIRMATION, context());
        assertFalse(result.retainedFiles());
        assertFalse(Files.exists(managed.root));

        Path root = suite(temp.resolve("imported"));
        Path registry = temp.resolve("imported-registry.json");
        RegistryStore store = new RegistryStore();
        InstallationRecord imported = store.register(registry, "Imported", root, null);
        UninstallService importedService = new UninstallService(
                registry, new RootCoordinator(temp.resolve("imported-coordination")));
        assertThrows(IOException.class, () -> importedService.plan(imported, context()));
        assertTrue(Files.exists(root.resolve("MegaMek.jar")));
    }

    @Test
    void removeFromLauncherPreservesCompleteRootAndCleansSidecars() throws Exception {
        Fixture fixture = managed("remove");
        byte[] jar = Files.readAllBytes(fixture.root.resolve("MegaMek.jar"));
        FileTime timestamp = Files.getLastModifiedTime(fixture.root.resolve("MegaMek.jar"));

        UninstallService.Result result = fixture.service(point -> { })
                .removeFromLauncher(fixture.record);

        assertFalse(result.committed());
        assertArrayEquals(jar, Files.readAllBytes(fixture.root.resolve("MegaMek.jar")));
        assertEquals(timestamp, Files.getLastModifiedTime(fixture.root.resolve("MegaMek.jar")));
        assertTrue(fixture.store.read(fixture.registry).installations().isEmpty());
        assertFalse(Files.exists(new ReceiptStore().receiptPath(
                fixture.registry, fixture.record.id())));
    }

    @Test
    void failedLauncherRemovalRestoresAllMetadataAndRegistration() throws Exception {
        Fixture fixture = managed("remove-rollback");
        Path receipt = new ReceiptStore().receiptPath(fixture.registry, fixture.record.id());
        byte[] receiptBefore = Files.readAllBytes(receipt);

        UninstallService service = fixture.service(point -> {
            if (point.equals("remove-before-registry")) {
                throw new IOException("injected registration failure");
            }
        });
        assertThrows(IOException.class, () -> service.removeFromLauncher(fixture.record));

        assertArrayEquals(receiptBefore, Files.readAllBytes(receipt));
        assertEquals(fixture.record,
                fixture.store.resolve(fixture.store.read(fixture.registry), fixture.record.id()));
        assertTrue(Files.exists(fixture.root.resolve("MegaMek.jar")));
    }

    @Test
    void filesystemRootIsRejectedBeforeBackupPathConstruction() throws Exception {
        Fixture fixture = managed("root-rejection");
        InstallationRecord rootRecord = new InstallationRecord(
                fixture.record.id(), fixture.record.name(), fixture.root.getRoot().toString(),
                fixture.record.observedBuild(), fixture.record.products(), fixture.record.pin(),
                fixture.record.updateEligible(), fixture.record.registeredAt());

        IOException failure = assertThrows(IOException.class,
                () -> fixture.service(point -> { }).removeFromLauncher(rootRecord));

        assertEquals("installation root must not be a filesystem root", failure.getMessage());
        assertTrue(Files.exists(fixture.root.resolve("MegaMek.jar")));
    }

    @Test
    void abruptPreCommitFailureLeavesJournalAndRecoveryRollsBackExactly()
            throws Exception {
        Fixture fixture = managed("recovery");
        byte[] before = Files.readAllBytes(fixture.root.resolve("MegaMek.jar"));
        UninstallService crashing = fixture.service(point -> {
            if (point.equals("file-moved")) throw new SimulatedCrash();
        });
        UninstallService.Plan plan = crashing.plan(fixture.record, context());

        assertThrows(SimulatedCrash.class, () -> crashing.uninstall(
                plan, UninstallService.CONFIRMATION, context()));
        assertTrue(crashing.hasPending(fixture.record.id()));
        UpdatePreviewService.Eligibility eligibility = new UpdatePreviewService(
                new NoNetwork()).eligibility(fixture.registry, fixture.record.id());
        assertFalse(eligibility.available());
        assertTrue(eligibility.reason().contains("Uninstall recovery required"));

        UninstallService.RecoveryResult recovered =
                fixture.service(point -> { }).recover(fixture.record, context());
        assertFalse(recovered.committed());
        assertArrayEquals(before, Files.readAllBytes(fixture.root.resolve("MegaMek.jar")));
        assertEquals(fixture.record,
                fixture.store.resolve(fixture.store.read(fixture.registry), fixture.record.id()));
    }

    @Test
    void directoryRemovalPhaseIsDurableBeforeAnyKnownDirectoryIsDeleted()
            throws Exception {
        Fixture fixture = managed("directory-phase-start");
        Path knownEmptyDirectory = fixture.root.resolve("data");
        UninstallService crashing = fixture.service(point -> {
            if (point.equals("directory-removal-started")) throw new SimulatedCrash();
        });

        assertThrows(SimulatedCrash.class, () -> crashing.uninstall(
                crashing.plan(fixture.record, context()),
                UninstallService.CONFIRMATION, context()));

        assertTrue(Files.exists(knownEmptyDirectory));
        assertTrue(Files.readString(UninstallService.pendingJournalPath(
                fixture.registry, fixture.record.id()))
                .contains("\"phase\" : \"REMOVING_DIRECTORIES\""));
        UninstallService.RecoveryResult recovered =
                fixture.service(point -> { }).recover(fixture.record, context());
        assertFalse(recovered.committed());
        assertTrue(Files.exists(knownEmptyDirectory));
        assertTrue(Files.exists(fixture.root.resolve("MegaMek.jar")));
    }

    @Test
    void crashAfterKnownDirectoryDeletionRecoversFromRemovingDirectoriesPhase()
            throws Exception {
        Fixture fixture = managed("directory-phase-deleted");
        Path firstKnownEmptyDirectory = fixture.root.resolve("data");
        UninstallService crashing = fixture.service(point -> {
            if (point.equals("directory-removed")) throw new SimulatedCrash();
        });

        assertThrows(SimulatedCrash.class, () -> crashing.uninstall(
                crashing.plan(fixture.record, context()),
                UninstallService.CONFIRMATION, context()));

        assertFalse(Files.exists(firstKnownEmptyDirectory));
        assertTrue(Files.readString(UninstallService.pendingJournalPath(
                fixture.registry, fixture.record.id()))
                .contains("\"phase\" : \"REMOVING_DIRECTORIES\""));
        UninstallService.RecoveryResult recovered =
                fixture.service(point -> { }).recover(fixture.record, context());
        assertFalse(recovered.committed());
        assertTrue(Files.exists(firstKnownEmptyDirectory));
        assertTrue(Files.exists(fixture.root.resolve("MegaMek.jar")));
    }

    @Test
    void pendingUpdateCancellationAndConcurrentGateBlockBeforeMutation() throws Exception {
        Fixture fixture = managed("blocked");
        byte[] before = Files.readAllBytes(fixture.root.resolve("MegaMek.jar"));
        Files.createDirectory(fixture.root.resolve(RootCoordinator.UPDATE_NAMESPACE));
        assertThrows(IOException.class,
                () -> fixture.service(point -> { }).plan(fixture.record, context()));
        Files.delete(fixture.root.resolve(RootCoordinator.UPDATE_NAMESPACE));

        OperationContext cancelled = new OperationContext(OperationType.UNINSTALL);
        cancelled.requestCancellation();
        assertThrows(OperationCancelledException.class,
                () -> fixture.service(point -> { }).plan(fixture.record, cancelled));

        try (RootCoordinator.Lease ignored = new RootCoordinator(
                fixture.registry.resolveSibling("coordination"))
                .acquire(fixture.root, false)) {
            assertThrows(IOException.class,
                    () -> fixture.service(point -> { }).plan(fixture.record, context()));
        }
        assertArrayEquals(before, Files.readAllBytes(fixture.root.resolve("MegaMek.jar")));
        assertEquals(fixture.record,
                fixture.store.resolve(fixture.store.read(fixture.registry), fixture.record.id()));
    }

    @Test
    void postCommitCleanupFailureIsSuccessAndStartupScanFinishesIt() throws Exception {
        Fixture fixture = managed("cleanup-warning");
        UninstallService failing = fixture.service(point -> {
            if (point.equals("after-registration-commit")) {
                throw new IOException("simulated cleanup boundary");
            }
        });
        UninstallService.Result result = failing.uninstall(
                failing.plan(fixture.record, context()),
                UninstallService.CONFIRMATION, context());

        assertTrue(result.committed());
        assertTrue(result.warning().contains("could not be cleaned"));
        assertTrue(fixture.store.read(fixture.registry).installations().isEmpty());
        assertTrue(fixture.service(point -> { }).recoverCommitted().isEmpty());
        assertFalse(failing.hasPending(fixture.record.id()));
    }

    private Fixture managed(String name) throws Exception {
        Path root = suite(temp.resolve(name));
        Files.writeString(root.resolve("lib/runtime.txt"), "runtime");
        Files.writeString(root.resolve("docs/guide.txt"), "official");
        Files.createDirectories(root.resolve("custom"));
        Files.writeString(root.resolve("custom/user.txt"), "custom");
        Path registry = temp.resolve(name + "-registry.json");
        RegistryStore store = new RegistryStore();
        InstallationRecord record = store.register(registry, name, root, null);
        OwnershipPolicy.Build build =
                new OwnershipPolicy().build(root, OfficialRepository.MEGAMEK, "v1");
        OwnershipReceipt receipt = new ReceiptStore().write(registry, store.read(registry),
                record, OfficialRepository.MEGAMEK, "v1", "MegaMek-v1.tar.gz", 10,
                "sha256:" + "a".repeat(64), build);
        new ChannelPreferenceStore().initializeManaged(
                registry, record, receipt, FollowChannel.MILESTONE, false);
        return new Fixture(root, registry, store, record);
    }

    private static Path suite(Path root) throws Exception {
        Files.createDirectories(root.resolve("data"));
        Files.createDirectories(root.resolve("mmconf"));
        Files.createDirectories(root.resolve("lib"));
        Files.createDirectories(root.resolve("docs"));
        java.util.jar.Manifest manifest = new java.util.jar.Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "megamek.MegaMek");
        try (JarOutputStream output = new JarOutputStream(
                Files.newOutputStream(root.resolve("MegaMek.jar")), manifest)) {
            output.putNextEntry(new JarEntry("megamek/Version.properties"));
            output.write("major=1\nminor=0\npatch=0\n".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return root;
    }

    private static OperationContext context() {
        return OperationContext.none(OperationType.UNINSTALL);
    }

    private record Fixture(Path root, Path registry, RegistryStore store,
                           InstallationRecord record) {
        UninstallService service(UninstallService.FailureHook hook) {
            return new UninstallService(registry, store, new ReceiptStore(),
                    new ChannelPreferenceStore(), new InstallationInspector(),
                    new RootCoordinator(registry.resolveSibling("coordination")), hook);
        }
    }

    private static final class SimulatedCrash extends Error {
    }

    private static final class NoNetwork
            implements org.megamek.launcher.release.ReleaseTransport {
        @Override
        public Response get(java.net.URI uri, String accept) {
            throw new AssertionError("uninstall eligibility must not use the network");
        }
    }
}
