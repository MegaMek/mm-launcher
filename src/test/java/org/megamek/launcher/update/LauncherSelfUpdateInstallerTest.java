package org.megamek.launcher.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LauncherSelfUpdateInstallerTest {

    @Test
    void startsTheUpdaterProcessThenExitsWithZero(@TempDir Path directory) throws IOException {
        Path msi = Files.createFile(directory.resolve("MM-Launcher-0.2.0.msi"));
        AtomicReference<Path> started = new AtomicReference<>();
        AtomicInteger exitCode = new AtomicInteger(-1);
        AtomicInteger exitCalls = new AtomicInteger(0);

        LauncherSelfUpdateInstaller installer =
                new LauncherSelfUpdateInstaller(started::set);

        installer.applyAndExit(msi, code -> {
            exitCalls.incrementAndGet();
            exitCode.set(code);
        });

        assertEquals(msi.toAbsolutePath().normalize(), started.get());
        assertEquals(1, exitCalls.get());
        assertEquals(0, exitCode.get());
    }

    @Test
    void normalizesTheMsiPathBeforeStartingTheUpdater(@TempDir Path directory) throws IOException {
        Files.createFile(directory.resolve("MM-Launcher-0.2.0.msi"));
        Path relative = directory.relativize(directory.resolve("MM-Launcher-0.2.0.msi").toAbsolutePath());
        Path indirect = directory.resolve(".").resolve("MM-Launcher-0.2.0.msi");
        AtomicReference<Path> started = new AtomicReference<>();

        LauncherSelfUpdateInstaller installer =
                new LauncherSelfUpdateInstaller(started::set);

        installer.applyAndExit(indirect, code -> { });

        assertEquals(directory.resolve("MM-Launcher-0.2.0.msi").toAbsolutePath().normalize(), started.get());
    }

    @Test
    void rejectsAMissingUpdatePackageWithoutStartingAnything(@TempDir Path directory) {
        Path missing = directory.resolve("does-not-exist.msi");
        AtomicInteger startCalls = new AtomicInteger(0);
        LauncherSelfUpdateInstaller installer = new LauncherSelfUpdateInstaller(path -> {
            startCalls.incrementAndGet();
        });

        IOException error = assertThrows(IOException.class,
                () -> installer.applyAndExit(missing, code -> { }));

        assertTrue(error.getMessage().contains("missing"));
        assertEquals(0, startCalls.get());
    }

    @Test
    void rejectsANonMsiPackageWithoutStartingAnything(@TempDir Path directory) throws IOException {
        Path notMsi = Files.createFile(directory.resolve("MM-Launcher-0.2.0.exe"));
        AtomicInteger startCalls = new AtomicInteger(0);
        LauncherSelfUpdateInstaller installer = new LauncherSelfUpdateInstaller(path -> {
            startCalls.incrementAndGet();
        });

        IOException error = assertThrows(IOException.class,
                () -> installer.applyAndExit(notMsi, code -> { }));

        assertTrue(error.getMessage().contains(".msi"));
        assertEquals(0, startCalls.get());
    }

    @Test
    void neverCallsExitWhenTheUpdaterProcessFailsToStart(@TempDir Path directory) throws IOException {
        Path msi = Files.createFile(directory.resolve("MM-Launcher-0.2.0.msi"));
        AtomicInteger exitCalls = new AtomicInteger(0);
        LauncherSelfUpdateInstaller installer = new LauncherSelfUpdateInstaller(path -> {
            throw new IOException("msiexec could not be started");
        });

        assertThrows(IOException.class,
                () -> installer.applyAndExit(msi, code -> exitCalls.incrementAndGet()));

        assertEquals(0, exitCalls.get());
        assertFalse(Files.notExists(msi));
    }
}
