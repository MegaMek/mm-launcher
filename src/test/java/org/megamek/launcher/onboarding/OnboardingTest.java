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

package org.megamek.launcher.onboarding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.megamek.launcher.launch.ApplicationLauncher;
import org.megamek.launcher.launch.JavaRuntime;
import org.megamek.launcher.launch.LaunchOptions;
import org.megamek.launcher.launch.LaunchSignals;
import org.megamek.launcher.launch.ProcessIdentity;
import org.megamek.launcher.launch.ProcessRunner;
import org.megamek.launcher.launch.RootCoordinator;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryData;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.release.VersionIdentity;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OnboardingTest {
    @TempDir Path temp;

    @Test
    void detectsRealisticSuitePackagingAndExactVersionWithoutExecuting() throws Exception {
        Path root = suite("suite", true);
        Map<String, Long> before = inventory(root);

        Inspection result = new InstallationInspector().inspect(root);

        assertEquals("0.51.01 branch=main git=abc123", result.observedBuild());
        assertEquals(List.of("lab", "megamek", "mekhq"),
                result.products().stream().map(Product::key).sorted().toList());
        assertEquals(before, inventory(root));
        assertTrue(result.products().stream().allMatch(p -> !p.classPath().isEmpty()));
        assertEquals("recognized-packaging-optional-transitive-missing", result.confidence());
    }

    @Test
    void numericVersionRevisionIsTheFourthComponentOfTheProductVersion() throws Exception {
        Path root = layout("weekly");
        jar(root.resolve("lib/dependency.jar"), null, null, null);
        jar(root.resolve("MegaMek.jar"), "megamek.MegaMek", "lib/dependency.jar",
                Map.of("Version.properties", "major=0\nminor=51\npatch=00\nrevision=2\n".getBytes(
                        StandardCharsets.ISO_8859_1)));

        Inspection result = new InstallationInspector().inspect(root);

        assertEquals("0.51.00.2", result.observedBuild());
        Product megamek = result.products().stream()
                .filter(product -> product.key().equals("megamek")).findFirst().orElseThrow();
        assertEquals(VersionIdentity.fromExact("v0.51.00.2"), VersionIdentity.fromExact(megamek.build()));
    }

    @Test
    void requiresDirectDependenciesButAllowsMissingRelocatedTransitiveDependencies()
            throws Exception {
        Path unknown = suite("unknown", false);
        assertEquals("unknown", new InstallationInspector().inspect(unknown).observedBuild());

        Path incomplete = suite("incomplete", true);
        Files.delete(incomplete.resolve("lib/dependency.jar"));
        assertThrows(IOException.class, () -> new InstallationInspector().inspect(incomplete));
    }

    @Test
    void rejectsExistingCorruptAndUnsafeTransitiveDependencies() throws Exception {
        Path corrupt = layout("corrupt-transitive");
        jar(corrupt.resolve("MegaMek.jar"), "megamek.MegaMek", "lib/bridge.jar", null);
        jar(corrupt.resolve("lib/bridge.jar"), null, "corrupt.jar", null);
        Files.writeString(corrupt.resolve("lib/corrupt.jar"), "not zip");
        assertThrows(IOException.class, () -> new InstallationInspector().inspect(corrupt));

        Path unsafe = layout("unsafe-transitive");
        jar(unsafe.resolve("MegaMek.jar"), "megamek.MegaMek", "lib/bridge.jar", null);
        jar(unsafe.resolve("lib/bridge.jar"), null,
                "missing.jar %2e%2e/%2e%2e/outside.jar", null);
        IOException error = assertThrows(IOException.class,
                () -> new InstallationInspector().inspect(unsafe));
        assertTrue(error.getMessage().contains("unsafe Class-Path entry"));

        Path directory = layout("directory-transitive");
        jar(directory.resolve("MegaMek.jar"), "megamek.MegaMek", "lib/bridge.jar", null);
        jar(directory.resolve("lib/bridge.jar"), null, "directory.jar", null);
        Files.createDirectory(directory.resolve("lib/directory.jar"));
        assertThrows(IOException.class, () -> new InstallationInspector().inspect(directory));
    }

    @Test
    void rejectsUnsafeAndCorruptRootEntries() throws Exception {
        Path unsafe = layout("unsafe");
        jar(unsafe.resolve("MegaMek.jar"), "megamek.MegaMek", "file:/outside.jar", null);
        assertThrows(IOException.class, () -> new InstallationInspector().inspect(unsafe));

        Path corrupt = layout("corrupt");
        Files.writeString(corrupt.resolve("MegaMek.jar"), "not zip");
        assertThrows(IOException.class, () -> new InstallationInspector().inspect(corrupt));
    }

    @Test
    void registryPersistsStableRecordsDefaultsPinsAndRejectsAliasesOverlapAndCorruption()
            throws Exception {
        Path registry = temp.resolve("registry.json");
        RegistryStore store = new RegistryStore();
        Path firstRoot = suite("first", true);
        Path secondRoot = suite("second", false);
        InstallationRecord first = store.register(registry, "Stable", firstRoot, "stay-here");
        InstallationRecord second = store.register(registry, "Nightly", secondRoot, null);
        RegistryData data = store.read(registry);
        assertEquals(first.id(), data.defaultInstallationId());
        assertFalse(first.updateEligible());
        assertEquals("stay-here", first.pin());
        assertThrows(IOException.class,
                () -> store.register(registry, "Alias", firstRoot.resolve("."), null));

        Path nested = suite("first/nested", false);
        assertThrows(IOException.class, () -> store.register(registry, "Nested", nested, null));

        store.select(registry, second.id());
        assertEquals(second.id(), store.read(registry).defaultInstallationId());
        store.remove(registry, second);
        assertEquals(first.id(), store.read(registry).defaultInstallationId());

        byte[] valid = Files.readAllBytes(registry);
        Files.writeString(registry, "{\"schemaVersion\":99}");
        assertThrows(IOException.class, () -> store.read(registry));
        assertThrows(IOException.class, () -> store.register(registry, "No reset", secondRoot, null));
        assertEquals("{\"schemaVersion\":99}", Files.readString(registry));
        Files.write(registry, valid);
        assertThrows(IOException.class,
                () -> store.register(secondRoot.resolve("registry.json"), "Inside", secondRoot, null));
    }

    @Test
    void javaValidationHandlesVersionFailureAndTimeoutWithoutFallback() throws Exception {
        Path fake = Files.writeString(temp.resolve("java"), "fixture");
        ProcessRunner good = (command, cwd, timeout, inherit) ->
                new ProcessRunner.Result(0, "openjdk version \"21.0.4\"", false);
        assertEquals(21, new JavaRuntime(good).validate(fake, temp));

        ProcessRunner old = (command, cwd, timeout, inherit) ->
                new ProcessRunner.Result(0, "java version \"17.0.1\"", false);
        assertThrows(IOException.class, () -> new JavaRuntime(old).validate(fake, temp));
        ProcessRunner timeout = (command, cwd, duration, inherit) ->
                new ProcessRunner.Result(-1, "", true);
        assertThrows(IOException.class, () -> new JavaRuntime(timeout).validate(fake, temp));
    }

    @Test
    void launchUsesDirectArgvWorkingDirectoryAndPropagatesChildExit() throws Exception {
        Path root = suite("launch", true);
        Path java = Files.writeString(temp.resolve("java.exe"), "fixture").toRealPath();
        Inspection inspection = new InstallationInspector().inspect(root);
        InstallationRecord record = new InstallationRecord(
                "12345678-1234-1234-1234-123456789012", "fixture",
                inspection.canonicalRoot(), inspection.observedBuild(), inspection.products(),
                null, false, "2026-01-01T00:00:00Z");
        RecordingRunner runner = new RecordingRunner();
        ApplicationLauncher launcher = new ApplicationLauncher(runner);
        JavaRuntime.CurrentJava gameJava =
                new JavaRuntime(runner).validateExternal(java, root);

        assertEquals(7, launcher.launch(record, "megamek", gameJava));
        assertEquals(2, runner.commands.size());
        assertEquals(List.of(java.toString(), "-version"), runner.commands.get(0));
        List<String> launched = runner.commands.get(1);
        assertEquals(java.toString(), launched.getFirst());
        assertTrue(launched.contains("megamek.MegaMek"));
        assertFalse(launched.stream().anyMatch(value -> value.equals("cmd")
                || value.equals("sh") || value.equals("-jar")));
        assertEquals(root.toRealPath(), runner.workingDirectories.get(1));
    }

    @Test
    void launchWaitsForTheReadyFileOfAnApplicationThatReportsReadiness() throws Exception {
        Path root = suite("ready", true, true);
        Path java = Files.writeString(Files.createDirectories(temp.resolve("ready-jdk")).resolve("java.exe"), "fixture").toRealPath();
        InstallationRecord record = record(root);
        LaunchSignals signals = new LaunchSignals(temp.resolve("signals"));
        SignallingRunner runner = new SignallingRunner(true);
        ApplicationLauncher launcher = new ApplicationLauncher(runner, new RootCoordinator(temp.resolve("coordination")),
                signals);

        assertEquals(0, launcher.launch(record, "megamek",
                new JavaRuntime(runner).validateExternal(java, root), LaunchOptions.builtIn(),
                runner.ready::countDown));

        assertEquals(0, runner.ready.getCount());
        Path readyFile = runner.readyFile;
        assertEquals(temp.resolve("signals").toAbsolutePath().normalize(), readyFile.getParent());
        assertFalse(Files.exists(readyFile), "the launcher removes the ready file it saw");
        assertFalse(launcher.command(record, "megamek",
                        new JavaRuntime(runner).validateExternal(java, root)).stream()
                        .anyMatch(value -> value.contains(LaunchSignals.READY_FILE_PROPERTY)),
                "copied launch commands never carry a launch's ready file");
    }

    @Test
    void launchReportsAnApplicationWithoutReadinessAsReadyOnceStarted() throws Exception {
        Path root = suite("plain", true);
        Path java = Files.writeString(Files.createDirectories(temp.resolve("plain-jdk")).resolve("java.exe"), "fixture").toRealPath();
        LaunchSignals signals = new LaunchSignals(temp.resolve("signals"));
        SignallingRunner runner = new SignallingRunner(false);
        ApplicationLauncher launcher = new ApplicationLauncher(runner, new RootCoordinator(temp.resolve("coordination")),
                signals);

        assertEquals(0, launcher.launch(record(root), "megamek",
                new JavaRuntime(runner).validateExternal(java, root), LaunchOptions.builtIn(),
                runner.ready::countDown));

        assertEquals(0, runner.ready.getCount());
        assertEquals(null, runner.readyFile);
    }

    @Test
    void manifestAttributeReadsTheProductJarAndToleratesMissingJars() throws Exception {
        Path root = suite("manifest", true, true);
        InstallationInspector inspector = new InstallationInspector();

        assertEquals(java.util.Optional.of("1"), inspector.manifestAttribute(root, "MegaMek.jar",
                LaunchSignals.READY_MANIFEST_ATTRIBUTE));
        assertEquals(java.util.Optional.empty(), inspector.manifestAttribute(root, "MekHQ.jar",
                LaunchSignals.READY_MANIFEST_ATTRIBUTE));
        assertEquals(java.util.Optional.empty(), inspector.manifestAttribute(root, "Missing.jar",
                LaunchSignals.READY_MANIFEST_ATTRIBUTE));
        assertEquals(java.util.Optional.empty(), inspector.manifestAttribute(root, "../outside.jar",
                LaunchSignals.READY_MANIFEST_ATTRIBUTE));
    }

    private static InstallationRecord record(Path root) throws IOException {
        Inspection inspection = new InstallationInspector().inspect(root);
        return new InstallationRecord(
                "12345678-1234-1234-1234-123456789012", "fixture",
                inspection.canonicalRoot(), inspection.observedBuild(), inspection.products(),
                null, false, "2026-01-01T00:00:00Z");
    }

    @Test
    void benignFixtureActuallyLaunchesAndDoesNotWriteInstallation() throws Exception {
        Path root = layout("smoke");
        byte[] mainClass;
        try (InputStream input = getClass().getResourceAsStream("/megamek/MegaMek.class")) {
            mainClass = input.readAllBytes();
        }
        jar(root.resolve("MegaMek.jar"), "megamek.MegaMek", "lib/dependency.jar",
                Map.of("megamek/MegaMek.class", mainClass,
                        "Version.properties", "major=0\nminor=51\npatch=01\n".getBytes(
                                StandardCharsets.ISO_8859_1)));
        jar(root.resolve("lib/dependency.jar"), null, null, null);
        Inspection inspection = new InstallationInspector().inspect(root);
        Path java = new JavaRuntime().candidates().getFirst().toRealPath();
        InstallationRecord record = new InstallationRecord(
                "12345678-1234-1234-1234-123456789012", "fixture",
                inspection.canonicalRoot(), inspection.observedBuild(), inspection.products(),
                null, false, "2026-01-01T00:00:00Z");
        Map<String, Long> before = inventory(root);

        JavaRuntime runtime = new JavaRuntime();
        int exit = new ApplicationLauncher().launch(record, "megamek",
                runtime.validateExternal(java, root));

        assertEquals(0, exit);
        assertEquals(before, inventory(root));
    }

    private Path suite(String name, boolean version) throws Exception {
        return suite(name, version, false);
    }

    private Path suite(String name, boolean version, boolean reportsReady) throws Exception {
        Path root = layout(name);
        Map<String, byte[]> metadata = version ? Map.of(
                "Version.properties", "major=0\nminor=51\npatch=01\n".getBytes(
                        StandardCharsets.ISO_8859_1),
                "extraVersion.properties", "branch=main\ngitHash=abc123\n".getBytes(
                        StandardCharsets.ISO_8859_1)) : null;
        jar(root.resolve("lib/dependency.jar"), null, null, null);
        jar(root.resolve("lib/MegaMek.jar"), null, "lib/dependency.jar", metadata);
        jar(root.resolve("MegaMek.jar"), "megamek.MegaMek", "lib/dependency.jar", metadata,
                reportsReady ? Map.of(LaunchSignals.READY_MANIFEST_ATTRIBUTE, "1") : Map.of());
        jar(root.resolve("MegaMekLab.jar"), "megameklab.MegaMekLab",
                "lib/MegaMek.jar lib/dependency.jar", null);
        jar(root.resolve("MekHQ.jar"), "mekhq.MekHQ",
                "MegaMek.jar MegaMekLab.jar lib/dependency.jar", null);
        return root;
    }

    private Path layout(String name) throws IOException {
        Path root = Files.createDirectory(temp.resolve(name));
        Files.createDirectories(root.resolve("data"));
        Files.createDirectories(root.resolve("mmconf"));
        Files.createDirectories(root.resolve("lib"));
        return root;
    }

    private static void jar(Path path, String main, String classPath, Map<String, byte[]> entries)
            throws IOException {
        jar(path, main, classPath, entries, Map.of());
    }

    private static void jar(Path path, String main, String classPath, Map<String, byte[]> entries,
                            Map<String, String> attributes) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (main != null) manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, main);
        if (classPath != null) manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, classPath);
        attributes.forEach(manifest.getMainAttributes()::putValue);
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            if (entries != null) {
                for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                    output.putNextEntry(new JarEntry(entry.getKey()));
                    output.write(entry.getValue());
                    output.closeEntry();
                }
            }
        }
    }

    private static Map<String, Long> inventory(Path root) throws IOException {
        Map<String, Long> result = new HashMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                result.put(root.relativize(path).toString(), Files.size(path));
            }
        }
        return result;
    }

    /** Validates Java, then plays a started game that writes its ready file when asked to. */
    private static final class SignallingRunner implements ProcessRunner {
        final CountDownLatch ready = new CountDownLatch(1);
        private final boolean writeReady;
        volatile Path readyFile;

        SignallingRunner(boolean writeReady) {
            this.writeReady = writeReady;
        }

        @Override
        public Result run(List<String> command, Path workingDirectory, Duration timeout,
                          boolean inheritIo) {
            return new Result(0, "openjdk version \"21.0.4\"", false);
        }

        @Override
        public Result runTracked(List<String> command, Path workingDirectory, Duration timeout,
                                 boolean inheritIo, Consumer<ProcessIdentity> started)
                throws IOException, InterruptedException {
            String prefix = "-D" + LaunchSignals.READY_FILE_PROPERTY + "=";
            readyFile = command.stream().filter(value -> value.startsWith(prefix))
                    .map(value -> Path.of(value.substring(prefix.length()))).findFirst().orElse(null);
            started.accept(ProcessIdentity.of(ProcessHandle.current()));
            if (writeReady) {
                Files.createFile(readyFile);
            }
            if (!ready.await(10, TimeUnit.SECONDS)) {
                throw new IOException("the launcher never reported the game as ready");
            }
            return new Result(0, "", false);
        }
    }

    private static final class RecordingRunner implements ProcessRunner {
        final java.util.ArrayList<List<String>> commands = new java.util.ArrayList<>();
        final java.util.ArrayList<Path> workingDirectories = new java.util.ArrayList<>();

        @Override
        public Result run(List<String> command, Path workingDirectory, Duration timeout,
                          boolean inheritIo) {
            commands.add(List.copyOf(command));
            workingDirectories.add(workingDirectory);
            return commands.size() == 1
                    ? new Result(0, "openjdk version \"21.0.4\"", false)
                    : new Result(7, "", false);
        }
    }
}
