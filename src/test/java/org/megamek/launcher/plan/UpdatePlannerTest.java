package org.megamek.launcher.plan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.megamek.launcher.manifest.FileEntry;
import org.megamek.launcher.manifest.Manifest;
import org.megamek.launcher.manifest.ManifestException;
import org.megamek.launcher.manifest.ManifestReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdatePlannerTest {
    private static final List<String> PROTECTED = List.of("saves", "campaigns", "userdata");

    @TempDir
    Path installation;

    @Test
    void plansEveryOwnershipOutcomeWithoutChangingInstallation() throws Exception {
        write("keep.txt", "target");
        write("replace.txt", "old");
        write("modified.txt", "mine");
        write("remove.txt", "old-remove");
        write("obsolete-modified.txt", "mine");
        write("collision.txt", "unknown");
        write("saves/campaign.sav", "personal");

        Manifest baseline = manifest("1", List.of(
                file("keep.txt", "old"),
                file("replace.txt", "old"),
                file("modified.txt", "old"),
                file("remove.txt", "old-remove"),
                file("obsolete-modified.txt", "old-remove"),
                file("already-gone.txt", "old")));
        Manifest target = manifest("2", List.of(
                file("keep.txt", "target"),
                file("replace.txt", "target"),
                file("modified.txt", "target"),
                file("collision.txt", "target"),
                file("missing.txt", "target")));

        List<Decision> decisions = new UpdatePlanner().plan(baseline, target, installation);

        assertEquals(List.of(
                new Decision(Action.KEEP, "already-gone.txt", "obsolete managed file is already absent"),
                new Decision(Action.SKIP, "collision.txt", "target collides with an untracked local file"),
                new Decision(Action.KEEP, "keep.txt", "local file already matches target SHA-256"),
                new Decision(Action.ADD, "missing.txt", "target managed file is missing"),
                new Decision(Action.SKIP, "modified.txt", "local file differs from both baseline and target"),
                new Decision(Action.SKIP, "obsolete-modified.txt", "obsolete local file was modified"),
                new Decision(Action.REMOVE, "remove.txt", "obsolete local file matches baseline"),
                new Decision(Action.REPLACE, "replace.txt", "local file matches baseline; target differs")
        ), decisions);
        assertEquals("old", Files.readString(installation.resolve("replace.txt")));
        assertEquals("old-remove", Files.readString(installation.resolve("remove.txt")));
        assertEquals("personal", Files.readString(installation.resolve("saves/campaign.sav")));
        assertTrue(Files.notExists(installation.resolve("missing.txt")));
    }

    @Test
    void refusesLinkInManagedPath() throws Exception {
        Path outside = Files.createDirectory(installation.resolve("real-directory"));
        try {
            Files.createSymbolicLink(installation.resolve("linked"), outside);
        } catch (UnsupportedOperationException | IOException e) {
            return;
        }
        Manifest baseline = manifest("1", List.of());
        Manifest target = manifest("2", List.of(file("linked/payload.txt", "target")));

        ManifestException error = assertThrows(ManifestException.class,
                () -> new UpdatePlanner().plan(baseline, target, installation));
        assertTrue(error.getMessage().contains("link/reparse escape refused"));
    }

    private void write(String path, String contents) throws IOException {
        Path destination = installation.resolve(path);
        Files.createDirectories(destination.getParent());
        Files.writeString(destination, contents, StandardCharsets.UTF_8);
    }

    private static Manifest manifest(String release, List<FileEntry> files) {
        return new Manifest(ManifestReader.SCHEMA_VERSION, "MegaMek", "standalone", release,
                PROTECTED, files);
    }

    private static FileEntry file(String path, String contents) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return new FileEntry(path, HexFormat.of().formatHex(
                digest.digest(contents.getBytes(StandardCharsets.UTF_8))));
    }
}
