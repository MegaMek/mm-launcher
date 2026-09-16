package org.megamek.launcher.manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManifestReaderTest {
    private static final String HASH = "0".repeat(64);

    @TempDir
    Path directory;
    private int sequence;

    @Test
    void rejectsUnknownFields() throws Exception {
        Path manifest = write("""
                {"schemaVersion":1,"product":"MegaMek","packageId":"standalone","releaseId":"1",
                 "protectedPaths":["saves"],"files":[],"unexpected":true}
                """);
        assertThrows(ManifestException.class, () -> new ManifestReader().read(manifest));
    }

    @Test
    void rejectsTraversalAndProtectedOwnership() throws Exception {
        Path traversal = write(json("../outside.txt"));
        assertTrue(assertThrows(ManifestException.class,
                () -> new ManifestReader().read(traversal)).getMessage().contains("unsafe file path"));

        Path protectedFile = write(json("saves/game.sav"));
        assertTrue(assertThrows(ManifestException.class,
                () -> new ManifestReader().read(protectedFile)).getMessage().contains("protected user-data"));
        assertThrows(ManifestException.class,
                () -> new ManifestReader().read(write(json("NUL.txt"))));
    }

    @Test
    void rejectsBadHashesDuplicatesCaseAliasesAndParentConflicts() throws Exception {
        assertThrows(ManifestException.class, () -> new ManifestReader().read(write("""
                {"schemaVersion":1,"product":"MegaMek","packageId":"standalone","releaseId":"1",
                 "protectedPaths":["saves"],"files":[{"path":"a","sha256":"ABC"}]}
                """)));
        assertThrows(ManifestException.class, () -> new ManifestReader().read(write("""
                {"schemaVersion":1,"product":"MegaMek","packageId":"standalone","releaseId":"1",
                 "protectedPaths":["saves"],"files":[
                 {"path":"A.txt","sha256":"%s"},{"path":"a.txt","sha256":"%s"}]}
                """.formatted(HASH, HASH))));
        assertThrows(ManifestException.class, () -> new ManifestReader().read(write("""
                {"schemaVersion":1,"product":"MegaMek","packageId":"standalone","releaseId":"1",
                 "protectedPaths":["saves"],"files":[
                 {"path":"lib","sha256":"%s"},{"path":"lib/a.jar","sha256":"%s"}]}
                """.formatted(HASH, HASH))));
    }

    @Test
    void rejectsIncompatiblePackage() throws Exception {
        ManifestReader reader = new ManifestReader();
        Manifest baseline = reader.read(write(json("app.jar")));
        Manifest target = new Manifest(1, "MekHQ", "bundle", "2",
                baseline.protectedPaths(), baseline.files());
        assertThrows(ManifestException.class, () -> reader.validatePair(baseline, target));
    }

    private Path write(String value) throws Exception {
        Path file = directory.resolve("manifest-" + sequence++ + ".json");
        return Files.writeString(file, value);
    }

    private static String json(String path) {
        return """
                {"schemaVersion":1,"product":"MegaMek","packageId":"standalone","releaseId":"1",
                 "protectedPaths":["saves"],"files":[{"path":"%s","sha256":"%s"}]}
                """.formatted(path, HASH);
    }
}
