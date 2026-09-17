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
    void workflowIsReadOnlyAndCoversTheDeclaredNativeRunners() throws Exception {
        String text = Files.readString(WORKFLOW, StandardCharsets.UTF_8);
        JsonNode root = new ObjectMapper(new YAMLFactory()).readTree(text);

        assertTrue(root.path("on").has("workflow_dispatch"));
        assertTrue(root.path("on").has("push"));
        assertTrue(root.path("on").has("pull_request"));
        assertEquals("read", root.path("permissions").path("contents").asText());

        JsonNode job = root.path("jobs").path("test-and-package");
        List<String> runners = new ArrayList<>();
        for (JsonNode row : job.path("strategy").path("matrix").path("include")) {
            runners.add(row.path("os").asText());
        }
        assertEquals(List.of(
                "windows-2025", "ubuntu-24.04", "macos-15-intel", "macos-15"
        ), runners);

        List<String> actions = new ArrayList<>();
        for (JsonNode step : job.path("steps")) {
            if (step.has("uses")) {
                actions.add(step.path("uses").asText());
            }
        }
        assertEquals(List.of(
                "actions/checkout@v4",
                "actions/setup-java@v4",
                "gradle/actions/setup-gradle@v4",
                "actions/upload-artifact@v4",
                "actions/upload-artifact@v4"
        ), actions);
        assertTrue(text.contains("xvfb-run -a ./gradlew test"));
        assertFalse(text.contains("pull_request_target"));
        assertFalse(text.contains("contents: write"));
        assertFalse(text.contains("secrets."));
        assertFalse(text.contains("release"), "workflow must not create or publish a release");
    }
}
