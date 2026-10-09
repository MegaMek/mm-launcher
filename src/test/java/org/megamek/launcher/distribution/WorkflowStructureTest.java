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
        assertEquals("Launcher build checks - ${{ github.event_name }} (${{ github.head_ref || github.ref_name }})",
                root.path("run-name").asText());

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
        assertEquals("python3 -B -m unittest discover -s scripts -p 'test_launcher_*.py'",
                step(tooling, "Test launcher release tooling without network or publication").path("run").asText());
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
            assertTrue(paths.contains(".github/workflows/launcher-test-signing.yml"));
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
        assertTrue(inspection.contains("$file.Length -gt 300MB"));
        assertTrue(inspection.contains("SelectNodes('//pkg-ref[@version]')"));
        assertTrue(inspection.contains("CFBundleShortVersionString"));
        assertTrue(inspection.contains("$packageInfo.DocumentElement.GetAttribute('identifier') -cne 'org.megamek.launcher'"));
        assertFalse(inspection.contains("$packageInfo.'pkg-info'.version"),
                "jpackage's zero-version receipt does not establish the app version");
        assertTrue(inspection.contains("dpkg-deb --show"));
        assertTrue(inspection.contains("\"megamek-launcher`t${version}`tamd64\""));
        assertFalse(inspection.contains("${version}-1"),
                "the Debian package has no revision suffix");
        assertTrue(inspection.contains("${Package}\\t${Version}\\t${Architecture}"));
        assertTrue(inspection.contains("%{NAME}\\t%{VERSION}\\t%{RELEASE}\\t%{ARCH}"));
        assertTrue(inspection.contains("/opt/megamek-launcher/bin/MegaMek Launcher$"));
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
        for (JsonNode stage : installers.path("steps")) {
            if (stage.path("env").toString().contains("secrets.")) {
                assertEquals("Import release-only Apple signing credentials", stage.path("name").asText());
                assertEquals("runner.os == 'macOS' && inputs.apple_signing", stage.path("if").asText());
            }
        }
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
        assertEquals(5, jobs.size());
        JsonNode prepare = jobs.path("prepare");
        assertEquals("${{ steps.candidate.outputs.version }}", prepare.path("outputs").path("version").asText());
        assertEquals("${{ steps.candidate.outputs.commit }}", prepare.path("outputs").path("commit").asText());
        assertEquals("${{ steps.candidate.outputs.base }}", prepare.path("outputs").path("base").asText());
        assertEquals("${{ steps.signing.outputs.mode }}", prepare.path("outputs").path("signing_mode").asText());
        for (String setting : List.of("organization_id", "project_slug", "signing_policy_slug",
                "artifact_configuration_slug", "certificate_sha256")) {
            assertEquals("${{ steps.signing.outputs." + setting + " }}",
                    prepare.path("outputs").path(setting).asText());
        }
        assertEquals("${{ github.sha }}", prepare.path("steps").get(0).path("with").path("ref").asText());
        JsonNode signingPlan = step(prepare, "Validate and capture optional Windows signing configuration");
        assertEquals("signing", signingPlan.path("id").asText());
        assertEquals("python3 -B scripts/launcher_release.py signing-plan", signingPlan.path("run").asText());
        assertEquals("${{ vars.LAUNCHER_SIGNING_ENABLED }}",
                signingPlan.path("env").path("LAUNCHER_SIGNING_ENABLED").asText());
        for (String setting : List.of("ORGANIZATION_ID", "PROJECT_SLUG", "SIGNING_POLICY_SLUG",
                "ARTIFACT_CONFIGURATION_SLUG", "CERTIFICATE_SHA256")) {
            assertEquals("${{ vars.SIGNPATH_" + setting + " }}",
                    signingPlan.path("env").path("SIGNPATH_" + setting).asText());
        }
        assertEquals("${{ secrets.SIGNPATH_API_TOKEN }}",
                signingPlan.path("env").path("SIGNPATH_API_TOKEN").asText());
        assertTrue(text.indexOf("Validate and capture optional Windows signing configuration")
                < text.indexOf("Create repository-scoped release bot token"),
                "invalid enabled signing must fail before candidate writes");
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
        assertEquals(5, installers.path("secrets").size());
        installers.path("secrets").fieldNames().forEachRemaining(secret ->
                assertTrue(secret.startsWith("APPLE_"), "only Apple signing secrets may enter the reusable build"));
        JsonNode publish = jobs.path("publish");
        assertEquals(List.of("prepare", "release-assets"), dependencies(publish));
        assertFalse(publish.has("if"), "default success dependency guard must not be bypassed");
        assertEquals("write", publish.path("permissions").path("contents").asText());
        assertEquals("read", publish.path("permissions").path("actions").asText());
        assertEquals(List.of("actions/checkout@v4", "actions/download-artifact@v4",
                "actions/download-artifact@v4", "actions/upload-artifact@v4"), actions(publish));
        assertEquals("${{ needs.prepare.outputs.signing_mode }}",
                publish.path("env").path("WINDOWS_SIGNING").asText());
        assertEquals("${{ needs.prepare.outputs.certificate_sha256 }}",
                publish.path("env").path("CERTIFICATE_SHA256").asText());
        assertEquals("${{ needs.prepare.outputs.commit }}",
                publish.path("steps").get(0).path("with").path("ref").asText());
        assertFalse(publish.path("steps").get(0).path("with").path("persist-credentials").asBoolean());
        JsonNode download = publish.path("steps").get(1);
        assertEquals("${{ needs.release-assets.outputs.artifact_id }}",
                download.path("with").path("artifact-ids").asText());
        assertFalse(download.path("with").has("pattern"), "publication must not reuse unsigned build outputs");
        assertEquals("build/release", download.path("with").path("path").asText());
        assertTrue(download.path("with").path("merge-multiple").asBoolean());
        assertFalse(download.path("with").has("run-id"), "artifacts must come from this exact run");
        JsonNode verification = publish.path("steps").get(2);
        assertEquals("needs.prepare.outputs.signing_mode == 'signpath' || needs.prepare.outputs.apple_mode == 'apple'",
                verification.path("if").asText());
        assertEquals("${{ needs.release-assets.outputs.verification_artifact_id }}",
                verification.path("with").path("artifact-ids").asText());
        assertEquals("build/signing-verification", verification.path("with").path("path").asText());
        assertTrue(verification.path("with").path("merge-multiple").asBoolean());
        JsonNode publication = step(publish, "Validate and publish the complete stable launcher release");
        assertEquals("${{ needs.prepare.outputs.commit }}",
                publication.path("env").path("RELEASE_COMMIT").asText());
        assertEquals("${{ needs.prepare.outputs.version }}",
                publication.path("env").path("RELEASE_VERSION").asText());
        assertTrue(publication.path("run").asText().contains("launcher_release.py publish"));
        assertTrue(publication.path("run").asText().contains("--assets build/release"));
        assertTrue(publication.path("run").asText().contains("--windows-signing \"$WINDOWS_SIGNING\""));
        assertTrue(publication.path("run").asText().contains("--signing-verification"));
        assertTrue(publication.path("run").asText().contains("--signer-certificate-sha256 \"$CERTIFICATE_SHA256\""));
        assertFalse(publication.path("continue-on-error").asBoolean(), "publication failures still block main");
        JsonNode provenance = step(publish, "Record verified publication for later CI reuse");
        assertEquals("provenance", provenance.path("id").asText());
        assertTrue(provenance.path("continue-on-error").asBoolean(), "optional CI reuse must not block a release");
        assertEquals(publication.path("env"), provenance.path("env"));
        assertTrue(provenance.path("run").asText().contains("launcher_release.py record"));
        assertTrue(provenance.path("run").asText().contains("--provenance build/launcher-release-provenance.json"));
        assertTrue(provenance.path("run").asText().contains("--windows-signing \"$WINDOWS_SIGNING\""));
        assertTrue(provenance.path("run").asText().contains("--signing-verification"));
        JsonNode retained = step(publish, "Retain immutable publication provenance");
        assertEquals("steps.provenance.outcome == 'success'", retained.path("if").asText());
        assertTrue(retained.path("continue-on-error").asBoolean());
        assertEquals("launcher-release-provenance-${{ github.run_id }}-${{ github.run_attempt }}",
                retained.path("with").path("name").asText());
        assertEquals("build/launcher-release-provenance.json", retained.path("with").path("path").asText());
        assertEquals("error", retained.path("with").path("if-no-files-found").asText());
        assertEquals(14, retained.path("with").path("retention-days").asInt());
        assertFalse(retained.path("with").path("overwrite").asBoolean(), "the evidence must not be replaced");
        assertTrue(text.indexOf("Validate and publish the complete stable launcher release")
                < text.indexOf("Record verified publication for later CI reuse"));
        assertTrue(text.indexOf("Record verified publication for later CI reuse")
                < text.indexOf("Retain immutable publication provenance"));
        JsonNode finalize = jobs.path("finalize");
        assertEquals(List.of("prepare", "publish", "release-assets"), dependencies(finalize));
        assertFalse(finalize.has("if"), "main synchronization must require successful publication");
        assertEquals("read", finalize.path("permissions").path("contents").asText(),
                "use a fresh App token, not a writable default token");
        assertEquals("read", finalize.path("permissions").path("actions").asText());
        assertEquals(publish.path("env"), finalize.path("env"));
        assertEquals(List.of("actions/checkout@v4", "actions/download-artifact@v4",
                "actions/download-artifact@v4", "actions/create-github-app-token@v2"), actions(finalize));
        assertEquals("${{ needs.prepare.outputs.commit }}",
                finalize.path("steps").get(0).path("with").path("ref").asText());
        assertFalse(finalize.path("steps").get(0).path("with").path("persist-credentials").asBoolean());
        assertEquals(download.path("with"), finalize.path("steps").get(1).path("with"));
        assertEquals(verification.path("with"), finalize.path("steps").get(2).path("with"));
        assertEquals(verification.path("if"), finalize.path("steps").get(2).path("if"));
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
        assertTrue(sync.path("run").asText().contains("--windows-signing \"$WINDOWS_SIGNING\""));
        assertTrue(sync.path("run").asText().contains("--signing-verification"));
        assertTrue(sync.path("run").asText().contains("--signer-certificate-sha256 \"$CERTIFICATE_SHA256\""));
        assertFalse(text.contains("launcher-gui-smoke"),
                "advisory desktop checks must not gate publication");
        assertFalse(text.contains("${{ inputs.version }}\""), "input must pass through environment, not shell expansion");
        assertFalse(text.contains("buildWindowsInstaller"), "reuse packaging instead of duplicating it");
        assertFalse(text.contains("msiexec"));
        assertFalse(text.contains("secrets: inherit"));
    }

    @Test
    void releaseAssetsSignOnlyWhenEnabledAndRetainImmutableVerifiedBytes() throws Exception {
        String text = Files.readString(Path.of(".github", "workflows", "launcher-release.yml"));
        JsonNode root = new ObjectMapper(new YAMLFactory()).readTree(text);
        JsonNode assets = root.path("jobs").path("release-assets");
        assertEquals(List.of("prepare", "installers"), dependencies(assets));
        assertFalse(assets.has("if"), "failed installer tests must prevent staging and signing");
        assertEquals("${{ needs.prepare.outputs.signing_mode == 'signpath' && 'windows-2025' || 'ubuntu-24.04' }}",
                assets.path("runs-on").asText());
        assertEquals("read", assets.path("permissions").path("contents").asText());
        assertEquals("read", assets.path("permissions").path("actions").asText());
        assertEquals("${{ steps.publication.outputs.artifact-id }}",
                assets.path("outputs").path("artifact_id").asText());
        assertEquals("${{ steps.verification.outputs.artifact-id }}",
                assets.path("outputs").path("verification_artifact_id").asText());
        assertEquals("${{ needs.prepare.outputs.commit }}",
                assets.path("steps").get(0).path("with").path("ref").asText());
        assertFalse(assets.path("steps").get(0).path("with").path("persist-credentials").asBoolean());
        assertEquals(List.of("actions/checkout@v4", "actions/download-artifact@v4",
                "actions/download-artifact@v4",
                "actions/upload-artifact@v4",
                "signpath/github-action-submit-signing-request@f6d04783b4569d051e0c80105fe66e82819d0092",
                "actions/upload-artifact@v4", "actions/upload-artifact@v4"), actions(assets));
        assertEquals("${{ needs.prepare.outputs.signing_mode }}",
                assets.path("env").path("WINDOWS_SIGNING").asText());
        assertEquals("${{ needs.prepare.outputs.certificate_sha256 }}",
                assets.path("env").path("CERTIFICATE_SHA256").asText());
        JsonNode download = assets.path("steps").get(1);
        assertEquals("MegaMek-Launcher-*-installers", download.path("with").path("pattern").asText());
        assertEquals("build/unsigned", download.path("with").path("path").asText());
        assertTrue(download.path("with").path("merge-multiple").asBoolean());
        assertFalse(download.path("with").has("run-id"));
        assertTrue(step(assets, "Prepare the exact same-run installer set").path("run").asText()
                .contains("launcher_signing.py prepare"));
        String enabled = "needs.prepare.outputs.signing_mode == 'signpath'";
        JsonNode unsigned = step(assets, "Upload only the tested unsigned Windows MSI for SignPath");
        assertEquals(enabled, unsigned.path("if").asText());
        assertEquals("unsigned", unsigned.path("id").asText());
        assertEquals("build/signing-input/*.msi", unsigned.path("with").path("path").asText());
        JsonNode signing = step(assets, "Request signing and download the completed artifact");
        assertEquals(enabled, signing.path("if").asText());
        assertEquals("signpath", signing.path("id").asText());
        assertEquals("${{ secrets.SIGNPATH_API_TOKEN }}", signing.path("with").path("api-token").asText());
        for (String setting : List.of("organization-id", "project-slug", "signing-policy-slug",
                "artifact-configuration-slug")) {
            assertEquals("${{ needs.prepare.outputs." + setting.replace('-', '_') + " }}",
                    signing.path("with").path(setting).asText());
        }
        assertEquals("${{ steps.unsigned.outputs.artifact-id }}",
                signing.path("with").path("github-artifact-id").asText());
        assertTrue(signing.path("with").path("wait-for-completion").asBoolean());
        assertEquals("build/signpath-signed", signing.path("with").path("output-artifact-directory").asText());
        JsonNode verify = step(assets, "Verify the trusted signer, timestamp and unchanged MSI identity");
        assertEquals(enabled, verify.path("if").asText());
        assertEquals("pwsh", verify.path("shell").asText());
        assertTrue(verify.path("run").asText().contains("Confirm-LauncherMsi"));
        assertTrue(verify.path("run").asText().contains("launcher_signing.py complete"));
        assertTrue(verify.path("run").asText().contains("--certificate-sha256 \"$env:CERTIFICATE_SHA256\""));
        JsonNode publication = step(assets, "Retain verified final installer and checksum bytes");
        assertFalse(publication.has("if"), "disabled signing must still stage the complete unsigned asset set");
        assertEquals("publication", publication.path("id").asText());
        assertEquals("launcher-publication-${{ github.run_id }}-${{ github.run_attempt }}",
                publication.path("with").path("name").asText());
        assertEquals("build/publication/*", publication.path("with").path("path").asText());
        JsonNode verification = step(assets, "Retain platform signing verification");
        assertEquals(enabled + " || needs.prepare.outputs.apple_mode == 'apple'",
                verification.path("if").asText());
        assertEquals("verification", verification.path("id").asText());
        assertEquals("build/signing-verification/", verification.path("with").path("path").asText());
        for (JsonNode stage : List.of(unsigned, publication, verification)) {
            assertEquals("error", stage.path("with").path("if-no-files-found").asText());
            assertFalse(stage.path("with").path("overwrite").asBoolean(), "final artifact IDs must stay immutable");
        }
        for (JsonNode stage : assets.path("steps")) {
            assertFalse(stage.path("continue-on-error").asBoolean(), "signing/staging failure must block publication");
        }
        assertTrue(text.indexOf("Verify the trusted signer, timestamp and unchanged MSI identity")
                < text.indexOf("Retain verified final installer and checksum bytes"));
    }

    @Test
    void appleSigningIsIndependentReleaseOnlyAndVerifiedBeforePublication() throws Exception {
        ObjectMapper yaml = new ObjectMapper(new YAMLFactory());
        JsonNode build = yaml.readTree(Files.readString(WORKFLOW));
        JsonNode inputs = build.path("on").path("workflow_call").path("inputs");
        assertEquals("boolean", inputs.path("apple_signing").path("type").asText());
        assertFalse(inputs.path("apple_signing").path("default").asBoolean());
        assertFalse(build.path("on").path("workflow_dispatch").has("inputs"),
                "ordinary manual native builds cannot opt into signing");
        JsonNode installer = build.path("jobs").path("installers");
        JsonNode setup = step(installer, "Import release-only Apple signing credentials");
        String enabled = "runner.os == 'macOS' && inputs.apple_signing";
        assertEquals(enabled, setup.path("if").asText());
        assertTrue(setup.path("run").asText().contains("launcher_apple.py setup --commit \"$SOURCE_COMMIT\""));
        assertEquals("${{ github.token }}", setup.path("env").path("GH_TOKEN").asText());
        assertEquals("${{ inputs.apple_team_id }}", setup.path("env").path("APPLE_TEAM_ID").asText());
        JsonNode macBuild = step(installer, "Build macOS installer (never install)");
        assertEquals("${{ inputs.apple_signing }}", macBuild.path("env").path("APPLE_ENABLED").asText());
        assertTrue(macBuild.path("run").asText().contains("if [ \"$APPLE_ENABLED\" = true ]"));
        assertTrue(macBuild.path("run").asText().contains("-PappleSigningKeychain=$APPLE_SIGNING_KEYCHAIN"));
        JsonNode notary = step(installer, "Notarize and verify the final macOS package");
        assertEquals(enabled, notary.path("if").asText());
        assertTrue(notary.path("run").asText().contains("launcher_apple.py complete --commit \"$SOURCE_COMMIT\""));
        assertFalse(notary.path("continue-on-error").asBoolean());
        JsonNode cleanup = step(installer, "Remove temporary Apple credentials and restore keychains");
        assertEquals("always() && " + enabled, cleanup.path("if").asText());
        assertTrue(cleanup.path("run").asText().contains("launcher_apple.py cleanup"));
        assertFalse(cleanup.path("continue-on-error").asBoolean(),
                "credential cleanup failure must block publication");
        List<String> stepNames = new ArrayList<>();
        for (JsonNode stage : installer.path("steps")) {
            stepNames.add(stage.path("name").asText());
        }
        int cleanupIndex = stepNames.indexOf(cleanup.path("name").asText());
        assertEquals(stepNames.indexOf(notary.path("name").asText()) + 1, cleanupIndex,
                "remove private keys immediately after notarization, including on failure");
        for (String laterStep : List.of(
                "Verify exact installer/checksum pairs without installing",
                "Upload native installers and SHA-256 files only",
                "Test source on Windows", "Test source on Linux", "Test source on macOS",
                "Upload platform test reports", "Retain same-run Apple verification for release staging")) {
            assertTrue(cleanupIndex < stepNames.indexOf(laterStep),
                    "private keys must be removed before " + laterStep);
        }
        JsonNode evidence = step(installer, "Retain same-run Apple verification for release staging");
        assertEquals(enabled, evidence.path("if").asText());
        assertEquals("launcher-apple-verification-${{ matrix.id }}", evidence.path("with").path("name").asText());
        assertTrue(evidence.path("with").path("overwrite").asBoolean(),
                "failed native-job reruns must replace matching same-run package and proof together");
        String releaseText = Files.readString(Path.of(".github", "workflows", "launcher-release.yml"));
        JsonNode jobs = yaml.readTree(releaseText).path("jobs");
        JsonNode prepare = jobs.path("prepare");
        JsonNode plan = step(prepare, "Validate and capture optional Apple signing configuration");
        assertEquals("python3 -B scripts/launcher_apple.py plan", plan.path("run").asText());
        assertEquals("${{ vars.APPLE_SIGNING_ENABLED }}", plan.path("env").path("APPLE_SIGNING_ENABLED").asText());
        assertEquals("${{ steps.apple.outputs.mode }}", prepare.path("outputs").path("apple_mode").asText());
        assertEquals("${{ needs.prepare.outputs.apple_mode == 'apple' }}",
                jobs.path("installers").path("with").path("apple_signing").asText());
        assertTrue(releaseText.indexOf("Validate and capture optional Apple signing configuration")
                < releaseText.indexOf("Create repository-scoped release bot token"));
        JsonNode assets = jobs.path("release-assets");
        JsonNode verify = step(assets, "Bind Apple verification to the final staged packages");
        assertEquals("needs.prepare.outputs.apple_mode == 'apple'", verify.path("if").asText());
        assertTrue(verify.path("run").asText().contains("launcher_signing.py verify-apple"));
        assertTrue(releaseText.indexOf("Bind Apple verification to the final staged packages")
                < releaseText.indexOf("Retain verified final installer and checksum bytes"));
        for (String consumer : List.of("publish", "finalize")) {
            JsonNode job = jobs.path(consumer);
            assertEquals("${{ needs.prepare.outputs.apple_mode }}", job.path("env").path("MACOS_SIGNING").asText());
            assertEquals("${{ needs.prepare.outputs.apple_team_id }}", job.path("env").path("APPLE_TEAM_ID").asText());
            for (JsonNode stage : job.path("steps")) {
                if (stage.path("run").asText().contains("launcher_release.py")) {
                    assertTrue(stage.path("run").asText().contains("--macos-signing \"$MACOS_SIGNING\""));
                    assertTrue(stage.path("run").asText().contains("--apple-verification"));
                    assertTrue(stage.path("run").asText().contains("--apple-team-id \"$APPLE_TEAM_ID\""));
                }
            }
        }
        String gradle = Files.readString(Path.of("build.gradle.kts"));
        assertTrue(gradle.contains("args(\"--mac-sign\", \"--mac-signing-key-user-name\", signingIdentity"));
        assertTrue(gradle.contains("--mac-signing-keychain"));
        assertFalse(gradle.contains("APPLE_APPLICATION_P12_PASSWORD"), "private credentials never enter Gradle");
    }

    @Test
    void testSigningIsManualPinnedReadOnlyAndNeverPublishes() throws Exception {
        String text = Files.readString(Path.of(".github", "workflows", "launcher-test-signing.yml"));
        JsonNode root = new ObjectMapper(new YAMLFactory()).readTree(text);
        assertEquals(1, root.path("on").size());
        assertTrue(root.path("on").has("workflow_dispatch"));
        assertFalse(root.path("on").path("workflow_dispatch").has("inputs"));
        assertEquals("read", root.path("permissions").path("contents").asText());
        assertEquals("read", root.path("permissions").path("actions").asText());
        assertFalse(root.path("concurrency").path("cancel-in-progress").asBoolean());
        JsonNode jobs = root.path("jobs");
        assertEquals(4, jobs.size());
        JsonNode plan = jobs.path("plan");
        assertFalse(plan.has("if"), "invalid dispatch must fail explicitly rather than silently skip");
        String origin = step(plan, "Reject nonofficial or nonmain dispatch before reading credentials")
                .path("run").asText();
        assertTrue(origin.contains("MegaMek/mm-launcher"));
        assertTrue(origin.contains("refs/heads/main"));
        assertTrue(origin.contains("workflow_dispatch"));
        assertTrue(origin.contains("exit 1"));
        assertTrue(text.indexOf("Reject nonofficial or nonmain dispatch before reading credentials")
                < text.indexOf("Validate official main and capture test-only signing settings"));
        JsonNode settings = step(plan, "Validate official main and capture test-only signing settings");
        assertEquals("python3 -B scripts/launcher_test_signing.py plan", settings.path("run").asText());
        assertEquals("${{ vars.SIGNPATH_TEST_CERTIFICATE_SHA256 }}",
                settings.path("env").path("SIGNPATH_TEST_CERTIFICATE_SHA256").asText());
        assertFalse(settings.path("env").has("SIGNPATH_CERTIFICATE_SHA256"));
        assertEquals("./.github/workflows/launcher-archives.yml", jobs.path("installers").path("uses").asText());
        assertEquals("${{ needs.plan.outputs.commit }}",
                jobs.path("installers").path("with").path("source_commit").asText());
        assertFalse(jobs.path("installers").has("secrets"));
        assertFalse(jobs.path("installers").path("with").has("apple_signing"));
        JsonNode signing = jobs.path("sign-test");
        assertEquals("windows-2025", signing.path("runs-on").asText());
        assertEquals(List.of("plan", "installers"), dependencies(signing));
        JsonNode request = step(signing, "Request self-signed test signing only");
        assertEquals("signpath/github-action-submit-signing-request@f6d04783b4569d051e0c80105fe66e82819d0092",
                request.path("uses").asText());
        assertEquals("test-signing", request.path("with").path("signing-policy-slug").asText());
        assertEquals("${{ steps.unsigned.outputs.artifact-id }}",
                request.path("with").path("github-artifact-id").asText());
        assertTrue(request.path("with").path("wait-for-completion").asBoolean());
        assertFalse(request.path("continue-on-error").asBoolean());
        JsonNode verify = jobs.path("verify-test");
        assertEquals(List.of("plan", "sign-test"), dependencies(verify));
        assertEquals("ubuntu-24.04", verify.path("runs-on").asText());
        JsonNode download = verify.path("steps").get(1);
        assertEquals("${{ needs.sign-test.outputs.artifact_id }}",
                download.path("with").path("artifact-ids").asText());
        assertTrue(step(verify, "Verify signature and MSI digest using only the pinned test certificate")
                .path("run").asText().contains("launcher_test_signing.py verify"));
        assertTrue(step(verify, "Retain verified test-only result (do not distribute or install)")
                .path("with").path("name").asText().startsWith("DO-NOT-INSTALL-test-signpath-"));
        for (JsonNode job : jobs) {
            assertFalse(job.has("secrets"));
            assertFalse(job.path("permissions").toString().contains("write"));
            for (JsonNode stage : job.path("steps")) {
                assertFalse(stage.path("continue-on-error").asBoolean());
                if (stage.path("uses").asText().startsWith("actions/checkout@")) {
                    assertFalse(stage.path("with").path("persist-credentials").asBoolean());
                }
                String command = stage.path("run").asText();
                assertFalse(command.contains("launcher_release.py"));
                assertFalse(command.contains("git push"));
                assertFalse(command.contains("gh release"));
                assertFalse(command.contains("Import-Certificate"));
            }
        }
        assertFalse(text.contains("LAUNCHER_RELEASE_APP"));
        assertFalse(text.contains("release-signing"));
        assertFalse(text.contains("APPLE_APPLICATION_P12"));
    }

    @Test
    void advisoryDesktopWorkflowIsVisibleReadOnlyAndIndependentOfPublication() throws Exception {
        String text = Files.readString(Path.of(".github", "workflows", "launcher-gui-smoke.yml"));
        JsonNode root = new ObjectMapper(new YAMLFactory()).readTree(text);
        assertTrue(root.path("name").asText().contains("advisory"));
        assertEquals("Desktop UI checks - ${{ github.event_name }} (${{ github.head_ref || github.ref_name }})",
                root.path("run-name").asText());
        assertEquals("read", root.path("permissions").path("contents").asText());
        for (String event : List.of("push", "pull_request", "schedule", "workflow_dispatch")) {
            assertTrue(root.path("on").has(event));
        }
        assertFalse(root.path("on").has("workflow_call"));
        for (String event : List.of("push", "pull_request")) {
            assertTrue(root.path("on").path(event).path("paths").toString().contains("scripts/**"),
                    "changes to the shared verification script must exercise its desktop consumer");
        }
        assertEquals(2, root.path("jobs").size());
        JsonNode verification = root.path("jobs").path("release-verification");
        assertEquals("ubuntu-24.04", verification.path("runs-on").asText());
        assertEquals(5, verification.path("timeout-minutes").asInt());
        assertEquals("read", verification.path("permissions").path("contents").asText());
        assertEquals("read", verification.path("permissions").path("actions").asText());
        assertEquals(List.of("actions/checkout@v4"), actions(verification));
        assertFalse(verification.has("continue-on-error"));
        assertEquals("${{ steps.ci-plan.outputs.run_desktop_tests }}",
                verification.path("outputs").path("run_desktop_tests").asText());
        JsonNode selection = step(verification,
                "Determine whether this push only records an already verified release");
        assertEquals("ci-plan", selection.path("id").asText());
        assertEquals("${{ github.token }}", selection.path("env").path("GH_TOKEN").asText());
        assertEquals("python3 -B scripts/launcher_release.py ci-plan", selection.path("run").asText());
        JsonNode desktop = root.path("jobs").path("desktop");
        List<String> platforms = new ArrayList<>();
        desktop.path("strategy").path("matrix").path("include")
                .forEach(row -> platforms.add(row.path("id").asText()));
        assertEquals(List.of("windows-x64", "linux-x64", "macos-intel", "macos-apple-silicon"), platforms);
        assertEquals("release-verification", desktop.path("needs").asText());
        assertEquals("needs.release-verification.outputs.run_desktop_tests == 'true'",
                desktop.path("if").asText());
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

    private static List<String> dependencies(JsonNode job) {
        List<String> result = new ArrayList<>();
        job.path("needs").forEach(dependency -> result.add(dependency.asText()));
        return result;
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
