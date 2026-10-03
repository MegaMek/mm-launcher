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
import org.megamek.launcher.operation.OperationPhase;
import org.megamek.launcher.operation.OperationProgress;
import org.megamek.launcher.operation.OperationType;
import org.megamek.launcher.operation.ProgressUnit;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.release.OfficialRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

class UninstallServiceTest {
    @TempDir Path temp;

    @Test
    void confirmedUninstallScansOnceAndJournalPublicationCountIsPhaseBounded() throws Exception {
        for (int fileCount : new int[] {16, 2048}) {
            Fixture fixture = managed("many-files-" + fileCount, fileCount);
            AtomicInteger publications = new AtomicInteger();
            UninstallService service = fixture.service(point -> {
                if (point.equals("journal-published")) publications.incrementAndGet();
            });
            List<OperationProgress> progress = new ArrayList<>();
            OperationContext operation = new OperationContext(OperationType.UNINSTALL, progress::add);
            long started = System.nanoTime();
            UninstallService.Result result = service.uninstallConfirmed(
                    fixture.record, UninstallService.CONFIRMATION, operation);
            System.out.printf("Uninstall %d official files: %.3f seconds, %d durable journal publications%n",
                    fileCount + 3, (System.nanoTime() - started) / 1_000_000_000.0,
                    publications.get());
            List<OperationProgress> checks = progress.stream().filter(event ->
                    event.phase() == OperationPhase.PLAN && event.unit() == ProgressUnit.FILES
                            && event.cancellationAllowed()).toList();
            assertEquals(fileCount + 3, checks.size(), "each official entry must be compared once");
            assertEquals(1, progress.stream().filter(event ->
                    event.phase() == OperationPhase.PLAN && event.unit() == ProgressUnit.NONE).count(),
                    "there must be exactly one planning pass");
            for (int i = 0; i < checks.size(); i++) {
                assertEquals(i + 1, checks.get(i).completed());
                assertEquals(fileCount + 3, checks.get(i).total());
            }
            assertTrue(result.committed());
            assertNull(result.warning());
            assertEquals(6, publications.get(), "journal writes must be phase-bounded, not per-file");
            assertEquals("custom", Files.readString(fixture.root.resolve("custom/user.txt")));
            assertFalse(Files.exists(fixture.root.resolve("docs")));
        }
    }

    @Test
    void confirmedUninstallKeepsTheRootGateDuringPlanningAndRemoval() throws Exception {
        Fixture fixture = managed("single-lease");
        AtomicInteger blocked = new AtomicInteger();
        OperationContext operation = new OperationContext(OperationType.UNINSTALL, event -> {
            if (event.unit() == ProgressUnit.NONE
                    && (event.phase() == OperationPhase.PLAN
                    || event.phase() == OperationPhase.UNINSTALL)) {
                assertThrows(IOException.class, () -> new RootCoordinator(
                        fixture.registry.resolveSibling("coordination")).acquire(fixture.root, false));
                blocked.incrementAndGet();
            }
        });
        assertTrue(fixture.service(point -> {}).uninstallConfirmed(
                fixture.record, UninstallService.CONFIRMATION, operation).committed());
        assertEquals(2, blocked.get());
    }

    @Test
    void confirmedUninstallCancellationDuringPlanningDoesNotMoveFiles() throws Exception {
        Fixture fixture = managed("cancel-single-scan");
        byte[] before = Files.readAllBytes(fixture.root.resolve("MegaMek.jar"));
        OperationContext[] holder = new OperationContext[1];
        holder[0] = new OperationContext(OperationType.UNINSTALL, event -> {
            if (event.phase() == OperationPhase.PLAN && event.completed() == 1
                    && event.cancellationAllowed()) {
                assertTrue(holder[0].requestCancellation().accepted());
            }
        });
        assertThrows(OperationCancelledException.class, () -> fixture.service(point -> {})
                .uninstallConfirmed(fixture.record, UninstallService.CONFIRMATION, holder[0]));
        assertArrayEquals(before, Files.readAllBytes(fixture.root.resolve("MegaMek.jar")));
        assertEquals(List.of(fixture.record), fixture.store.read(fixture.registry).installations());
        assertFalse(fixture.service(point -> {}).hasPending(fixture.record.id()));
    }

