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

package org.megamek.launcher.update;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.megamek.launcher.channel.ChannelPreferenceStore;
import org.megamek.launcher.launch.RootCoordinator;
import org.megamek.launcher.manifest.FileEntry;
import org.megamek.launcher.manifest.ManifestException;
import org.megamek.launcher.manifest.ManifestReader;
import org.megamek.launcher.onboarding.Inspection;
import org.megamek.launcher.onboarding.InstallationInspector;
import org.megamek.launcher.operation.OperationContext;
import org.megamek.launcher.operation.OperationPhase;
import org.megamek.launcher.operation.OperationType;
import org.megamek.launcher.operation.ProgressUnit;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryData;
import org.megamek.launcher.registry.RegistryStore;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Conservative, ledger-backed removal of launcher-managed application files.
 *
 * <p>Only regular files whose current digest equals the latest official provenance are moved.
 * The move target is an external sibling on the same filesystem. Registration removal is the
 * final commit barrier; every pre-commit failure restores moved files and launcher metadata.</p>
 */
public final class UninstallService {
    public static final int JOURNAL_SCHEMA = 1;
    public static final String CONFIRMATION = "UNINSTALL-OFFICIAL-FILES";
    private static final String JOURNAL_ROOT = "uninstall-v1";
    private static final String REMOVAL_ROOT = "registration-removal-v1";
    private static final int MAX_JOURNAL_BYTES = 32 * 1024 * 1024;
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> COMMITTED_PHASES =
            Set.of("COMMIT_AUTHORIZED", "COMMITTED");

    private final Path registry;
    private final RegistryStore registries;
    private final ReceiptStore receipts;
    private final CurrentStateStore states;
    private final ChannelPreferenceStore channels;
    private final AdoptionStateStore adoptions;
    private final InstallationInspector inspector;
    private final RootCoordinator coordinator;
    private final FailureHook failureHook;
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public UninstallService(Path registry, RootCoordinator coordinator) {
        this(registry, new RegistryStore(), new ReceiptStore(), new ChannelPreferenceStore(),
                new InstallationInspector(), coordinator, point -> { });
    }

    UninstallService(Path registry, RegistryStore registries, ReceiptStore receipts,
                     ChannelPreferenceStore channels, InstallationInspector inspector,
                     RootCoordinator coordinator, FailureHook failureHook) {
        this.registry = registry.toAbsolutePath().normalize();
        this.registries = Objects.requireNonNull(registries);
        this.receipts = Objects.requireNonNull(receipts);
        this.states = new CurrentStateStore(receipts);
        this.channels = Objects.requireNonNull(channels);
        this.adoptions = new AdoptionStateStore(receipts);
        this.inspector = Objects.requireNonNull(inspector);
        this.coordinator = Objects.requireNonNull(coordinator);
        this.failureHook = Objects.requireNonNull(failureHook);
        mapper.findAndRegisterModules();
    }

    /** Read-only eligibility and exact-file plan. It also rejects a currently running child. */
    public Plan plan(InstallationRecord expected, OperationContext context)
            throws IOException, InterruptedException, ManifestException {
        requireWorker();
        Objects.requireNonNull(context, "context");
        requirePresent(expected);
        try (OperationContext.WorkerRegistration ignored = context.activate();
             RootCoordinator.Lease lease = coordinator.acquire(rootOf(expected), false)) {
            lease.requireNoPendingUpdate();
            return planLocked(expected, context);
        }
    }

    /** Executes only an unchanged plan and cannot be cancelled after the first root move. */
    public Result uninstall(Plan expectedPlan, String confirmation, OperationContext context)
            throws IOException, InterruptedException, ManifestException {
        requireWorker();
        requireConfirmation(confirmation);
        Objects.requireNonNull(expectedPlan, "plan");
        Objects.requireNonNull(context, "context");
        Path root = rootOf(expectedPlan.record());
        requirePresent(expectedPlan.record());
        try (OperationContext.WorkerRegistration ignored = context.activate();
             RootCoordinator.Lease lease = coordinator.acquire(root, false)) {
            lease.requireNoPendingUpdate();
            Plan currentPlan = planLocked(expectedPlan.record(), context);
            if (!expectedPlan.equals(currentPlan)) {
                throw new IOException("installation files or ownership changed; review uninstall "
                        + "again");
            }
            return executeConfirmedPlan(currentPlan, context);
        }
    }

    /** Plans and executes a confirmed removal under one lease, without replanning. */
    public Result uninstallConfirmed(InstallationRecord expected, String confirmation,
                                     OperationContext context)
            throws IOException, InterruptedException, ManifestException {
        requireWorker();
        requireConfirmation(confirmation);
        Objects.requireNonNull(context, "context");
        requirePresent(expected);
        try (OperationContext.WorkerRegistration ignored = context.activate();
             RootCoordinator.Lease lease = coordinator.acquire(rootOf(expected), false)) {
            lease.requireNoPendingUpdate();
            return executeConfirmedPlan(planLocked(expected, context), context);
        }
    }

    private Result executeConfirmedPlan(Plan plan, OperationContext context)
            throws IOException, InterruptedException, ManifestException {
        context.checkpoint();
        context.enterFinalization("Uninstall file moves have begun and must finish or roll "
                + "back before the launcher can close.");
        return executeLocked(plan, context);
    }

    private static void requireConfirmation(String confirmation) throws IOException {
        if (!CONFIRMATION.equals(confirmation)) {
            throw new IOException("uninstall confirmation is missing");
        }
    }

    /**
     * Removes registration and sidecars, never application-root content. This operation still
     * uses the root gate and an exact registration snapshot.
     */
    public Result removeFromLauncher(InstallationRecord expected) throws IOException {
        return removeFromLauncher(expected, false);
    }

    public Result removeMissingFromLauncher(InstallationRecord expected) throws IOException {
        return removeFromLauncher(expected, true);
    }

