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

package org.megamek.launcher.sandbox;

import org.megamek.launcher.manifest.FileEntry;
import org.megamek.launcher.manifest.Manifest;
import org.megamek.launcher.manifest.ManifestException;
import org.megamek.launcher.manifest.ManifestReader;
import org.megamek.launcher.plan.Action;
import org.megamek.launcher.plan.Decision;
import org.megamek.launcher.plan.UpdatePlanner;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class SandboxUpdater {
    public static final String ACKNOWLEDGEMENT = "I-UNDERSTAND-THIS-IS-A-DISPOSABLE-TEST";
    private final StateStore store = new StateStore();
    private final ManifestReader manifests = new ManifestReader();

    public SandboxState initialize(Path baselinePath, Path fixturePath, Path requestedRoot)
            throws IOException, ManifestException {
        Manifest baseline = manifests.read(baselinePath);
        Path fixture = SafeFiles.realDirectory(fixturePath, "fixture");
        validateAllowedNewRoot(requestedRoot, fixture, baselinePath);
        validateTree(fixture);
        for (FileEntry entry : baseline.files()) {
            Path source = SafeFiles.resolve(fixture, entry.path());
            if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)
                    || !SafeFiles.hashStable(source).equals(entry.sha256())) {
                throw new ManifestException("fixture does not match baseline: " + entry.path());
            }
        }

        Path root = requestedRoot.toAbsolutePath().normalize();
        if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(root);
        } else if (!isEmptyRealDirectory(root)) {
            throw new ManifestException("sandbox must be absent or an empty real directory: " + root);
        }
        root = SafeFiles.realDirectory(root, "sandbox");
        copyTree(fixture, root);
        Files.createDirectory(StateStore.metadata(root));
        Files.createDirectory(StateStore.metadata(root).resolve("transactions"));
        Files.createFile(StateStore.metadata(root).resolve("lock"));
        SandboxState state = new SandboxState(StateStore.SCHEMA, UUID.randomUUID().toString(),
                root.toString(), null, baseline, List.of());
        store.write(StateStore.metadata(root).resolve("state.json"), state);
        return state;
    }

    public ApplyResult apply(Path rootPath, Path targetPath, Path payloadPath)
            throws IOException, ManifestException {
        Path root = SafeFiles.realDirectory(rootPath, "sandbox");
        Path payload = SafeFiles.realDirectory(payloadPath, "payload");
        rejectOverlap(root, payload, "sandbox and payload");
        validateTree(payload);
        validateMetadata(root);
        try (LockHandle ignored = lock(root)) {
            rejectPendingJournal(root);
            SandboxState state = store.readState(root);
            Manifest target = manifests.read(targetPath);
            manifests.validatePair(state.officialManifest(), target);
            Map<String, Set<String>> trusted = trustedHashes(state);
            List<Decision> plan = new UpdatePlanner().plan(
                    state.officialManifest(), target, root, trusted);

            Map<String, FileEntry> targetFiles = index(target);
            String transactionId = UUID.randomUUID().toString();
            Path transaction = StateStore.metadata(root).resolve("transactions").resolve(transactionId);
            Path staged = transaction.resolve("staged");
            Path backups = transaction.resolve("backups");

            // Validate every required payload before creating transaction data or mutating the installation.
            for (Decision decision : plan) {
                if (decision.action() == Action.ADD || decision.action() == Action.REPLACE) {
                    FileEntry expected = targetFiles.get(key(decision.path()));
                    Path source = SafeFiles.resolve(payload, expected.path());
                    String hash = SafeFiles.hashStable(source);
                    if (!hash.equals(expected.sha256())) {
                        throw new ManifestException("payload SHA-256 mismatch for " + expected.path());
                    }
                }
            }
            List<Journal.Operation> operations = new ArrayList<>();
            List<String> createdDirectories = new ArrayList<>();
            for (Decision decision : plan) {
                if (decision.action() != Action.ADD && decision.action() != Action.REPLACE
                        && decision.action() != Action.REMOVE) {
                    continue;
                }
                Path application = SafeFiles.resolve(root, decision.path());
                String before = Files.exists(application, LinkOption.NOFOLLOW_LINKS)
                        ? SafeFiles.hashStable(application) : null;
                String after = targetFiles.containsKey(key(decision.path()))
                        ? targetFiles.get(key(decision.path())).sha256() : null;
                String backup = null;
                if (before != null) {
                    backup = StateStore.METADATA + "/transactions/" + transactionId
                            + "/backups/" + decision.path();
                }
                collectMissingParents(root, decision.path(), createdDirectories);
                operations.add(new Journal.Operation(decision.action().name(), decision.path(),
                        before, after, backup, false, false));
            }

            SandboxState next = nextState(state, target, plan, transactionId);
            Journal journal = new Journal(StateStore.SCHEMA, transactionId, Journal.Phase.PREPARED,
                    state, next, List.copyOf(operations), List.copyOf(createdDirectories));
            writeJournal(root, journal);

            // Intent exists before transaction artifacts, so a crash anywhere below is recoverable.
            Files.createDirectories(staged);
            Files.createDirectory(backups);
            for (Decision decision : plan) {
                if (decision.action() == Action.ADD || decision.action() == Action.REPLACE) {
                    FileEntry expected = targetFiles.get(key(decision.path()));
                    Path source = SafeFiles.resolve(payload, expected.path());
                    Path destination = createParents(staged, expected.path(), null);
                    SafeFiles.copyDurable(source, destination);
                    if (!SafeFiles.hashStable(destination).equals(expected.sha256())) {
                        throw new ManifestException("staged payload verification failed for " + expected.path());
                    }
                }
            }
            for (Journal.Operation operation : operations) {
                if (operation.backupPath() != null) {
                    Path application = SafeFiles.resolve(root, operation.path());
                    Path backupFile = createParents(root, operation.backupPath(), null);
                    SafeFiles.copyDurable(application, backupFile);
                    if (!SafeFiles.hashStable(backupFile).equals(operation.beforeHash())) {
                        throw new ManifestException("installation changed while backing up: "
                                + operation.path());
                    }
                }
            }
            failpoint("BEFORE_APP_MUTATION");

            for (int i = 0; i < operations.size(); i++) {
                Journal.Operation operation = operations.get(i).markStarted();
                operations.set(i, operation);
                journal = with(journal, Journal.Phase.MUTATING, operations);
                writeJournal(root, journal);
                mutate(root, staged, operation);
                failpoint("APP_MUTATION");
                operation = operation.markCompleted();
                operations.set(i, operation);
                journal = with(journal, Journal.Phase.MUTATING, operations);
                writeJournal(root, journal);
            }
            store.write(StateStore.metadata(root).resolve("state.json"), next);
            failpoint("STATE_COMMIT");
            journal = with(journal, Journal.Phase.STATE_COMMITTED, operations);
            writeJournal(root, journal);
            verifyCommitted(root, journal);
            journal = with(journal, Journal.Phase.CLEANUP, operations);
            writeJournal(root, journal);
            failpoint("CLEANUP_START");
            cleanup(root, journal);
            return new ApplyResult(next, plan);
        }
    }

    public RecoveryResult recover(Path rootPath) throws IOException, ManifestException {
        Path root = SafeFiles.realDirectory(rootPath, "sandbox");
        validateMetadata(root);
        try (LockHandle ignored = lock(root)) {
            deleteInterruptedMetadataStage(root, "state.json.new");
            deleteInterruptedMetadataStage(root, "journal.json.new");
            Path journalFile = StateStore.metadata(root).resolve("journal.json");
            SandboxState current = store.readState(root);
            if (Files.notExists(journalFile, LinkOption.NOFOLLOW_LINKS)) {
                return new RecoveryResult(current, "no pending transaction");
            }
            Journal journal = store.readJournal(root);
            if (journal.transactionId().equals(current.lastTransactionId())) {
                verifyCommitted(root, journal);
                cleanup(root, journal);
                return new RecoveryResult(current, "completed committed transaction cleanup");
            }
            if (!sameStateIdentity(current, journal.previousState())) {
                throw new ManifestException("state conflicts with pending transaction; refusing recovery");
            }
            rollback(root, journal);
            store.write(StateStore.metadata(root).resolve("state.json"), journal.previousState());
            cleanup(root, journal);
            return new RecoveryResult(journal.previousState(), "rolled back interrupted pre-commit transaction");
        }
    }

    private static SandboxState nextState(SandboxState previous, Manifest target,
                                          List<Decision> plan, String transactionId) {
        Map<String, OverrideEntry> overrides = new LinkedHashMap<>();
        previous.overrides().forEach(value -> overrides.put(key(value.path()), value));
        Map<String, FileEntry> old = index(previous.officialManifest());
        for (Decision decision : plan) {
            String key = key(decision.path());
            if (decision.action() == Action.SKIP) {
                OverrideEntry prior = overrides.get(key);
                Set<String> hashes = new HashSet<>();
                if (prior != null) {
                    hashes.addAll(prior.officialHashes());
                }
                FileEntry oldEntry = old.get(key);
                if (oldEntry != null) {
                    hashes.add(oldEntry.sha256());
                }
                OverrideEntry.Kind kind = oldEntry == null
                        ? OverrideEntry.Kind.COLLISION
                        : (target.files().stream().anyMatch(f -> key(f.path()).equals(key))
                        ? OverrideEntry.Kind.MODIFIED : OverrideEntry.Kind.OBSOLETE_MODIFIED);
                overrides.put(key, new OverrideEntry(decision.path(), kind,
                        hashes.stream().sorted().toList()));
            } else if (decision.action() == Action.ADD || decision.action() == Action.REPLACE
                    || decision.action() == Action.REMOVE || decision.action() == Action.KEEP) {
                overrides.remove(key);
            }
        }
        return new SandboxState(StateStore.SCHEMA, previous.sandboxId(), previous.canonicalRoot(),
                transactionId, target, overrides.values().stream()
                .sorted(Comparator.comparing(OverrideEntry::path, String.CASE_INSENSITIVE_ORDER))
                .toList());
    }

    private void rollback(Path root, Journal journal) throws IOException, ManifestException {
        List<Journal.Operation> reverse = new ArrayList<>(journal.operations());
        java.util.Collections.reverse(reverse);
        for (Journal.Operation operation : reverse) {
            Path destination = SafeFiles.resolve(root, operation.path());
            String current = Files.exists(destination, LinkOption.NOFOLLOW_LINKS)
                    ? SafeFiles.hashStable(destination) : null;
            if (operation.beforeHash() == null) {
                if (current == null) {
                    continue;
                }
                if (!current.equals(operation.afterHash())) {
                    throw new ManifestException("external edit blocks rollback of " + operation.path());
                }
                Files.delete(destination);
            } else {
                Path backup = SafeFiles.resolve(root, operation.backupPath());
                Path rollbackStage = SafeFiles.resolve(root, operation.backupPath() + ".rollback");
                if (operation.beforeHash().equals(current)) {
                    deleteOwnedRollbackStage(rollbackStage, operation.path());
                    continue;
                }
                if (current != null && !current.equals(operation.afterHash())) {
                    throw new ManifestException("external edit blocks rollback of " + operation.path());
                }
                if (!Files.exists(backup, LinkOption.NOFOLLOW_LINKS)
                        || !SafeFiles.hashStable(backup).equals(operation.beforeHash())) {
                    throw new ManifestException("valid backup unavailable for " + operation.path());
                }
                prepareRollbackStage(backup, rollbackStage, operation);
                failpoint("ROLLBACK_BEFORE_REPLACE");
                SafeFiles.atomicReplace(rollbackStage, destination);
            }
        }
        List<String> dirs = new ArrayList<>(journal.createdDirectories());
        java.util.Collections.reverse(dirs);
        for (String directory : dirs) {
            Path path = SafeFiles.resolve(root, directory);
            if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS) && isEmptyRealDirectory(path)) {
                Files.delete(path);
            }
        }
    }

    private static void prepareRollbackStage(Path backup, Path rollbackStage,
                                             Journal.Operation operation)
            throws IOException, ManifestException {
        if (Files.exists(rollbackStage, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(rollbackStage, LinkOption.NOFOLLOW_LINKS)) {
                throw new ManifestException("invalid rollback staging entry for " + operation.path());
            }
            if (SafeFiles.hashStable(rollbackStage).equals(operation.beforeHash())) {
                return;
            }
            // This exact transaction-private path is updater-owned. A nonmatching regular file is
            // an interrupted copy and may be reconstructed only after the backup was validated.
            Files.delete(rollbackStage);
        }
        SafeFiles.copyDurable(backup, rollbackStage);
        if (!SafeFiles.hashStable(rollbackStage).equals(operation.beforeHash())) {
            throw new ManifestException("rollback staging verification failed for " + operation.path());
        }
    }

    private static void deleteOwnedRollbackStage(Path rollbackStage, String applicationPath)
            throws IOException, ManifestException {
        if (Files.notExists(rollbackStage, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (!Files.isRegularFile(rollbackStage, LinkOption.NOFOLLOW_LINKS)) {
            throw new ManifestException("invalid rollback staging entry for " + applicationPath);
        }
        Files.delete(rollbackStage);
    }

    private static void mutate(Path root, Path staged, Journal.Operation operation)
            throws IOException, ManifestException {
        Path destination = SafeFiles.resolve(root, operation.path());
        if (operation.action().equals(Action.REMOVE.name())) {
            if (!SafeFiles.hashStable(destination).equals(operation.beforeHash())) {
                throw new ManifestException("installation changed before remove: " + operation.path());
            }
            Files.delete(destination);
            return;
        }
        if (operation.beforeHash() != null
                && !SafeFiles.hashStable(destination).equals(operation.beforeHash())) {
            throw new ManifestException("installation changed before replacement: " + operation.path());
        }
        if (operation.beforeHash() == null && Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            throw new ManifestException("installation collision appeared before add: " + operation.path());
        }
        Path parent = destination.getParent();
        if (Files.notExists(parent, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(parent);
        }
        Path source = SafeFiles.resolve(staged, operation.path());
        if (!SafeFiles.hashStable(source).equals(operation.afterHash())) {
            throw new ManifestException("staged payload changed before mutation: " + operation.path());
        }
        SafeFiles.atomicReplace(source, destination);
    }

    private static void verifyCommitted(Path root, Journal journal) throws IOException, ManifestException {
        for (Journal.Operation operation : journal.operations()) {
            Path path = SafeFiles.resolve(root, operation.path());
            if (operation.afterHash() == null) {
                if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new ManifestException("committed remove verification failed: " + operation.path());
                }
            } else if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                    || !SafeFiles.hashStable(path).equals(operation.afterHash())) {
                throw new ManifestException("external edit blocks committed recovery: " + operation.path());
            }
        }
    }

    private static void cleanup(Path root, Journal journal) throws IOException, ManifestException {
        Path transaction = StateStore.metadata(root).resolve("transactions")
                .resolve(journal.transactionId());
        if (Files.exists(transaction, LinkOption.NOFOLLOW_LINKS)) {
            deleteKnownTree(transaction);
        }
        Files.deleteIfExists(StateStore.metadata(root).resolve("journal.json"));
    }

    private static void deleteKnownTree(Path directory) throws IOException, ManifestException {
        BasicTree.ensureNoLinks(directory);
        List<Path> paths;
        try (var stream = Files.walk(directory)) {
            paths = stream.sorted(Comparator.reverseOrder()).toList();
        }
        for (Path path : paths) {
            Files.delete(path);
        }
    }

    private static void rejectPendingJournal(Path root) throws ManifestException {
        if (Files.exists(StateStore.metadata(root).resolve("journal.json"), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(StateStore.metadata(root).resolve("journal.json.new"), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(StateStore.metadata(root).resolve("state.json.new"), LinkOption.NOFOLLOW_LINKS)) {
            throw new ManifestException("pending transaction exists; run recover before apply");
        }
    }

    private static void deleteInterruptedMetadataStage(Path root, String name)
            throws IOException, ManifestException {
        Path path = SafeFiles.resolve(root, StateStore.METADATA + "/" + name);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new ManifestException("invalid interrupted metadata staging entry: " + name);
            }
            Files.delete(path);
        }
    }

    private LockHandle lock(Path root) throws IOException, ManifestException {
        Path lockPath = SafeFiles.resolve(root, StateStore.METADATA + "/lock");
        FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.WRITE);
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) {
                channel.close();
                throw new ManifestException("another updater holds the sandbox lock");
            }
            return new LockHandle(channel, lock);
        } catch (OverlappingFileLockException e) {
            channel.close();
            throw new ManifestException("another updater holds the sandbox lock", e);
        }
    }

    private static Map<String, Set<String>> trustedHashes(SandboxState state) {
        Map<String, Set<String>> result = new HashMap<>();
        state.overrides().forEach(o -> result.put(key(o.path()), Set.copyOf(o.officialHashes())));
        return result;
    }

    private static Map<String, FileEntry> index(Manifest manifest) {
        Map<String, FileEntry> result = new HashMap<>();
        manifest.files().forEach(entry -> result.put(key(entry.path()), entry));
        return result;
    }

    private static String key(String value) {
        return value.toLowerCase(Locale.ROOT);
    }

    private static void validateAllowedNewRoot(Path requested, Path fixture, Path baseline)
            throws IOException, ManifestException {
        Path root = requested.toAbsolutePath().normalize();
        Path temp = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        Path build = Path.of("").toAbsolutePath().normalize().resolve("build");
        if (!root.startsWith(temp) && !root.startsWith(build)) {
            throw new ManifestException("sandbox must be under the system temp directory or this checkout's build");
        }
        Path parent = root.getParent();
        if (parent == null || !Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new ManifestException("sandbox parent must already exist");
        }
        SafeFiles.realDirectory(parent, "sandbox parent");
        rejectOverlap(root, fixture, "sandbox and fixture");
        rejectOverlap(root, baseline.toAbsolutePath().normalize(), "sandbox and baseline manifest");
    }

    private static void rejectOverlap(Path first, Path second, String label) throws ManifestException {
        Path a = first.toAbsolutePath().normalize();
        Path b = second.toAbsolutePath().normalize();
        if (a.startsWith(b) || b.startsWith(a)) {
            throw new ManifestException(label + " must not overlap");
        }
    }

    private static void validateTree(Path root) throws IOException, ManifestException {
        BasicTree.ensureNoLinks(root);
        if (Files.exists(root.resolve(StateStore.METADATA), LinkOption.NOFOLLOW_LINKS)) {
            throw new ManifestException("fixture contains reserved launcher metadata namespace");
        }
    }

    private static void validateMetadata(Path root) throws IOException, ManifestException {
        Path metadata = SafeFiles.resolve(root, StateStore.METADATA);
        BasicTree.ensureNoLinks(metadata);
        Path transactions = SafeFiles.resolve(root, StateStore.METADATA + "/transactions");
        if (!Files.isDirectory(transactions, LinkOption.NOFOLLOW_LINKS)) {
            throw new ManifestException("invalid launcher transaction storage");
        }
    }

    private static void copyTree(Path source, Path destination) throws IOException {
        try (var stream = Files.walk(source)) {
            for (Path item : stream.toList()) {
                Path relative = source.relativize(item);
                Path target = destination.resolve(relative.toString());
                if (Files.isDirectory(item, LinkOption.NOFOLLOW_LINKS)) {
                    if (!relative.toString().isEmpty()) {
                        Files.createDirectory(target);
                    }
                } else {
                    SafeFiles.copyDurable(item, target);
                }
            }
        }
    }

    private static Path createParents(Path root, String portable, List<String> created)
            throws IOException, ManifestException {
        Path destination = SafeFiles.resolve(root, portable);
        Path parent = destination.getParent();
        if (Files.notExists(parent, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectories(parent);
        }
        return destination;
    }

    private static void collectMissingParents(Path root, String portable, List<String> result)
            throws IOException, ManifestException {
        String[] parts = portable.split("/");
        String current = "";
        for (int i = 0; i < parts.length - 1; i++) {
            current = current.isEmpty() ? parts[i] : current + "/" + parts[i];
            if (Files.notExists(SafeFiles.resolve(root, current), LinkOption.NOFOLLOW_LINKS)
                    && !result.contains(current)) {
                result.add(current);
            }
        }
    }

    private static boolean isEmptyRealDirectory(Path path) throws IOException, ManifestException {
        Path real = SafeFiles.realDirectory(path, "directory");
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(real)) {
            return !entries.iterator().hasNext();
        }
    }

    private void writeJournal(Path root, Journal journal) throws IOException {
        store.write(StateStore.metadata(root).resolve("journal.json"), journal);
    }

    private static Journal with(Journal journal, Journal.Phase phase,
                                List<Journal.Operation> operations) {
        return new Journal(journal.schemaVersion(), journal.transactionId(), phase,
                journal.previousState(), journal.nextState(), List.copyOf(operations),
                journal.createdDirectories());
    }

    private static boolean sameStateIdentity(SandboxState first, SandboxState second) {
        return first.sandboxId().equals(second.sandboxId())
                && java.util.Objects.equals(first.lastTransactionId(), second.lastTransactionId())
                && first.officialManifest().equals(second.officialManifest())
                && first.overrides().equals(second.overrides());
    }

    private static void failpoint(String point) {
        if (point.equals(System.getenv("MM_LAUNCHER_TEST_HALT_AFTER"))) {
            Runtime.getRuntime().halt(91);
        }
    }

    public record ApplyResult(SandboxState state, List<Decision> decisions) {
    }

    public record RecoveryResult(SandboxState state, String outcome) {
    }

    private record LockHandle(FileChannel channel, FileLock lock) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            lock.release();
            channel.close();
        }
    }

    private static final class BasicTree {
        static void ensureNoLinks(Path root) throws IOException, ManifestException {
            try (var stream = Files.walk(root)) {
                for (Path path : stream.toList()) {
                    var attributes = Files.readAttributes(path,
                            java.nio.file.attribute.BasicFileAttributes.class,
                            LinkOption.NOFOLLOW_LINKS);
                    if (attributes.isSymbolicLink() || attributes.isOther()) {
                        throw new ManifestException("link/reparse point refused in tree: " + path);
                    }
                }
            }
        }
    }
}
