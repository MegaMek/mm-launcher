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
        assertTrue(root.path("on").has("workflow_call"));
        JsonNode sourceInput = root.path("on").path("workflow_call").path("inputs").path("source_commit");
        assertEquals("string", sourceInput.path("type").asText());
        assertFalse(sourceInput.path("required").asBoolean());
        assertEquals("", sourceInput.path("default").asText());
        assertTrue(root.path("on").has("push"));
        assertTrue(root.path("on").has("pull_request"));
        JsonNode pushBranches = root.path("on").path("push").path("branches");
        assertEquals(1, pushBranches.size(),
                "PR branches must not launch duplicate push matrix builds");
        assertEquals("main", pushBranches.path(0).asText());
        assertEquals("read", root.path("permissions").path("contents").asText());

        JsonNode jobs = root.path("jobs");
        assertEquals(2, jobs.size(), "installer matrix and offline release tooling contracts should run");
        JsonNode tooling = jobs.path("release-tooling");
        assertEquals("ubuntu-24.04", tooling.path("runs-on").asText());
        assertEquals(List.of("actions/checkout@v4"), actions(tooling));
        assertEquals("${{ inputs.source_commit || github.sha }}",
                tooling.path("steps").get(0).path("with").path("ref").asText());
        assertEquals("read", tooling.path("permissions").path("contents").asText());
        assertEquals("read", tooling.path("permissions").path("actions").asText());
        assertEquals("${{ steps.ci-plan.outputs.build_installers }}",
                tooling.path("outputs").path("build_installers").asText());
        assertTrue(step(tooling, "Test launcher release tooling without network or publication")
                .path("run").asText().contains("unittest discover -s scripts -p test_launcher_release.py"));
        JsonNode plan = step(tooling,
                "Determine whether this exact release candidate already passed installer CI");
        assertEquals("ci-plan", plan.path("id").asText());
        assertEquals("${{ github.token }}", plan.path("env").path("GH_TOKEN").asText());
        assertEquals("${{ inputs.source_commit }}", plan.path("env").path("CI_SOURCE_COMMIT").asText());
        assertEquals("python3 -B scripts/launcher_release.py ci-plan", plan.path("run").asText());
        for (String event : List.of("push", "pull_request")) {
            String paths = root.path("on").path(event).path("paths").toString();
            assertTrue(paths.contains("scripts/**"));
            assertTrue(paths.contains(".github/workflows/launcher-release.yml"));
            assertTrue(paths.contains(".github/workflows/launcher-gui-smoke.yml"));
        }
        JsonNode installers = jobs.path("installers");
        assertEquals("release-tooling", installers.path("needs").asText());
        assertEquals("needs.release-tooling.outputs.build_installers == 'true'", installers.path("if").asText());
        assertEquals("${{ inputs.source_commit || github.sha }}",
                installers.path("steps").get(0).path("with").path("ref").asText());
        assertEquals("${{ inputs.source_commit || github.sha }}",
                installers.path("env").path("SOURCE_COMMIT").asText());
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
        assertEquals(2, count(text, "-PbuildIdentifier=$env:SOURCE_COMMIT"));
        assertEquals(4, count(text, "-PbuildIdentifier=${SOURCE_COMMIT}"));

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
        assertTrue(upload.path("with").path("overwrite").asBoolean(),
                "failed-job retries may replace same-run CI artifacts, never published release assets");
        String paths = upload.path("with").path("path").asText();
        List<String> expectedPaths = new ArrayList<>();
        for (String extension : List.of("msi", "deb", "rpm", "pkg")) {
            String base = "build/distributions/MegaMek-Launcher-*-${{ matrix.id }}." + extension;
            expectedPaths.add(base);
            expectedPaths.add(base + ".sha256");
        }
        assertEquals(expectedPaths, paths.lines().map(String::trim).filter(line -> !line.isEmpty()).toList());
        assertTrue(step(installers, "Test source on Windows").path("run").asText().contains(" test"));
        assertTrue(step(installers, "Test source on Linux").path("run").asText()
                .contains("./gradlew \"-PbuildIdentifier=${SOURCE_COMMIT}\" test"));
        assertTrue(step(installers, "Test source on macOS").path("run").asText().contains(" test"));
        assertTrue(text.indexOf("Upload native installers and SHA-256 files only")
                < text.indexOf("Test source on Windows"),
                "test failures must not prevent installer artifacts from being inspected and uploaded");
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

    @Test
    void releaseWorkflowIsManualAndReusesTheEntireReadOnlyInstallerWorkflow()
            throws Exception {
        Path path = Path.of(".github", "workflows", "launcher-release.yml");
        String text = Files.readString(path, StandardCharsets.UTF_8);
        JsonNode root = new ObjectMapper(new YAMLFactory()).readTree(text);
        assertEquals(1, root.path("on").size(), "publication must never run on push or PR");
        JsonNode dispatch = root.path("on").path("workflow_dispatch");
        assertFalse(dispatch.has("inputs"), "patch release must require no version input");
        assertEquals("read", root.path("permissions").path("contents").asText());
        assertEquals("launcher-stable-release", root.path("concurrency").path("group").asText());
        assertFalse(root.path("concurrency").path("cancel-in-progress").asBoolean());
        JsonNode jobs = root.path("jobs");
        assertEquals(4, jobs.size());
        JsonNode prepare = jobs.path("prepare");
        assertEquals("${{ steps.candidate.outputs.version }}", prepare.path("outputs").path("version").asText());
        assertEquals("${{ steps.candidate.outputs.commit }}", prepare.path("outputs").path("commit").asText());
        assertEquals("${{ steps.candidate.outputs.base }}", prepare.path("outputs").path("base").asText());
        assertEquals("${{ github.sha }}", prepare.path("steps").get(0).path("with").path("ref").asText());
        JsonNode validation = step(prepare, "Validate official main source and next unused patch version");
        assertEquals("${{ github.token }}", validation.path("env").path("GH_TOKEN").asText());
        assertTrue(validation.path("run").asText().contains("launcher_release.py prepare"));
        JsonNode bot = step(prepare, "Create repository-scoped release bot token");
        assertEquals("actions/create-github-app-token@v2", bot.path("uses").asText());
        assertEquals("${{ vars.LAUNCHER_RELEASE_APP_ID }}", bot.path("with").path("app-id").asText());
        assertEquals("${{ secrets.LAUNCHER_RELEASE_APP_PRIVATE_KEY }}",
                bot.path("with").path("private-key").asText());
        assertEquals("${{ github.repository_owner }}", bot.path("with").path("owner").asText());
        assertEquals("mm-launcher", bot.path("with").path("repositories").asText());
        assertEquals("write", bot.path("with").path("permission-contents").asText());
        assertFalse(bot.path("with").path("skip-token-revoke").asBoolean());
        JsonNode bump = step(prepare, "Prepare version-only candidate outside main");
        assertEquals("${{ steps.release-bot.outputs.token }}", bump.path("env").path("GH_TOKEN").asText());
        assertTrue(bump.path("run").asText().contains("launcher_release.py candidate"));
        assertFalse(prepare.has("permissions"), "default workflow token stays read-only during preparation");
        assertTrue(text.indexOf("Validate official main source and next unused patch version")
                < text.indexOf("Create repository-scoped release bot token"));
        JsonNode installers = jobs.path("installers");
        assertEquals("prepare", installers.path("needs").asText());
        assertEquals("./.github/workflows/launcher-archives.yml", installers.path("uses").asText());
        assertEquals("read", installers.path("permissions").path("contents").asText());
        assertEquals("read", installers.path("permissions").path("actions").asText(),
                "the reusable tooling job needs read-only release-run provenance access");
        assertEquals("${{ needs.prepare.outputs.commit }}",
                installers.path("with").path("source_commit").asText());
        assertFalse(installers.has("secrets"), "installer jobs must not receive publisher secrets");
        JsonNode publish = jobs.path("publish");
        List<String> prerequisites = new ArrayList<>();
        publish.path("needs").forEach(dependency -> prerequisites.add(dependency.asText()));
        assertEquals(List.of("prepare", "installers"), prerequisites);
        assertFalse(publish.has("if"), "default success dependency guard must not be bypassed");
        assertEquals("write", publish.path("permissions").path("contents").asText());
        assertEquals(List.of("actions/checkout@v4", "actions/download-artifact@v4"), actions(publish));
        assertEquals("${{ needs.prepare.outputs.commit }}",
                publish.path("steps").get(0).path("with").path("ref").asText());
        assertFalse(publish.path("steps").get(0).path("with").path("persist-credentials").asBoolean());
        JsonNode download = publish.path("steps").get(1);
        assertEquals("MegaMek-Launcher-*-installers", download.path("with").path("pattern").asText());
        assertEquals("build/release", download.path("with").path("path").asText());
        assertTrue(download.path("with").path("merge-multiple").asBoolean());
        assertFalse(download.path("with").has("run-id"), "artifacts must come from this exact run");
        JsonNode publication = step(publish, "Validate and publish the complete stable launcher release");
        assertEquals("${{ needs.prepare.outputs.commit }}",
                publication.path("env").path("RELEASE_COMMIT").asText());
        assertEquals("${{ needs.prepare.outputs.version }}",
                publication.path("env").path("RELEASE_VERSION").asText());
        assertTrue(publication.path("run").asText().contains("launcher_release.py publish"));
        assertTrue(publication.path("run").asText().contains("--assets build/release"));
        JsonNode finalize = jobs.path("finalize");
        List<String> finalPrerequisites = new ArrayList<>();
        finalize.path("needs").forEach(dependency -> finalPrerequisites.add(dependency.asText()));
        assertEquals(List.of("prepare", "publish"), finalPrerequisites);
        assertFalse(finalize.has("if"), "main synchronization must require successful publication");
        assertFalse(finalize.has("permissions"), "use a fresh App token, not a writable default token");
        assertEquals("${{ needs.prepare.outputs.commit }}",
                finalize.path("steps").get(0).path("with").path("ref").asText());
        assertFalse(finalize.path("steps").get(0).path("with").path("persist-credentials").asBoolean());
        assertEquals(download.path("with"), finalize.path("steps").get(1).path("with"));
        JsonNode syncToken = step(finalize, "Create repository-scoped version synchronization token");
        assertEquals(bot.path("with"), syncToken.path("with"));
        assertEquals("actions/create-github-app-token@v2", syncToken.path("uses").asText());
        JsonNode sync = step(finalize, "Confirm published release and synchronize main");
        assertEquals("${{ steps.release-bot.outputs.token }}", sync.path("env").path("GH_TOKEN").asText());
        assertEquals("${{ needs.prepare.outputs.base }}", sync.path("env").path("RELEASE_BASE").asText());
        assertEquals("${{ needs.prepare.outputs.commit }}", sync.path("env").path("RELEASE_COMMIT").asText());
        assertEquals("${{ needs.prepare.outputs.version }}", sync.path("env").path("RELEASE_VERSION").asText());
        assertTrue(sync.path("run").asText().contains("launcher_release.py finalize"));
        assertTrue(sync.path("run").asText().contains("--base \"$RELEASE_BASE\""));
        assertTrue(sync.path("run").asText().contains("--assets build/release"));
        assertFalse(text.contains("launcher-gui-smoke"),
                "advisory desktop checks must not gate publication");
        assertFalse(text.contains("${{ inputs.version }}\""), "input must pass through environment, not shell expansion");
        assertFalse(text.contains("buildWindowsInstaller"), "reuse packaging instead of duplicating it");
        assertFalse(text.contains("msiexec"));
        assertFalse(text.contains("secrets: inherit"));
    }

    @Test
    void advisoryDesktopWorkflowIsVisibleReadOnlyAndIndependentOfPublication() throws Exception {
        String text = Files.readString(Path.of(".github", "workflows", "launcher-gui-smoke.yml"));
        JsonNode root = new ObjectMapper(new YAMLFactory()).readTree(text);
        assertTrue(root.path("name").asText().contains("advisory"));
        assertEquals("read", root.path("permissions").path("contents").asText());
        for (String event : List.of("push", "pull_request", "schedule", "workflow_dispatch")) {
            assertTrue(root.path("on").has(event));
        }
        assertFalse(root.path("on").has("workflow_call"));
        JsonNode desktop = root.path("jobs").path("desktop");
        List<String> platforms = new ArrayList<>();
        desktop.path("strategy").path("matrix").path("include")
                .forEach(row -> platforms.add(row.path("id").asText()));
        assertEquals(List.of("windows-x64", "linux-x64", "macos-intel", "macos-apple-silicon"), platforms);
        assertFalse(desktop.has("needs"));
        assertFalse(desktop.has("continue-on-error"));
        assertEquals("21", desktop.path("steps").get(1).path("with").path("java-version").asText());
        assertEquals(3, count(text, " nativeGuiTest"));
        assertTrue(step(desktop, "Desktop tests on Linux with Xvfb")
                .path("run").asText().contains("xvfb-run -a ./gradlew nativeGuiTest"));
        JsonNode reports = step(desktop, "Upload advisory desktop reports");
        assertEquals("always()", reports.path("if").asText());
        assertTrue(reports.path("with").path("path").asText().contains("test-results/nativeGuiTest"));
        assertFalse(text.contains("secrets."));
        assertFalse(text.contains("continue-on-error"));
        assertFalse(text.contains("launcher_release.py"));
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
