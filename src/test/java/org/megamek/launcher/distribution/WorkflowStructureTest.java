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
    void workflowBuildsOnceThenVerifiesTheSameArtifactOnEveryDeclaredRunner()
            throws Exception {
        String text = Files.readString(WORKFLOW, StandardCharsets.UTF_8);
        JsonNode root = new ObjectMapper(new YAMLFactory()).readTree(text);

        assertTrue(root.path("on").has("workflow_dispatch"));
        assertTrue(root.path("on").has("push"));
        assertTrue(root.path("on").has("pull_request"));
        assertEquals("read", root.path("permissions").path("contents").asText());

        JsonNode producer = root.path("jobs").path("build-archive");
        assertEquals("windows-2025", producer.path("runs-on").asText());
        assertEquals(List.of(
                "actions/checkout@v4",
                "actions/setup-java@v4",
                "gradle/actions/setup-gradle@v4",
                "actions/upload-artifact@v4",
                "actions/upload-artifact@v4"
        ), actions(producer));
        assertEquals(1, count(text, "buildArchive"),
                "the workflow must select the producer only once");

        JsonNode verifier = root.path("jobs").path("verify-archive");
        assertEquals("build-archive", verifier.path("needs").asText());
        List<String> runners = new ArrayList<>();
        List<String> platforms = new ArrayList<>();
        for (JsonNode row : verifier.path("strategy").path("matrix").path("include")) {
            runners.add(row.path("os").asText());
            platforms.add(row.path("platform").asText());
        }
        assertEquals(List.of(
                "windows-2025", "ubuntu-24.04", "macos-15-intel", "macos-15"
        ), runners);
        assertEquals(List.of("windows", "linux", "mac", "mac"), platforms);
        assertEquals(List.of(
                "actions/checkout@v4",
                "actions/download-artifact@v4",
                "actions/setup-java@v4",
                "gradle/actions/setup-gradle@v4",
                "actions/upload-artifact@v4",
                "actions/upload-artifact@v4"
        ), actions(verifier));

        assertEquals(3, count(text, "verifyProvidedArchive"));
        assertEquals(4, count(text, "-PbuildIdentifier=${{ github.sha }}"),
                "the producer and all verifier commands must use the same build identity");
        assertTrue(text.contains("name: MegaMek-Launcher-all-platform"));
        assertTrue(text.contains("Archive SHA-256: $actual"));
        assertTrue(text.contains("xvfb-run -a ./gradlew"));
        assertTrue(text.contains("MegaMek-Launcher-test-reports-${{ matrix.id }}"));
        assertTrue(text.contains("buildArchive buildWindowsInstaller"));
        assertEquals(3, count(text, "verifyPortableArchive"));
        assertTrue(text.contains("name: MegaMek-Launcher-windows-x64-msi"));
        assertTrue(text.contains("name: MegaMek-Launcher-${{ matrix.id }}-portable"));
        assertTrue(text.contains("MSI checksum mismatch"));
        assertTrue(text.contains("Portable checksum mismatch"));
        String wixStep = producer.path("steps").get(3).path("run").asText();
        assertTrue(wixStep.contains("wix3141rtm/wix314-binaries.zip"));
        assertTrue(wixStep.contains("6ac824e1642d6f7277d0ed7ea09411a508f6116ba6fae0aa5f2c7daa2ff43d31"));
        assertTrue(wixStep.indexOf("Get-FileHash") < wixStep.indexOf("Expand-Archive"));
        assertTrue(wixStep.indexOf("throw \"WiX archive SHA-256 mismatch")
                < wixStep.indexOf("Expand-Archive"));

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