    private Result removeFromLauncher(InstallationRecord expected, boolean missingOnly)
            throws IOException {
        requireWorker();
        Path root = rootOf(expected);
        boolean missing = InstallationDirectory.missing(root);
        if (!missingOnly && missing) throw new MissingInstallationException(expected);
        if (missingOnly && !missing) {
            throw new IOException("The installation folder exists again; nothing was removed.");
        }
        try (RootCoordinator.Lease lease = missing
                ? coordinator.acquireForRecovery(root, false) : coordinator.acquire(root, false)) {
            lease.requireNoPendingUpdate();
            RegistryData data = registries.read(registry);
            InstallationRecord current = registries.resolve(data, expected.id());
            requireExact(current, expected);
            recoverRemoval(current);
            if (hasUninstallPending(expected.id()) && !missing) {
                throw new IOException("Uninstall recovery required before removing this copy");
            }
            if (missing) {
                requireMissing(root);
            } else {
                requireCurrentLayout(current);
            }
            UninstallJournal pendingJournal = missing && hasUninstallPending(current.id())
                    ? readJournal(current.id()) : null;
            if (pendingJournal != null) requireJournalBinding(pendingJournal, current);

            Path metadata = receipts.metadataDirectory(registry);
            List<UninstallJournal.MetadataMove> moves = new ArrayList<>();
            for (Path sidecar : existingSidecars(metadata, current.id())) {
                moves.add(new UninstallJournal.MetadataMove(
                        sidecar.getFileName().toString(), hash(sidecar), Files.size(sidecar),
                        "PLANNED"));
            }
            ensureMetadataDirectory(metadata, data);
            Path removals = removalRoot();
            if (!Files.exists(removals, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(removals);
            StrictPathSafety.requireDirectory(removals, "registration-removal recovery directory");
            Path transaction = removalDirectory(current.id());
            Files.createDirectory(transaction);
            RegistrationRemovalJournal removal = new RegistrationRemovalJournal(
                    JOURNAL_SCHEMA, registry.toString(), current, "PREPARED", List.copyOf(moves));
            writeRemovalJournal(removal);
            Path staging = transaction.resolve("metadata");
            try {
                if (!moves.isEmpty()) {
                    Files.createDirectory(staging);
                    StrictPathSafety.requireDirectory(staging, "registration-removal staging");
                    for (UninstallJournal.MetadataMove move : moves) {
                        Path source = metadata.resolve(move.fileName());
                        requireExactFile(source, move.sha256(), move.size());
                        failureHook.at("remove-metadata-verified");
                        atomicMove(source, staging.resolve(move.fileName()));
                        failureHook.at("remove-metadata-moved");
                    }
                }
                if (missing) requireMissing(root);
                if (pendingJournal != null) {
                    writeJournal(withPhase(pendingJournal, "COMMIT_AUTHORIZED"));
                }
                removal = new RegistrationRemovalJournal(removal.schemaVersion(),
                        removal.canonicalRegistry(), current, "COMMIT_AUTHORIZED", removal.metadata());
                writeRemovalJournal(removal);
                failureHook.at("remove-before-registry");
                if (missing) requireMissing(root);
                registries.remove(registry, current);
            } catch (IOException | RuntimeException failure) {
                if (COMMITTED_PHASES.contains(removal.phase()) && registrationIsAbsent(current)) {
                    String warning = cleanupRemoval(removal);
                    if (pendingJournal != null) {
                        String recoveryWarning = cleanupMissingJournal(pendingJournal);
                        if (warning == null) warning = recoveryWarning;
                    }
                    cleanupEmptyMetadataParents();
                    return new Result(false, false, warning == null
                            ? "Removal completed after a registry close warning."
                            : warning);
                }
                try {
                    restoreRemovalMetadata(removal);
                } catch (IOException rollback) {
                    failure.addSuppressed(rollback);
                }
                if (pendingJournal != null) {
                    try {
                        writeJournal(pendingJournal);
                    } catch (IOException restoreFailure) {
                        failure.addSuppressed(restoreFailure);
                    }
                }
                throw failure;
            }
            String warning;
            try {
                failureHook.at("remove-after-registry");
                warning = cleanupRemoval(removal);
            } catch (IOException | RuntimeException failure) {
                warning = "Registration removed, but launcher metadata cleanup is still required: "
                        + detail(failure);
            }
            if (pendingJournal != null) {
                String recoveryWarning = cleanupMissingJournal(pendingJournal);
                if (warning == null) warning = recoveryWarning;
            }
            cleanupEmptyMetadataParents();
            return new Result(false, false, warning);
        }
    }

    public boolean hasPending(String installationId) throws IOException {
        return hasPending(registry, installationId);
    }

    public static boolean hasPending(Path registry, String installationId) throws IOException {
        return journalExists(pendingJournalPath(registry, installationId))
                || journalExists(pendingRemovalJournalPath(registry, installationId));
    }

    private boolean hasUninstallPending(String id) throws IOException {
        return journalExists(pendingJournalPath(registry, id));
    }

    private static boolean journalExists(Path journal) throws IOException {
        try {
            BasicFileAttributes attributes = Files.readAttributes(
                    journal, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink()
                    || attributes.isOther()) {
                throw new IOException("uninstall recovery state has an unexpected type");
            }
            return true;
        } catch (NoSuchFileException missing) {
            return false;
        }
    }

    public static Path pendingJournalPath(Path registry, String installationId)
            throws IOException {
        UUID.fromString(installationId);
        Path file = registry.toAbsolutePath().normalize();
        return new ReceiptStore().metadataDirectory(file).resolve(JOURNAL_ROOT)
                .resolve(installationId).resolve("journal.json");
    }

    public static Path pendingRemovalJournalPath(Path registry, String installationId)
            throws IOException {
        requireTransactionId(installationId);
        return new ReceiptStore().metadataDirectory(registry.toAbsolutePath().normalize())
                .resolve(REMOVAL_ROOT).resolve(installationId).resolve("journal.json");
    }

    /**
     * Conservatively rolls back a registered transaction. If registration is already absent,
     * only a durable COMMIT_AUTHORIZED/COMMITTED journal permits bounded cleanup.
     */
    public RecoveryResult recover(InstallationRecord expected, OperationContext context)
            throws IOException, InterruptedException {
        requireWorker();
        Objects.requireNonNull(context, "context");
        context.enterFinalization("Uninstall recovery must complete before the launcher closes.");
        if (journalExists(pendingRemovalJournalPath(registry, expected.id()))) {
            RegistrationRemovalJournal removal = readRemovalJournal(expected.id());
            requireExact(removal.record(), expected);
            InstallationRecord current = registries.read(registry).installations().stream()
                    .filter(record -> record.id().equals(expected.id())).findFirst().orElse(null);
            if (current == null) {
                if (!COMMITTED_PHASES.contains(removal.phase())) {
                    throw new IOException("orphan registration-removal journal lacks commit authority");
                }
                String warning = cleanupRemoval(removal);
                if (warning != null || !hasUninstallPending(expected.id())) {
                    return new RecoveryResult(true, "Registration removal cleanup completed.", warning);
                }
            } else {
                requireExact(current, expected);
                try (RootCoordinator.Lease ignored =
                             coordinator.acquireForRecovery(rootOf(expected), false)) {
                    requireExact(registries.resolve(registries.read(registry), expected.id()), expected);
                    restoreRemovalMetadata(removal);
                }
                if (!hasUninstallPending(expected.id())) {
                    return new RecoveryResult(false,
                            "Registration removal was rolled back; the copy remains registered.", null);
                }
            }
        }
        UninstallJournal journal = readJournal(expected.id());
        requireJournalBinding(journal, expected);
        Path root = rootOf(expected);
        RegistryData data = registries.read(registry);
        InstallationRecord current = data.installations().stream()
                .filter(record -> record.id().equals(expected.id())).findFirst().orElse(null);
        if (current == null) {
            if (!COMMITTED_PHASES.contains(journal.phase())) {
                throw new IOException("uninstall authority is ambiguous; registration is "
                        + "absent without a commit authorization");
            }
            String warning = cleanupCommitted(journal);
            return new RecoveryResult(true, "Uninstall cleanup completed.", warning);
        }
        try (OperationContext.WorkerRegistration ignoredOperation = context.activate();
             RootCoordinator.Lease ignored = coordinator.acquireForRecovery(root, false)) {
            context.phase(OperationPhase.RECOVER, "Restoring interrupted uninstall");
            if (!journal.rootRemovalStarted()) requirePresent(expected);
            ensureRootForRecovery(root, journal);
            requireExact(current, expected);
            IOException failure = rollback(journal);
            if (failure != null) throw failure;
            return new RecoveryResult(false, "Uninstall was rolled back; the copy remains "
                    + "registered.", null);
        }
    }

    /**
     * Bounded startup scan for journals whose registration commit already completed. Registered
     * journals remain visible on their card and require explicit recovery.
     */
    public List<String> recoverCommitted() throws IOException {
        requireWorker();
        List<String> warnings = new ArrayList<>();
        recoverRemovals(warnings);
        Path directory = journalRoot();
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return List.copyOf(warnings);
        StrictPathSafety.requireDirectory(directory, "uninstall recovery directory");
        RegistryData data = registries.read(registry);
        Set<String> registered = new HashSet<>();
        data.installations().forEach(record -> registered.add(record.id()));
        List<Path> transactions;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            transactions = new ArrayList<>();
            for (Path entry : stream) transactions.add(entry);
        }
        for (Path entry : transactions) {
            StrictPathSafety.requireDirectory(entry, "uninstall transaction");
            String id = entry.getFileName().toString();
            if (registered.contains(id)) continue;
            if (removeEmptyTransaction(entry, id)) continue;
            UninstallJournal journal = readJournal(id);
            if (!COMMITTED_PHASES.contains(journal.phase())) {
                throw new IOException("orphan uninstall journal lacks commit authority");
            }
            String warning = cleanupCommitted(journal);
            if (warning != null) warnings.add(warning);
        }
        return List.copyOf(warnings);
    }

    private Plan planLocked(InstallationRecord expected, OperationContext context)
            throws IOException, InterruptedException, ManifestException {
        if (context.latest() == null
                || context.latest().phase().ordinal() < OperationPhase.PLAN.ordinal()) {
            context.phase(OperationPhase.METADATA,
                    "Validating managed installation ownership");
        }
        RegistryData data = registries.read(registry);
        InstallationRecord record = registries.resolve(data, expected.id());
        requireExact(record, expected);
        if (hasPending(record.id())) {
            throw new IOException("Uninstall recovery required");
        }
        requireCurrentLayout(record);
        OwnershipReceipt receipt = receipts.read(registry, data, record);
        CurrentUpdateState current = states.read(registry, data, record, receipt);
        ChannelPreferenceStore.ReadResult fixed = channels.read(registry, data, record);
        if (fixed.status() != ChannelPreferenceStore.Status.CONFIGURED
                || fixed.preference() == null) {
            throw new IOException("Uninstall is available only for a managed copy with a fixed "
                    + "channel");
        }
        adoptions.validateForUse(registry, record, receipt, current, fixed.preference());
        Path root = StrictPathSafety.requireDirectory(Path.of(record.canonicalRoot()),
                "uninstall root");
        context.phase(OperationPhase.PLAN, "Comparing official application files");
        List<PlanEntry> entries = new ArrayList<>();
        Set<String> directories = new HashSet<>();
        for (String protectedPath : current.officialManifest().protectedPaths()) {
            directories.add(protectedPath);
            addParents(directories, protectedPath);
        }
        int completed = 0;
        for (FileEntry official : current.officialManifest().files()) {
            context.checkpoint();
            Path file;
            try {
                file = RealUpdateFiles.resolve(root, official.path(), true);
            } catch (ManifestException unsafe) {
                entries.add(new PlanEntry(official.path(), official.sha256(), -1,
                        Action.RETAIN, "unsafe path or local alias"));
                continue;
            }
            addParents(directories, official.path());
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                entries.add(new PlanEntry(official.path(), official.sha256(), -1,
                        Action.ABSENT, "already absent"));
            } else {
                BasicFileAttributes attrs = Files.readAttributes(file,
                        BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attrs.isRegularFile() || attrs.isSymbolicLink() || attrs.isOther()) {
                    entries.add(new PlanEntry(official.path(), official.sha256(), -1,
                            Action.RETAIN, "modified or non-regular"));
                } else {
                    String hash = RealUpdateFiles.hash(file, context);
                    entries.add(new PlanEntry(official.path(), official.sha256(), attrs.size(),
                            hash.equals(official.sha256()) ? Action.DELETE : Action.RETAIN,
                            hash.equals(official.sha256())
                                    ? "exact current official file" : "modified"));
                }
            }
            context.progress(OperationPhase.PLAN, ++completed,
                    current.officialManifest().files().size(), ProgressUnit.FILES,
                    "Compared official application files");
        }
        List<String> knownDirectories = directories.stream()
                .sorted(Comparator.comparingInt((String value) -> depth(value)).reversed()
                        .thenComparing(Comparator.naturalOrder()))
                .toList();
        return new Plan(record, receipt, current, List.copyOf(entries), knownDirectories);
    }