    @Test
    void confirmedUninstallStillVerifiesFilesChangedAfterPlanningAndRollsBack() throws Exception {
        Fixture fixture = managed("changed-after-single-scan");
        byte[] jar = Files.readAllBytes(fixture.root.resolve("MegaMek.jar"));
        Path changed = fixture.root.resolve("lib/runtime.txt");
        OperationContext operation = new OperationContext(OperationType.UNINSTALL, event -> {
            if (event.phase() == OperationPhase.UNINSTALL && event.unit() == ProgressUnit.NONE) {
                try {
                    Files.writeString(changed, "changed after planning");
                } catch (IOException error) {
                    throw new java.io.UncheckedIOException(error);
                }
            }
        });
        assertThrows(IOException.class, () -> fixture.service(point -> {})
                .uninstallConfirmed(fixture.record, UninstallService.CONFIRMATION, operation));
        assertEquals("changed after planning", Files.readString(changed));
        assertArrayEquals(jar, Files.readAllBytes(fixture.root.resolve("MegaMek.jar")));
        assertEquals(List.of(fixture.record), fixture.store.read(fixture.registry).installations());
        assertFalse(fixture.service(point -> {}).hasPending(fixture.record.id()));
    }

    @Test
    void separatelyPreparedUninstallStillReplansAndRejectsChangedFiles() throws Exception {
        Fixture fixture = managed("prepared-plan-changed");
        UninstallService service = fixture.service(point -> {});
        UninstallService.Plan plan = service.plan(fixture.record, context());
        Files.writeString(fixture.root.resolve("docs/guide.txt"), "changed since preview");
        IOException failure = assertThrows(IOException.class, () ->
                service.uninstall(plan, UninstallService.CONFIRMATION, context()));
        assertTrue(failure.getMessage().contains("review uninstall again"));
        assertTrue(Files.exists(fixture.root.resolve("MegaMek.jar")));
        assertEquals("changed since preview", Files.readString(fixture.root.resolve("docs/guide.txt")));
        assertEquals(List.of(fixture.record), fixture.store.read(fixture.registry).installations());
        assertFalse(service.hasPending(fixture.record.id()));
    }

    @Test
    void confirmedUninstallRequiresConfirmationAndTheExactRegistration() throws Exception {
        Fixture fixture = managed("single-scan-authorization");
        UninstallService service = fixture.service(point -> {});
        assertThrows(IOException.class, () ->
                service.uninstallConfirmed(fixture.record, "", context()));
        InstallationRecord renamed = fixture.store.rename(fixture.registry, fixture.record, "Renamed");
        assertThrows(IOException.class, () ->
                service.uninstallConfirmed(fixture.record, UninstallService.CONFIRMATION, context()));
        assertEquals(List.of(renamed), fixture.store.read(fixture.registry).installations());
        assertTrue(Files.exists(fixture.root.resolve("MegaMek.jar")));
        assertFalse(service.hasPending(fixture.record.id()));
    }

