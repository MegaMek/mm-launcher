package org.megamek.launcher.manifest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void previewReportsOutermostCaseOnlyPrefixesDeterministicallyButStrictPairRejects()
            throws Exception {
        Manifest baseline = manifest("MegaMek", "standalone", List.of("saves"), List.of(
                entry("data/images/units/meks/Archer_5CS.png"),
                entry("data/Icons/old.png"),
                entry("lib/same.txt")));
        Manifest target = manifest("MegaMek", "standalone", List.of("saves"), List.of(
                entry("lib/same.txt"),
                entry("data/icons/new.png"),
                entry("data/images/units/meks/Archer_5Cs.png")));
        ManifestReader reader = new ManifestReader();

        ManifestException strict = assertThrows(ManifestException.class,
                () -> reader.validatePair(baseline, target));
        assertTrue(strict.getMessage().contains("case/normalization collision"));

        var expected = List.of(
                new ManifestReader.CaseOnlyRename("data/Icons", "data/icons"),
                new ManifestReader.CaseOnlyRename(
                        "data/images/units/meks/Archer_5CS.png",
                        "data/images/units/meks/Archer_5Cs.png"));
        assertEquals(expected, reader.validatePreviewPair(baseline, target).caseOnlyRenames());

        List<FileEntry> reversedBaseline = new ArrayList<>(baseline.files());
        List<FileEntry> reversedTarget = new ArrayList<>(target.files());
        java.util.Collections.reverse(reversedBaseline);
        java.util.Collections.reverse(reversedTarget);
        assertEquals(expected, reader.validatePreviewPair(
                manifest("MegaMek", "standalone", List.of("saves"), reversedBaseline),
                manifest("MegaMek", "standalone", List.of("saves"), reversedTarget))
                .caseOnlyRenames());
    }

    @Test
    void previewPairKeepsManifestContractsAndUnsafeManifestContentStrict() {
        ManifestReader reader = new ManifestReader();
        Manifest baseline = manifest("MegaMek", "standalone", List.of("saves"),
                List.of(entry("app.jar")));

        assertThrows(ManifestException.class, () -> reader.validatePreviewPair(
                baseline, manifest("MekHQ", "standalone", List.of("saves"),
                        List.of(entry("app.jar")))));
        assertThrows(ManifestException.class, () -> reader.validatePreviewPair(
                baseline, manifest("MegaMek", "bundle", List.of("saves"),
                        List.of(entry("app.jar")))));
        assertThrows(ManifestException.class, () -> reader.validatePreviewPair(
                baseline, manifest("MegaMek", "standalone", List.of("campaigns"),
                        List.of(entry("app.jar")))));
        assertThrows(ManifestException.class, () -> reader.validatePreviewPair(
                manifest("MegaMek", "standalone", List.of("saves"),
                        List.of(entry("duplicate.txt"), entry("duplicate.txt"))),
                baseline));
        assertThrows(ManifestException.class, () -> reader.validatePreviewPair(
                baseline, manifest("MegaMek", "standalone", List.of("saves"),
                        List.of(entry("data/Cafe\u0301.txt")))));
    }

    @Test
    void previewRejectsCrossReleaseFileParentConflictHiddenUnderCaseChangedDirectory() {
        Manifest baseline = manifest("MegaMek", "standalone", List.of("saves"), List.of(
                entry("data/Icons/node"),
                entry("data/Icons/old.png")));
        Manifest target = manifest("MegaMek", "standalone", List.of("saves"), List.of(
                entry("data/icons/new.png"),
                entry("data/icons/node/child.png")));

        ManifestException error = assertThrows(ManifestException.class,
                () -> new ManifestReader().validatePreviewPair(baseline, target));
        assertTrue(error.getMessage().contains("file/parent path conflict"));
        assertTrue(error.getMessage().contains("node"));
    }

    private Path write(String value) throws Exception {
        Path file = directory.resolve("manifest-" + sequence++ + ".json");
        return Files.writeString(file, value);
    }

    private static Manifest manifest(String product, String packageId, List<String> protectedPaths,
                                     List<FileEntry> files) {
        return new Manifest(ManifestReader.SCHEMA_VERSION, product, packageId, "release",
                protectedPaths, files);
    }

    private static FileEntry entry(String path) {
        return new FileEntry(path, HASH);
    }

    private static String json(String path) {
        return """
                {"schemaVersion":1,"product":"MegaMek","packageId":"standalone","releaseId":"1",
                 "protectedPaths":["saves"],"files":[{"path":"%s","sha256":"%s"}]}
                """.formatted(path, HASH);
    }
}