    private Result executeLocked(Plan plan, OperationContext context)
            throws IOException, InterruptedException, ManifestException {
        InstallationRecord record = plan.record();
        Path root = rootOf(record);
        String transactionId = UUID.randomUUID().toString();
        Path rootBackup = root.resolveSibling("." + root.getFileName()
                + ".mm-launcher-uninstall-" + transactionId);
        rejectCaseAlias(rootBackup.getParent(), rootBackup.getFileName().toString());
        if (Files.exists(rootBackup, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("uninstall backup path already exists");
        }
        RegistryData data = registries.read(registry);
        Path metadataDirectory = receipts.metadataDirectory(registry);
        ensureMetadataDirectory(metadataDirectory, data);
        Path transaction = journalDirectory(record.id());
        if (Files.exists(transaction, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Uninstall recovery required");
        }
        Files.createDirectories(transaction);
        StrictPathSafety.requireDirectory(transaction, "uninstall journal directory");

        List<UninstallJournal.FileMove> fileMoves = plan.entries().stream()
                .filter(entry -> entry.action() == Action.DELETE)
                .map(entry -> new UninstallJournal.FileMove(entry.relativePath(),
                        entry.expectedSha256(), entry.expectedSize(), "PLANNED")).toList();
        List<UninstallJournal.MetadataMove> metadataMoves = new ArrayList<>();
        for (Path sidecar : existingSidecars(metadataDirectory, record.id())) {
            metadataMoves.add(new UninstallJournal.MetadataMove(
                    sidecar.getFileName().toString(), hash(sidecar), Files.size(sidecar),
                    "PLANNED"));
        }
        List<UninstallJournal.DirectoryState> directoryStates =
                captureDirectoryStates(root, plan.knownDirectories());
        UninstallJournal journal = new UninstallJournal(JOURNAL_SCHEMA, transactionId,
                "PREPARED", registry.toString(), record, plan.current(), rootBackup.toString(),
                fileMoves, List.copyOf(metadataMoves), directoryStates, false);
        writeJournal(journal);
        failureHook.at("journal-written");
        try {
            Files.createDirectory(rootBackup);
            StrictPathSafety.requireDirectory(rootBackup, "uninstall backup");
            Path fileBackup = Files.createDirectory(rootBackup.resolve("files"));
            journal = withPhase(journal, "MOVING_FILES");
            writeJournal(journal);
            context.phase(OperationPhase.UNINSTALL,
                    "Moving verified official files to recovery");
            int moved = 0;
            for (int i = 0; i < journal.files().size(); i++) {
                UninstallJournal.FileMove move = journal.files().get(i);
                Path source = RealUpdateFiles.resolve(root, move.relativePath(), false);
                requireExactFile(source, move.expectedSha256(), move.expectedSize());
                Path destination = safeBackupPath(fileBackup, move.relativePath());
                Files.createDirectories(destination.getParent());
                StrictPathSafety.requireDirectory(destination.getParent(),
                        "uninstall backup parent");
                failureHook.at("file-verified");
                atomicMove(source, destination);
                failureHook.at("file-moved");
                context.progress(OperationPhase.UNINSTALL, ++moved, journal.files().size(),
                        ProgressUnit.FILES, "Secured official application files");
            }

            journal = withPhase(journal, "REMOVING_DIRECTORIES");
            writeJournal(journal);
            failureHook.at("directory-removal-started");
            for (String relative : plan.knownDirectories()) {
                Path directory;
                try {
                    directory = RealUpdateFiles.resolve(root, relative, true);
                } catch (ManifestException unsafe) {
                    continue; // A linked/non-regular local path is retained, never traversed.
                }
                if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) continue;
                StrictPathSafety.requireDirectory(directory, "known package directory");
                if (!RealUpdateFiles.emptyDirectory(directory)) continue;
                Files.delete(directory);
                failureHook.at("directory-removed");
            }
            if (RealUpdateFiles.emptyDirectory(root)) {
                journal = withRootRemoval(journal);
                writeJournal(journal);
                Files.delete(root);
                failureHook.at("root-removed");
            }

            Path metadataBackup = Files.createDirectory(transaction.resolve("metadata"));
            StrictPathSafety.requireDirectory(metadataBackup, "uninstall metadata backup");
            journal = withPhase(journal, "MOVING_METADATA");
            writeJournal(journal);
            for (int i = 0; i < journal.metadata().size(); i++) {
                UninstallJournal.MetadataMove move = journal.metadata().get(i);
                Path source = metadataDirectory.resolve(move.fileName());
                requireExactFile(source, move.sha256(), move.size());
                StrictPathSafety.requireDirectory(metadataBackup,
                        "uninstall metadata backup");
                failureHook.at("metadata-verified");
                atomicMove(source, metadataBackup.resolve(move.fileName()));
                failureHook.at("metadata-moved");
            }

            journal = withPhase(journal, "COMMIT_AUTHORIZED");
            writeJournal(journal);
            failureHook.at("before-registration-commit");
            registries.remove(registry, record);
        } catch (IOException | InterruptedException | ManifestException
                 | RuntimeException failure) {
            if ("COMMIT_AUTHORIZED".equals(journal.phase())
                    && registrationIsAbsent(record)) {
                String warning = "Uninstall completed, but launcher recovery files could not "
                        + "be cleaned: " + detail(failure);
                return new Result(true, Files.exists(root, LinkOption.NOFOLLOW_LINKS), warning);
            }
            IOException rollback = rollback(journal);
            if (rollback != null) failure.addSuppressed(rollback);
            throw failure;
        }

        String warning;
        try {
            journal = withPhase(journal, "COMMITTED");
            writeJournal(journal);
            failureHook.at("after-registration-commit");
            warning = cleanupCommitted(journal);
        } catch (IOException | RuntimeException cleanupFailure) {
            warning = "Uninstall completed, but launcher recovery files could not be cleaned: "
                    + detail(cleanupFailure);
        }
        boolean rootPresent = Files.exists(root, LinkOption.NOFOLLOW_LINKS);
        return new Result(true, rootPresent, warning);
    }

    private IOException rollback(UninstallJournal journal) {
        try {
            Path root = Path.of(journal.record().canonicalRoot());
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
                Path parent = StrictPathSafety.requireDirectory(root.getParent(),
                        "uninstall root parent");
                Files.createDirectory(parent.resolve(root.getFileName()));
            }
            StrictPathSafety.requireDirectory(root, "uninstall rollback root");
            Path fileBackup = Path.of(journal.rootBackup()).resolve("files");
            for (UninstallJournal.FileMove move : journal.files()) {
                Path source = safeBackupPath(fileBackup, move.relativePath());
                Path destination = root.resolve(move.relativePath()
                                .replace('/', java.io.File.separatorChar))
                        .normalize();
                restoreOne(source, destination, move.expectedSha256(), move.expectedSize());
            }
            List<UninstallJournal.DirectoryState> directories =
                    new ArrayList<>(journal.removedDirectories());
            directories.sort(Comparator.comparingInt(
                    state -> depth(state.relativePath())));
            for (UninstallJournal.DirectoryState directory : directories) {
                Path restored = directory.relativePath().isEmpty() ? root
                        : root.resolve(directory.relativePath()
                        .replace('/', java.io.File.separatorChar)).normalize();
                if (!restored.startsWith(root)) {
                    throw new IOException("rollback directory escapes installation root");
                }
                if (!Files.exists(restored, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectory(restored);
                }
            }
            Path metadata = receipts.metadataDirectory(registry);
            Path metadataBackup = journalDirectory(journal.record().id()).resolve("metadata");
            for (UninstallJournal.MetadataMove move : journal.metadata()) {
                restoreOne(metadataBackup.resolve(move.fileName()),
                        metadata.resolve(move.fileName()), move.sha256(), move.size());
            }
            // Restore directory timestamps after child restoration has changed them.
            directories.sort(Comparator.comparingInt(
                    (UninstallJournal.DirectoryState state) -> depth(state.relativePath()))
                    .reversed());
            for (UninstallJournal.DirectoryState directory : directories) {
                Path restored = directory.relativePath().isEmpty() ? root
                        : root.resolve(directory.relativePath()
                                .replace('/', java.io.File.separatorChar));
                Files.setLastModifiedTime(restored,
                        FileTime.fromMillis(directory.modifiedMillis()));
            }
            String cleanup = cleanupOwned(Path.of(journal.rootBackup()));
            if (cleanup != null) throw new IOException(cleanup);
            cleanup = cleanupOwned(journalDirectory(journal.record().id()));
            if (cleanup != null) throw new IOException(cleanup);
            cleanupEmptyMetadataParents();
            return null;
        } catch (IOException | RuntimeException error) {
            return new IOException("Uninstall recovery required; rollback could not safely "
                    + "restore every item: " + detail(error), error);
        }
    }

    private void restoreOne(Path backup, Path destination, String expectedHash, long expectedSize)
            throws IOException {
        boolean backupExists = Files.exists(backup, LinkOption.NOFOLLOW_LINKS);
        boolean destinationExists = Files.exists(destination, LinkOption.NOFOLLOW_LINKS);
        if (!backupExists && destinationExists) return;
        if (!backupExists) throw new IOException("rollback backup is missing");
        if (destinationExists) {
            throw new IOException("unexpected edit blocks rollback without overwrite: "
                    + destination);
        }
        requireExactFile(backup, expectedHash, expectedSize);
        Files.createDirectories(destination.getParent());
        StrictPathSafety.requireDirectory(destination.getParent(),
                "uninstall rollback parent");
        atomicMove(backup, destination);
    }

    private String cleanupCommitted(UninstallJournal journal) throws IOException {
        String first = cleanupOwned(Path.of(journal.rootBackup()));
        Path transaction = journalDirectory(journal.record().id());
        Path metadataBackup = transaction.resolve("metadata");
        String second = cleanupOwned(metadataBackup);
        if (first != null || second != null) {
            return first != null ? first : second;
        }
        Files.deleteIfExists(journalPath(journal.record().id()));
        failureHook.at("cleanup-journal-deleted");
        Files.deleteIfExists(transaction);
        cleanupEmptyMetadataParents();
        return null;
    }

    private String cleanupMissingJournal(UninstallJournal journal) {
        try {
            return cleanupCommitted(journal);
        } catch (IOException | RuntimeException failure) {
            return "Registration removed, but uninstall recovery cleanup is still required: "
                    + detail(failure);
        }
    }

    private UninstallJournal readJournal(String id) throws IOException {
        Path file = journalPath(id);
        StrictPathSafety.requireFile(file, "uninstall journal");
        long size = Files.size(file);
        if (size <= 0 || size > MAX_JOURNAL_BYTES) {
            throw new IOException("uninstall journal size is invalid");
        }
        UninstallJournal journal = mapper.readValue(Files.readAllBytes(file),
                UninstallJournal.class);
        validateJournal(journal, id);
        return journal;
    }

    private void writeJournal(UninstallJournal journal) throws IOException {
        validateJournal(journal, journal.record().id());
        publishJournal(journalPath(journal.record().id()), journal);
        failureHook.at("journal-published");
    }

    private void publishJournal(Path file, Object journal) throws IOException {
        Path staging = file.resolveSibling(file.getFileName() + ".new");
        byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(journal);
        if (bytes.length <= 0 || bytes.length > MAX_JOURNAL_BYTES) {
            throw new IOException("uninstall journal exceeds size limit");
        }
        Files.deleteIfExists(staging);
        try (FileChannel output = FileChannel.open(staging, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) output.write(buffer);
            output.force(true);
        }
        try {
            Files.move(staging, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException error) {
            Files.deleteIfExists(staging);
            throw new IOException("atomic uninstall journal publication is unsupported", error);
        }
    }

    private Path removalRoot() {
        return receipts.metadataDirectory(registry).resolve(REMOVAL_ROOT);
    }

    private Path removalDirectory(String id) throws IOException {
        requireTransactionId(id);
        return removalRoot().resolve(id);
    }

    private void writeRemovalJournal(RegistrationRemovalJournal journal) throws IOException {
        validateRemovalJournal(journal, journal.record().id());
        publishJournal(removalDirectory(journal.record().id()).resolve("journal.json"), journal);
        failureHook.at("remove-journal-published");
    }

    private RegistrationRemovalJournal readRemovalJournal(String id) throws IOException {
        Path file = removalDirectory(id).resolve("journal.json");
        StrictPathSafety.requireFile(file, "registration-removal journal");
        long size = Files.size(file);
        if (size <= 0 || size > MAX_JOURNAL_BYTES) {
            throw new IOException("registration-removal journal size is invalid");
        }
        RegistrationRemovalJournal journal = mapper.readValue(
                Files.readAllBytes(file), RegistrationRemovalJournal.class);
        validateRemovalJournal(journal, id);
        return journal;
    }

    private void validateRemovalJournal(RegistrationRemovalJournal journal, String id)
            throws IOException {
        requireTransactionId(id);
        if (journal == null || journal.schemaVersion() != JOURNAL_SCHEMA
                || !registry.toString().equals(journal.canonicalRegistry())
                || journal.record() == null || !id.equals(journal.record().id())
                || journal.phase() == null
                || !Set.of("PREPARED", "COMMIT_AUTHORIZED").contains(journal.phase())
                || journal.metadata() == null) {
            throw new IOException("registration-removal journal schema or binding is invalid");
        }
        rootOf(journal.record());
        Set<String> names = new HashSet<>();
        for (UninstallJournal.MetadataMove move : journal.metadata()) {
            if (move == null || !ownedSidecarName(move.fileName(), id)
                    || !names.add(move.fileName()) || move.size() < 0
                    || move.sha256() == null || !HASH.matcher(move.sha256()).matches()
                    || !"PLANNED".equals(move.state())) {
                throw new IOException("registration-removal metadata intent is invalid");
            }
        }
    }

    private void recoverRemovals(List<String> warnings) throws IOException {
        Path directory = removalRoot();
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        StrictPathSafety.requireDirectory(directory, "registration-removal recovery directory");
        List<Path> transactions = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) transactions.add(entry);
        }
        for (Path entry : transactions) {
            String id = entry.getFileName().toString();
            requireTransactionId(id);
            InstallationRecord current = registries.read(registry).installations().stream()
                    .filter(record -> record.id().equals(id)).findFirst().orElse(null);
            if (current == null) {
                if (removeEmptyTransaction(entry, id)) continue;
                RegistrationRemovalJournal journal = readRemovalJournal(id);
                if (!COMMITTED_PHASES.contains(journal.phase())) {
                    throw new IOException("orphan registration-removal journal lacks commit authority");
                }
                String warning = cleanupRemoval(journal);
                if (warning != null) warnings.add(warning);
            } else {
                try (RootCoordinator.Lease ignored =
                             coordinator.acquireForRecovery(rootOf(current), false)) {
                    requireExact(registries.resolve(registries.read(registry), id), current);
                    recoverRemoval(current);
                }
            }
        }
        cleanupEmptyMetadataParents();
    }

