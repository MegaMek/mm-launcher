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

package org.megamek.launcher.distribution;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkflowStructureTest {
    private static final Path WORKFLOW =
            Path.of(".github", "workflows", "launcher-archives.yml");

    @Test
    void workflowBuildsAndVerifiesNativeInstallersOnEveryDeclaredRunner()
            throws Exception {
        String text = Files.readString(WORKFLOW, StandardCharsets.UTF_8);
        JsonNode root = new ObjectMapper(new YAMLFactory()).readTree(text);

        assertTrue(root.path("on").has("workflow_dispatch"));
        assertTrue(root.path("on").has("push"));
        assertTrue(root.path("on").has("pull_request"));
        assertEquals("read", root.path("permissions").path("contents").asText());

        JsonNode jobs = root.path("jobs");
        assertEquals(1, jobs.size(), "only the native installer matrix should run");
        JsonNode installers = jobs.path("installers");
        assertEquals("${{ matrix.os }}", installers.path("runs-on").asText());
        List<String> ids = new ArrayList<>();
        List<String> runners = new ArrayList<>();
        List<String> extensions = new ArrayList<>();
        for (JsonNode row : installers.path("strategy").path("matrix").path("include")) {
            ids.add(row.path("id").asText());
            runners.add(row.path("os").asText());
            assertFalse(row.has("tasks"), "matrix tasks must not imply they drive build commands");
            extensions.add(row.path("extensions").asText());
        }
        assertEquals(List.of("windows-x64", "linux-x64", "macos-intel", "macos-apple-silicon"), ids);
        assertEquals(List.of("windows-2025", "ubuntu-24.04", "macos-15-intel", "macos-15"), runners);
        assertEquals(List.of("msi", "deb,rpm", "pkg", "pkg"), extensions);
        assertEquals(List.of(
                "actions/checkout@v4",
                "actions/setup-java@v4",
                "gradle/actions/setup-gradle@v4",
                "actions/upload-artifact@v4",
                "actions/upload-artifact@v4"
        ), actions(installers));
        assertEquals("temurin", installers.path("steps").get(1).path("with").path("distribution").asText());
        assertEquals("21", installers.path("steps").get(1).path("with").path("java-version").asText());

        JsonNode wix = step(installers, "Install verified WiX 3.14 binaries");
        assertEquals("runner.os == 'Windows'", wix.path("if").asText());
        String wixStep = wix.path("run").asText();
        assertTrue(wixStep.contains("wix3141rtm/wix314-binaries.zip"));
        assertTrue(wixStep.contains("6ac824e1642d6f7277d0ed7ea09411a508f6116ba6fae0aa5f2c7daa2ff43d31"));
        assertTrue(wixStep.indexOf("Get-FileHash") < wixStep.indexOf("Expand-Archive"));
        assertTrue(wixStep.indexOf("throw \"WiX archive SHA-256 mismatch")
                < wixStep.indexOf("Expand-Archive"));
        JsonNode linuxTools = step(installers, "Install Linux packaging tools");
        assertEquals("runner.os == 'Linux'", linuxTools.path("if").asText());
        assertTrue(linuxTools.path("run").asText().contains("sudo apt-get install -y rpm fakeroot"));

        assertTrue(step(installers, "Build Windows installer (never install)")
                .path("run").asText().contains("buildWindowsInstaller"));
        assertTrue(step(installers, "Build Linux installers (never install)")
                .path("run").asText().contains("buildDebInstaller buildRpmInstaller"));
        assertTrue(step(installers, "Build macOS installer (never install)")
                .path("run").asText().contains("buildPkgInstaller"));
        assertEquals(6, count(text, "-PbuildIdentifier=${{ github.sha }}"));

        JsonNode verify = step(installers, "Verify exact installer/checksum pairs without installing");
        assertEquals("${{ matrix.id }}", verify.path("env").path("PLATFORM").asText());
        assertEquals("${{ matrix.extensions }}", verify.path("env").path("EXTENSIONS").asText());
        String inspection = verify.path("run").asText();
        assertTrue(inspection.contains("$files.Count -ne $types.Count"));
        assertTrue(inspection.contains("$matchesForType.Count -ne 1"));
        assertTrue(inspection.contains("ReadAllText(\"$($file.FullName).sha256\")"));
        assertTrue(inspection.contains("if ($line -cne \"$actual  $($file.Name)\")"));
        assertTrue(inspection.contains("OpenDatabase($file.FullName, 0)"), "MSI inspection must be read-only");
        assertTrue(inspection.contains("pkgutil --expand-full"));
        assertTrue(inspection.contains("dpkg-deb --field"));
        assertTrue(inspection.contains("rpm -qp"));
        assertFalse(inspection.matches("(?s).*\\n\\s*(?:&\\s*)?msiexec(?:\\.exe)?\\b.*"),
                "verification must not execute the MSI");

        JsonNode upload = step(installers, "Upload native installers and SHA-256 files only");
        assertEquals("actions/upload-artifact@v4", upload.path("uses").asText());
        assertEquals("MegaMek-Launcher-${{ matrix.id }}-installers",
                upload.path("with").path("name").asText());
        assertEquals("error", upload.path("with").path("if-no-files-found").asText());
        String paths = upload.path("with").path("path").asText();
        List<String> expectedPaths = new ArrayList<>();
        for (String extension : List.of("msi", "deb", "rpm", "pkg")) {
            String base = "build/distributions/MegaMek-Launcher-*-${{ matrix.id }}." + extension;
            expectedPaths.add(base);
            expectedPaths.add(base + ".sha256");
        }
        assertEquals(expectedPaths, paths.lines().map(String::trim).filter(line -> !line.isEmpty()).toList());
        assertTrue(step(installers, "Test source on Windows").path("run").asText().contains(" test"));
        assertTrue(step(installers, "Test source on Linux with Xvfb").path("run").asText()
                .contains("xvfb-run -a ./gradlew \"-PbuildIdentifier=${{ github.sha }}\" test"));
        assertTrue(step(installers, "Test source on macOS").path("run").asText().contains(" test"));
        assertTrue(text.indexOf("Upload native installers and SHA-256 files only")
                < text.indexOf("Test source on Windows"),
                "hosted GUI test failures must not prevent installer artifacts from being inspected and uploaded");
        JsonNode reports = step(installers, "Upload platform test reports");
        assertEquals("always()", reports.path("if").asText());
        assertEquals("MegaMek-Launcher-test-reports-${{ matrix.id }}",
                reports.path("with").path("name").asText());
        assertEquals("build/test-results/**/*.xml", reports.path("with").path("path").asText());

        assertFalse(text.contains("buildArchive"));
        assertFalse(text.contains("verifyProvidedArchive"));
        assertFalse(text.contains("verifyPortableArchive"));
        assertFalse(text.contains("actions/download-artifact"));
        assertFalse(text.contains("-portable"));
        assertFalse(text.contains("windowsArchive"));
        assertFalse(text.contains("linuxArchive"));
        assertFalse(text.contains("macArchive"));
        assertFalse(text.contains("buildAllArchives"));
        assertFalse(text.contains("verifyArchives"));
        assertFalse(text.contains("createExe"));
        assertFalse(text.contains("pull_request_target"));
        assertFalse(text.contains("contents: write"));
        assertFalse(text.contains("secrets."));
        assertFalse(text.contains("gh release"));
        assertFalse(text.contains("actions/create-release"));
    }

    private static JsonNode step(JsonNode job, String name) {
        for (JsonNode step : job.path("steps")) {
            if (name.equals(step.path("name").asText())) {
                return step;
            }
        }
        throw new AssertionError("Missing workflow step: " + name);
    }

    private static List<String> actions(JsonNode job) {
        List<String> actions = new ArrayList<>();
        for (JsonNode step : job.path("steps")) {
            if (step.has("uses")) {
                actions.add(step.path("uses").asText());
            }
        }
        return actions;
    }

    private static int count(String text, String token) {
        int result = 0;
        int offset = 0;
        while ((offset = text.indexOf(token, offset)) >= 0) {
            result++;
            offset += token.length();
        }
        return result;
    }
}
