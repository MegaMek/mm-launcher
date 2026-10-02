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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class WindowsMsiUpdateTest {
    @TempDir Path temp;

    private Path report(String content) throws IOException {
        Path path = temp.resolve("msi-update-result.txt");
        Files.writeString(path, content);
        return path;
    }

    @Test void activePendingAndUnknownResultsRemainForReview() throws Exception {
        Path path = report("pending");
        assertTrue(WindowsMsiUpdate.consumeReport(path, "0.1.1",
                () -> fail("active helper must not verify MSI"), (file, pid, started) -> true).pending());
        assertEquals("pending", Files.readString(path));
        Files.writeString(path, "installed:0.1.1garbage");
        assertThrows(IOException.class, () -> WindowsMsiUpdate.consumeReport(path, "0.1.1",
                () -> fail("corrupt result must not verify MSI")));
        assertTrue(Files.exists(path));
        Files.delete(path);
        Path link = temp.resolve("msi-update-result.txt");
        try {
            Files.createSymbolicLink(link, reportTarget());
        } catch (UnsupportedOperationException | IOException | SecurityException unavailable) {
            return; // symlinks can require Windows developer mode / elevated privilege
        }
        assertThrows(IOException.class, () -> WindowsMsiUpdate.consumeReport(link, "0.1.1",
                () -> fail("link must not verify MSI")));
        assertTrue(Files.isSymbolicLink(link));
    }

    @Test void legacyIncompleteReportIsRetainedAndNeverClaimedAsConfirmedUpdate() throws Exception {
        Path path = report("pending");
        var result = WindowsMsiUpdate.consumeReport(path, "0.1.1", () -> {},
                (file, pid, started) -> false);
        assertTrue(result.recovered());
        assertFalse(result.installed(), "the previous target and result are unknown");
        assertTrue(result.message().contains("did not record"));
        assertFalse(Files.exists(path));
        try (var files = Files.list(temp)) {
            Path retained = files.filter(file -> file.getFileName().toString().contains(".incomplete-"))
                    .findFirst().orElseThrow();
            assertEquals("pending", Files.readString(retained));
        }
        report("pending");
        assertThrows(IOException.class, () -> WindowsMsiUpdate.consumeReport(path, "0.1.1",
                () -> { throw new IOException("installed version could not be confirmed"); },
                (file, pid, started) -> false));
        assertEquals("pending", Files.readString(path));
    }

    @Test void identifiedHelperMustFinishBeforeInstalledVersionCanReconcileItsReport() throws Exception {
        Path path = report("pending:0.1.1:12345:123456789");
        assertTrue(WindowsMsiUpdate.consumeReport(path, "0.1.1",
                () -> fail("active helper cannot be acknowledged"), (file, pid, started) -> {
                    assertEquals(12345, pid);
                    assertEquals(123456789, started);
                    return true;
                }).pending());
        assertEquals("pending:0.1.1:12345:123456789", Files.readString(path));
        var incomplete = WindowsMsiUpdate.consumeReport(path, "0.1.0", () -> {},
                (file, pid, started) -> false);
        assertFalse(incomplete.installed());
        assertFalse(incomplete.recovered(), "a failed attempt still needs user attention");
        assertTrue(incomplete.message().contains("still 0.1.0"));
        assertFalse(Files.exists(path));
        report("pending:0.1.1:12345:123456789");
        var result = WindowsMsiUpdate.consumeReport(path, "0.1.1", () -> {},
                (file, pid, started) -> false);
        assertTrue(result.installed());
        assertTrue(result.recovered(), "a verified target with an exited helper is safely reconciled");
        assertTrue(result.message().contains("did not save"));
        assertFalse(Files.exists(path));
    }

    @Test void malformedOrChangedPendingReportsCannotBeAcknowledged() throws Exception {
        for (String invalid : new String[]{"pending:0.1.1:no-pid:123", "pending:0.1.1:0:123",
                "pending:0.1.1:123:0", "pending:0.1.1:123:123:extra"}) {
            Path path = report(invalid);
            assertThrows(IOException.class, () -> WindowsMsiUpdate.consumeReport(path, "0.1.1",
                    () -> fail("malformed input cannot verify MSI"), (file, pid, started) -> false));
            assertEquals(invalid, Files.readString(path));
        }
        Path path = report("pending");
        assertThrows(IOException.class, () -> WindowsMsiUpdate.consumeReport(path, "0.1.1",
                () -> Files.writeString(path, "pending:0.1.2:123:123"),
                (file, pid, started) -> false));
        assertEquals("pending:0.1.2:123:123", Files.readString(path));
    }

    @Test void restartRequiredResultIsConfirmedWithoutForcingAComputerRestart() throws Exception {
        Path path = report("installed-reboot-required:0.1.1");
        var result = WindowsMsiUpdate.consumeReport(path, "0.1.1", () -> {});
        assertTrue(result.installed());
        assertEquals(WindowsMsiUpdate.ReportState.INSTALLED_WARNING, result.state());
        assertFalse(result.recovered(), "a required restart must remain visible");
        assertTrue(result.message().contains("no restart was forced"));
        assertFalse(Files.exists(path));
    }

    private Path reportTarget() throws IOException {
        Path target = temp.resolve("target");
        Files.writeString(target, "installed:0.1.1");
        return target;
    }

    @Test void completedFailureIsShownOnceAndDoesNotBlockRetry() throws Exception {
        Path path = report("1603");
        var result = WindowsMsiUpdate.consumeReport(path, "0.1.0",
                () -> fail("failure must not verify MSI"));
        assertFalse(result.installed());
        assertTrue(result.message().contains("1603"));
        assertFalse(Files.exists(path));
        assertNull(WindowsMsiUpdate.consumeReport(path, "0.1.0", () -> {}));
        report("handoff failed: staged MSI digest changed");
        var handoff = WindowsMsiUpdate.consumeReport(path, "0.1.0",
                () -> fail("handoff failure must not verify MSI"));
        assertFalse(handoff.installed());
        assertTrue(handoff.message().contains("staged MSI digest changed"));
        assertFalse(Files.exists(path));
    }

    @Test void installedResultRequiresMatchingAndConfirmedVersionEvenOffline() throws Exception {
        Path path = report("installed:0.1.1");
        var mismatch = WindowsMsiUpdate.consumeReport(path, "0.1.0",
                () -> fail("mismatch must not verify MSI"));
        assertFalse(mismatch.installed());
        assertFalse(Files.exists(path));
        report("installed:0.1.1");
        var unconfirmed = WindowsMsiUpdate.consumeReport(path, "0.1.1",
                () -> { throw new IOException("MSI version mismatch"); });
        assertFalse(unconfirmed.installed());
        assertTrue(unconfirmed.message().contains("MSI version mismatch"));
        assertFalse(Files.exists(path));
        report("installed:0.1.1");
        var installed = WindowsMsiUpdate.consumeReport(path, "0.1.1", () -> {});
        assertTrue(installed.installed());
        assertEquals(WindowsMsiUpdate.ReportState.INSTALLED, installed.state());
        assertEquals("Launcher updated to 0.1.1.", installed.message());
        assertFalse(Files.exists(path));
    }

    @Test void cleanupWarningIsSuccessAndCannotMaskAReportChange() throws Exception {
        Path path = report("installation succeeded; staged MSI cleanup failed");
        var warning = WindowsMsiUpdate.consumeReport(path, "0.1.1", () -> {});
        assertTrue(warning.installed());
        assertEquals(WindowsMsiUpdate.ReportState.INSTALLED_WARNING, warning.state());
        assertFalse(warning.recovered(), "a cleanup warning must remain visible");
        assertTrue(warning.message().contains("cleanup failed"));
        assertFalse(Files.exists(path));
        report("installation succeeded; staged MSI cleanup failed; restart required");
        var restartWarning = WindowsMsiUpdate.consumeReport(path, "0.1.1", () -> {});
        assertEquals(WindowsMsiUpdate.ReportState.INSTALLED_WARNING, restartWarning.state());
        assertTrue(restartWarning.installed());
        assertTrue(restartWarning.message().contains("cleanup failed"));
        assertTrue(restartWarning.message().contains("computer restart"));
        assertFalse(Files.exists(path));
        report("installed:0.1.1");
        assertThrows(IOException.class, () -> WindowsMsiUpdate.consumeReport(path, "0.1.1",
                () -> Files.writeString(path, "pending")));
        assertEquals("pending", Files.readString(path));
        Files.writeString(path, "installed:0.1.1" + " ".repeat(4096));
        assertThrows(IOException.class, () -> WindowsMsiUpdate.consumeReport(path, "0.1.1", () -> {}));
        assertTrue(Files.exists(path));
    }

    private static byte[] release(String digest, String url) {
        return ("""
                {"tag_name":"0.1.1","draft":false,"prerelease":false,"assets":[
                {"name":"MegaMek-Launcher-0.1.1-windows-x64.msi","size":42,
                "digest":"%s","browser_download_url":"%s"}]}
                """.formatted(digest, url)).getBytes(StandardCharsets.UTF_8);
    }

    @Test void requiresOfficialMsiAndPublishedChecksum() throws IOException {
        WindowsMsiUpdate updater = new WindowsMsiUpdate();
        String official = "https://github.com/MegaMek/mm-launcher/releases/download/"
                + "0.1.1/MegaMek-Launcher-0.1.1-windows-x64.msi";
        String digest = "sha256:" + "a".repeat(64);
        assertNull(updater.parseCandidate(release(digest, official), "0.1.1"));
        assertEquals("0.1.1", updater.parseCandidate(release(digest, official), "0.1.0").version());
        assertThrows(IOException.class, () -> updater.parseCandidate(release("", official), "0.1.0"));
        assertThrows(IOException.class, () -> updater.parseCandidate(
                release(digest, "https://example.org/update.msi"), "0.1.0"));
    }

    @Test void comparesAllThreeNumericMsiComponents() throws IOException {
        assertTrue(WindowsMsiUpdate.compare("0.1.1", "0.1.0") > 0);
        assertTrue(WindowsMsiUpdate.compare("1.0.0", "0.99.99") > 0);
        assertTrue(WindowsMsiUpdate.compare("0.1.0", "0.1.1") < 0);
        assertEquals(0, WindowsMsiUpdate.compare("0.1.1", "0.1.1"));
        assertThrows(IOException.class, () -> WindowsMsiUpdate.compare("0.1.1-SNAPSHOT", "0.1.0"));
        assertThrows(IOException.class, () -> WindowsMsiUpdate.compare("v0.1.1", "0.1.0"));
    }

    @Test void handoffQuotesSpacedMsiPathWithoutBackslashesAndWaitsForExit() {
        String script = WindowsMsiUpdate.handoffScript(
                Path.of("C:\\Users\\Example User\\AppData\\Local\\Temp\\mm-launcher-msi-test",
                        "MegaMek-Launcher-0.1.1-windows-x64.msi"),
                "0.1.1", "a".repeat(64), Path.of("C:\\Users\\Example User\\result.txt"), 12345);
        assertTrue(script.contains("('\"'+$msi+'\"')"));
        assertFalse(script.contains("'\\\"'"));
        assertTrue(script.contains("while(Get-Process -Id 12345"));
        assertTrue(script.indexOf("while(Get-Process") < script.indexOf("Starting Windows Installer"));
        assertTrue(script.contains("[IO.Directory]::Delete($dir)"));
        assertTrue(script.contains("'handoff failed: '"));
        assertTrue(script.contains("'installed:0.1.1'"));
        assertTrue(script.contains("[IO.File]::Replace($tmp,$report,[NullString]::Value)"));
        assertTrue(script.indexOf("[IO.Directory]::Delete($dir)")
                < script.indexOf("[IO.File]::Replace($tmp,$report,[NullString]::Value)"));
        assertTrue(script.indexOf("[IO.File]::Replace")
                < script.lastIndexOf("Start-Process -FilePath"));
        assertTrue(script.contains("'/passive'"));
        assertFalse(script.contains("'/qn'"));
    }

    @Test void cleanupAndRebootCompletionReportsKeepTheirExactTargetVersion() throws Exception {
        for (String completion : new String[]{"installed-cleanup-warning", "installed-reboot-required",
                "installed-reboot-required-cleanup-warning"}) {
            Path path = report(completion + ":0.1.1");
            var result = WindowsMsiUpdate.consumeReport(path, "0.1.0",
                    () -> fail("wrong running version cannot confirm a completed update"));
            assertFalse(result.installed());
            assertTrue(result.message().contains("reported version 0.1.1"));
            assertFalse(Files.exists(path));
            report(completion + ":0.1.1");
            var warning = WindowsMsiUpdate.consumeReport(path, "0.1.1", () -> {});
            assertTrue(warning.installed());
            assertEquals(WindowsMsiUpdate.ReportState.INSTALLED_WARNING, warning.state());
            assertFalse(warning.recovered());
            assertEquals(completion.contains("cleanup-warning"), warning.message().contains("cleanup failed"));
            assertEquals(completion.contains("reboot-required"), warning.message().contains("computer restart"));
            assertFalse(Files.exists(path));
        }
    }

    @Test void cannotDiscardAnUnownedPath() {
        assertThrows(IOException.class, () -> new WindowsMsiUpdate().discard(
                Path.of("C:\\Users\\Example User\\unrelated.msi")));
    }

    @Test void powershellArgumentListPreservesSpacedMsiPath() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(System.getProperty("os.name", "").startsWith("Windows"));
        String path = "C:\\Users\\Example User\\Temp\\MegaMek Launcher 0.1.1.msi";
        String script = "$msi='" + path + "'; $arguments=" + WindowsMsiUpdate.installerArguments("$msi")
                + "; [Console]::Out.Write($arguments[1])";
        String encoded = Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
        Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                "-EncodedCommand", encoded).start();
        if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            org.junit.jupiter.api.Assertions.fail("PowerShell did not return the installer arguments");
        }
        assertEquals(0, process.exitValue());
        assertEquals("\"" + path + "\"",
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }
}
