/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.megamek.launcher.update;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WindowsMsiHandoffTest {
    @TempDir Path temp;
    private Path msi;
    private Path report;
    private Path arguments;
    private Path reopened;
    private Path installer;
    private Path launcher;
    private String sha256;

    @BeforeEach void prepareSafeSubstitutes() throws Exception {
        assumeTrue(System.getProperty("os.name", "").startsWith("Windows"),
                "material helper execution requires Windows PowerShell");
        Path staging = Files.createDirectory(temp.resolve("staged MSI & ' spaces"));
        msi = staging.resolve("MegaMek-Launcher-0.1.1-windows-x64.msi");
        Files.writeString(msi, "fixture: not an actual installer");
        sha256 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(msi)));
        report = temp.resolve("result.txt");
        Files.writeString(report, "pending");
        arguments = temp.resolve("arguments.txt");
        reopened = temp.resolve("reopened.txt");
        installer = temp.resolve("safe-installer.cmd");
        launcher = temp.resolve("safe-launcher.cmd");
        Files.writeString(launcher, "@echo off\r\ntype \"" + report + "\" > \"" + reopened
                + "\"\r\nexit /b 0\r\n");
        installerExit(0);
    }

    private void installerExit(int code) throws Exception {
        Files.writeString(installer, "@echo off\r\necho %* > \"" + arguments
                + "\"\r\nexit /b " + code + "\r\n");
    }

    private Process start(String script, Path log) throws Exception {
        return new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand",
                Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE)))
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }

    private int execute() throws Exception {
        String script = WindowsMsiUpdate.handoffScript(msi, "0.1.1", sha256, report,
                Integer.MAX_VALUE, launcher, installer);
        Process helper = start(script, WindowsMsiUpdate.helperLog(report));
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

    private void awaitReopen() throws Exception {
        await(() -> {
            if (!Files.exists(reopened)) return false;
            try {
                return Files.readString(report).equals(Files.readString(reopened));
            } catch (java.io.IOException error) {
                throw new java.io.UncheckedIOException(error);
            }
        });
    }

    @Test void writesCompletionWithWindowsPowerShellAndReopensOnlyAfterSuccess() throws Exception {
        assertEquals(0, execute(), Files.readString(WindowsMsiUpdate.helperLog(report)));
        assertEquals("installed:0.1.1", Files.readString(report), Files.readString(WindowsMsiUpdate.helperLog(report)));
        awaitReopen();
        assertEquals(Files.readString(report), Files.readString(reopened));
        String supplied = Files.readString(arguments);
        assertTrue(supplied.contains("/passive"));
        assertTrue(supplied.contains("/norestart"));
        assertTrue(supplied.contains("/l*v"));
        assertTrue(supplied.contains("\"" + msi + "\""), "spaced MSI argument must remain quoted");
        assertFalse(Files.exists(msi.getParent()));
        assertTrue(WindowsMsiUpdate.consumeReport(report, "0.1.1", () -> {}).installed());
        assertFalse(Files.exists(report));
    }

    @Test void waitsForParentAndRecognizesAnActiveLegacyHelperWithoutTouchingItsReport() throws Exception {
        Path releaseParent = temp.resolve("release-parent.txt");
        String literal = releaseParent.toString().replace("'", "''");
        Process parent = start("while(!(Test-Path -LiteralPath '" + literal
                + "')){Start-Sleep -Milliseconds 50}", temp.resolve("parent.log"));
        Process helper = start(WindowsMsiUpdate.handoffScript(msi, "0.1.1", sha256, report,
                parent.pid(), launcher, installer), WindowsMsiUpdate.helperLog(report));
        try {
            await(() -> {
                try {
                    return Files.readString(WindowsMsiUpdate.helperLog(report)).contains("Waiting for launcher");
                } catch (java.io.IOException error) {
                    throw new java.io.UncheckedIOException(error);
                }
            });
            assertFalse(Files.exists(arguments), "installer must not run while parent is alive");
            assertTrue(WindowsMsiUpdate.consumeReport(report, "0.1.1",
                    () -> fail("active helper must not verify or acknowledge installation")).pending());
            assertEquals("pending", Files.readString(report));
            String identified = "pending:0.1.1:" + helper.pid() + ":"
                    + helper.info().startInstant().orElseThrow().toEpochMilli();
            Files.writeString(report, identified);
            assertTrue(WindowsMsiUpdate.consumeReport(report, "0.1.1",
                    () -> fail("identified active helper must retain its report")).pending());
            assertEquals(identified, Files.readString(report));
            Files.writeString(releaseParent, "release");
            assertTrue(parent.waitFor(15, TimeUnit.SECONDS));
            assertTrue(helper.waitFor(30, TimeUnit.SECONDS));
            assertEquals(0, helper.exitValue(), Files.readString(WindowsMsiUpdate.helperLog(report)));
            assertEquals("installed:0.1.1", Files.readString(report));
            awaitReopen();
        } finally {
            if (helper.isAlive()) helper.destroyForcibly();
            if (parent.isAlive()) parent.destroyForcibly();
        }
    }

    @Test void nonzeroInstallerResultsAreRecordedWithoutRelaunchOrFalseSuccess() throws Exception {
        for (int code : new int[]{1602, 1603}) {
            installerExit(code);
            execute();
            assertEquals(Integer.toString(code), Files.readString(report));
            assertFalse(Files.exists(reopened));
            assertFalse(WindowsMsiUpdate.consumeReport(report, "0.1.0",
                    () -> fail("failed install cannot verify success")).installed());
            Files.createDirectories(msi.getParent());
            Files.writeString(msi, "fixture: not an actual installer");
            Files.writeString(report, "pending");
        }
    }

    @Test void rebootRequiredIsSuccessfulButDoesNotForceRestart() throws Exception {
        installerExit(3010);
        execute();
        assertEquals("installed-reboot-required:0.1.1", Files.readString(report));
        awaitReopen();
        assertEquals(Files.readString(report), Files.readString(reopened));
        var result = WindowsMsiUpdate.consumeReport(report, "0.1.1", () -> {});
        assertTrue(result.installed());
        assertTrue(result.message().contains("computer restart"));
    }

    @Test void changedChecksumNeverRunsInstallerAndLeavesUsefulDiagnostics() throws Exception {
        Files.writeString(msi, "changed after verification");
        execute();
        assertTrue(Files.readString(report).startsWith("handoff failed:"));
        assertFalse(Files.exists(arguments));
        assertFalse(Files.exists(reopened));
        assertTrue(Files.exists(msi), "unrecognized replacement bytes must not be deleted");
        assertTrue(Files.readString(WindowsMsiUpdate.helperLog(report)).contains("Staged MSI digest changed"));
    }

    @Test void cleanupWarningKeepsUnrelatedFilesAndStillReopensVerifiedUpdate() throws Exception {
        Path unrelated = msi.getParent().resolve("unrelated.txt");
        Files.writeString(unrelated, "not owned by the update");
        for (int code : new int[]{0, 3010}) {
            installerExit(code);
            Files.writeString(msi, "fixture: not an actual installer");
            Files.writeString(report, "pending");
            Files.deleteIfExists(reopened);
            execute();
            assertEquals(code == 0 ? "installed-cleanup-warning:0.1.1"
                    : "installed-reboot-required-cleanup-warning:0.1.1", Files.readString(report));
            assertEquals("not owned by the update", Files.readString(unrelated));
            awaitReopen();
            assertEquals(Files.readString(report), Files.readString(reopened));
            var result = WindowsMsiUpdate.consumeReport(report, "0.1.1", () -> {});
            assertTrue(result.installed());
            assertTrue(result.message().contains("cleanup failed"));
            assertEquals(code == 3010, result.message().contains("computer restart"));
        }
    }

    @Test void failedCompletionWriteIsLoggedAndCannotSilentlyRelaunch() throws Exception {
        Files.delete(report);
        assertNotEquals(0, execute());
        assertTrue(Files.exists(arguments), "the fake installer ran before the completion write failed");
        assertFalse(Files.exists(reopened));
        assertTrue(Files.readString(WindowsMsiUpdate.helperLog(report)).contains("Replace"));
    }
}
