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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.megamek.launcher.launch.ProcessRunner;
import org.megamek.launcher.operation.OperationContext;
import org.megamek.launcher.operation.OperationPhase;
import org.megamek.launcher.operation.OperationType;
import org.megamek.launcher.release.ReleaseTransport;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NativeLauncherUpdateTest {
    @TempDir Path temp;
    private static final String MAC =
            "/Applications/MegaMek Launcher.app/Contents/MacOS/MegaMek Launcher";
    private static final String LINUX = "/opt/megamek-launcher/bin/MegaMek Launcher";

    @Test void eligibilityRequiresTheNativeEntryPointAndSupportedArchitecture() {
        assertEquals(NativeLauncherUpdate.Platform.MAC_INTEL,
                NativeLauncherUpdate.platform("Mac OS X", "x86_64", MAC));
        assertEquals(NativeLauncherUpdate.Platform.MAC_ARM,
                NativeLauncherUpdate.platform("Mac OS X", "aarch64", MAC));
        assertEquals(NativeLauncherUpdate.Platform.LINUX,
                NativeLauncherUpdate.platform("Linux", "amd64", LINUX));
        assertNull(NativeLauncherUpdate.platform("Mac OS X", "aarch64",
                "/Users/example/MegaMek Launcher.app/Contents/MacOS/MegaMek Launcher"));
        assertNull(NativeLauncherUpdate.platform("Linux", "aarch64", LINUX));
        assertNull(NativeLauncherUpdate.platform("Linux", "amd64", "/home/example/bin/MegaMek Launcher"));
        assertNull(NativeLauncherUpdate.platform("Windows 11", "amd64", LINUX));
        assertNull(NativeLauncherUpdate.platform("Linux", "amd64", ""));
    }

    private LauncherSelfUpdate.Candidate candidate(String suffix, byte[] content) throws Exception {
        String name = "MegaMek-Launcher-0.1.1-" + suffix;
        return new LauncherSelfUpdate.Candidate("v0.1.1", "0.1.1", name,
                URI.create("https://github.com/MegaMek/mm-launcher/releases/download/v0.1.1/" + name),
                content.length, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"linux-x64.deb", "linux-x64.rpm", "macos-intel.pkg", "macos-apple-silicon.pkg"})
    void discoverySelectsOnlyTheExactNativeAssetAndRequiresItsOfficialDigest(String suffix) throws Exception {
        var candidate = candidate(suffix, new byte[]{1, 2, 3});
        String json = """
                {"tag_name":"v0.1.1","draft":false,"prerelease":false,"assets":[
                {"name":"%s","size":3,"digest":"sha256:%s","browser_download_url":"%s"}]}
                """.formatted(candidate.name(), candidate.sha256(), candidate.url());
        LauncherSelfUpdate updater = new LauncherSelfUpdate();
        assertEquals(candidate, updater.parseCandidate(json.getBytes(StandardCharsets.UTF_8), "0.1.0", suffix));
        assertNull(updater.parseCandidate(json.getBytes(StandardCharsets.UTF_8), "0.1.1", suffix));
        assertNull(updater.parseCandidate(json.getBytes(StandardCharsets.UTF_8), "1.0.0", suffix));
        assertThrows(IOException.class, () -> updater.parseCandidate(json.replace("sha256:", "")
                .getBytes(StandardCharsets.UTF_8), "0.1.0", suffix));
        assertThrows(IOException.class, () -> updater.parseCandidate(json.replace("github.com",
                "example.org").getBytes(StandardCharsets.UTF_8), "0.1.0", suffix));
        assertThrows(IOException.class, () -> updater.parseCandidate(json.getBytes(StandardCharsets.UTF_8),
                "0.1.0", "windows-x64.msi"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"linux-x64.deb", "linux-x64.rpm", "macos-intel.pkg", "macos-apple-silicon.pkg"})
    void nativeDownloadsUseTheSharedVerifiedPipelineAndOwnedCleanup(String suffix) throws Exception {
        byte[] content = "safe fixture, not an installer".getBytes(StandardCharsets.UTF_8);
        LauncherSelfUpdate updater = new LauncherSelfUpdate((uri, accept) ->
                new ReleaseTransport.Response(200, Map.of(), new ByteArrayInputStream(content)));
        OperationContext context = new OperationContext(OperationType.LAUNCHER_UPDATE);
        Path staged = updater.stage(candidate(suffix, content), "0.1.0", context, (file, version) -> {
            assertArrayEquals(content, Files.readAllBytes(file));
            assertEquals(OperationPhase.VERIFY, context.latest().phase());
        }, suffix);
        assertTrue(staged.getParent().getFileName().toString().startsWith("mm-launcher-update-"));
        updater.discard(staged);
        assertFalse(Files.exists(staged.getParent()));
        assertThrows(IOException.class, () -> updater.stage(candidate(suffix, content), "0.1.0",
                context, (file, version) -> fail("wrong package family cannot be verified"),
                "windows-x64.msi"));
    }

    @Test void macDistributionRequiresExactlyTheLauncherReceiptAndMappedVersion() throws Exception {
        String valid = """
                <installer-gui-script minSpecVersion="1">
                <choice id="default"><pkg-ref id="org.megamek.launcher"/></choice>
                <pkg-ref id="org.megamek.launcher" version="1.14.14">launcher.pkg</pkg-ref>
                </installer-gui-script>
                """;
        NativeLauncherUpdate.verifyDistribution(valid, "0.14.14");
        assertEquals("2.0.0", NativeLauncherUpdate.macVersion("1.0.0"));
        for (String invalid : List.of(valid.replace("org.megamek.launcher", "org.example.other"),
                valid.replace("1.14.14", "0.14.14"),
                valid.replace("</installer-gui-script>",
                        "<pkg-ref id=\"org.other\" version=\"1.14.14\"/></installer-gui-script>"),
                "<!DOCTYPE installer-gui-script [<!ENTITY x SYSTEM \"file:///missing\">]>" + valid,
                "<installer-gui-script/>")) {
            assertThrows(IOException.class, () -> NativeLauncherUpdate.verifyDistribution(invalid, "0.14.14"));
        }
    }

    private Path launcher() throws IOException {
        return Files.writeString(temp.resolve("MegaMek Launcher"), "fixture").toRealPath();
    }

    private NativeLauncherUpdate linux(Path launcher, String debVersion, String rpmMetadata,
                                        List<List<String>> calls) {
        ProcessRunner runner = (command, directory, timeout, inheritIo) -> {
            calls.add(command);
            assertEquals(launcher.getParent(), directory);
            assertFalse(inheritIo);
            if (command.getFirst().endsWith("dpkg-query")) {
                if (command.contains("--search")) return new ProcessRunner.Result(
                        debVersion == null ? 1 : 0, debVersion == null ? "" : "megamek-launcher: " + launcher, false);
                return new ProcessRunner.Result(0, "install ok installed\t" + debVersion + "\tamd64\n", false);
            }
            if (command.getFirst().endsWith("rpm"))
                return new ProcessRunner.Result(rpmMetadata == null ? 1 : 0,
                        rpmMetadata == null ? "" : rpmMetadata, false);
            if (command.getFirst().endsWith("dpkg-deb"))
                return new ProcessRunner.Result(0, "megamek-launcher\t0.1.1-1\tamd64\n", false);
            throw new IOException("Unexpected fixture command: " + command);
        };
        return new NativeLauncherUpdate(NativeLauncherUpdate.Platform.LINUX, launcher, runner, path -> true);
    }

    @Test void linuxSelectsTheInstalledPackageNotTheFirstAvailableToolAndAvoidsAnotherInspection()
            throws Exception {
        Path entry = launcher();
        List<List<String>> debCalls = new ArrayList<>();
        var deb = linux(entry, "0.1.0-1", null, debCalls);
        deb.verifyInstalledVersion("0.1.0");
        int calls = debCalls.size();
        assertEquals("linux-x64.deb", deb.suffix("0.1.0"));
        assertEquals(calls, debCalls.size(), "asset selection must reuse the verified package family");
        deb.verifyPackage(temp.resolve("new.deb"), "0.1.1");
        assertTrue(debCalls.getLast().getFirst().endsWith("dpkg-deb"));
        var rpm = linux(entry, null, "megamek-launcher\t0.1.0\t1\tx86_64\n", new ArrayList<>());
        rpm.verifyInstalledVersion("0.1.0");
        assertEquals("linux-x64.rpm", rpm.suffix("0.1.0"));
    }

    @Test void conflictingMissingAndMismatchedPackageRegistrationsCannotUpdate() throws Exception {
        Path entry = launcher();
        for (var updater : List.of(linux(entry, null, null, new ArrayList<>()),
                linux(entry, "0.1.0-1", "megamek-launcher\t0.1.0\t1\tx86_64", new ArrayList<>()),
                linux(entry, "0.1.2-1", null, new ArrayList<>()),
                linux(entry, null, "megamek-launcher\t0.1.0\t1\taarch64", new ArrayList<>()))) {
            assertThrows(IOException.class, () -> updater.verifyInstalledVersion("0.1.0"));
        }
    }

    @Test void packageIdentityAndInspectionTimeoutsFailExplicitly() throws Exception {
        Path entry = launcher();
        ProcessRunner wrong = (command, directory, timeout, inheritIo) ->
                new ProcessRunner.Result(0, "foreign package", false);
        var mac = new NativeLauncherUpdate(NativeLauncherUpdate.Platform.MAC_ARM, entry, wrong);
        assertThrows(IOException.class, () -> mac.verifyInstalledVersion("0.1.0"));
        assertThrows(IOException.class, () -> mac.verifyPackage(temp.resolve("new.pkg"), "0.1.1"));
        ProcessRunner hung = (command, directory, timeout, inheritIo) ->
                new ProcessRunner.Result(-1, "", true);
        var timedOut = new NativeLauncherUpdate(NativeLauncherUpdate.Platform.MAC_INTEL, entry, hung);
        assertTrue(assertThrows(IOException.class,
                () -> timedOut.verifyInstalledVersion("0.1.0")).getMessage().contains("timed out"));
    }

    @Test void macPackageInspectionExtractsOnlyDistributionAndPreservesDownloadQuarantine() throws Exception {
        Path entry = launcher();
        List<List<String>> calls = new ArrayList<>();
        ProcessRunner runner = (command, directory, timeout, inheritIo) -> {
            calls.add(command);
            if (command.getFirst().endsWith("xar")) {
                assertEquals("Distribution", command.getLast());
                assertFalse(command.contains("-O"), "macOS xar does not support tar's stdout option");
                Path inspection = Path.of(command.get(command.indexOf("-C") + 1));
                Files.writeString(inspection.resolve("Distribution"),
                        "<installer-gui-script><pkg-ref id=\"org.megamek.launcher\" version=\"1.1.1\"/>"
                                + "</installer-gui-script>");
            } else if (!command.getFirst().endsWith("xattr")) {
                throw new IOException("Unexpected fixture command: " + command);
            }
            return new ProcessRunner.Result(0, "", false);
        };
        var mac = new NativeLauncherUpdate(NativeLauncherUpdate.Platform.MAC_INTEL, entry, runner);
        mac.verifyPackage(temp.resolve("fixture.pkg"), "0.1.1");
        Path inspection = Path.of(calls.getFirst().get(calls.getFirst().indexOf("-C") + 1));
        assertFalse(Files.exists(inspection));
        assertEquals("com.apple.quarantine", calls.getLast().get(2));
        assertEquals("-w", calls.getLast().get(1));
    }

    @Test void nativeCommandsPreserveOsAuthorizationAndDoNotBypassTrustChecks() {
        var mac = NativeLauncherUpdate.commands(NativeLauncherUpdate.Kind.MAC_ARM, Path.of(MAC), "");
        assertTrue(mac.install().contains("open -W -n -b com.apple.installer"));
        assertFalse(mac.install().contains("sudo"));
        assertFalse(mac.install().contains("spctl"));
        assertFalse(mac.install().contains("xattr"));
        var deb = NativeLauncherUpdate.commands(NativeLauncherUpdate.Kind.DEB, Path.of(LINUX), "/usr/bin/apt-get");
        assertTrue(deb.install().startsWith("/usr/bin/pkexec /bin/sh"));
        assertTrue(deb.install().contains("/usr/bin/apt-get"));
        assertFalse(deb.install().contains("allow-downgrades"));
        var rpm = NativeLauncherUpdate.commands(NativeLauncherUpdate.Kind.RPM, Path.of(LINUX), "/usr/bin/dnf");
        assertTrue(rpm.install().contains("upgrade"));
        assertFalse(rpm.install().contains("--nogpgcheck"));
        assertFalse(rpm.install().contains("--nodeps"));
    }

    @Test void macInstalledIdentityUsesTheReceiptAndBundleVersionOffset() throws Exception {
        Path bundle = temp.resolve("MegaMek Launcher.app");
        Path entry = Files.writeString(Files.createDirectories(bundle.resolve("Contents/MacOS"))
                .resolve("MegaMek Launcher"), "fixture").toRealPath();
        ProcessRunner runner = (command, directory, timeout, inheritIo) -> {
            if (command.getFirst().endsWith("pkgutil"))
                return new ProcessRunner.Result(0, "package-id: org.megamek.launcher\nversion: 1.14.14\n", false);
            assertEquals(bundle.resolve("Contents/Info.plist"), Path.of(command.getLast()));
            String value = command.contains("Print :CFBundleIdentifier") ? "org.megamek.launcher" : "1.14.14";
            return new ProcessRunner.Result(0, value + "\n", false);
        };
        var mac = new NativeLauncherUpdate(NativeLauncherUpdate.Platform.MAC_INTEL, entry, runner);
        mac.verifyInstalledVersion("0.14.14");
        assertEquals("macos-intel.pkg", mac.suffix("0.14.14"));
        assertThrows(IOException.class, () -> mac.verifyInstalledVersion("0.14.15"));
    }

    @Test void missingLinuxAuthorizationToolsFailBeforeAnUpdateCanBeOffered() throws Exception {
        Path entry = launcher();
        var prepared = linux(entry, "0.1.0-1", null, new ArrayList<>());
        prepared.verifyInstalledVersion("0.1.0");
        prepared.verifyInstallerTools();
        ProcessRunner runner = (command, directory, timeout, inheritIo) ->
                new ProcessRunner.Result(0, command.contains("--search") ? "megamek-launcher: " + entry
                        : "install ok installed\t0.1.0-1\tamd64", false);
        var missing = new NativeLauncherUpdate(NativeLauncherUpdate.Platform.LINUX, entry, runner,
                path -> path.getFileName().toString().equals("dpkg-query"));
        missing.verifyInstalledVersion("0.1.0");
        assertTrue(assertThrows(IOException.class, missing::verifyInstallerTools)
                .getMessage().contains("PolicyKit"));
    }

    @Test void downloadedRpmIdentityMustMatchTheNewVersionReleaseAndArchitecture() throws Exception {
        Path entry = launcher();
        for (String metadata : List.of("megamek-launcher\t0.1.1\t1\tx86_64",
                "other-package\t0.1.1\t1\tx86_64", "megamek-launcher\t0.1.0\t1\tx86_64",
                "megamek-launcher\t0.1.1\t2\tx86_64", "megamek-launcher\t0.1.1\t1\taarch64")) {
            ProcessRunner runner = (command, directory, timeout, inheritIo) ->
                    new ProcessRunner.Result(0, command.contains("-qp") ? metadata
                            : "megamek-launcher\t0.1.0\t1\tx86_64", false);
            var rpm = new NativeLauncherUpdate(NativeLauncherUpdate.Platform.LINUX, entry, runner,
                    path -> path.getFileName().toString().equals("rpm"));
            rpm.verifyInstalledVersion("0.1.0");
            if (metadata.equals("megamek-launcher\t0.1.1\t1\tx86_64"))
                rpm.verifyPackage(temp.resolve("new.rpm"), "0.1.1");
            else assertThrows(IOException.class, () -> rpm.verifyPackage(temp.resolve("new.rpm"), "0.1.1"));
        }
    }

    @Test void nativeCompletionUsesTheSharedExactVersionAndWarningLifecycle() throws Exception {
        Path report = temp.resolve("launcher-update-result.txt");
        Files.writeString(report, "installed:0.1.1");
        assertEquals(LauncherSelfUpdate.ReportState.INSTALLED,
                LauncherSelfUpdate.consumeReport(report, "0.1.1", () -> {}).state());
        assertFalse(Files.exists(report));
        Files.writeString(report, "installed-reopen-warning:0.1.1");
        var warning = LauncherSelfUpdate.consumeReport(report, "0.1.1", () -> {});
        assertEquals(LauncherSelfUpdate.ReportState.INSTALLED_WARNING, warning.state());
        assertTrue(warning.message().contains("Reopen"));
        Files.writeString(report, "handoff failed: installer exited with 126");
        assertFalse(LauncherSelfUpdate.consumeReport(report, "0.1.0",
                () -> fail("permission denial cannot confirm installation")).installed());
        assertEquals("launcher-update-helper.log", LauncherSelfUpdate.helperLog(report).getFileName().toString());
        assertEquals("launcher-update-installer.log", LauncherSelfUpdate.installerLog(report).getFileName().toString());
    }

    @Test void failedHandoffClearsOnlyItsOwnReservationAndPreservesChangedReports() throws Exception {
        Path report = temp.resolve("launcher-update-result.txt");
        String pending = "pending:0.1.1:12345:123456789";
        for (String owned : List.of("starting:0.1.1", pending)) {
            Files.writeString(report, owned);
            LauncherSelfUpdate.clearFailedHandoff(report, "0.1.1", pending);
            assertFalse(Files.exists(report));
        }
        Files.writeString(report, "pending:0.1.1:54321:987654321");
        assertThrows(IOException.class, () -> LauncherSelfUpdate.clearFailedHandoff(report, "0.1.1", pending));
        assertEquals("pending:0.1.1:54321:987654321", Files.readString(report));
    }
}
