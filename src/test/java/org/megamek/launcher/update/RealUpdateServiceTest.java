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
import org.megamek.launcher.plan.Action;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryData;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.release.FreshInstaller;
import org.megamek.launcher.release.OfficialRepository;
import org.megamek.launcher.release.ReleaseTransport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import java.util.concurrent.atomic.AtomicBoolean;
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
        assertEquals(null, first.record().javaExecutable(),
                "an update must not invent a Java selection");
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
        assertEquals(null, second.record().javaExecutable(),
                "a repeated update must not invent a Java selection");
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
        assertEquals(null, third.record().javaExecutable());
        RegistryStore registryStore = new RegistryStore();
        InstallationRecord beforeJavaSelection = registryStore.resolve(
                registryStore.read(fixture.registry), fixture.id);
        assertEquals("4.0.0", beforeJavaSelection.observedBuild());
        assertEquals(null, beforeJavaSelection.javaExecutable());
        assertEquals("user-collision", Files.readString(root.resolve("data/collision.txt")));
        assertEquals("case-old", Files.readString(root.resolve("data/Icons/unit.png")));

        Path java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                        ? "java.exe" : "java");
        registryStore.selectJava(fixture.registry, fixture.id, java.toString());
        var launchRecord = registryStore.resolve(registryStore.read(fixture.registry), fixture.id);
        ProcessRunner benign = (command, workingDirectory, timeout, inheritIo) ->
                new ProcessRunner.Result(0, "openjdk version \"21.0.1\"", false);
        List<String> launch = new ApplicationLauncher(benign).command(launchRecord, "megamek");
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
    void selectedJavaAndRegistryPreferencesSurviveRepeatedUpdatesAndFreshLoadLaunch()
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
        assertEquals(21, selectingServices.selectJava(installed, java));

        Path unrelatedRoot = fixture.registry.getParent().resolve("unrelated-copy");
        materialize(unrelatedRoot, packageFiles(9, Map.of(
                "lib/runtime.txt", bytes("unrelated-runtime"))));
        InstallationRecord unrelated = store.register(
                fixture.registry, "Unrelated copy", unrelatedRoot, "keep-unrelated-pin");
        Path unrelatedJava = fakeJava("unrelated-jdk");
        store.selectJava(fixture.registry, unrelated.id(), unrelatedJava.toString());

        RegistryData beforeData = store.read(fixture.registry);
        InstallationRecord before = store.resolve(beforeData, fixture.id);
        InstallationRecord unrelatedBefore = store.resolve(beforeData, unrelated.id());
        ChannelPreferenceStore channels = new ChannelPreferenceStore();
        channels.set(fixture.registry, before, FollowChannel.DEVELOPMENT, true);
        assertEquals(java.toRealPath().toString(), before.javaExecutable());
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
        assertEquals(FollowChannel.DEVELOPMENT,
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
        assertEquals(FollowChannel.DEVELOPMENT,
                channels.read(fixture.registry, secondData, secondOnDisk).preference().channel());

        LauncherServices reloadedServices = launcherServices(fixture.registry, benign);
        LauncherServices.HomeState home = reloadedServices.loadHome();
        assertEquals(null, home.preferredError());
        assertEquals(before.javaExecutable(), home.preferred().javaExecutable());
        assertIdentityAndPreferences(before, home.preferred());
        List<String> command = reloadedServices.preview(home.preferred(), "megamek");
        assertEquals(before.javaExecutable(), command.getFirst());
        assertTrue(command.contains("megamek.MegaMek"));
        assertEquals(0, reloadedServices.launch(home.preferred(), "megamek"),
                "benign launch must not require selecting Java again");
    }

    @Test
    void registryRefreshUsesLatestJavaSelectedDuringUpdateInsteadOfSnapshotValue()
            throws Exception {
        for (String changePoint : List.of("BEFORE_APP_MUTATION", "AFTER_STATE_COMMIT")) {
            Fixture fixture = install("java-race-" + changePoint.toLowerCase(Locale.ROOT),
                    "v1", packageFiles(1, Map.of(
                            "lib/runtime.txt", bytes("runtime-1"),
                            "docs/change.txt", bytes("old"))));
            RegistryStore store = new RegistryStore();
            Path oldJava = fakeJava("old-jdk-" + changePoint.toLowerCase(Locale.ROOT));
            Path latestJava = fakeJava("latest-jdk-" + changePoint.toLowerCase(Locale.ROOT));
            if (changePoint.equals("BEFORE_APP_MUTATION")) {
                store.selectJava(fixture.registry, fixture.id, oldJava.toString());
            }

            byte[] archive = archive("MegaMek-v2", packageFiles(2, Map.of(
                    "lib/runtime.txt", bytes("runtime-2"),
                    "docs/change.txt", bytes("new"))));
            AtomicBoolean changed = new AtomicBoolean();
            RealUpdateService service = service(transport("v2", archive), point -> {
                if (point.equals(changePoint)) {
                    store.selectJava(fixture.registry, fixture.id, latestJava.toString());
                    changed.set(true);
                }
            });
            RealUpdateService.Snapshot snapshot = service.snapshot(fixture.registry, fixture.id);
            assertEquals(changePoint.equals("BEFORE_APP_MUTATION")
                            ? oldJava.toString() : null,
                    snapshot.record().javaExecutable());

            RealUpdateService.ApplyResult result = service.apply(snapshot, "v2", archive.length,
                    "sha256:" + sha(archive), RealUpdateService.CONFIRM, quiet());

            assertTrue(changed.get());
            assertEquals(latestJava.toString(), result.record().javaExecutable());
            assertEquals(latestJava.toString(), store.resolve(
                    store.read(fixture.registry), fixture.id).javaExecutable());
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
            store.selectJava(fixture.registry, fixture.id, java.toString());
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
            assertEquals(java.toString(), first.record().javaExecutable());
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
        new ReceiptStore().write(registry, store.read(registry), record,
                OfficialRepository.MEGAMEK, tag, "MegaMek-" + tag + ".tar.gz",
                sourceArchive.length, "sha256:" + sha(sourceArchive),
                new OwnershipPolicy().build(root, OfficialRepository.MEGAMEK, tag));
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
        assertEquals(expected.javaExecutable(), actual.javaExecutable());
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
        String json = """
                {"tag_name":"%s","name":"%s","draft":false,"prerelease":false,
                "html_url":"https://github.com/MegaMek/megamek/releases/tag/%s",
                "assets":[{"name":"MegaMek-%s.tar.gz","size":%d,"digest":"sha256:%s",
                "browser_download_url":"https://github.com/MegaMek/megamek/releases/download/%s/MegaMek-%s.tar.gz"}]}
                """.formatted(tag, tag, tag, tag, archive.length, sha(archive), tag, tag);
        return new PackageTransport(response(json), () -> new ReleaseTransport.Response(
                200, Map.of("content-length", List.of(Integer.toString(archive.length))),
                new ByteArrayInputStream(archive)));
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

        @SafeVarargs
        private PackageTransport(Supplier<Response>... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public Response get(URI uri, String accept) throws IOException {
            requests.add(uri);
            Supplier<Response> response = responses.poll();
            if (response == null) throw new IOException("unexpected network request");
            return response.get();
        }
    }
}