    @Test
    void confirmedUninstallCrashKeepsTheJournalAndRecoversExactly() throws Exception {
        Fixture fixture = managed("single-scan-crash");
        byte[] jar = Files.readAllBytes(fixture.root.resolve("MegaMek.jar"));
        Path receipt = new ReceiptStore().receiptPath(fixture.registry, fixture.record.id());
        byte[] metadata = Files.readAllBytes(receipt);
        UninstallService crashing = fixture.service(point -> {
            if (point.equals("file-moved")) throw new SimulatedCrash();
        });
        assertThrows(SimulatedCrash.class, () -> crashing.uninstallConfirmed(
                fixture.record, UninstallService.CONFIRMATION, context()));
        assertTrue(crashing.hasPending(fixture.record.id()));
        assertFalse(fixture.service(point -> {}).recover(fixture.record, context()).committed());
        assertArrayEquals(jar, Files.readAllBytes(fixture.root.resolve("MegaMek.jar")));
        assertArrayEquals(metadata, Files.readAllBytes(receipt));
        assertEquals(List.of(fixture.record), fixture.store.read(fixture.registry).installations());
        assertFalse(crashing.hasPending(fixture.record.id()));
    }

    @Test
    void intentJournalRecoversBeforeAndAfterFileAndMetadataMovesAndRegistryCommit() throws Exception {
        for (String checkpoint : List.of("file-verified", "file-moved", "metadata-verified",
                "metadata-moved", "before-registration-commit")) {
            Fixture fixture = managed(checkpoint);
            byte[] jar = Files.readAllBytes(fixture.root.resolve("MegaMek.jar"));
            Path receipt = new ReceiptStore().receiptPath(fixture.registry, fixture.record.id());
            byte[] metadata = Files.readAllBytes(receipt);
            UninstallService crashing = fixture.service(point -> {
                if (point.equals(checkpoint)) throw new SimulatedCrash();
            });
            assertThrows(SimulatedCrash.class, () -> crashing.uninstall(
                    crashing.plan(fixture.record, context()), UninstallService.CONFIRMATION, context()));
            assertTrue(crashing.hasPending(fixture.record.id()));
            var recovered = fixture.service(point -> {}).recover(fixture.record, context());
            assertFalse(recovered.committed());
            assertArrayEquals(jar, Files.readAllBytes(fixture.root.resolve("MegaMek.jar")));
            assertArrayEquals(metadata, Files.readAllBytes(receipt));
            assertFalse(crashing.hasPending(fixture.record.id()));
        }
    }

    @Test
    void legacyPerFileMoveStatesStillRecoverAndUnexpectedDestinationEditsAreNeverOverwritten()
            throws Exception {
        Fixture fixture = managed("legacy-recovery");
        UninstallService crashing = fixture.service(point -> {
            if (point.equals("metadata-moved")) throw new SimulatedCrash();
        });
        assertThrows(SimulatedCrash.class, () -> crashing.uninstall(
                crashing.plan(fixture.record, context()), UninstallService.CONFIRMATION, context()));
        Path journal = UninstallService.pendingJournalPath(fixture.registry, fixture.record.id());
        String legacy = Files.readString(journal).replaceFirst("\"state\" : \"PLANNED\"", "\"state\" : \"MOVED\"")
                .replaceFirst("\"state\" : \"PLANNED\"", "\"state\" : \"MOVING\"");
        Files.writeString(journal, legacy);
        Path edited = Files.writeString(fixture.root.resolve("MegaMek.jar"), "unexpected edit");
        assertThrows(IOException.class, () -> fixture.service(point -> {}).recover(fixture.record, context()));
        assertEquals("unexpected edit", Files.readString(edited));
        assertTrue(Files.exists(journal));
        Files.delete(edited);
        fixture.service(point -> {}).recover(fixture.record, context());
        assertEquals(fixture.record,
                fixture.store.resolve(fixture.store.read(fixture.registry), fixture.record.id()));
        assertTrue(Files.size(fixture.root.resolve("MegaMek.jar")) > 0);
        assertFalse(Files.exists(journal));
    }

