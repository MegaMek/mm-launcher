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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class NativeLauncherHandoffTest {
    @TempDir Path temp;
    private Path pkg;
    private Path report;
    private Path arguments;
    private Path reopened;
    private Path installer;
    private Path launcher;
    private Path verification;
    private String sha256;
    private String shell;
    private String hashCommand;

    @BeforeEach void prepareSafeSubstitutes() throws Exception {
        boolean windows = System.getProperty("os.name", "").startsWith("Windows");
        shell = windows ? "C:\\Program Files\\Git\\bin\\bash.exe" : "/bin/sh";
        assumeTrue(Files.isRegularFile(Path.of(shell)), "material helper tests require a POSIX shell");
        hashCommand = System.getProperty("os.name", "").startsWith("Mac")
                ? "/usr/bin/shasum -a 256" : "sha256sum";
        Path directory = Files.createDirectory(temp.resolve("staged installer & ' spaces"));
        pkg = Files.writeString(directory.resolve("MegaMek-Launcher-0.1.1-macos-intel.pkg"), "safe fixture");
        sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(pkg)));
        report = Files.writeString(temp.resolve("launcher-update-result.txt"), "pending:0.1.1:1:1");
        arguments = temp.resolve("arguments.txt");
        reopened = temp.resolve("reopened.txt");
        verification = Files.writeString(temp.resolve("installed.txt"), "1.1.1");
        installer = temp.resolve("safe installer ' &.sh");
        launcher = Files.writeString(temp.resolve("safe launcher ' &.sh"),
                "cat " + quote(report) + " > " + quote(reopened) + "\n");
        installerExit(0);
    }

    private static String quote(Path path) { return NativeLauncherUpdate.quote(path); }

    private void installerExit(int code) throws Exception {
        Files.writeString(installer, "printf '%s' \"$1\" > " + quote(arguments) + "\nexit " + code + "\n");
    }

    private NativeLauncherUpdate.Commands commands() {
        return new NativeLauncherUpdate.Commands(hashCommand, "sh " + quote(installer) + " \"$pkg\"",
                "cat " + quote(verification), "sh " + quote(launcher), false);
    }

    private Process start(String script, Path log) throws Exception {
        Path helper = Files.writeString(Files.createTempFile(temp, "safe-helper-", ".sh"), script);
        return new ProcessBuilder(shell, helper.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }

    private int execute(NativeLauncherUpdate.Commands commands) throws Exception {
        return execute(commands, LauncherSelfUpdate.helperLog(report));
    }

    private int execute(NativeLauncherUpdate.Commands commands, Path log) throws Exception {
        Process helper = start(NativeLauncherUpdate.handoffScript(pkg, "0.1.1", sha256,
                report, Integer.MAX_VALUE, commands), log);
        try {
            assertTrue(helper.waitFor(30, TimeUnit.SECONDS), "helper exceeded timeout");
            return helper.exitValue();
        } finally {
            if (helper.isAlive()) helper.destroyForcibly();
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(condition.getAsBoolean(), "controlled helper condition exceeded timeout");
    }

    @Test void recordsVerifiedSuccessBeforeReopeningAndQuotesTheExactPackagePath() throws Exception {
        assertEquals(0, execute(commands()), Files.readString(LauncherSelfUpdate.helperLog(report)));
        assertEquals("installed:0.1.1", Files.readString(report));
        assertEquals(Files.readString(report), Files.readString(reopened));
        assertEquals(pkg.toString().replace(java.io.File.separatorChar, '/'), Files.readString(arguments));
        assertFalse(Files.exists(pkg.getParent()));
    }

    @Test void canceledAuthorizationAndInstallerFailureNeverReopenOrClaimSuccess() throws Exception {
        for (int code : new int[]{1, 126, 127}) {
            installerExit(code);
            execute(commands());
            assertEquals("handoff failed: installer exited with " + code, Files.readString(report));
            assertFalse(Files.exists(reopened));
            Files.createDirectories(pkg.getParent());
            Files.writeString(pkg, "safe fixture");
        }
    }

    @Test void installerExitZeroStillRequiresTheRequestedInstalledVersion() throws Exception {
        Files.writeString(verification, "1.1.0");
        execute(commands());
        assertEquals("handoff failed: requested installed version could not be confirmed", Files.readString(report));
        assertFalse(Files.exists(reopened));
    }

    @Test void changedChecksumDoesNotStartInstallerOrDeleteTheReplacedPackage() throws Exception {
        Files.writeString(pkg, "replacement");
        assertNotEquals(0, execute(commands()));
        assertEquals("handoff failed: staged package digest changed", Files.readString(report));
        assertFalse(Files.exists(arguments));
        assertFalse(Files.exists(reopened));
        assertTrue(Files.exists(pkg));
    }

    @Test void cleanupNeverRecursesAndStillReopensAConfirmedInstallationWithAWarning() throws Exception {
        Path unrelated = Files.writeString(pkg.getParent().resolve("unrelated.txt"), "do not remove");
        assertEquals(0, execute(commands()));
        assertEquals("installed-cleanup-warning:0.1.1", Files.readString(report));
        assertEquals(Files.readString(report), Files.readString(reopened));
        assertEquals("do not remove", Files.readString(unrelated));
    }

    @Test void missingCompletionDirectoryCannotBeReportedAsSuccessOrReopened() throws Exception {
        report = temp.resolve("missing-directory").resolve("launcher-update-result.txt");
        assertNotEquals(0, execute(commands(), temp.resolve("failed-write-helper.log")));
        assertFalse(Files.exists(reopened));
        assertFalse(Files.exists(report));
    }

    @Test void reopenFailureRetainsAnExplicitInstalledWarning() throws Exception {
        var good = commands();
        var failing = new NativeLauncherUpdate.Commands(good.hash(), good.install(), good.verify(), "exit 1", false);
        execute(failing);
        assertEquals("installed-reopen-warning:0.1.1", Files.readString(report));
        assertTrue(Files.readString(LauncherSelfUpdate.helperLog(report)).contains("could not be reopened"));
    }

    @Test void linuxCompletionRequiresPackageStatusVersionReleaseAndArchitecture() throws Exception {
        for (String suffix : List.of("linux-x64.deb", "linux-x64.rpm")) {
            Files.deleteIfExists(pkg);
            Files.createDirectories(pkg.getParent());
            pkg = Files.writeString(pkg.getParent().resolve("MegaMek-Launcher-0.1.1-" + suffix), "safe fixture");
            Files.writeString(verification, suffix.endsWith(".deb")
                    ? "install ok installed\t0.1.1-1\tamd64" : "megamek-launcher\t0.1.1\t1\tx86_64");
            var stub = commands();
            var linux = new NativeLauncherUpdate.Commands(stub.hash(), stub.install(), stub.verify(),
                    stub.reopen(), true);
            assertEquals(0, execute(linux));
            assertEquals("installed:0.1.1", Files.readString(report));
            assertEquals(Files.readString(report), Files.readString(reopened));
            Files.delete(reopened);
            Files.createDirectories(pkg.getParent());
            Files.writeString(pkg, "safe fixture");
            Files.writeString(verification, "install ok installed\t0.1.1-1\tarm64");
            execute(linux);
            assertTrue(Files.readString(report).startsWith("handoff failed:"));
            assertFalse(Files.exists(reopened));
        }
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void waitsForTheParentProcessBeforeStartingTheInstaller() throws Exception {
        Path release = temp.resolve("release-parent.txt");
        Process parent = start("while [ ! -f " + quote(release) + " ]; do sleep 0.05; done",
                temp.resolve("parent.log"));
        Path helperLog = LauncherSelfUpdate.helperLog(report);
        Process helper = start(NativeLauncherUpdate.handoffScript(pkg, "0.1.1", sha256, report,
                parent.pid(), commands()), helperLog);
        try {
            await(() -> {
                try {
                    return Files.readString(helperLog).contains("Waiting for launcher");
                } catch (java.io.IOException error) {
                    throw new java.io.UncheckedIOException(error);
                }
            });
            assertFalse(Files.exists(arguments));
            Files.writeString(release, "release");
            assertTrue(parent.waitFor(15, TimeUnit.SECONDS));
            assertTrue(helper.waitFor(30, TimeUnit.SECONDS));
            assertEquals(0, helper.exitValue(), Files.readString(helperLog));
            assertEquals(Files.readString(report), Files.readString(reopened));
        } finally {
            if (helper.isAlive()) helper.destroyForcibly();
            if (parent.isAlive()) parent.destroyForcibly();
        }
    }

    private int executeRootCopy(Path manager, String digest) throws Exception {
        List<String> command = new ArrayList<>(List.of(shell, "-c",
                NativeLauncherUpdate.rootInstallScript(NativeLauncherUpdate.Kind.DEB, manager.toString()),
                "safe-root-copy-test", pkg.toString(), digest));
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(temp.resolve("root-copy.log").toFile()).start();
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS));
            return process.exitValue();
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void privilegedPlanInstallsOnlyTheVerifiedPrivateCopyWithoutDeletingTheOriginal() throws Exception {
        Path manager = Files.writeString(temp.resolve("safe-manager"), "#!/bin/sh\n"
                + "printf '%s' \"$3\" > " + quote(arguments) + "\n"
                + "cmp \"$3\" " + quote(pkg) + "\n");
        Files.setPosixFilePermissions(manager, PosixFilePermissions.fromString("rwx------"));
        assertEquals(0, executeRootCopy(manager, sha256), Files.readString(temp.resolve("root-copy.log")));
        Path copy = Path.of(Files.readString(arguments));
        assertNotEquals(pkg, copy);
        assertTrue(copy.toString().startsWith("/var/tmp/mm-launcher-update."));
        assertFalse(Files.exists(copy.getParent()), "private copy must be cleaned after package manager exits");
        assertTrue(Files.exists(pkg), "outer helper still owns its original staging file");
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    void privilegedCopyRejectsDigestDriftBeforeCallingThePackageManager() throws Exception {
        Path manager = Files.writeString(temp.resolve("safe-manager"), "#!/bin/sh\n"
                + "touch " + quote(arguments) + "\n");
        Files.setPosixFilePermissions(manager, PosixFilePermissions.fromString("rwx------"));
        assertNotEquals(0, executeRootCopy(manager, "b".repeat(64)));
        assertFalse(Files.exists(arguments));
        assertTrue(Files.readString(temp.resolve("root-copy.log")).contains("digest changed"));
    }
}