    private void recoverRemoval(InstallationRecord record) throws IOException {
        Path transaction = removalDirectory(record.id());
        if (!Files.exists(transaction, LinkOption.NOFOLLOW_LINKS)
                || removeEmptyTransaction(transaction, record.id())) return;
        RegistrationRemovalJournal journal = readRemovalJournal(record.id());
        requireExact(journal.record(), record);
        restoreRemovalMetadata(journal);
    }

    private void restoreRemovalMetadata(RegistrationRemovalJournal journal) throws IOException {
        Path metadata = receipts.metadataDirectory(registry);
        StrictPathSafety.requireDirectory(metadata, "launcher metadata");
        Path backup = removalDirectory(journal.record().id()).resolve("metadata");
        for (UninstallJournal.MetadataMove move : journal.metadata()) {
            restoreOne(backup.resolve(move.fileName()), metadata.resolve(move.fileName()),
                    move.sha256(), move.size());
        }
        String warning = cleanupRemoval(journal);
        if (warning != null) throw new IOException(warning);
    }

    private String cleanupRemoval(RegistrationRemovalJournal journal) {
        try {
            Path transaction = removalDirectory(journal.record().id());
            String warning = cleanupOwned(transaction.resolve("metadata"));
            if (warning != null) return warning;
            Files.deleteIfExists(transaction.resolve("journal.json"));
            failureHook.at("remove-cleanup-journal-deleted");
            Files.delete(transaction);
            cleanupEmptyMetadataParents();
            return null;
        } catch (IOException | RuntimeException failure) {
            return "Registration removed, but launcher metadata cleanup is still required: "
                    + detail(failure);
        }
    }

