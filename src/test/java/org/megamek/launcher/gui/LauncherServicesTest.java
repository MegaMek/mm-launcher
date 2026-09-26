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
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LauncherServicesTest {
    @TempDir Path temp;

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
    void registryBelowNonDirectoryIsNotMistakenForFirstRun() throws Exception {
        Path parentFile = Files.writeString(temp.resolve("not-a-directory"), "fixture");
        LauncherServices services = services(parentFile.resolve("registry.json"),
                new RecordingRunner());

        IOException error = assertThrows(IOException.class, services::readRegistry);
        assertTrue(error.getMessage().contains("non-directory"));
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
