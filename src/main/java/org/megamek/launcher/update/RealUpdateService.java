package org.megamek.launcher.update;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.megamek.launcher.launch.RootCoordinator;
import org.megamek.launcher.manifest.FileEntry;
import org.megamek.launcher.manifest.Manifest;
import org.megamek.launcher.manifest.ManifestException;
import org.megamek.launcher.manifest.ManifestReader;
import org.megamek.launcher.onboarding.Inspection;
import org.megamek.launcher.onboarding.InstallationInspector;
import org.megamek.launcher.plan.Action;
import org.megamek.launcher.plan.Decision;
import org.megamek.launcher.plan.UpdatePlanner;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryData;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.release.FreshInstaller;
import org.megamek.launcher.release.OfficialRepository;
import org.megamek.launcher.release.ReleaseTransport;
import org.megamek.launcher.release.VerifiedPackageFetcher;
import org.megamek.launcher.sandbox.OverrideEntry;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Explicit, receipt-authorized real package update with a separate transaction format.  It never
 * calls or impersonates the disposable {@code SandboxUpdater}.
 */
public final class RealUpdateService {
    public static final String CONFIRM = RootCoordinator.CLOSE_ALL_CONFIRMATION;
    private static final int JOURNAL_SCHEMA = 1;
    private static final int MAX_METADATA = 64 * 1024 * 1024;
    private static final Pattern TAG = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");
    private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-fA-F]{64}");
    private static final String NAMESPACE = RootCoordinator.UPDATE_NAMESPACE;

    private final ReleaseTransport transport;
    private final RegistryStore registries;
    private final ReceiptStore receipts;
    private final CurrentStateStore states;
    private final RootCoordinator coordinator;
    private final FailureHook failureHook;
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public RealUpdateService(ReleaseTransport transport) {
        this(transport, new RegistryStore(), new ReceiptStore(), new RootCoordinator(), point -> {});
    }

    RealUpdateService(ReleaseTransport transport, RegistryStore registries, ReceiptStore receipts,
                      RootCoordinator coordinator, FailureHook failureHook) {
        this.transport = transport;
        this.registries = registries;
        this.receipts = receipts;
        this.states = new CurrentStateStore(receipts);
        this.coordinator = coordinator;
        this.failureHook = failureHook;
    }

    public Snapshot snapshot(Path registry, String id) throws IOException {
        Path registryPath = registry.toAbsolutePath().normalize();
        RegistryData data = registries.read(registryPath);
        InstallationRecord record = registries.resolve(data, id);
        Path root = validateBoundary(registryPath, data, record);
        OwnershipReceipt receipt = receipts.read(registryPath, data, record);
        CurrentUpdateState state = states.read(registryPath, data, record, receipt);
        return new Snapshot(registryPath, record, receipt, state, root);
    }

    public ApplyResult apply(Path registry, String id, String expectedFromTag, String targetTag,
                             long expectedSize, String expectedDigest, String confirmation,
                             PrintStream progress)
            throws IOException, InterruptedException, ManifestException {
        validateAuthorization(expectedFromTag, targetTag, expectedSize, expectedDigest,
                confirmation);
        Snapshot snapshot = snapshot(registry, id);
        if (!snapshot.current().tag().equals(expectedFromTag)) {
            throw new IOException("current verified source tag changed; preview and consent again");
        }
        return apply(snapshot, targetTag, expectedSize, expectedDigest, confirmation, progress);
    }

    public ApplyResult apply(Snapshot expected, String targetTag, long expectedSize,
                             String expectedDigest, String confirmation, PrintStream progress)
            throws IOException, InterruptedException, ManifestException {
        return apply(expected, targetTag, null, expectedSize, expectedDigest, confirmation,
                progress);
    }

    public ApplyResult apply(Snapshot expected, String targetTag, String expectedAssetName,
                             long expectedSize, String expectedDigest, String confirmation,
                             PrintStream progress)
            throws IOException, InterruptedException, ManifestException {
        Objects.requireNonNull(expected, "expected snapshot");
        validateAuthorization(expected.current().tag(), targetTag, expectedSize, expectedDigest,
                confirmation);
        try (RootCoordinator.Lease gate = coordinator.acquire(expected.root(), true)) {
            gate.requireNoPendingUpdate();
            Snapshot current = snapshot(expected.registry(), expected.record().id());
            if (!current.record().equals(expected.record())
                    || !current.receipt().equals(expected.receipt())
                    || !current.current().equals(expected.current())
                    || !current.root().equals(expected.root())) {
                throw new IOException("selected record or verified provenance changed after consent");
            }
            OfficialRepository repository = OfficialRepository.parse(current.current().repository());
            try (VerifiedPackageFetcher.Workspace workspace =
                         new VerifiedPackageFetcher(transport).fetch(repository, targetTag,
                                 receipts.metadataDirectory(current.registry()), ".apply-download-",
                                 progress, new VerifiedPackageFetcher.ExpectedAsset(
                                         expectedAssetName, expectedSize, expectedDigest))) {
                Inspection targetInspection = new InstallationInspector().inspect(
                        workspace.extracted());
                if (targetInspection.products().stream().noneMatch(
                        product -> product.key().equals(repository.requiredProduct()))) {
                    throw new IOException("target package does not contain receipt application "
                            + repository.requiredProduct());
                }
                OwnershipPolicy.Build target = new OwnershipPolicy().build(workspace.extracted(),
                        repository, workspace.release().tag());
                ManifestReader.PreviewPairValidation pair = new ManifestReader()
                        .validatePreviewPair(current.current().officialManifest(), target.manifest());
                List<Decision> decisions = new UpdatePlanner().planPreview(
                        current.current().officialManifest(), target.manifest(), current.root(), pair,
                        trustedHashes(current.current()), stickyPrefixes(current.current()));
                validatePristineRuntime(current.root(), current.current(), target.manifest(),
                        decisions);
                preflight(current.root(), decisions);
                return transact(current, workspace, target, targetInspection, pair, decisions);
            }
        }
    }

    public RecoveryResult recover(Path registry, String id, String confirmation)
            throws IOException, ManifestException {
        if (!CONFIRM.equals(confirmation)) {
            throw new IOException("recovery confirmation must be exactly " + CONFIRM);
        }
        Path registryPath = registry.toAbsolutePath().normalize();
        RegistryData data = registries.read(registryPath);
        InstallationRecord record = registries.resolve(data, id);
        Path root = validateBoundary(registryPath, data, record);
        try (RootCoordinator.Lease ignored = coordinator.acquire(root, true)) {
            Path namespace = root.resolve(NAMESPACE);
            if (!Files.exists(namespace, LinkOption.NOFOLLOW_LINKS)) {
                return new RecoveryResult("no pending transaction", null);
            }
            StrictPathSafety.requireDirectory(namespace, "real update namespace");
            Path pendingFile = namespace.resolve("pending.json");
            if (!Files.exists(pendingFile, LinkOption.NOFOLLOW_LINKS)) {
                OwnershipReceipt receipt = receipts.read(registryPath, data, record);
                CurrentUpdateState current = states.read(registryPath, data, record, receipt);
                if (current.lastTransactionId() != null) {
                    Path transaction = transaction(namespace, current.lastTransactionId());
                    Path journalFile = transaction.resolve("journal.json");
                    if (Files.exists(journalFile, LinkOption.NOFOLLOW_LINKS)) {
                        RealUpdateJournal journal = read(journalFile, RealUpdateJournal.class,
                                "committed cleanup journal");
                        validateJournal(journal, registryPath, record, receipt, root);
                        if ("COMMITTED".equals(journal.phase())
                                && current.equals(journal.nextState())
                                && appState(root, journal.operations()) == AppState.AFTER) {
                            Inspection installed = inspectTarget(root, journal);
                            registries.refreshAfterUpdate(registryPath, journal.expectedRecord(),
                                    installed.observedBuild(), installed.products());
                            validateNamespaceEntries(namespace, transaction, pendingFile, false);
                            deleteValidatedTransaction(transaction, journal);
                            Path transactions = namespace.resolve("transactions");
                            if (RealUpdateFiles.emptyDirectory(transactions)) {
                                Files.delete(transactions);
                            }
                            if (RealUpdateFiles.emptyDirectory(namespace)) Files.delete(namespace);
                            return new RecoveryResult("completed committed transaction cleanup",
                                    record);
                        }
                    }
                }
                pruneEmptyNamespace(namespace);
                return new RecoveryResult("removed empty transaction namespace", null);
            }
            PendingUpdate pending = read(pendingFile, PendingUpdate.class, "pending update");
            validatePending(pending, registryPath, record, root);
            Path transaction = transaction(namespace, pending.transactionId());
            Path journalFile = transaction.resolve("journal.json");
            if (!Files.exists(journalFile, LinkOption.NOFOLLOW_LINKS)) {
                // Application mutation is forbidden until a durable journal exists.
                if (Files.exists(transaction, LinkOption.NOFOLLOW_LINKS)
                        && !RealUpdateFiles.emptyDirectory(transaction)) {
                    throw new IOException("missing/corrupt journal with transaction artifacts "
                            + "was retained; recovery fails closed");
                }
                cleanupPreparing(namespace, transaction, pendingFile);
                return new RecoveryResult("removed interrupted pre-journal preparation", null);
            }
            RealUpdateJournal journal = read(journalFile, RealUpdateJournal.class,
                    "real update journal");
            OwnershipReceipt receipt = receipts.read(registryPath, data, record);
            validateJournal(journal, registryPath, record, receipt, root);
            clearJournalStaging(journalFile, journal);
            validateTransactionTree(transaction, journal);
            states.clearInterruptedStaging(registryPath, record.id(), journal.previousState(),
                    journal.nextState());
            CurrentUpdateState currentState = states.read(registryPath,
                    registries.read(registryPath), record, receipt);
            AppState app = appState(root, journal.operations());
            if (app == AppState.AFTER) {
                Inspection installed = inspectTarget(root, journal);
                if (!currentState.equals(journal.nextState())) {
                    if (!currentState.equals(journal.previousState())) {
                        throw new IOException("current provenance conflicts with pending update");
                    }
                    states.write(registryPath, record, receipt, journal.nextState());
                }
                InstallationRecord refreshed = registries.refreshAfterUpdate(registryPath,
                        journal.expectedRecord(), installed.observedBuild(), installed.products());
                writeJournal(journalFile, withPhase(journal, "COMMITTED"));
                cleanupCommitted(namespace, transaction, pendingFile, journal);
                return new RecoveryResult("completed verified post-mutation commit", refreshed);
            }
            if (!currentState.equals(journal.previousState())
                    && !currentState.equals(journal.nextState())) {
                throw new IOException("current provenance conflicts with rollback");
            }
            rollback(root, journal);
            if (journal.previousStatePublished()) {
                states.write(registryPath, record, receipt, journal.previousState());
            } else {
                states.rollbackPublication(registryPath, record.id(), false, journal.nextState());
            }
            cleanupCommitted(namespace, transaction, pendingFile, journal);
            return new RecoveryResult("rolled back interrupted update; external edits were not "
                    + "overwritten", record);
        }
    }

    public boolean hasPending(Path root) throws IOException {
        return coordinator.pending(root);
    }

    private ApplyResult transact(Snapshot snapshot, VerifiedPackageFetcher.Workspace workspace,
                                 OwnershipPolicy.Build target, Inspection targetInspection,
                                 ManifestReader.PreviewPairValidation pair,
                                 List<Decision> decisions)
            throws IOException, ManifestException {
        String transactionId = UUID.randomUUID().toString();
        CurrentUpdateState next = nextState(snapshot.current(), target, workspace, pair, decisions,
                transactionId);
        Path namespace = createNamespace(snapshot, transactionId);
        Path transaction = transaction(namespace, transactionId);
        Path pendingFile = namespace.resolve("pending.json");
        boolean previousPublished = states.isPublished(snapshot.registry(), snapshot.record().id());
        List<String> createdDirectories = missingParents(snapshot.root(), decisions);
        List<RealUpdateJournal.Operation> operations = operations(snapshot.root(), decisions,
                target.manifest(), transactionId);
        RealUpdateJournal journal = new RealUpdateJournal(JOURNAL_SCHEMA, transactionId,
                snapshot.root().toString(), snapshot.registry().toString(),
                snapshot.record().id(), snapshot.record().registeredAt(), "PREPARED",
                previousPublished, snapshot.current(), next, snapshot.record(),
                targetInspection.observedBuild(), targetInspection.products(), decisions,
                operations, createdDirectories);
        validateJournal(journal, snapshot.registry(), snapshot.record(), snapshot.receipt(),
                snapshot.root());
        Files.createDirectories(transaction);
        Path journalFile = transaction.resolve("journal.json");
        writeJournal(journalFile, journal); // durable intent precedes every application mutation
        Files.createDirectory(transaction.resolve("staged"));
        Files.createDirectory(transaction.resolve("backups"));
        stageAndBackup(snapshot.root(), workspace.extracted(), target.manifest(), transaction,
                operations);
        failureHook.hit("AFTER_STAGING");
        verifyArtifacts(snapshot.root(), transaction, operations);
        verifyAtomicStorage(transaction);
        failureHook.hit("BEFORE_APP_MUTATION");
        applyOperations(snapshot.root(), transaction, operations);
        failureHook.hit("AFTER_APP_MUTATION");

        Inspection installed = new InstallationInspector().inspect(snapshot.root());
        if (!installed.observedBuild().equals(targetInspection.observedBuild())
                || !installed.products().equals(targetInspection.products())) {
            throw new IOException("updated runtime does not match verified target layout");
        }
        states.write(snapshot.registry(), snapshot.record(), snapshot.receipt(), next);
        failureHook.hit("AFTER_STATE_COMMIT");
        InstallationRecord refreshed = registries.refreshAfterUpdate(snapshot.registry(),
                snapshot.record(), installed.observedBuild(), installed.products());
        failureHook.hit("AFTER_REGISTRY_COMMIT");
        writeJournal(journalFile, withPhase(journal, "COMMITTED"));
        cleanupCommitted(namespace, transaction, pendingFile, journal);
        long skipped = decisions.stream().filter(item -> item.action() == Action.SKIP).count();
        return new ApplyResult(refreshed, next, decisions, skipped,
                next.overrides().size() + next.caseOverrides().size());
    }

    private Path createNamespace(Snapshot snapshot, String transactionId) throws IOException {
        Path namespace = snapshot.root().resolve(NAMESPACE);
        if (Files.exists(namespace, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("pending or unknown real-update namespace requires recovery");
        }
        Files.createDirectory(namespace);
        Path pending = namespace.resolve("pending.json");
        PendingUpdate value = new PendingUpdate(JOURNAL_SCHEMA, transactionId,
                snapshot.record().id(), snapshot.root().toString(), snapshot.registry().toString(),
                snapshot.record().registeredAt());
        writeAtomic(pending, value);
        Files.createDirectory(namespace.resolve("transactions"));
        return namespace;
    }

    private void stageAndBackup(Path root, Path payload, Manifest target, Path transaction,
                                List<RealUpdateJournal.Operation> operations)
            throws IOException, ManifestException {
        Map<String, FileEntry> targetFiles = index(target);
        for (RealUpdateJournal.Operation operation : operations) {
            if (operation.afterHash() != null) {
                FileEntry entry = targetFiles.get(key(operation.path()));
                Path source = RealUpdateFiles.resolve(payload, entry.path(), false);
                if (!RealUpdateFiles.hash(source).equals(entry.sha256())) {
                    throw new ManifestException("verified package changed before staging: "
                            + entry.path());
                }
                Path staged = internal(transaction, "staged", entry.path());
                createInternalParents(transaction.resolve("staged"), entry.path());
                RealUpdateFiles.copyDurable(source, staged);
            }
            if (operation.beforeHash() != null) {
                Path application = RealUpdateFiles.resolve(root, operation.path(), false);
                Path backup = internal(transaction, "backups", operation.path());
                createInternalParents(transaction.resolve("backups"), operation.path());
                RealUpdateFiles.copyDurable(application, backup);
            }
        }
    }

    private void verifyArtifacts(Path root, Path transaction,
                                 List<RealUpdateJournal.Operation> operations)
            throws IOException, ManifestException {
        for (RealUpdateJournal.Operation operation : operations) {
            if (operation.afterHash() != null) {
                Path staged = internal(transaction, "staged", operation.path());
                if (!operation.afterHash().equals(RealUpdateFiles.hash(staged))) {
                    throw new ManifestException("staged payload integrity failed for "
                            + operation.path());
                }
            }
            if (operation.beforeHash() != null) {
                Path backup = internal(transaction, "backups", operation.path());
                if (!operation.beforeHash().equals(RealUpdateFiles.hash(backup))) {
                    throw new ManifestException("verified backup integrity failed for "
                            + operation.path());
                }
            }
        }
    }

    private void applyOperations(Path root, Path transaction,
                                 List<RealUpdateJournal.Operation> operations)
            throws IOException, ManifestException {
        for (RealUpdateJournal.Operation operation : operations) {
            failureHook.hit("BEFORE_OPERATION:" + operation.path());
            Path destination = RealUpdateFiles.resolve(root, operation.path(), true);
            String current = RealUpdateFiles.hashIfRegular(destination);
            if (!Objects.equals(current, operation.beforeHash())) {
                throw new ManifestException("application changed after planning: "
                        + operation.path());
            }
            if (operation.beforeHash() != null) {
                Path backup = internal(transaction, "backups", operation.path());
                if (!operation.beforeHash().equals(RealUpdateFiles.hash(backup))) {
                    throw new ManifestException("verified backup changed before mutation: "
                            + operation.path());
                }
            }
            createApplicationParents(root, operation.path());
            if (operation.afterHash() == null) {
                Files.delete(destination);
            } else {
                Path staged = internal(transaction, "staged", operation.path());
                if (!operation.afterHash().equals(RealUpdateFiles.hash(staged))) {
                    throw new ManifestException("staged payload changed before mutation: "
                            + operation.path());
                }
                RealUpdateFiles.atomicReplace(staged, destination);
            }
            if (!Objects.equals(RealUpdateFiles.hashIfRegular(destination),
                    operation.afterHash())) {
                throw new ManifestException("application mutation verification failed for "
                        + operation.path());
            }
            failureHook.hit("APP_MUTATION:" + operation.path());
        }
    }

    private void rollback(Path root, RealUpdateJournal journal)
            throws IOException, ManifestException {
        Path transaction = transaction(root.resolve(NAMESPACE), journal.transactionId());
        List<RealUpdateJournal.Operation> reverse = new ArrayList<>(journal.operations());
        Collections.reverse(reverse);
        for (RealUpdateJournal.Operation operation : reverse) {
            Path destination = RealUpdateFiles.resolve(root, operation.path(), true);
            String current = RealUpdateFiles.hashIfRegular(destination);
            if (Objects.equals(current, operation.beforeHash())) continue;
            if (!Objects.equals(current, operation.afterHash())) {
                throw new ManifestException("external edit blocks recovery of " + operation.path());
            }
            if (operation.beforeHash() == null) {
                Files.delete(destination);
                continue;
            }
            Path backup = internal(transaction, "backups", operation.path());
            if (!Files.exists(backup, LinkOption.NOFOLLOW_LINKS)
                    || !operation.beforeHash().equals(RealUpdateFiles.hash(backup))) {
                throw new ManifestException("valid backup unavailable for " + operation.path());
            }
            Path rollbackStage = internal(transaction, "rollback", operation.path());
            createInternalParents(transaction.resolve("rollback"), operation.path());
            if (!Files.exists(rollbackStage, LinkOption.NOFOLLOW_LINKS)) {
                RealUpdateFiles.copyDurable(backup, rollbackStage);
            } else if (!operation.beforeHash().equals(RealUpdateFiles.hash(rollbackStage))) {
                throw new ManifestException("invalid rollback staging file for " + operation.path());
            }
            createApplicationParents(root, operation.path());
            RealUpdateFiles.atomicReplace(rollbackStage, destination);
        }
        List<String> directories = new ArrayList<>(journal.createdDirectories());
        Collections.reverse(directories);
        for (String relative : directories) {
            Path directory = RealUpdateFiles.resolve(root, relative, true);
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)
                    && RealUpdateFiles.emptyDirectory(directory)) {
                Files.delete(directory);
            }
        }
    }

    private AppState appState(Path root, List<RealUpdateJournal.Operation> operations)
            throws IOException, ManifestException {
        boolean allBefore = true;
        boolean allAfter = true;
        for (RealUpdateJournal.Operation operation : operations) {
            String hash = RealUpdateFiles.hashIfRegular(
                    RealUpdateFiles.resolve(root, operation.path(), true));
            if (!Objects.equals(hash, operation.beforeHash())
                    && !Objects.equals(hash, operation.afterHash())) {
                throw new ManifestException("external edit blocks recovery of " + operation.path());
            }
            allBefore &= Objects.equals(hash, operation.beforeHash());
            allAfter &= Objects.equals(hash, operation.afterHash());
        }
        if (allAfter) return AppState.AFTER;
        if (allBefore) return AppState.BEFORE;
        return AppState.PARTIAL;
    }

    private Inspection inspectTarget(Path root, RealUpdateJournal journal) throws IOException {
        Inspection inspection = new InstallationInspector().inspect(root);
        if (!inspection.observedBuild().equals(journal.targetObservedBuild())
                || !inspection.products().equals(journal.targetProducts())) {
            throw new IOException("post-update runtime does not match journaled verified target");
        }
        return inspection;
    }

    private void validateJournal(RealUpdateJournal journal, Path registry,
                                 InstallationRecord record, OwnershipReceipt receipt, Path root)
            throws IOException, ManifestException {
        if (journal == null || journal.schemaVersion() != JOURNAL_SCHEMA
                || journal.transactionId() == null || journal.canonicalRoot() == null
                || journal.canonicalRegistry() == null || journal.installationId() == null
                || journal.registeredAt() == null || journal.phase() == null
                || journal.previousState() == null || journal.nextState() == null
                || journal.expectedRecord() == null || journal.targetObservedBuild() == null
                || !uuid(journal.transactionId())
                || !root.toString().equals(journal.canonicalRoot())
                || !registry.toString().equals(journal.canonicalRegistry())
                || !record.id().equals(journal.installationId())
                || !record.registeredAt().equals(journal.registeredAt())
                || journal.operations() == null || journal.decisions() == null
                || journal.createdDirectories() == null || journal.targetProducts() == null
                || !Set.of("PREPARED", "COMMITTED").contains(journal.phase())
                || !journal.expectedRecord().id().equals(record.id())
                || !journal.expectedRecord().canonicalRoot().equals(record.canonicalRoot())
                || !journal.expectedRecord().registeredAt().equals(record.registeredAt())
                || !journal.transactionId().equals(journal.nextState().lastTransactionId())) {
            throw new IOException("real update journal identity or schema is invalid");
        }
        states.validate(journal.previousState(), journal.expectedRecord(), receipt);
        states.validate(journal.nextState(), journal.expectedRecord(), receipt);
        new ManifestReader().validatePreviewPair(journal.previousState().officialManifest(),
                journal.nextState().officialManifest());
        Map<String, FileEntry> before = index(journal.previousState().officialManifest());
        Map<String, FileEntry> after = index(journal.nextState().officialManifest());
        Map<String, Set<String>> trusted = trustedHashes(journal.previousState());
        Map<String, RealUpdateJournal.Operation> operations = new HashMap<>();
        for (RealUpdateJournal.Operation operation : journal.operations()) {
            if (operation == null || !Set.of("ADD", "REPLACE", "REMOVE")
                    .contains(operation.action())) {
                throw new IOException("journal contains an unsupported operation");
            }
            ManifestReader.validatePortablePath(operation.path(), "journal operation",
                    "real update");
            String operationKey = key(operation.path());
            if (!OwnershipPolicy.managed(operation.path())
                    || operations.putIfAbsent(operationKey, operation) != null) {
                throw new IOException("journal operation is outside ownership or duplicated");
            }
            FileEntry oldEntry = before.get(operationKey);
            FileEntry newEntry = after.get(operationKey);
            if (newEntry == null && !"REMOVE".equals(operation.action())
                    || newEntry != null && operation.afterHash() == null
                    || newEntry != null && !newEntry.sha256().equals(operation.afterHash())
                    || operation.beforeHash() == null && !"ADD".equals(operation.action())
                    || operation.beforeHash() != null && "ADD".equals(operation.action())
                    || oldEntry == null && operation.beforeHash() != null
                    || operation.beforeHash() != null && oldEntry != null
                    && !operation.beforeHash().equals(oldEntry.sha256())
                    && !trusted.getOrDefault(operationKey, Set.of())
                    .contains(operation.beforeHash())) {
                throw new IOException("journal operation hashes do not match managed manifests");
            }
            String expectedBackup = operation.beforeHash() == null ? null
                    : NAMESPACE + "/transactions/" + journal.transactionId()
                    + "/backups/" + operation.path();
            String expectedStaged = operation.afterHash() == null ? null
                    : NAMESPACE + "/transactions/" + journal.transactionId()
                    + "/staged/" + operation.path();
            if (!Objects.equals(expectedBackup, operation.backupPath())
                    || !Objects.equals(expectedStaged, operation.stagedPath())) {
                throw new IOException("journal artifact path is not transaction-owned");
            }
        }
        Set<String> decisionMutations = new HashSet<>();
        Set<String> decisionPaths = new HashSet<>();
        for (Decision decision : journal.decisions()) {
            if (decision == null || decision.action() == null
                    || !before.containsKey(key(decision.path()))
                    && !after.containsKey(key(decision.path()))) {
                throw new IOException("journal plan contains a non-managed path");
            }
            if (!decisionPaths.add(decision.path())) {
                throw new IOException("journal plan contains duplicate paths");
            }
            if (decision.action() == Action.ADD || decision.action() == Action.REPLACE
                    || decision.action() == Action.REMOVE) {
                decisionMutations.add(key(decision.path()));
                RealUpdateJournal.Operation operation = operations.get(key(decision.path()));
                if (operation == null || !operation.action().equals(decision.action().name())) {
                    throw new IOException("journal operation does not match its captured plan");
                }
            }
        }
        if (!decisionMutations.equals(operations.keySet())) {
            throw new IOException("journal plan and operation allowlists differ");
        }
        Set<String> expectedPaths = new HashSet<>();
        journal.previousState().officialManifest().files().forEach(
                item -> expectedPaths.add(item.path()));
        journal.nextState().officialManifest().files().forEach(
                item -> expectedPaths.add(item.path()));
        if (!decisionPaths.equals(expectedPaths)) {
            throw new IOException("journal plan is not the complete managed manifest union");
        }
        for (String directory : journal.createdDirectories()) {
            ManifestReader.validatePortablePath(directory, "created directory", "real update");
            boolean parent = journal.operations().stream().anyMatch(op ->
                    op.path().startsWith(directory + "/"));
            if (!parent || directory.equals(NAMESPACE) || directory.startsWith(NAMESPACE + "/")) {
                throw new IOException("journal created-directory allowlist is invalid");
            }
        }
    }

    private void validatePristineRuntime(Path root, CurrentUpdateState current, Manifest target,
                                         List<Decision> decisions)
            throws IOException, ManifestException {
        for (Decision decision : decisions) {
            if (decision.action() == Action.SKIP && OwnershipPolicy.runtimePath(decision.path())) {
                throw new IOException("entire update refused: runtime customization/conflict at "
                        + decision.path());
            }
        }
        Map<String, FileEntry> old = index(current.officialManifest());
        Map<String, FileEntry> after = index(target);
        Map<String, Set<String>> trusted = trustedHashes(current);
        Set<String> actualRuntime = new HashSet<>();
        try (var walk = Files.walk(root)) {
            for (Path path : walk.skip(1).toList()) {
                Path relative = root.relativize(path);
                String portable = relative.toString().replace('\\', '/');
                if (portable.equals(NAMESPACE) || portable.startsWith(NAMESPACE + "/")) continue;
                var attrs = Files.readAttributes(path,
                        java.nio.file.attribute.BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (attrs.isSymbolicLink() || attrs.isOther()) {
                    if (OwnershipPolicy.runtimePath(portable)) {
                        throw new IOException("entire update refused: linked/special runtime path "
                                + portable);
                    }
                    continue;
                }
                if (!attrs.isRegularFile() || !OwnershipPolicy.runtimePath(portable)) continue;
                String folded = key(portable);
                actualRuntime.add(folded);
                FileEntry oldEntry = old.get(folded);
                FileEntry newEntry = after.get(folded);
                String hash = RealUpdateFiles.hash(path);
                boolean official = oldEntry != null && hash.equals(oldEntry.sha256())
                        || newEntry != null && hash.equals(newEntry.sha256())
                        || trusted.getOrDefault(folded, Set.of()).contains(hash);
                if (!official) {
                    throw new IOException("entire update refused: unrecognized/customized runtime "
                            + portable);
                }
            }
        }
        for (FileEntry entry : old.values()) {
            if (OwnershipPolicy.runtimePath(entry.path())
                    && !actualRuntime.contains(key(entry.path()))) {
                throw new IOException("entire update refused: managed runtime is missing "
                        + entry.path());
            }
        }
    }

    private void preflight(Path root, List<Decision> decisions) throws IOException {
        if (!Files.isWritable(root)) throw new IOException("application root is not writable");
        FileStore rootStore = Files.getFileStore(root);
        for (Decision decision : decisions) {
            if (decision.action() != Action.ADD && decision.action() != Action.REPLACE
                    && decision.action() != Action.REMOVE) continue;
            Path path = root.resolve(decision.path().replace('/', java.io.File.separatorChar));
            Path parent = path.getParent();
            while (parent != null && !Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
                parent = parent.getParent();
            }

            if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                    || !Files.isWritable(parent)) {
                throw new IOException("update destination parent is not writable for "
                        + decision.path());
            }
            if (!Files.getFileStore(parent).equals(rootStore)) {
                throw new IOException("update destination is not on the application volume");
            }
        }
    }

    private static void verifyAtomicStorage(Path transaction) throws IOException {
        Path source = transaction.resolve("atomic-probe-a");
        Path destination = transaction.resolve("atomic-probe-b");
        try {
            try (FileChannel channel = FileChannel.open(source, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                channel.write(ByteBuffer.wrap(new byte[]{1}));
                channel.force(true);
            }
            try {
                Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("atomic same-volume update storage is unsupported", e);
            }
        } finally {
            Files.deleteIfExists(source);
            Files.deleteIfExists(destination);
        }
    }

    private CurrentUpdateState nextState(CurrentUpdateState previous,
                                         OwnershipPolicy.Build target,
                                         VerifiedPackageFetcher.Workspace workspace,
                                         ManifestReader.PreviewPairValidation pair,
                                         List<Decision> decisions, String transactionId) {
        Map<String, OverrideEntry> overrides = new LinkedHashMap<>();
        previous.overrides().forEach(value -> overrides.put(key(value.path()), value));
        Map<String, FileEntry> old = index(previous.officialManifest());
        for (Decision decision : decisions) {
            String folded = key(decision.path());
            boolean caseAffected = affectedByCase(decision.path(), previous.caseOverrides(), pair);
            if (decision.action() == Action.SKIP && !caseAffected) {
                OverrideEntry prior = overrides.get(folded);
                Set<String> hashes = new TreeSet<>();
                if (prior != null) hashes.addAll(prior.officialHashes());
                FileEntry oldEntry = old.get(folded);
                if (oldEntry != null) hashes.add(oldEntry.sha256());
                OverrideEntry.Kind kind = oldEntry == null ? OverrideEntry.Kind.COLLISION
                        : target.manifest().files().stream().anyMatch(
                        item -> key(item.path()).equals(folded))
                        ? OverrideEntry.Kind.MODIFIED : OverrideEntry.Kind.OBSOLETE_MODIFIED;
                overrides.put(folded, new OverrideEntry(
                        prior == null ? decision.path() : prior.path(), kind, List.copyOf(hashes)));
            } else if (!caseAffected) {
                overrides.remove(folded);
            }
        }
        Map<String, CaseOverride> cases = new LinkedHashMap<>();
        previous.caseOverrides().forEach(item -> cases.put(item.foldedPrefix(), item));
        for (ManifestReader.CaseOnlyRename rename : pair.caseOnlyRenames()) {
            String folded = key(rename.baselinePrefix());
            CaseOverride prior = cases.get(folded);
            Set<String> spellings = new TreeSet<>();
            if (prior != null) spellings.addAll(prior.officialSpellings());
            spellings.add(rename.baselinePrefix());
            spellings.add(rename.targetPrefix());
            cases.put(folded, new CaseOverride(folded,
                    prior == null ? rename.baselinePrefix() : prior.localPrefix(),
                    List.copyOf(spellings)));
        }
        return new CurrentUpdateState(CurrentStateStore.SCHEMA, OwnershipPolicy.VERSION,
                previous.installationId(), previous.canonicalRoot(), previous.registeredAt(),
                previous.repository(), workspace.release().tag(), workspace.asset().name(),
                workspace.asset().size(),
                workspace.asset().digest().substring("sha256:".length()).toLowerCase(Locale.ROOT),
                target.manifest(), target.excludedPaths(),
                overrides.values().stream().sorted(Comparator.comparing(
                        OverrideEntry::path, String.CASE_INSENSITIVE_ORDER)).toList(),
                cases.values().stream().sorted(Comparator.comparing(CaseOverride::foldedPrefix))
                        .toList(),
                transactionId);
    }

    private static boolean affectedByCase(String path, List<CaseOverride> prior,
                                          ManifestReader.PreviewPairValidation pair) {
        String folded = key(path);
        for (CaseOverride item : prior) {
            if (folded.equals(item.foldedPrefix())
                    || folded.startsWith(item.foldedPrefix() + "/")) return true;
        }
        for (ManifestReader.CaseOnlyRename item : pair.caseOnlyRenames()) {
            String prefix = key(item.baselinePrefix());
            if (folded.equals(prefix) || folded.startsWith(prefix + "/")) return true;
        }
        return false;
    }

    private static List<RealUpdateJournal.Operation> operations(
            Path root, List<Decision> decisions, Manifest target, String transactionId)
            throws IOException, ManifestException {
        Map<String, FileEntry> after = index(target);
        List<RealUpdateJournal.Operation> result = new ArrayList<>();
        for (Decision decision : decisions) {
            if (decision.action() != Action.ADD && decision.action() != Action.REPLACE
                    && decision.action() != Action.REMOVE) continue;
            Path application = RealUpdateFiles.resolve(root, decision.path(), true);
            String before = RealUpdateFiles.hashIfRegular(application);
            FileEntry targetEntry = after.get(key(decision.path()));
            String targetHash = targetEntry == null ? null : targetEntry.sha256();
            String prefix = NAMESPACE + "/transactions/" + transactionId;
            result.add(new RealUpdateJournal.Operation(decision.action().name(), decision.path(),
                    before, targetHash, before == null ? null
                    : prefix + "/backups/" + decision.path(),
                    targetHash == null ? null : prefix + "/staged/" + decision.path()));
        }
        return List.copyOf(result);
    }

    private static List<String> missingParents(Path root, List<Decision> decisions)
            throws IOException, ManifestException {
        List<String> result = new ArrayList<>();
        for (Decision decision : decisions) {
            if (decision.action() != Action.ADD && decision.action() != Action.REPLACE) continue;
            String current = "";
            String[] parts = decision.path().split("/");
            for (int i = 0; i < parts.length - 1; i++) {
                current = current.isEmpty() ? parts[i] : current + "/" + parts[i];
                if (!Files.exists(RealUpdateFiles.resolve(root, current, true),
                        LinkOption.NOFOLLOW_LINKS) && !result.contains(current)) {
                    result.add(current);
                }
            }
        }
        return List.copyOf(result);
    }

    private static void createApplicationParents(Path root, String path)
            throws IOException, ManifestException {
        String current = "";
        String[] parts = path.split("/");
        for (int i = 0; i < parts.length - 1; i++) {
            current = current.isEmpty() ? parts[i] : current + "/" + parts[i];
            Path directory = RealUpdateFiles.resolve(root, current, true);
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(directory);
            StrictPathSafety.requireDirectory(directory, "application update parent");
        }
    }

    private static void createInternalParents(Path base, String path) throws IOException {
        Path parent = base.resolve(path.replace('/', java.io.File.separatorChar)).getParent();
        if (parent != null) {
            Files.createDirectories(parent);
            StrictPathSafety.requireDirectory(parent, "transaction artifact parent");
        }
    }

    private static Path internal(Path transaction, String area, String path) {
        return transaction.resolve(area).resolve(path.replace('/', java.io.File.separatorChar));
    }

    private void cleanupPreparing(Path namespace, Path transaction, Path pending)
            throws IOException {
        validateNamespaceEntries(namespace, transaction, pending, true);
        if (Files.exists(transaction, LinkOption.NOFOLLOW_LINKS)) {
            RealUpdateFiles.deleteOwnedTree(transaction);
        }
        Files.deleteIfExists(pending);
        Path transactions = namespace.resolve("transactions");
        if (Files.exists(transactions, LinkOption.NOFOLLOW_LINKS)
                && RealUpdateFiles.emptyDirectory(transactions)) Files.delete(transactions);
        if (RealUpdateFiles.emptyDirectory(namespace)) Files.delete(namespace);
    }

    private void cleanupCommitted(Path namespace, Path transaction, Path pending,
                                  RealUpdateJournal journal) throws IOException {
        // Validate and remove only the exact allowlisted transaction tree first. If interrupted
        // after that, the still-present blocker has no journal and recovery can safely remove it
        // because application/provenance/registry are already coherent.
        validateNamespaceEntries(namespace, transaction, pending, true);
        deleteValidatedTransaction(transaction, journal);
        Files.delete(pending);
        failureHook.hit("AFTER_PENDING_DELETE");
        Path transactions = namespace.resolve("transactions");
        if (RealUpdateFiles.emptyDirectory(transactions)) Files.delete(transactions);
        if (RealUpdateFiles.emptyDirectory(namespace)) Files.delete(namespace);
    }

    /**
     * Removes only the two known empty containers left when cleanup was interrupted after the
     * pending identity disappeared. Unknown siblings, transactions, and linked/reparse objects
     * are retained and fail recovery.
     */
    private static void pruneEmptyNamespace(Path namespace) throws IOException {
        Path transactions = namespace.resolve("transactions");
        List<Path> namespaceEntries;
        try (var entries = Files.list(namespace)) {
            namespaceEntries = entries.toList();
        }
        if (namespaceEntries.isEmpty()) {
            Files.delete(namespace);
            return;
        }
        if (namespaceEntries.size() != 1 || !namespaceEntries.getFirst().equals(transactions)) {
            throw new IOException("update namespace has no valid pending identity; unknown "
                    + "artifacts were retained");
        }
        StrictPathSafety.requireDirectory(transactions, "update transactions directory");
        if (!RealUpdateFiles.emptyDirectory(transactions)) {
            throw new IOException("update namespace has no valid pending identity; unknown "
                    + "transactions were retained");
        }
        Files.delete(transactions);
        if (!RealUpdateFiles.emptyDirectory(namespace)) {
            throw new IOException("unknown update namespace artifacts appeared during cleanup");
        }
        Files.delete(namespace);
    }

    private static void validateNamespaceEntries(Path namespace, Path transaction, Path pending,
                                                 boolean pendingExpected) throws IOException {
        Path transactions = namespace.resolve("transactions");
        try (var entries = Files.list(namespace)) {
            for (Path entry : entries.toList()) {
                if (!entry.equals(transactions) && !(pendingExpected && entry.equals(pending))) {
                    throw new IOException("unknown update namespace artifact retained: " + entry);
                }
            }
        }
        if (Files.exists(transactions, LinkOption.NOFOLLOW_LINKS)) {
            StrictPathSafety.requireDirectory(transactions, "update transactions directory");
            try (var entries = Files.list(transactions)) {
                for (Path entry : entries.toList()) {
                    if (!entry.equals(transaction)) {
                        throw new IOException("unknown update transaction retained: " + entry);
                    }
                }
            }
        }
    }

    private static void deleteValidatedTransaction(Path transaction, RealUpdateJournal journal)
            throws IOException {
        validateTransactionTree(transaction, journal);
        RealUpdateFiles.deleteOwnedTree(transaction);
    }

    private static void validateTransactionTree(Path transaction, RealUpdateJournal journal)
            throws IOException {
        Set<Path> directories = new HashSet<>();
        Set<Path> files = new HashSet<>();
        directories.add(transaction);
        files.add(transaction.resolve("journal.json"));
        files.add(transaction.resolve("atomic-probe-a"));
        files.add(transaction.resolve("atomic-probe-b"));
        for (String area : List.of("staged", "backups", "rollback")) {
            directories.add(transaction.resolve(area));
        }
        for (RealUpdateJournal.Operation operation : journal.operations()) {
            if (operation.afterHash() != null) {
                allowArtifact(transaction.resolve("staged"), operation.path(), directories, files);
            }
            if (operation.beforeHash() != null) {
                allowArtifact(transaction.resolve("backups"), operation.path(), directories, files);
                allowArtifact(transaction.resolve("rollback"), operation.path(), directories, files);
            }
        }
        try (var walk = Files.walk(transaction)) {
            for (Path path : walk.toList()) {
                var attrs = Files.readAttributes(path,
                        java.nio.file.attribute.BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (attrs.isSymbolicLink() || attrs.isOther()) {
                    throw new IOException("unknown transaction link/type retained: " + path);
                }
                if (attrs.isDirectory() ? !directories.contains(path) : !files.contains(path)) {
                    throw new IOException("unknown transaction artifact retained: " + path);
                }
            }
        }
    }

    private static void allowArtifact(Path base, String portable, Set<Path> directories,
                                      Set<Path> files) {
        Path file = base.resolve(portable.replace('/', java.io.File.separatorChar));
        files.add(file);
        for (Path parent = file.getParent(); parent != null && parent.startsWith(base);
             parent = parent.getParent()) {
            directories.add(parent);
            if (parent.equals(base)) break;
        }
    }

    private static Path transaction(Path namespace, String id) throws IOException {
        if (!uuid(id)) throw new IOException("invalid transaction UUID");
        return namespace.resolve("transactions").resolve(id);
    }

    private void validatePending(PendingUpdate pending, Path registry, InstallationRecord record,
                                 Path root) throws IOException {
        if (pending == null || pending.schemaVersion() != JOURNAL_SCHEMA
                || pending.transactionId() == null || pending.installationId() == null
                || pending.canonicalRoot() == null || pending.canonicalRegistry() == null
                || pending.registeredAt() == null
                || !uuid(pending.transactionId())
                || !pending.installationId().equals(record.id())
                || !pending.canonicalRoot().equals(root.toString())
                || !pending.canonicalRegistry().equals(registry.toString())
                || !pending.registeredAt().equals(record.registeredAt())) {
            throw new IOException("pending update has a stale or wrong installation binding");
        }
    }

    private static RealUpdateJournal withPhase(RealUpdateJournal journal, String phase) {
        return new RealUpdateJournal(journal.schemaVersion(), journal.transactionId(),
                journal.canonicalRoot(), journal.canonicalRegistry(), journal.installationId(),
                journal.registeredAt(), phase, journal.previousStatePublished(),
                journal.previousState(), journal.nextState(), journal.expectedRecord(),
                journal.targetObservedBuild(), journal.targetProducts(), journal.decisions(),
                journal.operations(), journal.createdDirectories());
    }

    private void writeJournal(Path file, RealUpdateJournal journal) throws IOException {
        writeAtomic(file, journal);
    }

    private void clearJournalStaging(Path journalFile, RealUpdateJournal journal)
            throws IOException {
        Path staging = journalFile.resolveSibling(journalFile.getFileName() + ".new");
        if (!Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) return;
        RealUpdateJournal candidate = read(staging, RealUpdateJournal.class,
                "journal publication staging");
        if (!candidate.transactionId().equals(journal.transactionId())
                || !candidate.canonicalRoot().equals(journal.canonicalRoot())
                || !candidate.canonicalRegistry().equals(journal.canonicalRegistry())
                || !candidate.operations().equals(journal.operations())
                || !candidate.previousState().equals(journal.previousState())
                || !candidate.nextState().equals(journal.nextState())) {
            throw new IOException("unexpected journal staging blocks recovery");
        }
        Files.delete(staging);
    }

    private void writeAtomic(Path file, Object value) throws IOException {
        Path staging = file.resolveSibling(file.getFileName() + ".new");
        if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("interrupted metadata publication requires recovery: " + staging);
        }
        byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        if (bytes.length > MAX_METADATA) throw new IOException("update metadata exceeds size limit");
        try (FileChannel channel = FileChannel.open(staging, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(bytes));
            channel.force(true);
        }
        try {
            Files.move(staging, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.deleteIfExists(staging);
            throw new IOException("atomic update metadata publication is unsupported", e);
        }
    }

    private <T> T read(Path file, Class<T> type, String label) throws IOException {
        StrictPathSafety.requireFile(file, label);
        long size = Files.size(file);
        if (size <= 0 || size > MAX_METADATA) throw new IOException(label + " size is invalid");
        try {
            return mapper.readValue(Files.readAllBytes(file), type);
        } catch (IOException | RuntimeException e) {
            throw new IOException(label + " is invalid: " + detail(e), e);
        }
    }

    private static Path validateBoundary(Path registry, RegistryData data,
                                         InstallationRecord record) throws IOException {
        Path root = Path.of(record.canonicalRoot()).toAbsolutePath().normalize();
        StrictPathSafety.requireDirectory(root, "registered root");
        if (Files.exists(root.resolve(".mm-launcher"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("real update refuses disposable sandbox state");
        }
        ReceiptStore.validateMetadataPlacement(
                registry.resolveSibling(registry.getFileName() + ".metadata"), data);
        return root;
    }

    private static void validateAuthorization(String fromTag, String targetTag, long size,
                                              String digest, String confirmation)
            throws IOException {
        if (!CONFIRM.equals(confirmation)) {
            throw new IOException("--confirm must be exactly " + CONFIRM);
        }
        if (fromTag == null || !TAG.matcher(fromTag).matches()
                || targetTag == null || !TAG.matcher(targetTag).matches()) {
            throw new IOException("source and target must be explicit valid release tags");
        }
        if (size <= 0 || size > FreshInstaller.MAX_DOWNLOAD
                || digest == null || !DIGEST.matcher(digest).matches()) {
            throw new IOException("consent must bind the exact target byte size and SHA-256");
        }
    }

    private static Map<String, Set<String>> trustedHashes(CurrentUpdateState state) {
        Map<String, Set<String>> result = new HashMap<>();
        for (OverrideEntry override : state.overrides()) {
            result.put(key(override.path()), Set.copyOf(override.officialHashes()));
        }
        return Map.copyOf(result);
    }

    private static List<String> stickyPrefixes(CurrentUpdateState state) {
        return state.caseOverrides().stream().map(CaseOverride::foldedPrefix).toList();
    }

    private static Map<String, FileEntry> index(Manifest manifest) {
        Map<String, FileEntry> result = new HashMap<>();
        manifest.files().forEach(entry -> result.put(key(entry.path()), entry));
        return result;
    }

    private static String key(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    private static boolean uuid(String value) {
        try {
            return value != null && UUID.fromString(value).toString().equals(value);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static String detail(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    public record Snapshot(Path registry, InstallationRecord record, OwnershipReceipt receipt,
                           CurrentUpdateState current, Path root) {
    }

    public record ApplyResult(InstallationRecord record, CurrentUpdateState state,
                              List<Decision> decisions, long skippedDecisions,
                              long retainedOverrides) {
    }

    public record RecoveryResult(String outcome, InstallationRecord record) {
    }

    @FunctionalInterface
    interface FailureHook {
        void hit(String point) throws IOException;
    }

    private enum AppState {
        BEFORE, PARTIAL, AFTER
    }
}