    private static boolean removeEmptyTransaction(Path directory, String id) throws IOException {
        requireTransactionId(id);
        StrictPathSafety.requireDirectory(directory, "launcher recovery transaction");
        if (!RealUpdateFiles.emptyDirectory(directory)) return false;
        // No backups or journal remain, so this can only remove the empty namespace.
        Files.delete(directory);
        return true;
    }

    private static void requireTransactionId(String id) throws IOException {
        try {
            if (!UUID.fromString(id).toString().equals(id)) {
                throw new IllegalArgumentException("noncanonical UUID");
            }
        } catch (RuntimeException failure) {
            throw new IOException("launcher recovery transaction id is invalid", failure);
        }
    }

    private record RegistrationRemovalJournal(int schemaVersion, String canonicalRegistry,
                                             InstallationRecord record, String phase,
                                             List<UninstallJournal.MetadataMove> metadata) {
        private RegistrationRemovalJournal {
            metadata = metadata == null ? null : List.copyOf(metadata);
        }
    }

    private void validateJournal(UninstallJournal journal, String id) throws IOException {
        if (journal == null || journal.schemaVersion() != JOURNAL_SCHEMA
                || journal.transactionId() == null || journal.phase() == null
                || journal.canonicalRegistry() == null || journal.record() == null
                || journal.current() == null || journal.rootBackup() == null
                || journal.files() == null || journal.metadata() == null
                || journal.removedDirectories() == null
                || !Set.of("PREPARED", "MOVING_FILES", "REMOVING_DIRECTORIES",
                "REMOVING_ROOT", "MOVING_METADATA", "COMMIT_AUTHORIZED", "COMMITTED")
                .contains(journal.phase())
                || !journal.record().id().equals(id)
                || !registry.toString().equals(journal.canonicalRegistry())) {
            throw new IOException("uninstall journal schema or binding is invalid");
        }
        try {
            UUID.fromString(journal.transactionId());
        } catch (RuntimeException error) {
            throw new IOException("uninstall journal transaction id is invalid", error);
        }
        Path root = rootOf(journal.record());
        Path rootParent = StrictPathSafety.requireDirectory(
                root.getParent(), "uninstall root parent");
        if (!rootParent.resolve(root.getFileName()).equals(root)) {
            throw new IOException("uninstall root binding is not canonical");
        }
        Path backup = Path.of(journal.rootBackup()).toAbsolutePath().normalize();
        Path expectedBackup = root.resolveSibling("." + root.getFileName()
                + ".mm-launcher-uninstall-" + journal.transactionId());
        if (!backup.equals(expectedBackup)) {
            throw new IOException("uninstall backup binding is invalid");
        }
        Set<String> paths = new HashSet<>();
        for (UninstallJournal.FileMove move : journal.files()) {
            if (move == null || !paths.add(move.relativePath())
                    || move.expectedSize() < 0 || move.expectedSha256() == null
                    || !HASH.matcher(move.expectedSha256()).matches()
                    || !Set.of("PLANNED", "MOVING", "MOVED").contains(move.state())) {
                throw new IOException("uninstall file journal is invalid");
            }
            try {
                ManifestReader.validatePortablePath(move.relativePath(),
                        "uninstall path", "journal");
            } catch (ManifestException error) {
                throw new IOException("uninstall file journal is invalid", error);
            }
        }
        Set<String> metadataNames = new HashSet<>();
        for (UninstallJournal.MetadataMove move : journal.metadata()) {
            if (move == null || move.fileName() == null || move.fileName().contains("/")
                    || move.fileName().contains("\\") || move.size() < 0
                    || move.sha256() == null || !HASH.matcher(move.sha256()).matches()
                    || !metadataNames.add(move.fileName())
                    || !Set.of("PLANNED", "MOVING", "MOVED").contains(move.state())) {
                throw new IOException("uninstall metadata journal is invalid");
            }
        }
        Set<String> directories = new HashSet<>();
        for (UninstallJournal.DirectoryState directory : journal.removedDirectories()) {
            if (directory == null || directory.relativePath() == null
                    || !directories.add(directory.relativePath())) {
                throw new IOException("uninstall directory journal is invalid");
            }
            if (!directory.relativePath().isEmpty()) {
                try {
                    ManifestReader.validatePortablePath(directory.relativePath(),
                            "uninstall directory", "journal");
                } catch (ManifestException error) {
                    throw new IOException("uninstall directory journal is invalid", error);
                }
            }
        }
        if (!journal.current().installationId().equals(journal.record().id())
                || !journal.current().canonicalRoot().equals(journal.record().canonicalRoot())
                || !journal.current().registeredAt().equals(journal.record().registeredAt())) {
            throw new IOException("uninstall current-provenance binding is invalid");
        }
        try {
            new ManifestReader().validateInMemory(journal.current().officialManifest(),
                    "uninstall journal");
        } catch (ManifestException error) {
            throw new IOException("uninstall current manifest is invalid", error);
        }
    }