    @Test
    void crashAfterTransactionRemovesEmptyRootStillRestoresTheWholeInstallation() throws Exception {
        Fixture fixture = managed("empty-root-recovery");
        Files.delete(fixture.root.resolve("custom/user.txt"));
        Files.delete(fixture.root.resolve("custom"));
        byte[] jar = Files.readAllBytes(fixture.root.resolve("MegaMek.jar"));
        UninstallService crashing = fixture.service(point -> {
            if (point.equals("root-removed")) throw new SimulatedCrash();
        });
        assertThrows(SimulatedCrash.class, () -> crashing.uninstall(
                crashing.plan(fixture.record, context()), UninstallService.CONFIRMATION, context()));
        assertFalse(Files.exists(fixture.root));
        assertFalse(fixture.service(point -> {}).recover(fixture.record, context()).committed());
        assertArrayEquals(jar, Files.readAllBytes(fixture.root.resolve("MegaMek.jar")));
        assertEquals(List.of(fixture.record), fixture.store.read(fixture.registry).installations());
    }

    @Test
    void deletedManagedAndImportedRootsCanBeForgottenWithoutTouchingOtherInstallations() throws Exception {
        Fixture fixture = managed("deleted-managed");
        InstallationRecord other = fixture.store.register(
                fixture.registry, "Other", suite(temp.resolve("other")), null);
        deleteFixture(fixture.root);
        assertThrows(UninstallService.MissingInstallationException.class,
                () -> fixture.service(point -> {}).removeFromLauncher(fixture.record));
        var result = fixture.service(point -> {}).removeMissingFromLauncher(fixture.record);
        assertNull(result.warning());
        var data = fixture.store.read(fixture.registry);
        assertEquals(List.of(other), data.installations());
        assertEquals(other.id(), data.defaultInstallationId());
        assertEquals(other.id(), data.preferredInstallationIds().get("megamek"));
        assertFalse(Files.exists(fixture.root));
        assertTrue(Files.exists(Path.of(other.canonicalRoot()).resolve("MegaMek.jar")));
        assertFalse(Files.exists(new ReceiptStore().receiptPath(fixture.registry, fixture.record.id())));

        deleteFixture(Path.of(other.canonicalRoot()));
        fixture.service(point -> {}).removeMissingFromLauncher(other);
        assertTrue(fixture.store.read(fixture.registry).installations().isEmpty());
    }

    @Test
    void deletedAncestorsCanBeForgottenWithOrWithoutAnInterruptedUninstall() throws Exception {
        for (boolean interrupted : List.of(false, true)) {
            String name = "deleted-ancestors-" + interrupted;
            Path container = temp.resolve(name + "-release");
            Fixture fixture = managed(name, 0, container.resolve("payload").resolve("installation"));
            InstallationRecord retained = fixture.store.register(
                    fixture.registry, "Retained", suite(temp.resolve(name + "-retained")), null);
            if (interrupted) {
                UninstallService crashing = fixture.service(point -> {
                    if (point.equals("file-moved")) throw new SimulatedCrash();
                });
                assertThrows(SimulatedCrash.class, () -> crashing.uninstall(
                        crashing.plan(fixture.record, context()), UninstallService.CONFIRMATION, context()));
            }
            deleteFixture(container);
            assertThrows(UninstallService.MissingInstallationException.class,
                    () -> UninstallService.requirePresent(fixture.record));
            try (var ignored = new RootCoordinator(fixture.registry.resolveSibling("coordination"))
                    .acquireForRecovery(fixture.root, false)) {
                assertThrows(IOException.class,
                        () -> fixture.service(point -> {}).removeMissingFromLauncher(fixture.record));
            }
            assertNull(fixture.service(point -> {}).removeMissingFromLauncher(fixture.record).warning());
            assertEquals(List.of(retained), fixture.store.read(fixture.registry).installations());
            assertEquals(retained.id(),
                    fixture.store.read(fixture.registry).preferredInstallationIds().get("megamek"));
            assertFalse(Files.exists(container));
            assertTrue(Files.isRegularFile(Path.of(retained.canonicalRoot()).resolve("MegaMek.jar")));
            assertFalse(Files.exists(new ReceiptStore().receiptPath(fixture.registry, fixture.record.id())));
            assertFalse(Files.exists(UninstallService.pendingJournalPath(fixture.registry, fixture.record.id())));
            assertTrue(fixture.service(point -> {}).recoverCommitted().isEmpty());
        }
    }

