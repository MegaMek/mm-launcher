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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.megamek.launcher.launch.ApplicationLauncher;
import org.megamek.launcher.launch.JavaRuntime;
import org.megamek.launcher.launch.ProcessRunner;
import org.megamek.launcher.launch.RootCoordinator;
import org.megamek.launcher.onboarding.InstallationInspector;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.release.OfficialRepository;
import org.megamek.launcher.release.ReleaseCatalog;
import org.megamek.launcher.release.ReleaseTransport;
import org.megamek.launcher.update.WindowsMsiUpdate;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class LauncherServicesTest {
    @TempDir Path temp;

    private static WindowsMsiUpdate.Candidate launcherCandidate() {
        String name = "MegaMek-Launcher-0.14.12-windows-x64.msi";
        return new WindowsMsiUpdate.Candidate("v0.14.12", "0.14.12", name,
                URI.create("https://github.com/MegaMek/mm-launcher/releases/download/v0.14.12/" + name),
                100, "a".repeat(64));
    }

    @Test
    void confirmedLauncherUpdatesAreQuietAndRecordedBeforeReleaseLookup() throws Exception {
        LauncherServices services = services(temp.resolve("registry.json"), new RecordingRunner());
        var candidate = launcherCandidate();
        String detail = "Launcher updated to 0.14.11.";
        var report = new WindowsMsiUpdate.ReportResult(WindowsMsiUpdate.ReportState.INSTALLED, detail);
        AtomicInteger lookups = new AtomicInteger();
        for (boolean newerRelease : List.of(false, true)) {
            var checked = services.checkLauncherUpdate(report, () -> {
                lookups.incrementAndGet();
                String logs = services.operationLogsForViewer();
                assertTrue(logs.contains(detail));
                assertTrue(logs.contains("Launcher update confirmed successfully"));
                assertFalse(logs.contains("previous installer completion result is unknown"));
                return newerRelease ? candidate : null;
            });
            assertNull(checked.message(), "clean success must not enter a popup or confirmation notice");
            assertFalse(checked.pending());
            assertSame(newerRelease ? candidate : null, checked.candidate());
        }
        assertEquals(2, lookups.get());
        assertEquals(detail, report.message());
    }

    @Test
    void recoveredLauncherReportsAreDiagnosticOnlyAndKeepTheirDetails() throws Exception {
        LauncherServices services = services(temp.resolve("registry.json"), new RecordingRunner());
        var candidate = launcherCandidate();
        AtomicInteger lookups = new AtomicInteger();
        for (var state : List.of(WindowsMsiUpdate.ReportState.RECOVERED,
                                WindowsMsiUpdate.ReportState.INSTALLED_RECOVERED)) {
            String detail = "Recovery " + state + ": Windows confirms 0.14.10; previous completion unknown; "
                    + "retained report at " + temp.resolve("msi-update-result.txt.incomplete-fixture");
            var report = new WindowsMsiUpdate.ReportResult(state, detail);
            for (boolean newerRelease : List.of(false, true)) {
                var checked = services.checkLauncherUpdate(report, () -> {
                    lookups.incrementAndGet();
                    return newerRelease ? candidate : null;
                });
                assertNull(checked.message(),
                        "recovery must not enter a popup, status message or update-confirmation notice");
                assertFalse(checked.pending(), "recovery does not block update checks");
                assertSame(newerRelease ? candidate : null, checked.candidate());
            }
            assertEquals(detail, report.message(), "the diagnostic detail must remain available");
            String logs = services.operationLogsForViewer();
            assertTrue(logs.contains("Recovery " + state));
            assertTrue(logs.contains("previous completion unknown"));
            assertTrue(logs.contains("msi-update-result.txt.incomplete-fixture"));
            assertTrue(logs.contains("Stale launcher update report reconciled"));
        }
        assertEquals(4, lookups.get());
    }

    @Test
    void actionableLauncherReportsKeepTheirOriginalNotice() throws Exception {
        LauncherServices services = services(temp.resolve("registry.json"), new RecordingRunner());
        IOException failure = assertThrows(IOException.class, () -> services.checkLauncherUpdate(
                new WindowsMsiUpdate.ReportResult(WindowsMsiUpdate.ReportState.FAILED, "Installer failed"),
                () -> fail("failed installation must be surfaced before release lookup")));
        assertEquals("Installer failed", failure.getMessage());
        var pending = services.checkLauncherUpdate(
                new WindowsMsiUpdate.ReportResult(WindowsMsiUpdate.ReportState.PENDING, "Still finishing"),
                () -> fail("active helper must finish before release lookup"));
        assertTrue(pending.pending());
        assertEquals("Still finishing", pending.message());
        var candidate = launcherCandidate();
        for (String notice : List.of("A restart is required", "Staged MSI cleanup failed")) {
            for (boolean newerRelease : List.of(false, true)) {
                var checked = services.checkLauncherUpdate(
                        new WindowsMsiUpdate.ReportResult(WindowsMsiUpdate.ReportState.INSTALLED_WARNING, notice),
                        () -> newerRelease ? candidate : null);
                assertEquals(notice, checked.message());
                assertFalse(checked.pending());
                assertSame(newerRelease ? candidate : null, checked.candidate());
            }
        }
        assertNull(services.checkLauncherUpdate(null, () -> null).message());
        assertFalse(Files.exists(services.operationLogLocation()), "ordinary notices do not create recovery logs");
    }

    @Test
    void quietReportDetailsDoNotGetAppendedToLookupFailuresOrInterruptions() throws Exception {
        LauncherServices services = services(temp.resolve("registry.json"), new RecordingRunner());
        for (var state : List.of(WindowsMsiUpdate.ReportState.INSTALLED,
                                WindowsMsiUpdate.ReportState.RECOVERED,
                                WindowsMsiUpdate.ReportState.INSTALLED_RECOVERED)) {
            var report = new WindowsMsiUpdate.ReportResult(state,
                    state == WindowsMsiUpdate.ReportState.INSTALLED
                            ? "Launcher updated to 0.14.11." : "Retained old report; no action required");
            IOException offline = new IOException("Release lookup is offline");
            assertSame(offline, assertThrows(IOException.class,
                    () -> services.checkLauncherUpdate(report, () -> { throw offline; })));
            InterruptedException interrupted = new InterruptedException("Release lookup interrupted");
            assertSame(interrupted, assertThrows(InterruptedException.class,
                    () -> services.checkLauncherUpdate(report, () -> { throw interrupted; })));
        }
        String notice = "Windows requires a restart";
        IOException lookupError = new IOException("Release lookup is offline");
        IOException combined = assertThrows(IOException.class, () -> services.checkLauncherUpdate(
                new WindowsMsiUpdate.ReportResult(WindowsMsiUpdate.ReportState.INSTALLED_WARNING, notice),
                () -> { throw lookupError; }));
        assertTrue(combined.getMessage().contains(notice));
        assertTrue(combined.getMessage().contains(lookupError.getMessage()));
        assertSame(lookupError, combined.getCause());
    }

    @Test
    void quietReportDiagnosticWriteFailuresAreExplicitAndDoNotChangeTheResult() throws Exception {
        LauncherServices services = services(temp.resolve("registry.json"), new RecordingRunner());
        Files.writeString(services.operationLogLocation(), "not a log directory");
        for (var state : List.of(WindowsMsiUpdate.ReportState.INSTALLED,
                                WindowsMsiUpdate.ReportState.RECOVERED,
                                WindowsMsiUpdate.ReportState.INSTALLED_RECOVERED)) {
            var report = new WindowsMsiUpdate.ReportResult(state, "Fixture updater report details.");
            IOException error = assertThrows(IOException.class, () -> services.checkLauncherUpdate(report,
                    () -> fail("diagnostic write failure must be surfaced explicitly")));
            assertTrue(error.getMessage().contains("Local operation log could not be saved"));
            assertEquals(state, report.state());
            assertEquals(state != WindowsMsiUpdate.ReportState.RECOVERED, report.installed());
        }
        assertEquals("not a log directory", Files.readString(services.operationLogLocation()));
    }

    @Test
    void firstRunIsEmptyButExistingCorruptRegistryIsNotReset() throws Exception {
        Path registry = temp.resolve("registry.json");
        LauncherServices services = services(registry, new RecordingRunner());
        assertTrue(services.readRegistry().installations().isEmpty());
        assertFalse(Files.exists(registry));

        Files.writeString(registry, "{broken");
        assertThrows(IOException.class, services::readRegistry);
        assertEquals("{broken", Files.readString(registry));
    }

    @Test
    void repairSourceRejectsImportedAndStaleSelectionsWithoutDownloading() throws Exception {
        Path registry = temp.resolve("repair-registry.json");
        LauncherServices services = services(registry, new RecordingRunner());
        InstallationRecord imported = services.register("Imported", suite("repair-import"));
        assertThrows(IOException.class, () -> services.repairSource(imported));
        InstallationRecord stale = new InstallationRecord(imported.id(), "Old selection",
                imported.canonicalRoot(), imported.observedBuild(), imported.products(),
                imported.pin(), imported.updateEligible(), imported.registeredAt());
        IOException error = assertThrows(IOException.class,
                () -> services.repairSource(stale));
        assertTrue(error.getMessage().contains("selected installation changed"));
    }

    @Test
    void registryBelowNonDirectoryIsNotMistakenForFirstRun() throws Exception {
        Path parentFile = Files.writeString(temp.resolve("not-a-directory"), "fixture");
        LauncherServices services = services(parentFile.resolve("registry.json"),
                new RecordingRunner());

        IOException error = assertThrows(IOException.class, services::readRegistry);
        assertTrue(error instanceof java.nio.file.NotDirectoryException
                        || error.getMessage().contains("non-directory")
                        || error.getMessage().toLowerCase(java.util.Locale.ROOT)
                                .contains("not a directory"),
                () -> "Expected a non-directory error, got " + error);
    }

    @Test
    void homePreservesValidPreferredAndReportsMissingOrChangedPreferred() throws Exception {
        RecordingRunner runner = new RecordingRunner();
        LauncherServices services = services(temp.resolve("registry.json"), runner);
        Path root = suite("preferred");
        services.register("Preferred", root);

        LauncherServices.HomeState valid = services.loadHome();
        assertEquals("Preferred", valid.preferred().name());
        assertEquals(null, valid.preferredError());
        assertTrue(runner.commands.isEmpty(), "Home rendering must not execute Java");

        Files.delete(root.resolve("MegaMek.jar"));
        LauncherServices.HomeState missing = services.loadHome();
        assertEquals("Preferred", missing.preferred().name());
        assertTrue(missing.preferredError().contains("unavailable"));

        writeJar(root.resolve("MegaMek.jar"), "megamek.MegaMek");
        writeJar(root.resolve("MekHQ.jar"), "mekhq.MekHQ");
        LauncherServices.HomeState changed = services.loadHome();
        assertEquals("Preferred", changed.preferred().name());
        assertTrue(changed.preferredError().contains("no longer matches"));
    }

    @Test
    void inspectionDoesNotExecuteAndRegistrationRequiresSeparateCall() throws Exception {
        Path root = suite("copy");
        Path registry = temp.resolve("registry.json");
        RecordingRunner runner = new RecordingRunner();
        LauncherServices services = services(registry, runner);

        assertEquals("unknown", services.inspect(root).observedBuild());
        assertFalse(Files.exists(registry));
        assertTrue(runner.commands.isEmpty());

        services.register("Existing copy", root);
        assertEquals(1, services.readRegistry().installations().size());
        assertTrue(runner.commands.isEmpty());
    }

    @Test
    void multipleNamedRecordsPreserveDefaultAndRemovalPreservesFiles() throws Exception {
        LauncherServices services = services(temp.resolve("registry.json"), new RecordingRunner());
        Path firstRoot = suite("first");
        Path secondRoot = suite("second");
        InstallationRecord first = services.register("First", firstRoot);
        InstallationRecord second = services.register("Second", secondRoot);
        assertEquals(first.id(), services.readRegistry().defaultInstallationId());

        services.select(second);
        assertEquals(second.id(), services.readRegistry().defaultInstallationId());
        services.removeFromLauncher(second);
        assertEquals(first.id(), services.readRegistry().defaultInstallationId());
        assertTrue(Files.isRegularFile(secondRoot.resolve("MegaMek.jar")));
    }

    @Test
    void homeTargetsUseProductUnionAndIndependentRegistryPreferences() throws Exception {
        LauncherServices services = services(
                temp.resolve("per-app.json"), new RecordingRunner());
        InstallationRecord mega = services.register("Mega only", suite("mega-only"));
        Path suite = suite("suite");
        writeJar(suite.resolve("MekHQ.jar"), "mekhq.MekHQ");
        writeJar(suite.resolve("MegaMekLab.jar"), "megameklab.MegaMekLab");
        InstallationRecord bundle = services.register("Bundle", suite);

        LauncherServices.HomeState home = services.loadHome();

        assertEquals(mega, home.preferredApplications().get("megamek"));
        assertEquals(bundle, home.preferredApplications().get("mekhq"));
        assertEquals(bundle, home.preferredApplications().get("lab"));
        assertEquals(java.util.List.of("mekhq", "megamek", "lab"),
                java.util.List.copyOf(home.preferredApplications().keySet()));
    }

    @Test
    void missingPublishedDigestRemainsEligibleForOfficialExactTransfer() throws Exception {
        ReleaseCatalog.Asset asset = new ReleaseCatalog.Asset("MekHQ-v0.50.02.tar.gz", 42,
                java.util.Optional.empty(),
                URI.create("https://github.com/MegaMek/mekhq/releases/download/"
                + "v0.50.02/MekHQ-v0.50.02.tar.gz"));
        ReleaseCatalog.Release release = new ReleaseCatalog.Release("v0.50.02", "0.50.02",
                false, false, URI.create("https://github.com/MegaMek/mekhq/releases/tag/v0.50.02"),
                List.of(asset));
        ReleaseCatalog catalog = new ReleaseCatalog(new UnusedTransport());

        ReleaseCatalog.Assessment assessment = catalog.assess(OfficialRepository.MEKHQ, release);
        assertTrue(assessment.eligible());
        assertEquals("Available", assessment.reason());
        assertEquals(asset,
                catalog.selectInstallAsset(OfficialRepository.MEKHQ, release));
    }

    @Test
    void javaRefusalAndPreviewDoNotStartGameUntilLaunch() throws Exception {
        Path root = suite("launch");
        Path registry = temp.resolve("registry.json");
        RecordingRunner runner = new RecordingRunner();
        LauncherServices services = services(registry, runner);
        InstallationRecord initialRecord = services.register("Launch", root);
        Path containedJava = Files.writeString(root.resolve("java.exe"), "fixture");
        assertThrows(IOException.class, () -> services.selectDefaultJava(containedJava));
        assertTrue(runner.commands.isEmpty());

        Path externalJava = Files.writeString(temp.resolve("java.exe"), "fixture");
        services.selectDefaultJava(externalJava);
        InstallationRecord record = services.readRegistry().installations().getFirst();
        int afterSelection = runner.commands.size();
        List<String> preview = services.preview(record, "megamek");
        assertTrue(preview.contains("megamek.MegaMek"));
        assertEquals(afterSelection + 1, runner.commands.size());
        assertFalse(runner.inheritIO.getLast());

        assertEquals(0, services.launch(record, "megamek"));
        assertTrue(runner.inheritIO.getLast());

        services.removeFromLauncher(record);
        assertTrue(assertThrows(IOException.class,
                () -> services.launch(record, "megamek"))
                .getMessage().contains("no longer registered"),
                "a removed captured record cannot fall back to another copy");
    }

    @Test
    void oneGameJavaSettingImmediatelyControlsEveryInstallation() throws Exception {
        Path registry = temp.resolve("global-java.json");
        RecordingRunner runner = new RecordingRunner();
        LauncherServices services = services(registry, runner);
        InstallationRecord first = services.register("First", suite("global-first"));
        InstallationRecord second = services.register("Second", suite("global-second"));

        LauncherServices.SettingsView fallback = services.settingsView();
        assertFalse(fallback.persisted());
        assertEquals(new JavaRuntime(runner).currentExecutable(), fallback.defaultJava());
        assertEquals(new JavaRuntime(runner).currentExecutable().toString(),
                services.preview(first, "megamek").getFirst());

        Path javaOne = Files.writeString(
                Files.createDirectories(temp.resolve("jdk-one").resolve("bin"))
                        .resolve("java.exe"), "one");
        services.selectDefaultJava(javaOne);
        assertEquals(javaOne.toRealPath().toString(),
                services.preview(first, "megamek").getFirst());
        assertEquals(javaOne.toRealPath().toString(),
                services.preview(second, "megamek").getFirst());

        Path javaTwo = Files.writeString(
                Files.createDirectories(temp.resolve("jdk-two").resolve("bin"))
                        .resolve("java.exe"), "two");
        services.selectDefaultJava(javaTwo);
        assertEquals(javaTwo.toRealPath().toString(),
                services.preview(first, "megamek").getFirst());
        assertEquals(javaTwo.toRealPath().toString(),
                services.preview(second, "megamek").getFirst());
        Files.delete(javaTwo);
        assertTrue(assertThrows(IOException.class,
                () -> services.preview(first, "megamek"))
                .getMessage().contains("Java"));
    }

    @Test
    void corruptExplicitSettingsFailSettingsAndLaunchButNotRegistration() throws Exception {
        Path registry = temp.resolve("corrupt-settings.json");
        RecordingRunner runner = new RecordingRunner();
        LauncherServices services = services(registry, runner);
        InstallationRecord record = services.register("Existing", suite("corrupt-copy"));
        Files.writeString(new LauncherSettingsStore(registry).path(), "{broken");

        assertThrows(IOException.class, services::settingsView);
        assertThrows(IOException.class, () -> services.preview(record, "megamek"));
        assertTrue(runner.commands.isEmpty(),
                "corrupt settings fail before any Java process is executed");
        assertEquals(1, services.readRegistry().installations().size());
    }

    @Test
    void backgroundSeamRunsOffEdtAndBusyGateCanResetAfterError() throws Exception {
        BusyGate gate = new BusyGate();
        assertTrue(gate.tryEnter());
        assertFalse(gate.tryEnter());
        gate.leave(); // the SwingWorker done/error path owns this same unconditional reset
        assertTrue(gate.tryEnter());
        gate.leave();

        CountDownLatch finished = new CountDownLatch(1);
        boolean[] wasEdt = {true};
        javax.swing.SwingWorker<Void, Void> worker = new javax.swing.SwingWorker<>() {
            @Override protected Void doInBackground() {
                wasEdt[0] = javax.swing.SwingUtilities.isEventDispatchThread();
                return null;
            }
            @Override protected void done() { finished.countDown(); }
        };
        worker.execute();
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        assertFalse(wasEdt[0]);
    }

    private LauncherServices services(Path registry, RecordingRunner runner) {
        return new LauncherServices(registry, new RegistryStore(), new InstallationInspector(),
                new UnusedTransport(), new JavaRuntime(runner), new ApplicationLauncher(runner),
                new RootCoordinator(temp.resolve("coord-" + registry.getFileName())));
    }

    private Path suite(String name) throws Exception {
        Path root = Files.createDirectory(temp.resolve(name));
        Files.createDirectories(root.resolve("data"));
        Files.createDirectories(root.resolve("mmconf"));
        Files.createDirectories(root.resolve("lib"));
        writeJar(root.resolve("MegaMek.jar"), "megamek.MegaMek");
        return root;
    }

    private static void writeJar(Path path, String mainClass) throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            // Static inspection needs only the manifest for this minimal fixture.
        }
    }

    private static final class RecordingRunner implements ProcessRunner {
        final java.util.ArrayList<List<String>> commands = new java.util.ArrayList<>();
        final java.util.ArrayList<Boolean> inheritIO = new java.util.ArrayList<>();

        @Override public Result run(List<String> command, Path workingDirectory, Duration timeout,
                                    boolean inherit) {
            commands.add(List.copyOf(command));
            inheritIO.add(inherit);
            return new Result(0, inherit ? "" : "openjdk version \"21.0.4\"", false);
        }
    }

    private static final class UnusedTransport implements ReleaseTransport {
        @Override public Response get(URI uri, String accept) {
            throw new AssertionError("network was not expected");
        }
    }
}