    private List<Path> existingSidecars(Path directory, String id) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        StrictPathSafety.requireDirectory(directory, "launcher metadata");
        List<String> names = new ArrayList<>(List.of(id + ".json", id + ".json.lock",
                id + ".current.json", id + ".current.json.new",
                id + ".adoption.json", id + ".adoption.json.new",
                id + ".channel.json", id + ".channel.json.new",
                id + ".channel.json.lock"));
        try (DirectoryStream<Path> stream =
                     Files.newDirectoryStream(directory, id + ".json.new-*")) {
            for (Path candidate : stream) {
                if (ownedSidecarName(candidate.getFileName().toString(), id)) {
                    names.add(candidate.getFileName().toString());
                }
            }
        }
        List<Path> result = new ArrayList<>();
        for (String name : names) {
            rejectCaseAlias(directory, name);
            Path file = directory.resolve(name);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) continue;
            StrictPathSafety.requireFile(file, "launcher metadata sidecar");
            result.add(file);
        }
        return List.copyOf(result);
    }

    private static boolean ownedSidecarName(String name, String id) {
        if (name == null) return false;
        if (Set.of(id + ".json", id + ".json.lock", id + ".current.json",
                id + ".current.json.new", id + ".adoption.json", id + ".adoption.json.new",
                id + ".channel.json", id + ".channel.json.new", id + ".channel.json.lock")
                .contains(name)) return true;
        if (!name.startsWith(id + ".json.new-")) return false;
        try {
            UUID.fromString(name.substring((id + ".json.new-").length()));
            return true;
        } catch (IllegalArgumentException ignored) {
            return false; // Not an owned receipt staging name.
        }
    }

    private void requireCurrentLayout(InstallationRecord record) throws IOException {
        requirePresent(record);
        Inspection current = inspector.inspect(rootOf(record));
        if (!current.canonicalRoot().equals(record.canonicalRoot())
                || !current.observedBuild().equals(record.observedBuild())
                || !current.products().equals(record.products())) {
            throw new IOException("selected installation changed; reopen Installations");
        }
    }

    public static void requirePresent(InstallationRecord record) throws IOException {
        if (InstallationDirectory.missing(rootOf(record))) {
            throw new MissingInstallationException(record);
        }
    }

    private static void requireMissing(Path root) throws IOException {
        if (!InstallationDirectory.missing(root)) {
            throw new IOException("The installation folder exists again; nothing was removed.");
        }
    }

    private boolean registrationIsAbsent(InstallationRecord record) {
        try {
            return registries.read(registry).installations().stream()
                    .noneMatch(candidate -> candidate.id().equals(record.id()));
        } catch (IOException | RuntimeException error) {
            return false;
        }
    }

    private static void requireExact(InstallationRecord current, InstallationRecord expected)
            throws IOException {
        if (!current.equals(expected)) {
            throw new IOException("selected installation changed; reopen Installations");
        }
    }

    private static Path rootOf(InstallationRecord record) throws IOException {
        if (record == null || record.canonicalRoot() == null) {
            throw new IOException("selected installation is required");
        }
        try {
            Path root = Path.of(record.canonicalRoot()).toAbsolutePath().normalize();
            if (!root.toString().equals(record.canonicalRoot())) {
                throw new IOException("installation root is not canonical");
            }
            if (root.getParent() == null || root.getFileName() == null) {
                throw new IOException("installation root must not be a filesystem root");
            }
            return root;
        } catch (RuntimeException error) {
            throw new IOException("installation root is invalid", error);
        }
    }

    private static void requireWorker() throws IOException {
        if (java.awt.EventQueue.isDispatchThread()) {
            throw new IOException("uninstall work must run off the UI thread");
        }
    }

    private void ensureMetadataDirectory(Path directory, RegistryData data) throws IOException {
        ReceiptStore.validateMetadataPlacement(directory, data);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            StrictPathSafety.requireDirectory(directory.getParent(), "launcher metadata parent");
            Files.createDirectory(directory);
        }
        StrictPathSafety.requireDirectory(directory, "launcher metadata");
        Path journalRoot = journalRoot();
        if (!Files.exists(journalRoot, LinkOption.NOFOLLOW_LINKS)) {
            Files.createDirectory(journalRoot);
        }
        StrictPathSafety.requireDirectory(journalRoot, "uninstall recovery directory");
    }

    private Path journalRoot() {
        return receipts.metadataDirectory(registry).resolve(JOURNAL_ROOT);
    }

    private Path journalDirectory(String id) throws IOException {
        UUID.fromString(id);
        return journalRoot().resolve(id);
    }

    private Path journalPath(String id) throws IOException {
        return pendingJournalPath(registry, id);
    }

    private static Path safeBackupPath(Path base, String relative) throws IOException {
        Path result = base.resolve(relative.replace('/', java.io.File.separatorChar)).normalize();
        if (!result.startsWith(base)) throw new IOException("backup path escapes its namespace");
        return result;
    }

    private static void requireExactFile(Path file, String expectedHash, long expectedSize)
            throws IOException {
        StrictPathSafety.requireFile(file, "uninstall item");
        if (Files.size(file) != expectedSize || !hash(file).equals(expectedHash)) {
            throw new IOException("uninstall item changed before it could be moved");
        }
    }

    private static String hash(Path file) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = new DigestInputStream(Files.newInputStream(file), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException(error);
        }
    }

    private static void atomicMove(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException error) {
            throw new IOException("atomic same-filesystem uninstall move is unsupported", error);
        }
    }

    private static void rejectCaseAlias(Path parent, String expected) throws IOException {
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent)) {
            for (Path entry : stream) {
                String actual = entry.getFileName().toString();
                if (actual.equalsIgnoreCase(expected) && !actual.equals(expected)) {
                    throw new IOException("case alias blocks uninstall path: " + expected);
                }
            }
        }
    }

    private static String cleanupOwned(Path path) {
        try {
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
            try (var walk = Files.walk(path)) {
                for (Path item : walk.sorted(Comparator.reverseOrder()).toList()) {
                    BasicFileAttributes attrs = Files.readAttributes(item,
                            BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (attrs.isSymbolicLink() || attrs.isOther()) {
                        throw new IOException("unexpected object blocks recovery cleanup");
                    }
                    Files.delete(item);
                }
            }
            return null;
        } catch (IOException error) {
            return "Launcher-owned backup cleanup is still required: " + detail(error);
        }
    }

    private void cleanupEmptyMetadataParents() {
        try {
            Path removals = removalRoot();
            if (Files.exists(removals, LinkOption.NOFOLLOW_LINKS)
                    && RealUpdateFiles.emptyDirectory(removals)) {
                Files.delete(removals);
            }
            Path journals = journalRoot();
            if (Files.exists(journals, LinkOption.NOFOLLOW_LINKS)
                    && RealUpdateFiles.emptyDirectory(journals)) {
                Files.delete(journals);
            }
            Path metadata = receipts.metadataDirectory(registry);
            if (Files.exists(metadata, LinkOption.NOFOLLOW_LINKS)
                    && RealUpdateFiles.emptyDirectory(metadata)) {
                Files.delete(metadata);
            }
        } catch (IOException ignored) {
            // Successful removal may leave only an empty launcher-owned metadata directory.
        }
    }

    private static Path ensureRootForRecovery(Path root, UninstallJournal journal)
            throws IOException {
        if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return root;
        if (!journal.rootRemovalStarted()) {
            throw new IOException("uninstall root disappeared outside the transaction");
        }
        StrictPathSafety.requireDirectory(root.getParent(), "uninstall root parent");
        Files.createDirectory(root);
        return root;
    }

    private void requireJournalBinding(UninstallJournal journal, InstallationRecord expected)
            throws IOException {
        if (!journal.record().equals(expected)
                || !journal.current().installationId().equals(expected.id())
                || !journal.current().canonicalRoot().equals(expected.canonicalRoot())
                || !journal.current().registeredAt().equals(expected.registeredAt())) {
            throw new IOException("uninstall journal does not match this installation");
        }
    }

    private static void addParents(Set<String> result, String path) {
        int slash = path.lastIndexOf('/');
        while (slash > 0) {
            result.add(path.substring(0, slash));
            slash = path.lastIndexOf('/', slash - 1);
        }
    }

    private static List<UninstallJournal.DirectoryState> captureDirectoryStates(
            Path root, List<String> knownDirectories) throws IOException {
        List<UninstallJournal.DirectoryState> result = new ArrayList<>();
        result.add(new UninstallJournal.DirectoryState("", Files.getLastModifiedTime(
                root, LinkOption.NOFOLLOW_LINKS).toMillis()));
        for (String relative : knownDirectories) {
            Path directory = root.resolve(relative.replace('/',
                    java.io.File.separatorChar)).normalize();
            if (!directory.startsWith(root)
                    || !Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) continue;
            StrictPathSafety.requireDirectory(directory, "known package directory");
            result.add(new UninstallJournal.DirectoryState(relative,
                    Files.getLastModifiedTime(
                            directory, LinkOption.NOFOLLOW_LINKS).toMillis()));
        }
        return List.copyOf(result);
    }

    private static int depth(String path) {
        return (int) path.chars().filter(ch -> ch == '/').count() + 1;
    }

    private static String detail(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private static UninstallJournal withPhase(UninstallJournal journal, String phase) {
        return new UninstallJournal(journal.schemaVersion(), journal.transactionId(), phase,
                journal.canonicalRegistry(), journal.record(), journal.current(),
                journal.rootBackup(), journal.files(), journal.metadata(),
                journal.removedDirectories(), journal.rootRemovalStarted());
    }

    private static UninstallJournal withRootRemoval(UninstallJournal journal) {
        return new UninstallJournal(journal.schemaVersion(), journal.transactionId(),
                "REMOVING_ROOT", journal.canonicalRegistry(), journal.record(), journal.current(),
                journal.rootBackup(), journal.files(), journal.metadata(),
                journal.removedDirectories(), true);
    }

    public enum Action {
        DELETE, ABSENT, RETAIN
    }

    public record Plan(InstallationRecord record, OwnershipReceipt original,
                       CurrentUpdateState current, List<PlanEntry> entries,
                       List<String> knownDirectories) {
        public Plan {
            entries = List.copyOf(entries);
            knownDirectories = List.copyOf(knownDirectories);
        }
    }

    public record PlanEntry(String relativePath, String expectedSha256, long expectedSize,
                            Action action, String reason) {
    }

    public record Result(boolean committed, boolean retainedFiles, String warning) {
    }

    public record RecoveryResult(boolean committed, String message, String warning) {
    }

    public static final class MissingInstallationException extends IOException {
        private final InstallationRecord record;

        private MissingInstallationException(InstallationRecord record) {
            super("The installation folder no longer exists: " + record.canonicalRoot());
            this.record = record;
        }

        public InstallationRecord record() {
            return record;
        }
    }

    @FunctionalInterface
    interface FailureHook {
        void at(String point) throws IOException;
    }
}
