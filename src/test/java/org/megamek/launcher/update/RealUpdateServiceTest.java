package org.megamek.launcher.update;

import org.megamek.launcher.channel.ChannelPreferenceStore;
import org.megamek.launcher.channel.FollowChannel;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.megamek.launcher.gui.LauncherServices;
import org.megamek.launcher.launch.RootCoordinator;
import org.megamek.launcher.launch.ApplicationLauncher;
import org.megamek.launcher.launch.JavaRuntime;
import org.megamek.launcher.launch.ProcessRunner;
import org.megamek.launcher.onboarding.InstallationInspector;
import org.megamek.launcher.operation.OperationCancelledException;
import org.megamek.launcher.operation.OperationContext;
import org.megamek.launcher.operation.OperationPhase;
import org.megamek.launcher.operation.OperationType;
import org.megamek.launcher.plan.Action;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryData;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.release.FreshInstaller;
import org.megamek.launcher.release.OfficialRepository;
import org.megamek.launcher.release.ReleaseTransport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RealUpdateServiceTest {
    @TempDir Path temp;

    @Test
    void preparedFacadeDownloadsOneBodyReextractsTrustedBytesAndReplansLocalData()
            throws Exception {
        Fixture fixture = install("prepared-once", "v1", packageFiles(1, Map.of(
                "lib/runtime.txt", bytes("runtime-1"),
                "docs/change.txt", bytes("old"))));
        Files.writeString(fixture.root.resolve("unknown.local"), "unknown");
        Files.writeString(fixture.root.resolve("mmconf/user.cfg"), "user-config");
        byte[] targetArchive = archive("MegaMek-v2", packageFiles(2, Map.of(
                "lib/runtime.txt", bytes("runtime-2"),
                "docs/change.txt", bytes("new"))));
        PackageTransport network = preparedTransport("v2", targetArchive);
        RootCoordinator coordinator = new RootCoordinator(temp.resolve("prepared-coordination"));
        LauncherServices services = new LauncherServices(fixture.registry, new RegistryStore(),
                new InstallationInspector(), network, new JavaRuntime(),
                new ApplicationLauncher(), coordinator);
        RegistryStore store = new RegistryStore();
        RegistryData beforeData = store.read(fixture.registry);
        InstallationRecord record = store.resolve(beforeData, fixture.id);
        ReceiptStore receiptStore = new ReceiptStore();
        OwnershipReceipt receipt = receiptStore.read(fixture.registry, beforeData, record);
        Path receiptFile = fixture.registry.resolveSibling(
                fixture.registry.getFileName() + ".metadata").resolve(fixture.id + ".json");
        byte[] registryBefore = Files.readAllBytes(fixture.registry);
        byte[] receiptBefore = Files.readAllBytes(receiptFile);
        var registryTime = Files.getLastModifiedTime(fixture.registry);
        var receiptTime = Files.getLastModifiedTime(receiptFile);
        byte[] runtimeBefore = Files.readAllBytes(fixture.root.resolve("lib/runtime.txt"));
        String digest = "sha256:" + sha(targetArchive);

        PreparedUpdate prepared = services.prepareUpdate(record, receipt,
                CurrentUpdateState.initial(receipt), "v2",
                "MegaMek-v2.tar.gz", targetArchive.length, digest, quiet());

        assertEquals(1, network.binaryRequests);
        assertEquals(targetArchive.length, network.binaryBytes);
        assertThrows(UnsupportedOperationException.class,
                () -> prepared.preview().targetManifest().files().clear(),
                "the displayed report must not mutate trusted expected hashes");
        assertArrayEquals(registryBefore, Files.readAllBytes(fixture.registry));
        assertArrayEquals(receiptBefore, Files.readAllBytes(receiptFile));
        assertEquals(registryTime, Files.getLastModifiedTime(fixture.registry));
        assertEquals(receiptTime, Files.getLastModifiedTime(receiptFile));
        assertArrayEquals(runtimeBefore, Files.readAllBytes(
                fixture.root.resolve("lib/runtime.txt")));
        assertFalse(Files.exists(receiptFile.resolveSibling(
                fixture.id + ".current.json")));

        // The preview extraction is observational only. Apply must reconstruct from the retained,
        // re-verified archive instead of blessing this changed JAR.
        Files.writeString(prepared.workspace().extracted().resolve("MegaMek.jar"), "tampered");
        // The local plan is also fresh at Apply time.
        Files.writeString(fixture.root.resolve("data/default.txt"), "user-after-preview");

        RealUpdateService.ApplyResult result = services.applyPrepared(
                prepared, RealUpdateService.CONFIRM, quiet());

        assertEquals("v2", result.state().tag());
        assertEquals("2.0.0", result.record().observedBuild());
        assertEquals("runtime-2", Files.readString(
                fixture.root.resolve("lib/runtime.txt")));
        assertEquals("new", Files.readString(fixture.root.resolve("docs/change.txt")));
        assertEquals("user-after-preview", Files.readString(
                fixture.root.resolve("data/default.txt")));
        assertEquals("unknown", Files.readString(fixture.root.resolve("unknown.local")));
        assertEquals("user-config", Files.readString(fixture.root.resolve("mmconf/user.cfg")));
        assertEquals(1, network.binaryRequests,
                "prepare plus Apply must issue exactly one package body request");
        assertEquals(targetArchive.length, network.binaryBytes);
        assertEquals(2, network.metadataRequests,
                "Apply may refresh metadata but must not fetch another package");
        assertThrows(IllegalStateException.class, prepared::preview);
        assertThrows(IOException.class, () -> services.applyPrepared(
                prepared, RealUpdateService.CONFIRM, quiet()));
    }

    @Test
    void preparedCancellationDuringHashExtractionAndPlanningWritesNoUpdateNamespace()
            throws Exception {
        for (String checkpoint : List.of("Re-verified", "Extracted", "Planned")) {
            Fixture fixture = install("cancel-" + checkpoint.toLowerCase(Locale.ROOT), "v1",
                    packageFiles(1, Map.of("lib/runtime.txt", bytes("runtime-1"))));
            byte[] targetArchive = archive("MegaMek-v2", packageFiles(2, Map.of(
                    "lib/runtime.txt", bytes("runtime-2"))));
            PackageTransport network = preparedTransport("v2", targetArchive);
            LauncherServices services = new LauncherServices(
                    fixture.registry, new RegistryStore(), new InstallationInspector(), network,
                    new JavaRuntime(), new ApplicationLauncher(),
                    new RootCoordinator(temp.resolve("coord-" + checkpoint)));
            RegistryData data = new RegistryStore().read(fixture.registry);
            InstallationRecord record = new RegistryStore().resolve(data, fixture.id);
            OwnershipReceipt receipt = new ReceiptStore().read(
                    fixture.registry, data, record);
            PreparedUpdate prepared = services.prepareUpdate(record, receipt,
                    CurrentUpdateState.initial(receipt), "v2", "MegaMek-v2.tar.gz",
                    targetArchive.length, "sha256:" + sha(targetArchive), quiet());
            AtomicReference<OperationContext> reference = new AtomicReference<>();
            OperationContext context = new OperationContext(OperationType.UPDATE_APPLY, event -> {
                if (event.phase() == OperationPhase.PREPARE_INSTALL
                        && event.detail().startsWith(checkpoint)
                        && event.cancellationAllowed()) {
                    reference.get().requestCancellation();
                }
            });
            reference.set(context);

            assertThrows(OperationCancelledException.class, () -> services.applyPrepared(
                    prepared, RealUpdateService.CONFIRM, quiet(), context), checkpoint);
            assertFalse(Files.exists(fixture.root.resolve(RootCoordinator.UPDATE_NAMESPACE)),
                    checkpoint);
            assertEquals("runtime-1",
                    Files.readString(fixture.root.resolve("lib/runtime.txt")), checkpoint);
            assertEquals(1, network.binaryRequests,
                    "cancelled prepared Apply must not download a second package body");
        }
    }

    @Test
    void actualTransactionBoundaryLetsCancelWinOrDeniesItWithoutInterruptingCommit()
            throws Exception {
        Fixture cancelled = install("boundary-cancel", "v1", packageFiles(1, Map.of(
                "lib/runtime.txt", bytes("runtime-1"))));
        byte[] targetArchive = archive("MegaMek-v2", packageFiles(2, Map.of(
                "lib/runtime.txt", bytes("runtime-2"))));
        PackageTransport cancelledNetwork = preparedTransport("v2", targetArchive);
        LauncherServices cancelledServices = new LauncherServices(
                cancelled.registry, new RegistryStore(), new InstallationInspector(),
                cancelledNetwork, new JavaRuntime(), new ApplicationLauncher(),
                new RootCoordinator(temp.resolve("boundary-cancel-coordination")));
        RegistryData cancelledData = new RegistryStore().read(cancelled.registry);
        InstallationRecord cancelledRecord =
                new RegistryStore().resolve(cancelledData, cancelled.id);
        OwnershipReceipt cancelledReceipt = new ReceiptStore().read(
                cancelled.registry, cancelledData, cancelledRecord);
        PreparedUpdate cancelledPrepared = cancelledServices.prepareUpdate(
                cancelledRecord, cancelledReceipt, CurrentUpdateState.initial(cancelledReceipt),
                "v2", "MegaMek-v2.tar.gz", targetArchive.length,
                "sha256:" + sha(targetArchive), quiet());
        AtomicReference<OperationContext> cancellingReference = new AtomicReference<>();
        AtomicReference<OperationContext.CancellationRequest> winningRequest =
                new AtomicReference<>();
        OperationContext cancelling = new OperationContext(OperationType.UPDATE_APPLY, event -> {
            if (event.phase() == OperationPhase.APPLY && event.cancellationAllowed()
                    && winningRequest.get() == null) {
                winningRequest.set(cancellingReference.get().requestCancellation());
            }
        });
        cancellingReference.set(cancelling);

        assertThrows(OperationCancelledException.class, () -> cancelledServices.applyPrepared(
                cancelledPrepared, RealUpdateService.CONFIRM, quiet(), cancelling));
        assertTrue(winningRequest.get().accepted());
        assertFalse(Files.exists(cancelled.root.resolve(RootCoordinator.UPDATE_NAMESPACE)));
        assertEquals("runtime-1",
                Files.readString(cancelled.root.resolve("lib/runtime.txt")));

        Fixture committed = install("boundary-commit", "v1", packageFiles(1, Map.of(
                "lib/runtime.txt", bytes("runtime-1"))));
        PackageTransport committedNetwork = preparedTransport("v2", targetArchive);
        LauncherServices committedServices = new LauncherServices(
                committed.registry, new RegistryStore(), new InstallationInspector(),
                committedNetwork, new JavaRuntime(), new ApplicationLauncher(),
                new RootCoordinator(temp.resolve("boundary-commit-coordination")));
        RegistryData committedData = new RegistryStore().read(committed.registry);
        InstallationRecord committedRecord =
                new RegistryStore().resolve(committedData, committed.id);
        OwnershipReceipt committedReceipt = new ReceiptStore().read(
                committed.registry, committedData, committedRecord);
        PreparedUpdate committedPrepared = committedServices.prepareUpdate(
                committedRecord, committedReceipt, CurrentUpdateState.initial(committedReceipt),
                "v2", "MegaMek-v2.tar.gz", targetArchive.length,
                "sha256:" + sha(targetArchive), quiet());
        AtomicReference<OperationContext> committedReference = new AtomicReference<>();
        AtomicReference<OperationContext.CancellationRequest> deniedRequest =
                new AtomicReference<>();
        OperationContext finalizing = new OperationContext(OperationType.UPDATE_APPLY, event -> {
            if (event.phase() == OperationPhase.APPLY && !event.cancellationAllowed()
                    && deniedRequest.get() == null) {
                deniedRequest.set(committedReference.get().requestCancellation());
            }
        });
        committedReference.set(finalizing);

        RealUpdateService.ApplyResult result = committedServices.applyPrepared(
                committedPrepared, RealUpdateService.CONFIRM, quiet(), finalizing);

        assertEquals("v2", result.state().tag());
        assertFalse(deniedRequest.get().accepted());
        assertTrue(deniedRequest.get().reason().contains("transaction"));
        assertFalse(Thread.currentThread().isInterrupted());
        assertEquals("runtime-2",
                Files.readString(committed.root.resolve("lib/runtime.txt")));
        assertFalse(Files.exists(committed.root.resolve(RootCoordinator.UPDATE_NAMESPACE)));
        assertEquals(1, committedNetwork.binaryRequests);
    }

    @Test
    void recoveryDisablesCancellationBeforeInspectingOrCleaningPendingState()
            throws Exception {
        Fixture fixture = install("recovery-cutoff", "v1", packageFiles(1, Map.of(
                "lib/runtime.txt", bytes("runtime-1"))));
        AtomicReference<OperationContext> reference = new AtomicReference<>();
        AtomicReference<OperationContext.CancellationRequest> request =
                new AtomicReference<>();
        OperationContext context = new OperationContext(OperationType.RECOVERY, event -> {
            if (event.phase() == OperationPhase.RECOVER && !event.cancellationAllowed()
                    && request.get() == null) {
                request.set(reference.get().requestCancellation());
            }
        });
        reference.set(context);

        RealUpdateService.RecoveryResult result = new RealUpdateService(
                new PackageTransport(),
                new RootCoordinator(temp.resolve("recovery-cutoff-coordination")))
                .recover(fixture.registry, fixture.id, RealUpdateService.CONFIRM, context);

        assertEquals("no pending transaction", result.outcome());
        assertFalse(request.get().accepted());
        assertTrue(request.get().reason().contains("Recovery"));
        assertFalse(Thread.currentThread().isInterrupted());
        assertEquals("runtime-1",
                Files.readString(fixture.root.resolve("lib/runtime.txt")));
    }

    @Test
    void retainedArchiveTamperAndHeldRootGateFailWithoutAnotherBodyOrApplicationWrite()
            throws Exception {
        Fixture tamper = install("prepared-tamper", "v1", packageFiles(1, Map.of(
                "lib/runtime.txt", bytes("runtime-1"),
                "docs/change.txt", bytes("old"))));
        byte[] targetArchive = archive("MegaMek-v2", packageFiles(2, Map.of(
                "lib/runtime.txt", bytes("runtime-2"),
                "docs/change.txt", bytes("new"))));
        PackageTransport tamperNetwork = preparedTransport("v2", targetArchive);
        PreparedUpdateService tamperService = new PreparedUpdateService(tamperNetwork,
                new RootCoordinator(temp.resolve("tamper-coordination")));
        RegistryData tamperData = new RegistryStore().read(tamper.registry);
        InstallationRecord tamperRecord = new RegistryStore().resolve(tamperData, tamper.id);
        OwnershipReceipt tamperReceipt = new ReceiptStore().read(
                tamper.registry, tamperData, tamperRecord);
        PreparedUpdate changed = tamperService.prepare(tamper.registry, tamperRecord,
                tamperReceipt, CurrentUpdateState.initial(tamperReceipt), "v2",
                "MegaMek-v2.tar.gz", targetArchive.length,
                "sha256:" + sha(targetArchive), quiet());
        Files.write(changed.workspace().staging().resolve("package.tar.gz"),
                bytes("not the retained archive"));
        IOException changedFailure = assertThrows(IOException.class, () ->
                tamperService.apply(changed, RealUpdateService.CONFIRM, quiet()));
        assertTrue(changedFailure.getMessage().contains("retained package"));
        assertEquals("runtime-1", Files.readString(tamper.root.resolve("lib/runtime.txt")));
        assertEquals("old", Files.readString(tamper.root.resolve("docs/change.txt")));
        assertEquals(1, tamperNetwork.binaryRequests);

        Fixture gated = install("prepared-gated", "v1", packageFiles(1, Map.of(
                "lib/runtime.txt", bytes("runtime-1"),
                "docs/change.txt", bytes("old"))));
        PackageTransport gateNetwork = preparedTransport("v2", targetArchive);
        RootCoordinator gateCoordinator =
                new RootCoordinator(temp.resolve("gate-coordination"));
        PreparedUpdateService gateService =
                new PreparedUpdateService(gateNetwork, gateCoordinator);
        RegistryData gateData = new RegistryStore().read(gated.registry);
        InstallationRecord gateRecord = new RegistryStore().resolve(gateData, gated.id);
        OwnershipReceipt gateReceipt = new ReceiptStore().read(
                gated.registry, gateData, gateRecord);
        PreparedUpdate blocked = gateService.prepare(gated.registry, gateRecord, gateReceipt,
                CurrentUpdateState.initial(gateReceipt), "v2",
                "MegaMek-v2.tar.gz", targetArchive.length,
                "sha256:" + sha(targetArchive), quiet());
        PreparedUpdateService wrongFacade = new PreparedUpdateService(gateNetwork,
                gateCoordinator);
        IOException wrong = assertThrows(IOException.class, () ->
                wrongFacade.apply(blocked, RealUpdateService.CONFIRM, quiet()));
        assertTrue(wrong.getMessage().contains("different launcher service"));
        assertEquals("v2", blocked.preview().targetRelease().tag(),
                "wrong-facade rejection must not consume the rightful owner's handle");
        try (RootCoordinator.Lease ignored = gateCoordinator.acquire(gated.root, false)) {
            IOException held = assertThrows(IOException.class, () ->
                    gateService.apply(blocked, RealUpdateService.CONFIRM, quiet()));
            assertTrue(held.getMessage().contains("another launch")
                    || held.getMessage().contains("active"));
        }
        assertEquals(1, gateNetwork.binaryRequests);
        assertEquals(1, gateNetwork.metadataRequests,
                "a held root gate must reject before Apply metadata refresh");
        assertEquals("runtime-1", Files.readString(gated.root.resolve("lib/runtime.txt")));
    }

    @Test
    void committedPreparedApplyReportsCleanupFailureTruthfullyAndAllowsExactRetry()
            throws Exception {
        Fixture fixture = install("prepared-cleanup", "v1", packageFiles(1, Map.of(
                "lib/runtime.txt", bytes("runtime-1"),
                "docs/change.txt", bytes("old"))));
        byte[] targetArchive = archive("MegaMek-v2", packageFiles(2, Map.of(
                "lib/runtime.txt", bytes("runtime-2"),
                "docs/change.txt", bytes("new"))));
        PackageTransport network = preparedTransport("v2", targetArchive);
        PreparedUpdateService service = new PreparedUpdateService(network,
                new RootCoordinator(temp.resolve("cleanup-coordination")));
        RegistryData data = new RegistryStore().read(fixture.registry);
        InstallationRecord record = new RegistryStore().resolve(data, fixture.id);
        OwnershipReceipt receipt = new ReceiptStore().read(fixture.registry, data, record);
        PreparedUpdate prepared = service.prepare(fixture.registry, record, receipt,
                CurrentUpdateState.initial(receipt), "v2",
                "MegaMek-v2.tar.gz", targetArchive.length,
                "sha256:" + sha(targetArchive), quiet());
        Path outside = Files.createDirectory(temp.resolve("cleanup-outside"));
        Path link = prepared.workspace().extracted().resolve("cleanup-link");
        createDirectoryLink(link, outside);

        RealUpdateService.ApplyResult result =
                service.apply(prepared, RealUpdateService.CONFIRM, quiet());

        assertEquals("v2", result.state().tag());
        assertTrue(result.cleanupWarning().contains("Update completed"));
        assertTrue(result.cleanupWarning().contains("do not retry Apply"));
        assertEquals("runtime-2", Files.readString(fixture.root.resolve("lib/runtime.txt")));
        assertFalse(Files.exists(fixture.root.resolve(RootCoordinator.UPDATE_NAMESPACE)));
        assertEquals(1, network.binaryRequests);
        Files.delete(link);
        prepared.close();
        prepared.close();
        assertFalse(Files.exists(prepared.workspace().staging()));
        assertTrue(Files.exists(outside),
                "workspace cleanup must never remove a linked external directory");
    }

    @Test
    void preparedSourceMetadataPendingAndRuntimeDriftNeverTriggerAnotherPackageBody()
            throws Exception {
        byte[] targetArchive = archive("MegaMek-v2", packageFiles(2, Map.of(
                "lib/runtime.txt", bytes("runtime-2"),
                "docs/change.txt", bytes("new"))));
        String digest = "sha256:" + sha(targetArchive);

        Fixture source = install("prepared-source-drift", "v1", packageFiles(1, Map.of(
                "lib/runtime.txt", bytes("runtime-1"),
                "docs/change.txt", bytes("old"))));
        PackageTransport sourceNetwork = preparedTransport("v2", targetArchive);
        PreparedUpdateService sourceService = new PreparedUpdateService(sourceNetwork,
                new RootCoordinator(temp.resolve("source-drift-coordination")));
        RegistryStore sourceStore = new RegistryStore();
        RegistryData sourceData = sourceStore.read(source.registry);
        InstallationRecord sourceRecord = sourceStore.resolve(sourceData, source.id);
        OwnershipReceipt sourceReceipt =
                new ReceiptStore().read(source.registry, sourceData, sourceRecord);
        PreparedUpdate sourcePrepared = sourceService.prepare(source.registry, sourceRecord,
                sourceReceipt, CurrentUpdateState.initial(sourceReceipt), "v2",
                "MegaMek-v2.tar.gz", targetArchive.length, digest, quiet());
        sourceStore.remove(source.registry, sourceRecord);
        IOException staleSource = assertThrows(IOException.class, () -> sourceService.apply(
                sourcePrepared, RealUpdateService.CONFIRM, quiet()));
        assertTrue(staleSource.getMessage().contains("source")
                || staleSource.getMessage().contains("record")
                || staleSource.getMessage().contains("installation"));
        assertEquals(1, sourceNetwork.binaryRequests);
        assertEquals(1, sourceNetwork.metadataRequests);
        assertEquals("runtime-1", Files.readString(source.root.resolve("lib/runtime.txt")));

        Fixture metadata = install("prepared-metadata-drift", "v1", packageFiles(1, Map.of(
                "lib/runtime.txt", bytes("runtime-1"),
                "docs/change.txt", bytes("old"))));
        byte[] changedArchive = archive("MegaMek-v2", packageFiles(2, Map.of(
                "lib/runtime.txt", bytes("different-runtime"),
                "docs/change.txt", bytes("different"))));
        PackageTransport metadataNetwork = new PackageTransport(
                releaseResponse("v2", targetArchive), packageResponse(targetArchive),
                releaseResponse("v2", changedArchive));
        PreparedUpdateService metadataService = new PreparedUpdateService(metadataNetwork,
                new RootCoordinator(temp.resolve("metadata-drift-coordination")));
        RegistryData metadataData = new RegistryStore().read(metadata.registry);
        InstallationRecord metadataRecord =
                new RegistryStore().resolve(metadataData, metadata.id);
        OwnershipReceipt metadataReceipt = new ReceiptStore().read(
                metadata.registry, metadataData, metadataRecord);
        PreparedUpdate metadataPrepared = metadataService.prepare(metadata.registry,
                metadataRecord, metadataReceipt, CurrentUpdateState.initial(metadataReceipt),
                "v2", "MegaMek-v2.tar.gz", targetArchive.length,
                digest, quiet());
        IOException staleTarget = assertThrows(IOException.class, () -> metadataService.apply(
                metadataPrepared, RealUpdateService.CONFIRM, quiet()));
        assertTrue(staleTarget.getMessage().contains("changed")
                || staleTarget.getMessage().contains("restart"));
        assertEquals(1, metadataNetwork.binaryRequests);
        assertEquals(targetArchive.length, metadataNetwork.binaryBytes);
        assertEquals("runtime-1", Files.readString(metadata.root.resolve("lib/runtime.txt")));
        assertEquals("old", Files.readString(metadata.root.resolve("docs/change.txt")));

        Fixture pending = install("prepared-pending", "v1", packageFiles(1, Map.of(
                "lib/runtime.txt", bytes("runtime-1"),
                "docs/change.txt", bytes("old"))));
        PackageTransport pendingNetwork = preparedTransport("v2", targetArchive);
        PreparedUpdateService pendingService = new PreparedUpdateService(pendingNetwork,
                new RootCoordinator(temp.resolve("pending-coordination")));
        RegistryData pendingData = new RegistryStore().read(pending.registry);
        InstallationRecord pendingRecord = new RegistryStore().resolve(pendingData, pending.id);
        OwnershipReceipt pendingReceipt =
                new ReceiptStore().read(pending.registry, pendingData, pendingRecord);
        PreparedUpdate pendingPrepared = pendingService.prepare(pending.registry, pendingRecord,
                pendingReceipt, CurrentUpdateState.initial(pendingReceipt), "v2",
                "MegaMek-v2.tar.gz", targetArchive.length, digest, quiet());
        Path pendingNamespace =
                Files.createDirectory(pending.root.resolve(RootCoordinator.UPDATE_NAMESPACE));
        IOException pendingFailure = assertThrows(IOException.class, () -> pendingService.apply(
                pendingPrepared, RealUpdateService.CONFIRM, quiet()));
        assertTrue(pendingFailure.getMessage().contains("pending")
                || pendingFailure.getMessage().contains("recover"));
        assertEquals(1, pendingNetwork.binaryRequests);
        assertEquals(1, pendingNetwork.metadataRequests);
        assertEquals("runtime-1", Files.readString(pending.root.resolve("lib/runtime.txt")));
        Files.delete(pendingNamespace);

        Fixture runtime = install("prepared-runtime-drift", "v1", packageFiles(1, Map.of(
                "lib/runtime.txt", bytes("runtime-1"),
                "docs/change.txt", bytes("old"))));
        PackageTransport runtimeNetwork = preparedTransport("v2", targetArchive);
        PreparedUpdateService runtimeService = new PreparedUpdateService(runtimeNetwork,
                new RootCoordinator(temp.resolve("runtime-drift-coordination")));
        RegistryData runtimeData = new RegistryStore().read(runtime.registry);
        InstallationRecord runtimeRecord = new RegistryStore().resolve(runtimeData, runtime.id);
        OwnershipReceipt runtimeReceipt =
                new ReceiptStore().read(runtime.registry, runtimeData, runtimeRecord);
        PreparedUpdate runtimePrepared = runtimeService.prepare(runtime.registry, runtimeRecord,
                runtimeReceipt, CurrentUpdateState.initial(runtimeReceipt), "v2",
                "MegaMek-v2.tar.gz", targetArchive.length, digest, quiet());
        Files.writeString(runtime.root.resolve("lib/runtime.txt"), "local-runtime-change");
        Exception runtimeFailure = assertThrows(Exception.class, () -> runtimeService.apply(
                runtimePrepared, RealUpdateService.CONFIRM, quiet()));
        assertTrue(runtimeFailure.getMessage().toLowerCase(Locale.ROOT).contains("runtime"));
        assertEquals(1, runtimeNetwork.binaryRequests);
        assertEquals(targetArchive.length, runtimeNetwork.binaryBytes);
        assertEquals("old", Files.readString(runtime.root.resolve("docs/change.txt")));
        assertFalse(Files.exists(runtime.root.resolve(RootCoordinator.UPDATE_NAMESPACE)));
    }

    @Test
    void concurrentDiscardCannotDeleteWorkspaceClaimedByPreparedApply() throws Exception {
        Fixture fixture = install("prepared-concurrent-close", "v1", packageFiles(1, Map.of(
                "lib/runtime.txt", bytes("runtime-1"),
                "docs/change.txt", bytes("old"))));
        byte[] targetArchive = archive("MegaMek-v2", packageFiles(2, Map.of(
                "lib/runtime.txt", bytes("runtime-2"),
                "docs/change.txt", bytes("new"))));
        PackageTransport delegate = preparedTransport("v2", targetArchive);
        AtomicInteger metadata = new AtomicInteger();
        CountDownLatch applyMetadata = new CountDownLatch(1);
        CountDownLatch releaseMetadata = new CountDownLatch(1);
        ReleaseTransport blocking = (uri, accept) -> {
            if (!"application/octet-stream".equals(accept)
                    && metadata.incrementAndGet() == 2) {
                applyMetadata.countDown();
                releaseMetadata.await();
            }
            return delegate.get(uri, accept);
        };
        PreparedUpdateService service = new PreparedUpdateService(blocking,
                new RootCoordinator(temp.resolve("concurrent-close-coordination")));
        RegistryData data = new RegistryStore().read(fixture.registry);
        InstallationRecord record = new RegistryStore().resolve(data, fixture.id);
        OwnershipReceipt receipt =
                new ReceiptStore().read(fixture.registry, data, record);
        PreparedUpdate prepared = service.prepare(fixture.registry, record,
                receipt, CurrentUpdateState.initial(receipt), "v2",
                "MegaMek-v2.tar.gz", targetArchive.length,
                "sha256:" + sha(targetArchive), quiet());
        var executor = Executors.newSingleThreadExecutor();
        try {
            var applying = executor.submit(() -> service.apply(
                    prepared, RealUpdateService.CONFIRM, quiet()));
            assertTrue(applyMetadata.await(5, TimeUnit.SECONDS));
            IOException close = assertThrows(IOException.class, prepared::close);
            assertTrue(close.getMessage().contains("applied")
                    || close.getMessage().contains("cleaned"));
            assertTrue(Files.exists(prepared.workspace().staging()),
                    "concurrent discard must not delete files Apply still owns");
            releaseMetadata.countDown();
            RealUpdateService.ApplyResult result = applying.get(10, TimeUnit.SECONDS);
            assertEquals("v2", result.state().tag());
            assertFalse(Files.exists(prepared.workspace().staging()));
            assertEquals(1, delegate.binaryRequests);
        } finally {
            releaseMetadata.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void applyFailureKeepsOriginalCauseAndRecoveryArtifactsWhenPackageCleanupAlsoFails()
            throws Exception {
        Fixture fixture = install("prepared-failure-cleanup", "v1", packageFiles(1, Map.of(
                "lib/runtime.txt", bytes("runtime-1"),
                "docs/change.txt", bytes("old"))));
        byte[] targetArchive = archive("MegaMek-v2", packageFiles(2, Map.of(
                "lib/runtime.txt", bytes("runtime-2"),
                "docs/change.txt", bytes("new"))));
        PackageTransport network = preparedTransport("v2", targetArchive);
        RootCoordinator coordinator =
                new RootCoordinator(temp.resolve("failure-cleanup-coordination"));
        AtomicBoolean failed = new AtomicBoolean();
        PreparedUpdateService service = new PreparedUpdateService(network,
                new RegistryStore(), new ReceiptStore(), coordinator, point -> {
            if (point.startsWith("APP_MUTATION:") && failed.compareAndSet(false, true)) {
                throw new IOException("original application mutation failure");
            }
        });
        RegistryData data = new RegistryStore().read(fixture.registry);
        InstallationRecord record = new RegistryStore().resolve(data, fixture.id);
        OwnershipReceipt receipt =
                new ReceiptStore().read(fixture.registry, data, record);
        PreparedUpdate prepared = service.prepare(fixture.registry, record,
                receipt, CurrentUpdateState.initial(receipt), "v2",
                "MegaMek-v2.tar.gz", targetArchive.length,
                "sha256:" + sha(targetArchive), quiet());
        Path outside = Files.createDirectory(temp.resolve("failure-cleanup-outside"));
        Path link = prepared.workspace().extracted().resolve("cleanup-link");
        createDirectoryLink(link, outside);

        IOException failure = assertThrows(IOException.class, () ->
                service.apply(prepared, RealUpdateService.CONFIRM, quiet()));

        assertTrue(failure.getMessage().contains("original application mutation failure"),
                "workspace cleanup must not mask the original Apply cause");
        assertTrue(failure.getSuppressed().length >= 1);
        assertTrue(Files.exists(fixture.root.resolve(RootCoordinator.UPDATE_NAMESPACE)));
        assertTrue(Files.exists(transaction(fixture.root).resolve("journal.json")));
        Files.delete(link);
        prepared.close();
        RealUpdateService.RecoveryResult recovered =
                new RealUpdateService(new PackageTransport(), coordinator).recover(
                        fixture.registry, fixture.id, RealUpdateService.CONFIRM);
        assertTrue(recovered.outcome().contains("rolled back"));
        assertEquals("runtime-1", Files.readString(fixture.root.resolve("lib/runtime.txt")));
        assertEquals("old", Files.readString(fixture.root.resolve("docs/change.txt")));
        assertFalse(Files.exists(fixture.root.resolve(RootCoordinator.UPDATE_NAMESPACE)));
        assertEquals(1, network.binaryRequests,
                "recovery after prepared Apply failure must not redownload");
    }

    @Test
    void verifiedUpdatePreservesDataAndUnknownThenAdvancesStickyHistoryAcrossReleases()
            throws Exception {
        Map<String, byte[]> v1 = packageFiles(1, Map.of(
                "lib/runtime.txt", bytes("runtime-1"),
                "docs/remove.txt", bytes("remove"),
                "docs/keep.txt", bytes("keep"),
                "data/modified.txt", bytes("official-1"),
                "data/obsolete.txt", bytes("obsolete-official"),
                "data/Icons/unit.png", bytes("case-old"),
                "saves/user.sav", bytes("protected-package")));
        Fixture fixture = install("history", "v1", v1);
        Path root = fixture.root;
        Path immutableReceipt = fixture.registry.resolveSibling(
                fixture.registry.getFileName() + ".metadata").resolve(fixture.id + ".json");
        byte[] originalReceipt = Files.readAllBytes(immutableReceipt);
        Files.writeString(root.resolve("data/modified.txt"), "user-modified");
        Files.writeString(root.resolve("data/obsolete.txt"), "user-obsolete");
        Files.writeString(root.resolve("data/collision.txt"), "user-collision");
        Files.writeString(root.resolve("unknown.local"), "unknown");
        Files.writeString(root.resolve("saves/user.sav"), "real-save");

        Map<String, byte[]> v2 = packageFiles(2, Map.of(
                "lib/runtime.txt", bytes("runtime-2"),
                "docs/add.txt", bytes("added"),
                "docs/keep.txt", bytes("keep"),
                "data/modified.txt", bytes("official-2"),
                "data/collision.txt", bytes("official-collision-2"),
                "data/icons/unit.png", bytes("case-new"),
                "saves/user.sav", bytes("target-protected")));
        RealUpdateService.ApplyResult first = apply(fixture, "v2", v2, point -> {});
        assertEquals("2.0.0", first.record().observedBuild());
        assertEquals("v2", first.state().tag());
        assertTrue(first.skippedDecisions() >= 4);
        for (Action action : Action.values()) {
            assertTrue(first.decisions().stream().anyMatch(item -> item.action() == action),
                    "representative success plan should include " + action);
        }
        assertEquals("runtime-2", Files.readString(root.resolve("lib/runtime.txt")));
        assertEquals("added", Files.readString(root.resolve("docs/add.txt")));
        assertFalse(Files.exists(root.resolve("docs/remove.txt")));
        assertEquals("user-modified", Files.readString(root.resolve("data/modified.txt")));
        assertEquals("user-obsolete", Files.readString(root.resolve("data/obsolete.txt")));
        assertEquals("user-collision", Files.readString(root.resolve("data/collision.txt")));
        assertEquals("case-old", Files.readString(root.resolve("data/Icons/unit.png")));
        assertArrayEquals(originalReceipt, Files.readAllBytes(immutableReceipt));
        assertEquals("real-save", Files.readString(root.resolve("saves/user.sav")));
        assertEquals("unknown", Files.readString(root.resolve("unknown.local")));
        assertFalse(Files.exists(root.resolve(RootCoordinator.UPDATE_NAMESPACE)));

        // Restoring an older official data hash is trusted ancestry, not a synthetic local receipt.
        Files.write(root.resolve("data/modified.txt"), v1.get("data/modified.txt"));
        Map<String, byte[]> v3 = packageFiles(3, Map.of(
                "lib/runtime.txt", bytes("runtime-3"),
                "docs/add.txt", bytes("added-3"),
                "docs/keep.txt", bytes("keep"),
                "data/modified.txt", bytes("official-3"),
                "data/collision.txt", bytes("official-collision-3"),
                "data/icons/unit.png", bytes("case-v3")));
        RealUpdateService.ApplyResult second = apply(fixture, "v3", v3, point -> {});
        assertEquals("v3", second.state().tag());
        assertEquals("official-3", Files.readString(root.resolve("data/modified.txt")));
        assertEquals("user-obsolete", Files.readString(root.resolve("data/obsolete.txt")));
        assertEquals("user-collision", Files.readString(root.resolve("data/collision.txt")));
        assertEquals("case-old", Files.readString(root.resolve("data/Icons/unit.png")));
        assertTrue(second.state().caseOverrides().stream().anyMatch(
                item -> item.foldedPrefix().equals("data/icons")));
        assertTrue(second.state().overrides().stream().anyMatch(
                item -> item.path().equals("data/obsolete.txt")));

        Map<String, byte[]> v4 = packageFiles(4, Map.of(
                "lib/runtime.txt", bytes("runtime-4"),
                "docs/add.txt", bytes("added-4"),
                "docs/keep.txt", bytes("keep"),
                "data/modified.txt", bytes("official-4"),
                "data/collision.txt", bytes("official-collision-4"),
                "data/icons/unit.png", bytes("case-v4")));
        RealUpdateService.ApplyResult third = apply(fixture, "v4", v4, point -> {});
        assertEquals("v4", third.state().tag());
        RegistryStore registryStore = new RegistryStore();
        InstallationRecord beforeJavaSelection = registryStore.resolve(
                registryStore.read(fixture.registry), fixture.id);
        assertEquals("4.0.0", beforeJavaSelection.observedBuild());
        assertEquals("user-collision", Files.readString(root.resolve("data/collision.txt")));
        assertEquals("case-old", Files.readString(root.resolve("data/Icons/unit.png")));

        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                        ? "java.exe" : "java");
        var launchRecord = registryStore.resolve(registryStore.read(fixture.registry), fixture.id);
        ProcessRunner benign = (command, workingDirectory, timeout, inheritIo) ->
                new ProcessRunner.Result(0, "openjdk version \"21.0.1\"", false);
        JavaRuntime.CurrentJava gameJava =
                new JavaRuntime(benign).validateExternal(java, fixture.root);
        List<String> launch = new ApplicationLauncher(benign)
                .command(launchRecord, "megamek", gameJava);
        assertTrue(launch.contains("megamek.MegaMek"));
        assertEquals("4.0.0", launchRecord.observedBuild());

        PackageTransport previewTransport = transport("v5", packageFiles(5, Map.of(
                "lib/runtime.txt", bytes("runtime-5"),
                "data/icons/unit.png", bytes("case-v5"))));
        UpdatePreviewService.Preview preview = new UpdatePreviewService(previewTransport).preview(
                fixture.registry, fixture.id, "v5", quiet());
        assertEquals("v4", preview.baseline().tag());
        assertTrue(preview.decisions().stream().anyMatch(item ->
                item.path().equals("data/icons/unit.png") && item.action() == Action.SKIP
                        && item.reason().contains("sticky")));
    }

    @Test
    void globalJavaAndRegistryPreferencesSurviveRepeatedUpdatesAndFreshLoadLaunch()
            throws Exception {
        Fixture fixture = installWithPin("java-retention", "v1",
                packageFiles(1, Map.of("lib/runtime.txt", bytes("runtime-1"))),
                "keep-main-pin");
        RegistryStore store = new RegistryStore();
        ProcessRunner benign = (command, workingDirectory, timeout, inheritIo) ->
                new ProcessRunner.Result(0, "openjdk version \"21.0.1\"", false);
        LauncherServices selectingServices = launcherServices(fixture.registry, benign);
        InstallationRecord installed = store.resolve(store.read(fixture.registry), fixture.id);
        Path java = fakeJava("selected-jdk");
        selectingServices.selectDefaultJava(java);

        Path unrelatedRoot = fixture.registry.getParent().resolve("unrelated-copy");
        materialize(unrelatedRoot, packageFiles(9, Map.of(
                "lib/runtime.txt", bytes("unrelated-runtime"))));
        InstallationRecord unrelated = store.register(
                fixture.registry, "Unrelated copy", unrelatedRoot, "keep-unrelated-pin");

        RegistryData beforeData = store.read(fixture.registry);
        InstallationRecord before = store.resolve(beforeData, fixture.id);
        InstallationRecord unrelatedBefore = store.resolve(beforeData, unrelated.id());
        ChannelPreferenceStore channels = new ChannelPreferenceStore();
        var fixedChannel = channels.read(
                fixture.registry, beforeData, before).preference();
        channels.setCheckOnOpen(fixture.registry, before, fixedChannel, true);
        assertEquals("keep-main-pin", before.pin());
        assertEquals("keep-unrelated-pin", unrelatedBefore.pin());

        RealUpdateService.ApplyResult first = apply(fixture, "v2",
                packageFiles(2, Map.of("lib/runtime.txt", bytes("runtime-2"))), point -> {});
        RegistryData firstData = store.read(fixture.registry);
        InstallationRecord firstOnDisk = store.resolve(firstData, fixture.id);
        assertIdentityAndPreferences(before, first.record());
        assertIdentityAndPreferences(before, firstOnDisk);
        assertEquals(beforeData.defaultInstallationId(), firstData.defaultInstallationId());
        assertEquals(unrelatedBefore, store.resolve(firstData, unrelated.id()));
        assertEquals(FollowChannel.MILESTONE,
                channels.read(fixture.registry, firstData, firstOnDisk).preference().channel());
        assertTrue(channels.read(fixture.registry, firstData, firstOnDisk)
                .preference().checkOnOpen());

        RealUpdateService.ApplyResult second = apply(fixture, "v3",
                packageFiles(3, Map.of("lib/runtime.txt", bytes("runtime-3"))), point -> {});
        RegistryData secondData = store.read(fixture.registry);
        InstallationRecord secondOnDisk = store.resolve(secondData, fixture.id);
        assertIdentityAndPreferences(before, second.record());
        assertIdentityAndPreferences(before, secondOnDisk);
        assertEquals(beforeData.defaultInstallationId(), secondData.defaultInstallationId());
        assertEquals(unrelatedBefore, store.resolve(secondData, unrelated.id()));
        assertEquals(FollowChannel.MILESTONE,
                channels.read(fixture.registry, secondData, secondOnDisk).preference().channel());

        LauncherServices reloadedServices = launcherServices(fixture.registry, benign);
        LauncherServices.HomeState home = reloadedServices.loadHome();
        assertEquals(null, home.preferredError());
        assertIdentityAndPreferences(before, home.preferred());
        List<String> command = reloadedServices.preview(home.preferred(), "megamek");
        assertEquals(java.toRealPath().toString(), command.getFirst());
        assertTrue(command.contains("megamek.MegaMek"));
        assertEquals(0, reloadedServices.launch(home.preferred(), "megamek"),
                "benign launch must not require selecting Java again");
    }

    @Test
    void registryRefreshDoesNotContainPerInstallationJava()
            throws Exception {
        for (String changePoint : List.of("BEFORE_APP_MUTATION", "AFTER_STATE_COMMIT")) {
            Fixture fixture = install("java-race-" + changePoint.toLowerCase(Locale.ROOT),
                    "v1", packageFiles(1, Map.of(
                            "lib/runtime.txt", bytes("runtime-1"),
                            "docs/change.txt", bytes("old"))));
            RegistryStore store = new RegistryStore();

            byte[] archive = archive("MegaMek-v2", packageFiles(2, Map.of(
                    "lib/runtime.txt", bytes("runtime-2"),
                    "docs/change.txt", bytes("new"))));
            AtomicBoolean changed = new AtomicBoolean();
            RealUpdateService service = service(transport("v2", archive), point -> {
                if (point.equals(changePoint)) {
                    changed.set(true);
                }
            });
            RealUpdateService.Snapshot snapshot = service.snapshot(fixture.registry, fixture.id);

            RealUpdateService.ApplyResult result = service.apply(snapshot, "v2", archive.length,
                    "sha256:" + sha(archive), RealUpdateService.CONFIRM, quiet());

            assertTrue(changed.get());
            assertFalse(new com.fasterxml.jackson.databind.ObjectMapper()
                    .valueToTree(result.record()).has("javaExecutable"));
        }
    }

    @Test
    void customizedRuntimeAndStaleConsentFailBeforeApplicationMutation() throws Exception {
        Fixture fixture = install("runtime", "v1", packageFiles(1, Map.of(
                "lib/runtime.txt", bytes("runtime-1"),
                "data/a.txt", bytes("a"))));
        Files.writeString(fixture.root.resolve("lib/runtime.txt"), "custom runtime");
        Map<String, byte[]> v2 = packageFiles(2, Map.of(
                "lib/runtime.txt", bytes("runtime-2"),
                "data/a.txt", bytes("b")));
        byte[] archive = archive("MegaMek-v2", v2);
        PackageTransport transport = transport("v2", archive);
        RealUpdateService service = service(transport, point -> {});
        RealUpdateService.Snapshot snapshot = service.snapshot(fixture.registry, fixture.id);
        IOException failure = assertThrows(IOException.class, () -> service.apply(snapshot, "v2",
                archive.length, "sha256:" + sha(archive), RealUpdateService.CONFIRM, quiet()));
        assertTrue(failure.getMessage().contains("runtime"));
        assertEquals("custom runtime", Files.readString(fixture.root.resolve("lib/runtime.txt")));
        assertEquals("a", Files.readString(fixture.root.resolve("data/a.txt")));
        assertFalse(Files.exists(fixture.root.resolve(RootCoordinator.UPDATE_NAMESPACE)));

        PackageTransport noNetwork = new PackageTransport();
        assertThrows(IOException.class, () -> service(noNetwork, point -> {}).apply(
                fixture.registry, fixture.id, "wrong-source", "v2", archive.length,
                "sha256:" + sha(archive), RealUpdateService.CONFIRM, quiet()));
        assertTrue(noNetwork.requests.isEmpty());

        PackageTransport changedDigest = transport("v2", archive);
        RealUpdateService changedService = service(changedDigest, point -> {});
        RealUpdateService.Snapshot current =
                changedService.snapshot(fixture.registry, fixture.id);
        IOException stale = assertThrows(IOException.class, () -> changedService.apply(current,
                "v2", archive.length, "sha256:" + "0".repeat(64),
                RealUpdateService.CONFIRM, quiet()));
        assertTrue(stale.getMessage().contains("changed since explicit consent"));
        assertEquals(1, changedDigest.requests.size(), "metadata only; package was not downloaded");
        assertFalse(Files.exists(fixture.root.resolve(RootCoordinator.UPDATE_NAMESPACE)));
    }

    @Test
    void perOperationRaceAndExternalRecoveryEditAreRefusedAndPreserved() throws Exception {
        Fixture fixture = install("race", "v1", packageFiles(1, Map.of(
                "lib/runtime.txt", bytes("runtime-1"),
                "docs/change.txt", bytes("old"))));
        byte[] archive = archive("MegaMek-v2", packageFiles(2, Map.of(
                "lib/runtime.txt", bytes("runtime-2"),
                "docs/change.txt", bytes("new"))));
        RealUpdateService service = service(transport("v2", archive), point -> {
            if (point.equals("BEFORE_OPERATION:docs/change.txt")) {
                Files.writeString(fixture.root.resolve("docs/change.txt"), "external-race");
            }
        });
        RealUpdateService.Snapshot snapshot = service.snapshot(fixture.registry, fixture.id);
        assertThrows(Exception.class, () -> service.apply(snapshot, "v2", archive.length,
                "sha256:" + sha(archive), RealUpdateService.CONFIRM, quiet()));
        assertEquals("external-race", Files.readString(fixture.root.resolve("docs/change.txt")));
        Exception recovery = assertThrows(Exception.class, () ->
                service(new PackageTransport(), point -> {}).recover(
                        fixture.registry, fixture.id, RealUpdateService.CONFIRM));
        assertTrue(recovery.getMessage().contains("external edit"));
        assertEquals("external-race", Files.readString(fixture.root.resolve("docs/change.txt")));
    }

    @Test
    void stagedPayloadTamperAndLinkedDestinationFailBeforeUnsafeReplacement() throws Exception {
        Fixture staged = install("staged", "v1", packageFiles(1, Map.of(
                "lib/runtime.txt", bytes("runtime-1"),
                "docs/change.txt", bytes("old"))));
        byte[] archive = archive("MegaMek-v2", packageFiles(2, Map.of(
                "lib/runtime.txt", bytes("runtime-2"),
                "docs/change.txt", bytes("new"))));
        RealUpdateService tampering = service(transport("v2", archive), point -> {
            if (point.equals("AFTER_STAGING")) {
                Files.writeString(transaction(staged.root).resolve("staged")
                        .resolve("docs").resolve("change.txt"), "tampered-stage");
            }
        });
        RealUpdateService.Snapshot snapshot = tampering.snapshot(staged.registry, staged.id);
        Exception integrity = assertThrows(Exception.class, () -> tampering.apply(snapshot, "v2",
                archive.length, "sha256:" + sha(archive), RealUpdateService.CONFIRM, quiet()));
        assertTrue(integrity.getMessage().contains("staged payload"));
        assertEquals("old", Files.readString(staged.root.resolve("docs/change.txt")));
        assertTrue(service(new PackageTransport(), point -> {}).recover(
                staged.registry, staged.id, RealUpdateService.CONFIRM).outcome()
                .contains("rolled back"));

        Fixture linked = install("linked", "v1", packageFiles(1, Map.of(
                "lib/runtime.txt", bytes("runtime-1"),
                "data/linked/file.txt", bytes("old"))));
        Path outside = Files.createDirectory(linked.root.getParent().resolve("outside"));
        Files.delete(linked.root.resolve("data/linked/file.txt"));
        Files.delete(linked.root.resolve("data/linked"));
        createDirectoryLink(linked.root.resolve("data/linked"), outside);
        byte[] linkedArchive = archive("MegaMek-v2", packageFiles(2, Map.of(
                "lib/runtime.txt", bytes("runtime-2"),
                "data/linked/file.txt", bytes("new"))));
        RealUpdateService linkedService = service(transport("v2", linkedArchive), point -> {});
        RealUpdateService.Snapshot linkedSnapshot =
                linkedService.snapshot(linked.registry, linked.id);
        assertThrows(Exception.class, () -> linkedService.apply(linkedSnapshot, "v2",
                linkedArchive.length, "sha256:" + sha(linkedArchive),
                RealUpdateService.CONFIRM, quiet()));
        assertFalse(Files.exists(outside.resolve("file.txt")));
        assertFalse(Files.exists(linked.root.resolve(RootCoordinator.UPDATE_NAMESPACE)));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void unwritableDestinationIsRejectedWithoutChangingManagedFiles() throws Exception {
        Fixture fixture = install("permissions", "v1", packageFiles(1, Map.of(
                "lib/runtime.txt", bytes("runtime-1"),
                "docs/change.txt", bytes("old"))));
        Path docs = fixture.root.resolve("docs");
        AclFileAttributeView acl = Files.getFileAttributeView(docs, AclFileAttributeView.class);
        List<AclEntry> original = acl.getAcl();
        var principal = docs.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        AclEntry deny = AclEntry.newBuilder().setType(AclEntryType.DENY)
                .setPrincipal(principal)
                .setPermissions(AclEntryPermission.ADD_FILE, AclEntryPermission.WRITE_DATA,
                        AclEntryPermission.APPEND_DATA, AclEntryPermission.DELETE_CHILD)
                .build();
        List<AclEntry> restricted = new java.util.ArrayList<>();
        restricted.add(deny);
        restricted.addAll(original);
        acl.setAcl(restricted);
        try {
            byte[] archive = archive("MegaMek-v2", packageFiles(2, Map.of(
                    "lib/runtime.txt", bytes("runtime-2"),
                    "docs/change.txt", bytes("new"))));
            RealUpdateService service = service(transport("v2", archive), point -> {});
            RealUpdateService.Snapshot snapshot =
                    service.snapshot(fixture.registry, fixture.id);
            assertThrows(IOException.class, () -> service.apply(snapshot, "v2", archive.length,
                    "sha256:" + sha(archive), RealUpdateService.CONFIRM, quiet()));
            assertEquals("old", Files.readString(docs.resolve("change.txt")));
        } finally {
            acl.setAcl(original);
        }
        if (Files.exists(fixture.root.resolve(RootCoordinator.UPDATE_NAMESPACE))) {
            service(new PackageTransport(), point -> {}).recover(
                    fixture.registry, fixture.id, RealUpdateService.CONFIRM);
        }
    }

    @Test
    void recoveryRollsBackBeforeMutationAndCompletesAllPostMutationCommitWindows()
            throws Exception {
        for (String failurePoint : List.of("BEFORE_APP_MUTATION", "AFTER_APP_MUTATION",
                "AFTER_STATE_COMMIT", "AFTER_REGISTRY_COMMIT")) {
            Fixture fixture = install("recover-" + failurePoint.toLowerCase(Locale.ROOT),
                    "v1", packageFiles(1, Map.of(
                            "lib/runtime.txt", bytes("runtime-1"),
                            "docs/change.txt", bytes("old"))));
            byte[] archive = archive("MegaMek-v2", packageFiles(2, Map.of(
                    "lib/runtime.txt", bytes("runtime-2"),
                    "docs/change.txt", bytes("new"))));
            RegistryStore store = new RegistryStore();
            Path java = fakeJava("recovery-jdk-" + failurePoint.toLowerCase(Locale.ROOT));
            InstallationRecord before = store.resolve(store.read(fixture.registry), fixture.id);
            RealUpdateService failing = service(transport("v2", archive), point -> {
                if (point.equals(failurePoint)) throw new IOException("simulated crash " + point);
            });
            RealUpdateService.Snapshot snapshot = failing.snapshot(fixture.registry, fixture.id);
            assertThrows(IOException.class, () -> failing.apply(snapshot, "v2", archive.length,
                    "sha256:" + sha(archive), RealUpdateService.CONFIRM, quiet()));
            assertTrue(Files.exists(fixture.root.resolve(RootCoordinator.UPDATE_NAMESPACE)));

            RealUpdateService recovery = service(new PackageTransport(), point -> {});
            RealUpdateService.RecoveryResult first = recovery.recover(fixture.registry, fixture.id,
                    RealUpdateService.CONFIRM);
            InstallationRecord recovered = store.resolve(store.read(fixture.registry), fixture.id);
            assertIdentityAndPreferences(before, recovered);
            if (failurePoint.equals("BEFORE_APP_MUTATION")) {
                assertTrue(first.outcome().contains("rolled back"));
                assertEquals("old", Files.readString(fixture.root.resolve("docs/change.txt")));
                assertEquals("v1", recovery.snapshot(fixture.registry, fixture.id).current().tag());
            } else {
                assertTrue(first.outcome().contains("completed"));
                assertEquals("new", Files.readString(fixture.root.resolve("docs/change.txt")));
                assertEquals("v2", recovery.snapshot(fixture.registry, fixture.id).current().tag());
                assertEquals("2.0.0", new RegistryStore().resolve(
                        new RegistryStore().read(fixture.registry), fixture.id).observedBuild());
            }
            assertFalse(Files.exists(fixture.root.resolve(RootCoordinator.UPDATE_NAMESPACE)));
            assertEquals("no pending transaction", recovery.recover(fixture.registry, fixture.id,
                    RealUpdateService.CONFIRM).outcome());
        }
    }

    @Test
    void recoveryPrunesOnlyEmptyKnownContainersAfterCommittedPendingDeletion()
            throws Exception {
        Fixture fixture = install("cleanup-window", "v1", packageFiles(1, Map.of(
                "lib/runtime.txt", bytes("runtime-1"),
                "docs/change.txt", bytes("old"))));
        byte[] archive = archive("MegaMek-v2", packageFiles(2, Map.of(
                "lib/runtime.txt", bytes("runtime-2"),
                "docs/change.txt", bytes("new"))));
        RealUpdateService interrupted = service(transport("v2", archive), point -> {
            if (point.equals("AFTER_PENDING_DELETE")) {
                throw new IOException("simulated cleanup interruption");
            }
        });
        RealUpdateService.Snapshot before =
                interrupted.snapshot(fixture.registry, fixture.id);
        assertThrows(IOException.class, () -> interrupted.apply(before, "v2", archive.length,
                "sha256:" + sha(archive), RealUpdateService.CONFIRM, quiet()));

        Path namespace = fixture.root.resolve(RootCoordinator.UPDATE_NAMESPACE);
        Path transactions = namespace.resolve("transactions");
        assertTrue(Files.isDirectory(transactions));
        try (var children = Files.list(transactions)) {
            assertTrue(children.findAny().isEmpty(), "only the empty container should remain");
        }
        assertFalse(Files.exists(namespace.resolve("pending.json")));
        assertEquals("new", Files.readString(fixture.root.resolve("docs/change.txt")));
        assertEquals("v2", interrupted.snapshot(fixture.registry, fixture.id).current().tag());
        assertEquals("2.0.0", new RegistryStore().resolve(
                new RegistryStore().read(fixture.registry), fixture.id).observedBuild());

        RealUpdateService recovery = service(new PackageTransport(), point -> {});
        assertTrue(recovery.recover(fixture.registry, fixture.id, RealUpdateService.CONFIRM)
                .outcome().contains("removed empty transaction namespace"));
        assertFalse(Files.exists(namespace));
        assertEquals("no pending transaction", recovery.recover(
                fixture.registry, fixture.id, RealUpdateService.CONFIRM).outcome());
        assertEquals("v2", recovery.snapshot(fixture.registry, fixture.id).current().tag());
        assertTrue(new UpdatePreviewService(new PackageTransport())
                .eligibility(fixture.registry, fixture.id).available());
        try (RootCoordinator.Lease lease =
                     new RootCoordinator(temp.resolve("post-cleanup-coordination"))
                             .acquire(fixture.root, false)) {
            lease.requireNoPendingUpdate();
        }
    }

    @Test
    void emptyContainerRecoveryRetainsUnknownEntriesAndRejectsLinkedContainer()
            throws Exception {
        RealUpdateService recovery = service(new PackageTransport(), point -> {});

        Fixture sibling = install("cleanup-unknown-sibling", "v1",
                packageFiles(1, Map.of("lib/runtime.txt", bytes("runtime-1"))));
        Path siblingNamespace = Files.createDirectory(
                sibling.root.resolve(RootCoordinator.UPDATE_NAMESPACE));
        Files.createDirectory(siblingNamespace.resolve("transactions"));
        Path mystery = Files.writeString(siblingNamespace.resolve("mystery.bin"), "unknown");
        IOException siblingFailure = assertThrows(IOException.class, () -> recovery.recover(
                sibling.registry, sibling.id, RealUpdateService.CONFIRM));
        assertTrue(siblingFailure.getMessage().contains("unknown artifacts"));
        assertTrue(Files.exists(mystery));
        assertTrue(Files.isDirectory(siblingNamespace.resolve("transactions")));

        Fixture unknownTransaction = install("cleanup-unknown-transaction", "v1",
                packageFiles(1, Map.of("lib/runtime.txt", bytes("runtime-1"))));
        Path transactionNamespace = Files.createDirectory(
                unknownTransaction.root.resolve(RootCoordinator.UPDATE_NAMESPACE));
        Path transactions = Files.createDirectory(transactionNamespace.resolve("transactions"));
        Path unknown = Files.createDirectory(transactions.resolve("not-owned"));
        Path unknownPayload = Files.writeString(unknown.resolve("foreign.bin"), "not updater-owned");
        IOException transactionFailure = assertThrows(IOException.class, () -> recovery.recover(
                unknownTransaction.registry, unknownTransaction.id, RealUpdateService.CONFIRM));
        assertTrue(transactionFailure.getMessage().contains("unknown transactions"));
        assertTrue(Files.isDirectory(unknown));
        assertTrue(Files.exists(unknownPayload));

        Fixture linked = install("cleanup-linked-container", "v1",
                packageFiles(1, Map.of("lib/runtime.txt", bytes("runtime-1"))));
        Path linkedNamespace = Files.createDirectory(
                linked.root.resolve(RootCoordinator.UPDATE_NAMESPACE));
        Path outside = Files.createDirectory(temp.resolve("cleanup-linked-outside"));
        Path linkedTransactions = linkedNamespace.resolve("transactions");
        createDirectoryLink(linkedTransactions, outside);
        IOException linkedFailure = assertThrows(IOException.class, () -> recovery.recover(
                linked.registry, linked.id, RealUpdateService.CONFIRM));
        assertTrue(linkedFailure.getMessage().contains("linked")
                || linkedFailure.getMessage().contains("reparse")
                || linkedFailure.getMessage().contains("noncanonical"));
        assertTrue(Files.isDirectory(outside));
    }

    @Test
    void corruptBindingProtectedOperationAndInvalidBackupFailClosed() throws Exception {
        Fixture binding = interrupted("wrong-binding", "BEFORE_APP_MUTATION");
        Path pending = binding.root.resolve(RootCoordinator.UPDATE_NAMESPACE).resolve("pending.json");
        ObjectMapper mapper = new ObjectMapper();
        JsonNode pendingTree = mapper.readTree(Files.readAllBytes(pending));
        ((com.fasterxml.jackson.databind.node.ObjectNode) pendingTree)
                .put("canonicalRegistry", binding.registry + "-other");
        Files.write(pending, mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsBytes(pendingTree));
        assertThrows(IOException.class, () -> service(new PackageTransport(), point -> {}).recover(
                binding.registry, binding.id, RealUpdateService.CONFIRM));

        Fixture corrupt = interrupted("corrupt-journal", "BEFORE_APP_MUTATION");
        Files.writeString(journal(corrupt.root), "{broken");
        IOException corruptFailure = assertThrows(IOException.class, () ->
                service(new PackageTransport(), point -> {}).recover(
                        corrupt.registry, corrupt.id, RealUpdateService.CONFIRM));
        assertTrue(corruptFailure.getMessage().contains("journal is invalid"));

        Fixture protectedOp = interrupted("protected-op", "BEFORE_APP_MUTATION");
        Path journal = journal(protectedOp.root);
        JsonNode tree = mapper.readTree(Files.readAllBytes(journal));
        ((com.fasterxml.jackson.databind.node.ObjectNode) tree.path("operations").get(0))
                .put("path", "saves/forbidden.sav");
        Files.write(journal, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(tree));
        IOException tamper = assertThrows(IOException.class, () ->
                service(new PackageTransport(), point -> {}).recover(
                        protectedOp.registry, protectedOp.id, RealUpdateService.CONFIRM));
        assertTrue(tamper.getMessage().contains("journal"));

        Fixture unknown = interrupted("unknown-transaction-artifact", "BEFORE_APP_MUTATION");
        Files.writeString(transaction(unknown.root).resolve("mystery.bin"), "not updater-owned");
        IOException retained = assertThrows(IOException.class, () ->
                service(new PackageTransport(), point -> {}).recover(
                        unknown.registry, unknown.id, RealUpdateService.CONFIRM));
        assertTrue(retained.getMessage().contains("unknown transaction artifact"));
        assertTrue(Files.exists(transaction(unknown.root).resolve("mystery.bin")));

        Fixture backup = install("bad-backup", "v1", packageFiles(1, Map.of(
                "lib/runtime.txt", bytes("runtime-1"),
                "docs/change.txt", bytes("old"))));
        byte[] archive = archive("MegaMek-v2", packageFiles(2, Map.of(
                "lib/runtime.txt", bytes("runtime-2"),
                "docs/change.txt", bytes("new"))));
        RealUpdateService failing = service(transport("v2", archive), point -> {
            if (point.equals("APP_MUTATION:data/default.txt")) {
                throw new IOException("stop after first mutation");
            }
        });
        RealUpdateService.Snapshot snap = failing.snapshot(backup.registry, backup.id);
        assertThrows(IOException.class, () -> failing.apply(snap, "v2", archive.length,
                "sha256:" + sha(archive), RealUpdateService.CONFIRM, quiet()));
        Path tx = transaction(backup.root);
        Path backupFile = tx.resolve("backups").resolve("data").resolve("default.txt");
        Files.writeString(backupFile, "corrupt");
        Exception invalid = assertThrows(Exception.class, () ->
                service(new PackageTransport(), point -> {}).recover(
                        backup.registry, backup.id, RealUpdateService.CONFIRM));
        assertTrue(invalid.getMessage().contains("backup")
                || invalid.getMessage().contains("external edit"));
    }

    private Fixture interrupted(String name, String failurePoint) throws Exception {
        Fixture fixture = install(name, "v1", packageFiles(1, Map.of(
                "lib/runtime.txt", bytes("runtime-1"),
                "docs/change.txt", bytes("old"))));
        byte[] archive = archive("MegaMek-v2", packageFiles(2, Map.of(
                "lib/runtime.txt", bytes("runtime-2"),
                "docs/change.txt", bytes("new"))));
        RealUpdateService service = service(transport("v2", archive), point -> {
            if (point.equals(failurePoint)) throw new IOException("stop");
        });
        RealUpdateService.Snapshot snapshot = service.snapshot(fixture.registry, fixture.id);
        assertThrows(IOException.class, () -> service.apply(snapshot, "v2", archive.length,
                "sha256:" + sha(archive), RealUpdateService.CONFIRM, quiet()));
        return fixture;
    }

    private static Path journal(Path root) throws IOException {
        return transaction(root).resolve("journal.json");
    }

    private static Path transaction(Path root) throws IOException {
        Path transactions = root.resolve(RootCoordinator.UPDATE_NAMESPACE).resolve("transactions");
        try (var children = Files.list(transactions)) {
            return children.findFirst().orElseThrow();
        }
    }

    private RealUpdateService.ApplyResult apply(Fixture fixture, String tag,
                                                Map<String, byte[]> files,
                                                RealUpdateService.FailureHook hook)
            throws Exception {
        byte[] archive = archive("MegaMek-" + tag, files);
        RealUpdateService service = service(transport(tag, archive), hook);
        RealUpdateService.Snapshot snapshot = service.snapshot(fixture.registry, fixture.id);
        return service.apply(snapshot, tag, archive.length, "sha256:" + sha(archive),
                RealUpdateService.CONFIRM, quiet());
    }

    private RealUpdateService service(ReleaseTransport transport,
                                      RealUpdateService.FailureHook hook) {
        return new RealUpdateService(transport, new RegistryStore(), new ReceiptStore(),
                new RootCoordinator(temp.resolve("coordination")), hook);
    }

    private LauncherServices launcherServices(Path registry, ProcessRunner runner) {
        return new LauncherServices(registry, new RegistryStore(), new InstallationInspector(),
                new PackageTransport(), new JavaRuntime(runner), new ApplicationLauncher(runner));
    }

    private Fixture install(String name, String tag, Map<String, byte[]> files) throws Exception {
        Path area = Files.createDirectory(temp.resolve(name));
        Path registry = area.resolve("registry.json");
        Path root = area.resolve("installed");
        byte[] archive = archive("MegaMek-" + tag, files);
        FreshInstaller.Result result = new FreshInstaller(transport(tag, archive)).install(
                OfficialRepository.MEGAMEK, tag, root, registry, name, quiet());
        new ChannelPreferenceStore().initializeManaged(registry, result.record(),
                result.ownershipReceipt(), FollowChannel.MILESTONE, false);
        return new Fixture(registry, root, result.record().id());
    }

    private Fixture installWithPin(String name, String tag, Map<String, byte[]> files, String pin)
            throws Exception {
        Path area = Files.createDirectory(temp.resolve(name));
        Path registry = area.resolve("registry.json");
        Path root = area.resolve("installed");
        materialize(root, files);
        RegistryStore store = new RegistryStore();
        InstallationRecord record = store.register(registry, name, root, pin);
        byte[] sourceArchive = archive("MegaMek-" + tag, files);
        OwnershipReceipt receipt = new ReceiptStore().write(
                registry, store.read(registry), record,
                OfficialRepository.MEGAMEK, tag, "MegaMek-" + tag + ".tar.gz",
                sourceArchive.length, "sha256:" + sha(sourceArchive),
                new OwnershipPolicy().build(root, OfficialRepository.MEGAMEK, tag));
        new ChannelPreferenceStore().initializeManaged(
                registry, record, receipt, FollowChannel.MILESTONE, false);
        return new Fixture(registry, root, record.id());
    }

    private static void materialize(Path root, Map<String, byte[]> files) throws IOException {
        Files.createDirectory(root);
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            Path target = root.resolve(entry.getKey());
            Files.createDirectories(target.getParent());
            Files.write(target, entry.getValue());
        }
    }

    private Path fakeJava(String name) throws IOException {
        Path bin = Files.createDirectories(temp.resolve(name).resolve("bin"));
        String executable = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                ? "java.exe" : "java";
        return Files.writeString(bin.resolve(executable), "non-executing Java fixture").toRealPath();
    }

    private static void assertIdentityAndPreferences(InstallationRecord expected,
                                                     InstallationRecord actual) {
        assertEquals(expected.id(), actual.id());
        assertEquals(expected.name(), actual.name());
        assertEquals(expected.canonicalRoot(), actual.canonicalRoot());
        assertEquals(expected.pin(), actual.pin());
        assertEquals(expected.updateEligible(), actual.updateEligible());
        assertEquals(expected.registeredAt(), actual.registeredAt());
    }

    private static Map<String, byte[]> packageFiles(int version, Map<String, byte[]> extra)
            throws IOException {
        Map<String, byte[]> result = new LinkedHashMap<>(extra);
        result.put("MegaMek.jar", jar(version));
        result.putIfAbsent("mmconf/clientsettings.xml", bytes("protected-config"));
        result.putIfAbsent("data/default.txt", bytes("official-data-" + version));
        return result;
    }

    private static byte[] jar(int version) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "megamek.MegaMek");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(output, manifest)) {
            jar.putNextEntry(new JarEntry("megamek/Version.properties"));
            jar.write(bytes("major=" + version + "\nminor=0\npatch=0\n"));
            jar.closeEntry();
        }
        return output.toByteArray();
    }

    private static byte[] archive(String root, Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(output);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            tar.putArchiveEntry(new TarArchiveEntry(root + "/"));
            tar.closeArchiveEntry();
            for (Map.Entry<String, byte[]> item : files.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey()).toList()) {
                TarArchiveEntry entry = new TarArchiveEntry(root + "/" + item.getKey());
                entry.setSize(item.getValue().length);
                tar.putArchiveEntry(entry);
                tar.write(item.getValue());
                tar.closeArchiveEntry();
            }
        }
        return output.toByteArray();
    }

    private static PackageTransport transport(String tag, Map<String, byte[]> files)
            throws Exception {
        return transport(tag, archive("MegaMek-" + tag, files));
    }

    private static PackageTransport transport(String tag, byte[] archive) throws Exception {
        return new PackageTransport(releaseResponse(tag, archive),
                packageResponse(archive));
    }

    private static PackageTransport preparedTransport(String tag, byte[] archive)
            throws Exception {
        return new PackageTransport(releaseResponse(tag, archive), packageResponse(archive),
                releaseResponse(tag, archive));
    }

    private static Supplier<ReleaseTransport.Response> releaseResponse(String tag, byte[] archive)
            throws Exception {
        String json = """
                {"tag_name":"%s","name":"%s","draft":false,"prerelease":false,
                "html_url":"https://github.com/MegaMek/megamek/releases/tag/%s",
                "assets":[{"name":"MegaMek-%s.tar.gz","size":%d,"digest":"sha256:%s",
                "browser_download_url":"https://github.com/MegaMek/megamek/releases/download/%s/MegaMek-%s.tar.gz"}]}
                """.formatted(tag, tag, tag, tag, archive.length, sha(archive), tag, tag);
        return response(json);
    }

    private static Supplier<ReleaseTransport.Response> packageResponse(byte[] archive) {
        return () -> new ReleaseTransport.Response(
                200, Map.of("content-length", List.of(Integer.toString(archive.length))),
                new ByteArrayInputStream(archive));
    }

    private static Supplier<ReleaseTransport.Response> response(String body) {
        return () -> new ReleaseTransport.Response(200, Map.of(),
                new ByteArrayInputStream(bytes(body)));
    }

    private static PrintStream quiet() {
        return new PrintStream(new ByteArrayOutputStream());
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }

    private static void createDirectoryLink(Path link, Path target) throws Exception {
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            Process process = new ProcessBuilder(System.getenv("ComSpec"), "/d", "/c", "mklink",
                    "/J", link.toString(), target.toString()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            if (process.waitFor() != 0) {
                throw new IOException("could not create disposable test junction: " + output);
            }
        } else {
            Files.createSymbolicLink(link, target);
        }
    }

    private record Fixture(Path registry, Path root, String id) {
    }

    private static final class PackageTransport implements ReleaseTransport {
        private final ArrayDeque<Supplier<Response>> responses;
        private final java.util.ArrayList<URI> requests = new java.util.ArrayList<>();
        private int metadataRequests;
        private int binaryRequests;
        private long binaryBytes;

        @SafeVarargs
        private PackageTransport(Supplier<Response>... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public Response get(URI uri, String accept) throws IOException {
            requests.add(uri);
            Supplier<Response> response = responses.poll();
            if (response == null) throw new IOException("unexpected network request");
            Response supplied = response.get();
            if (!"application/octet-stream".equals(accept)) {
                metadataRequests++;
                return supplied;
            }
            binaryRequests++;
            InputStream counted = new FilterInputStream(supplied.body()) {
                @Override
                public int read() throws IOException {
                    int value = in.read();
                    if (value >= 0) binaryBytes++;
                    return value;
                }

                @Override
                public int read(byte[] buffer, int offset, int length) throws IOException {
                    int count = in.read(buffer, offset, length);
                    if (count > 0) binaryBytes += count;
                    return count;
                }
            };
            return new Response(supplied.status(), supplied.headers(), counted);
        }
    }
}
