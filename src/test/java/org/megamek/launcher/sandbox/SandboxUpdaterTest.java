package org.megamek.launcher.sandbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.megamek.launcher.manifest.FileEntry;
import org.megamek.launcher.manifest.Manifest;
import org.megamek.launcher.manifest.ManifestException;
import org.megamek.launcher.manifest.ManifestReader;
import org.megamek.launcher.plan.Action;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SandboxUpdaterTest {
    private static final List<String> PROTECTED = List.of("saves", "userdata");

    @TempDir
    Path temporary;

    @Test
    void appliesAllActionsAndPersistsOverridesAcrossReleases() throws Exception {
        Path fixture = Files.createDirectory(temporary.resolve("fixture"));
        write(fixture, "keep.txt", "same");
        write(fixture, "replace.txt", "old");
        write(fixture, "modified.txt", "official-a");
        write(fixture, "remove.txt", "old-remove");
        write(fixture, "obsolete-modified.txt", "official-obsolete");
        write(fixture, "collision.txt", "unknown");
        write(fixture, "unknown.txt", "preserve");
        write(fixture, "saves/save.dat", "personal");
        Manifest a = manifest("A", List.of(
                file("keep.txt", "same"), file("replace.txt", "old"),
                file("modified.txt", "official-a"), file("remove.txt", "old-remove"),
                file("obsolete-modified.txt", "official-obsolete")));
        Path aJson = writeManifest("a.json", a);
        Path sandbox = temporary.resolve("sandbox");
        SandboxUpdater updater = new SandboxUpdater();
        updater.initialize(aJson, fixture, sandbox);
        Path isolated = temporary.resolve("isolated-sandbox");
        SandboxState isolatedState = updater.initialize(aJson, fixture, isolated);
        write(sandbox, "modified.txt", "mine");
        write(sandbox, "obsolete-modified.txt", "mine-obsolete");

        Manifest b = manifest("B", List.of(
                file("keep.txt", "same"), file("replace.txt", "new"),
                file("modified.txt", "official-b"), file("collision.txt", "publisher"),
                file("add.txt", "added")));
        Path bJson = writeManifest("b.json", b);
        Path payloadB = Files.createDirectory(temporary.resolve("payload-b"));
        write(payloadB, "replace.txt", "new");
        write(payloadB, "modified.txt", "official-b");
        write(payloadB, "collision.txt", "publisher");
        write(payloadB, "add.txt", "added");

        SandboxUpdater.ApplyResult first = updater.apply(sandbox, bJson, payloadB);

        assertTrue(first.decisions().stream().anyMatch(d -> d.action() == Action.ADD));
        assertTrue(first.decisions().stream().anyMatch(d -> d.action() == Action.REPLACE));
        assertTrue(first.decisions().stream().anyMatch(d -> d.action() == Action.REMOVE));
        assertTrue(first.decisions().stream().anyMatch(d -> d.action() == Action.KEEP));
        assertTrue(first.decisions().stream().anyMatch(d -> d.action() == Action.SKIP));
        assertEquals("new", Files.readString(sandbox.resolve("replace.txt")));
        assertFalse(Files.exists(sandbox.resolve("remove.txt")));
        assertEquals("mine", Files.readString(sandbox.resolve("modified.txt")));
        assertEquals("mine-obsolete", Files.readString(sandbox.resolve("obsolete-modified.txt")));
        assertEquals("unknown", Files.readString(sandbox.resolve("collision.txt")));
        assertEquals("preserve", Files.readString(sandbox.resolve("unknown.txt")));
        assertEquals("personal", Files.readString(sandbox.resolve("saves/save.dat")));
        assertEquals(3, first.state().overrides().size());
        OverrideEntry modifiedOverride = first.state().overrides().stream()
                .filter(o -> o.path().equals("modified.txt"))
                .findFirst().orElseThrow();
        assertEquals(List.of(file("modified.txt", "official-a").sha256()),
                modifiedOverride.officialHashes());
        assertFalse(modifiedOverride.officialHashes().contains(file("modified.txt", "mine").sha256()));
        assertEquals("old", Files.readString(isolated.resolve("replace.txt")));
        assertFalse(first.state().sandboxId().equals(isolatedState.sandboxId()));

        // Although B remains the managed baseline, restoring recorded ancestor A reconciles
        // ownership on C without ever trusting the skipped local "mine" hash.
        write(sandbox, "modified.txt", "official-a");
        Manifest c = manifest("C", List.of(
                file("keep.txt", "same"), file("replace.txt", "new"),
                file("modified.txt", "official-c"), file("collision.txt", "publisher-c"),
                file("obsolete-modified.txt", "official-c-obsolete"), file("add.txt", "added")));
        Path cJson = writeManifest("c.json", c);
        Path payloadC = Files.createDirectory(temporary.resolve("payload-c"));
        write(payloadC, "modified.txt", "official-c");
        write(payloadC, "collision.txt", "publisher-c");
        write(payloadC, "obsolete-modified.txt", "official-c-obsolete");

        SandboxUpdater.ApplyResult second = updater.apply(sandbox, cJson, payloadC);

        assertEquals("official-c", Files.readString(sandbox.resolve("modified.txt")));
        assertEquals("mine-obsolete", Files.readString(sandbox.resolve("obsolete-modified.txt")));
        assertEquals("unknown", Files.readString(sandbox.resolve("collision.txt")));
        assertEquals(2, second.state().overrides().size());
        assertTrue(second.state().overrides().stream()
                .anyMatch(o -> o.kind() == OverrideEntry.Kind.COLLISION));
    }

    @Test
    void payloadMismatchDoesNotMutateInstallationOrCreateJournal() throws Exception {
        Manifest a = manifest("A", List.of(file("app.txt", "old")));
        Path fixture = Files.createDirectory(temporary.resolve("fixture"));
        write(fixture, "app.txt", "old");
        Path sandbox = temporary.resolve("sandbox");
        SandboxUpdater updater = new SandboxUpdater();
        updater.initialize(writeManifest("a.json", a), fixture, sandbox);
        Manifest b = manifest("B", List.of(file("app.txt", "new")));
        Path payload = Files.createDirectory(temporary.resolve("payload"));
        write(payload, "app.txt", "wrong");

        assertThrows(ManifestException.class,
                () -> updater.apply(sandbox, writeManifest("b.json", b), payload));

        assertEquals("old", Files.readString(sandbox.resolve("app.txt")));
        assertFalse(Files.exists(sandbox.resolve(".mm-launcher/journal.json")));
        try (var entries = Files.list(sandbox.resolve(".mm-launcher/transactions"))) {
            assertEquals(0, entries.count());
        }
    }

    @Test
    void stateIsRootBoundAndUpdaterLockIsExclusive() throws Exception {
        Manifest a = manifest("A", List.of(file("app.txt", "old")));
        Path fixture = Files.createDirectory(temporary.resolve("fixture"));
        write(fixture, "app.txt", "old");
        Path sandbox = temporary.resolve("sandbox");
        SandboxUpdater updater = new SandboxUpdater();
        updater.initialize(writeManifest("a.json", a), fixture, sandbox);
        Path payload = Files.createDirectory(temporary.resolve("payload"));

        try (FileChannel channel = FileChannel.open(sandbox.resolve(".mm-launcher/lock"),
                StandardOpenOption.WRITE); FileLock ignored = channel.lock()) {
            ManifestException error = assertThrows(ManifestException.class,
                    () -> updater.apply(sandbox, writeManifest("same.json", a), payload));
            assertTrue(error.getMessage().contains("another updater"));
        }

        Path statePath = sandbox.resolve(".mm-launcher/state.json");
        String state = Files.readString(statePath).replace(
                sandbox.toRealPath().toString().replace("\\", "\\\\"),
                temporary.resolve("other").toString().replace("\\", "\\\\"));
        Files.writeString(statePath, state);
        assertThrows(ManifestException.class, () -> updater.recover(sandbox));
    }

    @Test
    void restartRecoveryHandlesEveryCrashWindowAndIsIdempotent() throws Exception {
        for (String failpoint : List.of(
                "BEFORE_APP_MUTATION", "APP_MUTATION", "STATE_COMMIT", "CLEANUP_START")) {
            Path caseRoot = Files.createDirectory(temporary.resolve(failpoint.toLowerCase()));
            Path fixture = Files.createDirectory(caseRoot.resolve("fixture"));
            write(fixture, "app.txt", "old");
            Manifest a = manifest("A", List.of(file("app.txt", "old")));
            Manifest b = manifest("B", List.of(file("app.txt", "new")));
            Path aJson = writeManifest(caseRoot.resolve("a.json"), a);
            Path bJson = writeManifest(caseRoot.resolve("b.json"), b);
            Path payload = Files.createDirectory(caseRoot.resolve("payload"));
            write(payload, "app.txt", "new");
            Path sandbox = caseRoot.resolve("sandbox");
            SandboxUpdater updater = new SandboxUpdater();
            updater.initialize(aJson, fixture, sandbox);

            ProcessBuilder builder = new ProcessBuilder(javaExecutable(), "-cp",
                    System.getProperty("java.class.path"), "org.megamek.launcher.Main",
                    "apply-test-sandbox", "--target", bJson.toString(),
                    "--payload", payload.toString(), "--sandbox", sandbox.toString(),
                    "--acknowledge", SandboxUpdater.ACKNOWLEDGEMENT);
            builder.environment().put("MM_LAUNCHER_TEST_HALT_AFTER", failpoint);
            Process halted = builder.start();
            assertEquals(91, halted.waitFor());

            SandboxUpdater.RecoveryResult recovered = updater.recover(sandbox);
            boolean committed = failpoint.equals("STATE_COMMIT") || failpoint.equals("CLEANUP_START");
            assertEquals(committed ? "new" : "old", Files.readString(sandbox.resolve("app.txt")));
            assertEquals(committed ? "B" : "A", recovered.state().officialManifest().releaseId());
            assertEquals("no pending transaction", updater.recover(sandbox).outcome());
        }
    }

    @Test
    void restartRecoveryReusesCompletedRollbackStageAndRestoresExactPriorState() throws Exception {
        Path caseRoot = Files.createDirectory(temporary.resolve("rollback-restart"));
        Path fixture = Files.createDirectory(caseRoot.resolve("fixture"));
        write(fixture, "app.txt", "old");
        write(fixture, "saves/protected.dat", "keep");
        Manifest a = manifest("A", List.of(file("app.txt", "old")));
        Manifest b = manifest("B", List.of(file("app.txt", "new")));
        Path aJson = writeManifest(caseRoot.resolve("a.json"), a);
        Path bJson = writeManifest(caseRoot.resolve("b.json"), b);
        Path payload = Files.createDirectory(caseRoot.resolve("payload"));
        write(payload, "app.txt", "new");
        Path sandbox = caseRoot.resolve("sandbox");
        SandboxUpdater updater = new SandboxUpdater();
        updater.initialize(aJson, fixture, sandbox);
        String priorState = Files.readString(sandbox.resolve(".mm-launcher/state.json"));

        Process apply = updaterProcess("apply-test-sandbox", bJson, payload, sandbox,
                "APP_MUTATION").start();
        assertEquals(91, apply.waitFor());
        assertEquals("new", Files.readString(sandbox.resolve("app.txt")));

        Process recover = updaterProcess("recover-test-sandbox", null, null, sandbox,
                "ROLLBACK_BEFORE_REPLACE").start();
        assertEquals(91, recover.waitFor());
        try (var paths = Files.walk(sandbox.resolve(".mm-launcher/transactions"))) {
            assertTrue(paths.anyMatch(path -> path.getFileName().toString().endsWith(".rollback")));
        }

        SandboxUpdater.RecoveryResult recovered = updater.recover(sandbox);

        assertEquals("old", Files.readString(sandbox.resolve("app.txt")));
        assertEquals("keep", Files.readString(sandbox.resolve("saves/protected.dat")));
        assertEquals(priorState, Files.readString(sandbox.resolve(".mm-launcher/state.json")));
        assertEquals("A", recovered.state().officialManifest().releaseId());
        assertFalse(Files.exists(sandbox.resolve(".mm-launcher/journal.json")));
        try (var paths = Files.list(sandbox.resolve(".mm-launcher/transactions"))) {
            assertEquals(0, paths.count());
        }
        assertEquals("no pending transaction", updater.recover(sandbox).outcome());
    }

    @Test
    void restartRecoveryReconstructsPartialOwnedRollbackStage() throws Exception {
        Path caseRoot = Files.createDirectory(temporary.resolve("rollback-partial"));
        Path fixture = Files.createDirectory(caseRoot.resolve("fixture"));
        write(fixture, "app.txt", "old");
        Manifest a = manifest("A", List.of(file("app.txt", "old")));
        Manifest b = manifest("B", List.of(file("app.txt", "new")));
        Path bJson = writeManifest(caseRoot.resolve("b.json"), b);
        Path payload = Files.createDirectory(caseRoot.resolve("payload"));
        write(payload, "app.txt", "new");
        Path sandbox = caseRoot.resolve("sandbox");
        SandboxUpdater updater = new SandboxUpdater();
        updater.initialize(writeManifest(caseRoot.resolve("a.json"), a), fixture, sandbox);

        Process apply = updaterProcess("apply-test-sandbox", bJson, payload, sandbox,
                "APP_MUTATION").start();
        assertEquals(91, apply.waitFor());
        Path backup;
        try (var paths = Files.walk(sandbox.resolve(".mm-launcher/transactions"))) {
            backup = paths.filter(path -> path.getFileName().toString().equals("app.txt")
                            && path.getParent().getFileName().toString().equals("backups"))
                    .findFirst().orElseThrow();
        }
        Files.writeString(backup.resolveSibling("app.txt.rollback"), "partial");

        updater.recover(sandbox);

        assertEquals("old", Files.readString(sandbox.resolve("app.txt")));
        assertFalse(Files.exists(backup.resolveSibling("app.txt.rollback")));
        assertEquals("no pending transaction", updater.recover(sandbox).outcome());
    }

    private static ProcessBuilder updaterProcess(String command, Path target, Path payload,
                                                 Path sandbox, String failpoint) {
        List<String> arguments = new java.util.ArrayList<>(List.of(
                javaExecutable(), "-cp", System.getProperty("java.class.path"),
                "org.megamek.launcher.Main", command));
        if (target != null) {
            arguments.addAll(List.of("--target", target.toString(), "--payload", payload.toString()));
        }
        arguments.addAll(List.of("--sandbox", sandbox.toString(),
                "--acknowledge", SandboxUpdater.ACKNOWLEDGEMENT));
        ProcessBuilder builder = new ProcessBuilder(arguments);
        builder.environment().put("MM_LAUNCHER_TEST_HALT_AFTER", failpoint);
        return builder;
    }

    private Path writeManifest(String name, Manifest manifest) throws Exception {
        return writeManifest(temporary.resolve(name), manifest);
    }

    private static Path writeManifest(Path path, Manifest manifest) throws Exception {
        new ObjectMapper().writeValue(path.toFile(), manifest);
        return path;
    }

    private static Manifest manifest(String release, List<FileEntry> files) {
        return new Manifest(ManifestReader.SCHEMA_VERSION, "MegaMek", "standalone",
                release, PROTECTED, files);
    }

    private static FileEntry file(String path, String contents) throws Exception {
        return new FileEntry(path, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(contents.getBytes(StandardCharsets.UTF_8))));
    }

    private static void write(Path root, String path, String contents) throws Exception {
        Path destination = root.resolve(path);
        Files.createDirectories(destination.getParent());
        Files.writeString(destination, contents, StandardCharsets.UTF_8);
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
    }
}
