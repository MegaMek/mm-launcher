package org.megamek.launcher.onboarding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.megamek.launcher.launch.ApplicationLauncher;
import org.megamek.launcher.launch.JavaRuntime;
import org.megamek.launcher.launch.ProcessRunner;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryData;
import org.megamek.launcher.registry.RegistryStore;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
        Path root = layout(name);
        Map<String, byte[]> metadata = version ? Map.of(
                "Version.properties", "major=0\nminor=51\npatch=01\n".getBytes(
                        StandardCharsets.ISO_8859_1),
                "extraVersion.properties", "branch=main\ngitHash=abc123\n".getBytes(
                        StandardCharsets.ISO_8859_1)) : null;
        jar(root.resolve("lib/dependency.jar"), null, null, null);
        jar(root.resolve("lib/MegaMek.jar"), null, "lib/dependency.jar", metadata);
        jar(root.resolve("MegaMek.jar"), "megamek.MegaMek", "lib/dependency.jar", metadata);
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
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        if (main != null) manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, main);
        if (classPath != null) manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH, classPath);
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