    @Test
    void deletedRootAfterInterruptedUninstallCanBeForgottenAndOwnedBackupsCleaned() throws Exception {
        Fixture fixture = managed("deleted-mid-uninstall");
        UninstallService crashing = fixture.service(point -> {
            if (point.equals("file-moved")) throw new SimulatedCrash();
        });
        assertThrows(SimulatedCrash.class, () -> crashing.uninstall(
                crashing.plan(fixture.record, context()), UninstallService.CONFIRMATION, context()));
        Path journal = UninstallService.pendingJournalPath(fixture.registry, fixture.record.id());
        var saved = new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readAllBytes(journal));
        Path backup = Path.of(saved.get("rootBackup").asText());
        assertTrue(Files.exists(backup));
        deleteFixture(fixture.root);
        assertThrows(UninstallService.MissingInstallationException.class,
                () -> fixture.service(point -> {}).recover(fixture.record, context()));
        var result = fixture.service(point -> {}).removeMissingFromLauncher(fixture.record);
        assertNull(result.warning());
        assertFalse(Files.exists(fixture.root), "forgetting must not recreate the deleted folder");
        assertFalse(Files.exists(backup));
        assertFalse(Files.exists(journal));
        assertTrue(fixture.store.read(fixture.registry).installations().isEmpty());
        assertTrue(fixture.service(point -> {}).recoverCommitted().isEmpty());
    }

    @Test
    void staleRecordReappearingFolderAndActiveRootGateBlockMissingRemoval() throws Exception {
        Fixture fixture = managed("missing-revalidation");
        deleteFixture(fixture.root);
        InstallationRecord renamed = fixture.store.rename(fixture.registry, fixture.record, "Renamed");
        assertThrows(IOException.class,
                () -> fixture.service(point -> {}).removeMissingFromLauncher(fixture.record));
        try (var ignored = new RootCoordinator(fixture.registry.resolveSibling("coordination"))
                .acquireForRecovery(fixture.root, false)) {
            assertThrows(IOException.class,
                    () -> fixture.service(point -> {}).removeMissingFromLauncher(renamed));
        }
        Files.createDirectory(fixture.root);
        Path custom = Files.writeString(fixture.root.resolve("keep.txt"), "keep");
        assertThrows(IOException.class,
                () -> fixture.service(point -> {}).removeMissingFromLauncher(renamed));
        assertEquals("keep", Files.readString(custom));
        assertEquals(List.of(renamed), fixture.store.read(fixture.registry).installations());
    }

    @Test
    void failedMissingRemovalRestoresMetadataAndDoesNotDeleteAReappearingRoot() throws Exception {
        Path container = temp.resolve("missing-rollback-release");
        Fixture fixture = managed("missing-rollback", 0,
                container.resolve("payload").resolve("installation"));
        Path receipt = new ReceiptStore().receiptPath(fixture.registry, fixture.record.id());
        byte[] before = Files.readAllBytes(receipt);
        deleteFixture(container);
        UninstallService racing = fixture.service(point -> {
            if (point.equals("remove-before-registry")) {
                Files.createDirectories(fixture.root);
                Files.writeString(fixture.root.resolve("new.txt"), "do not delete");
            }
        });
        assertThrows(IOException.class, () -> racing.removeMissingFromLauncher(fixture.record));
        assertArrayEquals(before, Files.readAllBytes(receipt));
        assertEquals(List.of(fixture.record), fixture.store.read(fixture.registry).installations());
        assertEquals("do not delete", Files.readString(fixture.root.resolve("new.txt")));
    }

    @Test
    void corruptPendingJournalCannotAuthorizeMissingRegistrationRemoval() throws Exception {
        Fixture fixture = managed("corrupt-missing-journal");
        Path journal = UninstallService.pendingJournalPath(fixture.registry, fixture.record.id());
        Files.createDirectories(journal.getParent());
        Files.writeString(journal, "{corrupt");
        deleteFixture(fixture.root);
        assertThrows(IOException.class,
                () -> fixture.service(point -> {}).removeMissingFromLauncher(fixture.record));
        assertEquals(List.of(fixture.record), fixture.store.read(fixture.registry).installations());
        assertEquals("{corrupt", Files.readString(journal));
    }

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

        UninstallService.Result result = service.uninstallConfirmed(
                fixture.record, UninstallService.CONFIRMATION, context());

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
        assertThrows(IOException.class, () -> importedService.uninstallConfirmed(
                imported, UninstallService.CONFIRMATION, context()));
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
    void registrationRemovalIntentRestoresMetadataAfterEveryPreCommitCrash() throws Exception {
        for (boolean missing : List.of(false, true)) {
            for (String checkpoint : List.of("remove-journal-published", "remove-metadata-verified",
                    "remove-metadata-moved", "remove-before-registry")) {
                Fixture fixture = managed("remove-crash-" + missing + "-" + checkpoint);
                Path receipt = new ReceiptStore().receiptPath(fixture.registry, fixture.record.id());
                byte[] before = Files.readAllBytes(receipt);
                if (missing) deleteFixture(fixture.root);
                UninstallService crashing = fixture.service(point -> {
                    if (point.equals(checkpoint)) throw new SimulatedCrash();
                });
                assertThrows(SimulatedCrash.class, () -> {
                    if (missing) crashing.removeMissingFromLauncher(fixture.record);
                    else crashing.removeFromLauncher(fixture.record);
                });
                assertEquals(List.of(fixture.record), fixture.store.read(fixture.registry).installations());
                assertTrue(crashing.hasPending(fixture.record.id()));
                assertThrows(IOException.class, () ->
                        new RealUpdateService(new NoNetwork()).snapshot(fixture.registry, fixture.record.id()));
                assertFalse(new UpdatePreviewService(new NoNetwork())
                        .eligibility(fixture.registry, fixture.record.id()).available());
                assertTrue(fixture.service(point -> {}).recoverCommitted().isEmpty());
                assertArrayEquals(before, Files.readAllBytes(receipt));
                assertEquals(!missing, Files.exists(fixture.root));
                assertFalse(Files.exists(removalTransaction(fixture)));
                var retry = missing
                        ? fixture.service(point -> {}).removeMissingFromLauncher(fixture.record)
                        : fixture.service(point -> {}).removeFromLauncher(fixture.record);
                assertNull(retry.warning());
                assertTrue(fixture.store.read(fixture.registry).installations().isEmpty());
            }
        }
    }

    @Test
    void explicitRegistrationRemovalRecoveryRestoresSidecarsWithoutRecreatingMissingRoot() throws Exception {
        Fixture fixture = managed("remove-explicit-recovery");
        Path receipt = new ReceiptStore().receiptPath(fixture.registry, fixture.record.id());
        byte[] before = Files.readAllBytes(receipt);
        deleteFixture(fixture.root);
        UninstallService crashing = fixture.service(point -> {
            if (point.equals("remove-metadata-moved")) throw new SimulatedCrash();
        });
        assertThrows(SimulatedCrash.class, () -> crashing.removeMissingFromLauncher(fixture.record));
        var result = fixture.service(point -> {}).recover(fixture.record, context());
        assertFalse(result.committed());
        assertNull(result.warning());
        assertArrayEquals(before, Files.readAllBytes(receipt));
        assertFalse(Files.exists(fixture.root));
        assertFalse(crashing.hasPending(fixture.record.id()));
        assertEquals(List.of(fixture.record), fixture.store.read(fixture.registry).installations());
    }

    @Test
    void interruptedMissingRemovalCanBeRetriedWithoutRestartAndNeverRecreatesRoot() throws Exception {
        Fixture fixture = managed("remove-retry-with-pending-uninstall");
        UninstallService uninstall = fixture.service(point -> {
            if (point.equals("file-moved")) throw new SimulatedCrash();
        });
        assertThrows(SimulatedCrash.class, () -> uninstall.uninstall(
                uninstall.plan(fixture.record, context()), UninstallService.CONFIRMATION, context()));
        deleteFixture(fixture.root);
        UninstallService removal = fixture.service(point -> {
            if (point.equals("remove-before-registry")) throw new SimulatedCrash();
        });
        assertThrows(SimulatedCrash.class, () -> removal.removeMissingFromLauncher(fixture.record));
        assertNull(fixture.service(point -> {}).removeMissingFromLauncher(fixture.record).warning());
        assertFalse(Files.exists(fixture.root));
        assertFalse(Files.exists(removalTransaction(fixture)));
        assertFalse(fixture.service(point -> {}).hasPending(fixture.record.id()));
        assertTrue(fixture.service(point -> {}).recoverCommitted().isEmpty());
    }

    @Test
    void registrationRemovalRecoveryRefusesUnexpectedMetadataEditsAndCorruptIntent() throws Exception {
        Fixture fixture = managed("remove-unexpected-edit");
        Path receipt = new ReceiptStore().receiptPath(fixture.registry, fixture.record.id());
        byte[] before = Files.readAllBytes(receipt);
        UninstallService crashing = fixture.service(point -> {
            if (point.equals("remove-metadata-moved")) throw new SimulatedCrash();
        });
        assertThrows(SimulatedCrash.class, () -> crashing.removeFromLauncher(fixture.record));
        Files.writeString(receipt, "unexpected edit");
        assertThrows(IOException.class, () -> fixture.service(point -> {}).recoverCommitted());
        assertEquals("unexpected edit", Files.readString(receipt));
        assertTrue(Files.exists(removalTransaction(fixture).resolve("journal.json")));
        Files.delete(receipt);
        assertTrue(fixture.service(point -> {}).recoverCommitted().isEmpty());
        assertArrayEquals(before, Files.readAllBytes(receipt));

        Fixture corrupt = managed("remove-corrupt-intent");
        UninstallService removal = corrupt.service(point -> {
            if (point.equals("remove-metadata-moved")) throw new SimulatedCrash();
        });
        assertThrows(SimulatedCrash.class, () -> removal.removeFromLauncher(corrupt.record));
        Path journal = removalTransaction(corrupt).resolve("journal.json");
        String valid = Files.readString(journal);
        Files.writeString(journal, valid.replace(corrupt.record.id() + ".json", "../unexpected.json"));
        assertThrows(IOException.class, () -> corrupt.service(point -> {}).recoverCommitted());
        assertEquals(List.of(corrupt.record), corrupt.store.read(corrupt.registry).installations());
    }

    @Test
    void committedRegistrationRemovalCleansOnStartupWithOrWithoutRemainingJournal() throws Exception {
        for (String checkpoint : List.of("remove-after-registry", "remove-cleanup-journal-deleted")) {
            Fixture fixture = managed(checkpoint);
            deleteFixture(fixture.root);
            UninstallService crashing = fixture.service(point -> {
                if (point.equals(checkpoint)) throw new SimulatedCrash();
            });
            assertThrows(SimulatedCrash.class, () -> crashing.removeMissingFromLauncher(fixture.record));
            assertTrue(fixture.store.read(fixture.registry).installations().isEmpty());
            assertTrue(Files.exists(removalTransaction(fixture)));
            assertTrue(fixture.service(point -> {}).recoverCommitted().isEmpty());
            assertFalse(Files.exists(removalTransaction(fixture)));
            assertFalse(Files.exists(fixture.root));
        }
    }

    @Test
    void cleanupInterruptedAfterJournalDeletionIsSafelyFinalizedOnlyForAnEmptyTransaction()
            throws Exception {
        for (boolean abrupt : List.of(false, true)) {
            Fixture fixture = managed("journal-deleted-" + abrupt);
            UninstallService failing = fixture.service(point -> {
                if (point.equals("cleanup-journal-deleted")) {
                    if (abrupt) throw new SimulatedCrash();
                    throw new IOException("interrupted final namespace removal");
                }
            });
            if (abrupt) {
                assertThrows(SimulatedCrash.class, () -> failing.uninstall(
                        failing.plan(fixture.record, context()), UninstallService.CONFIRMATION, context()));
            } else {
                var result = failing.uninstall(failing.plan(fixture.record, context()),
                        UninstallService.CONFIRMATION, context());
                assertTrue(result.committed());
                assertTrue(result.warning().contains("could not be cleaned"));
            }
            Path transaction = UninstallService.pendingJournalPath(
                    fixture.registry, fixture.record.id()).getParent();
            assertFalse(Files.exists(transaction.resolve("journal.json")));
            assertTrue(Files.isDirectory(transaction));
            assertTrue(fixture.store.read(fixture.registry).installations().isEmpty());
            Path unexpected = Files.writeString(transaction.resolve("unexpected.txt"), "keep");
            assertThrows(IOException.class, () -> fixture.service(point -> {}).recoverCommitted());
            assertEquals("keep", Files.readString(unexpected));
            Files.delete(unexpected);
            assertTrue(fixture.service(point -> {}).recoverCommitted().isEmpty());
            assertFalse(Files.exists(transaction));
            assertTrue(fixture.service(point -> {}).recoverCommitted().isEmpty());
        }
    }

    private Path removalTransaction(Fixture fixture) {
        return new ReceiptStore().metadataDirectory(fixture.registry)
                .resolve("registration-removal-v1").resolve(fixture.record.id());
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
        assertThrows(IOException.class, () -> fixture.service(point -> {}).uninstallConfirmed(
                fixture.record, UninstallService.CONFIRMATION, context()));
        Files.delete(fixture.root.resolve(RootCoordinator.UPDATE_NAMESPACE));

        OperationContext cancelled = new OperationContext(OperationType.UNINSTALL);
        cancelled.requestCancellation();
        assertThrows(OperationCancelledException.class,
                () -> fixture.service(point -> { }).plan(fixture.record, cancelled));
        assertThrows(OperationCancelledException.class, () -> fixture.service(point -> {}).uninstallConfirmed(
                fixture.record, UninstallService.CONFIRMATION, cancelled));

        try (RootCoordinator.Lease ignored = new RootCoordinator(
                fixture.registry.resolveSibling("coordination"))
                .acquire(fixture.root, false)) {
            assertThrows(IOException.class,
                    () -> fixture.service(point -> { }).plan(fixture.record, context()));
            assertThrows(IOException.class, () -> fixture.service(point -> {}).uninstallConfirmed(
                    fixture.record, UninstallService.CONFIRMATION, context()));
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
        return managed(name, 0);
    }

    private Fixture managed(String name, int fileCount) throws Exception {
        return managed(name, fileCount, temp.resolve(name));
    }

    private Fixture managed(String name, int fileCount, Path installationRoot) throws Exception {
        Path root = suite(installationRoot);
        for (int i = 0; i < fileCount; i++) {
            Path directory = Files.createDirectories(root.resolve("docs/bucket-" + (i % 64)));
            Files.writeString(directory.resolve("file-" + i + ".txt"), "official file " + i);
        }
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

    private void deleteFixture(Path root) throws IOException {
        assertTrue(root.startsWith(temp) && !root.equals(temp), "only delete this test's installation fixture");
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
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
