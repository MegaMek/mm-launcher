package org.megamek.launcher.launch;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.megamek.launcher.onboarding.ExistingImportService;
import org.megamek.launcher.onboarding.InstallationInspector;
import org.megamek.launcher.operation.OperationContext;
import org.megamek.launcher.operation.OperationType;
import org.megamek.launcher.registry.RegistryStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaRuntimeTest {
    @TempDir Path temp;

    @Test
    void currentExecutableUsesInjectedJavaHomeAndOperatingSystemFilename() throws Exception {
        Path windowsHome = runtimeHome("windows-home", "java.exe");
        RecordingRunner windowsRunner = new RecordingRunner();
        JavaRuntime windows = new JavaRuntime(windowsRunner, windowsHome::toString,
                () -> "Windows 11");
        assertEquals(windowsHome.resolve("bin/java.exe").toRealPath(),
                windows.currentExecutable());
        assertEquals(21, windows.currentFeature());
        assertEquals(List.of(windows.currentExecutable().toString(), "-version"),
                windowsRunner.commands.getFirst());

        Path linuxHome = runtimeHome("linux-home", "java");
        RecordingRunner linuxRunner = new RecordingRunner();
        JavaRuntime linux = new JavaRuntime(linuxRunner, linuxHome::toString, () -> "Linux");
        assertEquals(linuxHome.resolve("bin/java").toRealPath(), linux.currentExecutable());
        assertEquals(21, linux.currentFeature());
        assertEquals(List.of(linux.currentExecutable().toString(), "-version"),
                linuxRunner.commands.getFirst());
    }

    @Test
    void missingAndSymlinkedCurrentExecutablesAreRejectedWithoutRunningAProcess()
            throws Exception {
        Path missingHome = Files.createDirectory(temp.resolve("missing-home"));
        Files.createDirectory(missingHome.resolve("bin"));
        RecordingRunner missingRunner = new RecordingRunner();
        JavaRuntime missing = new JavaRuntime(missingRunner, missingHome::toString, () -> "Linux");
        assertThrows(IOException.class, missing::currentExecutable);
        assertTrue(missingRunner.commands.isEmpty());

        Path realHome = runtimeHome("real-home", "java");
        Path symlinkHome = temp.resolve("symlink-home");
        try {
            Files.createSymbolicLink(symlinkHome, realHome);
        } catch (IOException | UnsupportedOperationException error) {
            if (!System.getProperty("os.name", "").toLowerCase().contains("win")) throw error;
            Process junction = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J",
                    symlinkHome.toString(), realHome.toString()).start();
            if (junction.waitFor() != 0) {
                throw new AssertionError("could not create a symlink or junction fixture", error);
            }
        }
        RecordingRunner symlinkRunner = new RecordingRunner();
        JavaRuntime symlink = new JavaRuntime(symlinkRunner, symlinkHome::toString, () -> "Linux");
        try {
            assertThrows(IOException.class, symlink::currentExecutable);
            assertTrue(symlinkRunner.commands.isEmpty());
        } finally {
            Files.deleteIfExists(symlinkHome);
        }
    }

    @Test
    void currentJavaInsideImportedRootFailsBeforeAnyRegistryRecord() throws Exception {
        Path root = suite("contained-runtime");
        Path java = Files.createDirectories(root.resolve("bin")).resolve("java");
        Files.writeString(java, "fixture");
        RecordingRunner runner = new RecordingRunner();
        JavaRuntime runtime = new JavaRuntime(runner, root::toString, () -> "Linux");
        Path registry = temp.resolve("registry.json");
        ExistingImportService service = new ExistingImportService(registry, new RegistryStore(),
                new InstallationInspector(), runtime);

        IOException error = assertThrows(IOException.class, () -> service.prepare(root,
                OperationContext.none(OperationType.IMPORT_EXISTING)));

        assertTrue(error.getMessage().contains("inside the application folder"));
        assertFalse(Files.exists(registry));
        assertTrue(runner.commands.isEmpty(),
                "contained Java is rejected before executing even java -version");
    }

    @Test
    void changedCurrentJavaAfterQuoteFailsBeforeRegistration() throws Exception {
        Path root = suite("changed-runtime-copy");
        Path home = runtimeHome("changed-runtime-home", "java");
        RecordingRunner runner = new RecordingRunner();
        JavaRuntime runtime = new JavaRuntime(runner, home::toString, () -> "Linux");
        Path registry = temp.resolve("changed-runtime-registry.json");
        ExistingImportService service = new ExistingImportService(registry, new RegistryStore(),
                new InstallationInspector(), runtime);
        OperationContext context = OperationContext.none(OperationType.IMPORT_EXISTING);
        ExistingImportService.Plan plan = service.prepare(root, context);
        Files.writeString(home.resolve("bin/java"), "changed and longer");

        IOException error = assertThrows(IOException.class,
                () -> service.register(plan, "Changed runtime", context));

        assertTrue(error.getMessage().contains("selected default game Java changed"));
        assertFalse(Files.exists(registry));
        assertEquals(2, runner.commands.size());
    }

    private Path runtimeHome(String name, String executable) throws IOException {
        Path home = Files.createDirectory(temp.resolve(name));
        Path bin = Files.createDirectory(home.resolve("bin"));
        Files.writeString(bin.resolve(executable), "fixture");
        return home;
    }

    private Path suite(String name) throws Exception {
        Path root = Files.createDirectory(temp.resolve(name));
        Files.createDirectories(root.resolve("data"));
        Files.createDirectories(root.resolve("mmconf"));
        Files.createDirectories(root.resolve("lib"));
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "megamek.MegaMek");
        try (JarOutputStream ignored = new JarOutputStream(
                Files.newOutputStream(root.resolve("MegaMek.jar")), manifest)) {
        }
        return root;
    }

    private static final class RecordingRunner implements ProcessRunner {
        private final List<List<String>> commands = new ArrayList<>();

        @Override
        public Result run(List<String> command, Path workingDirectory, Duration timeout,
                          boolean inheritIo) {
            commands.add(List.copyOf(command));
            return new Result(0, "openjdk version \"21.0.8\"", false);
        }
    }
}
