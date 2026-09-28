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
import org.megamek.launcher.channel.ChannelUpdateChecker;
import org.megamek.launcher.launch.RootCoordinator;
import org.megamek.launcher.manifest.FileEntry;
import org.megamek.launcher.manifest.Manifest;
import org.megamek.launcher.manifest.ManifestException;
import org.megamek.launcher.manifest.ManifestReader;
import org.megamek.launcher.onboarding.Inspection;
import org.megamek.launcher.onboarding.InstallationInspector;
import org.megamek.launcher.operation.OperationContext;
import org.megamek.launcher.operation.OperationPhase;
import org.megamek.launcher.operation.OperationType;
import org.megamek.launcher.plan.Action;
import org.megamek.launcher.plan.Decision;
import org.megamek.launcher.plan.UpdatePlanner;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryData;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.release.FreshInstaller;
import org.megamek.launcher.release.OfficialRepository;
import org.megamek.launcher.release.ReleaseCatalog;
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
    /** Repair is a distinct, explicit authorization, not Update consent. */
    public static final String REPAIR_CONFIRM = "RESTORE-EXACT-OFFICIAL-RELEASE";
    private static final int JOURNAL_SCHEMA = 1;
    private static final int REPAIR_JOURNAL_SCHEMA = 2;
    private static final int MAX_METADATA = 64 * 1024 * 1024;
    private static final Pattern TAG = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");
    private static final Pattern DIGEST = Pattern.compile("sha256:[0-9a-fA-F]{64}");
    private static final String NAMESPACE = RootCoordinator.UPDATE_NAMESPACE;

    private final ReleaseTransport transport;
    private final RegistryStore registries;
    private final ReceiptStore receipts;
    private final CurrentStateStore states;
    private final ChannelPreferenceStore channelPreferences;
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

    public RealUpdateService(ReleaseTransport transport, RootCoordinator coordinator) {
        this(transport, new RegistryStore(), new ReceiptStore(), coordinator, point -> {});
    }

    RealUpdateService(ReleaseTransport transport, RegistryStore registries, ReceiptStore receipts,
                      RootCoordinator coordinator, FailureHook failureHook) {
        this.transport = transport;
        this.registries = registries;
        this.receipts = receipts;
        this.states = new CurrentStateStore(receipts);
        this.channelPreferences = new ChannelPreferenceStore();
        this.coordinator = coordinator;
        this.failureHook = failureHook;
    }

    public Snapshot snapshot(Path registry, String id) throws IOException {
        Path registryPath = registry.toAbsolutePath().normalize();
        if (Files.exists(UninstallService.pendingJournalPath(registryPath, id),
                LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Uninstall recovery required");
        }
        RegistryData data = registries.read(registryPath);
        InstallationRecord record = registries.resolve(data, id);
        ChannelPreferenceStore.ReadResult fixed =
                channelPreferences.read(registryPath, data, record);
        if (fixed.status() != ChannelPreferenceStore.Status.CONFIGURED
                || fixed.preference() == null) {
            throw new IOException("fixed channel provenance is unavailable; this copy is "
                    + "launch-only: " + fixed.reason());
        }
        Path root = validateBoundary(registryPath, data, record);
        OwnershipReceipt receipt = receipts.read(registryPath, data, record);
        CurrentUpdateState state = states.read(registryPath, data, record, receipt);
        new AdoptionStateStore(receipts).validateForUse(
                registryPath, record, receipt, state, fixed.preference());
        return new Snapshot(registryPath, record, receipt, state, root);
    }

    /**
     * Restores only paths owned by the currently recorded exact release. No preview or update
     * planner is used: local modifications to official docs/data are deliberately restored too.
     * The returned warning lists unowned lib JARs which may still influence class loading.
     */
    public RepairResult repair(Path registry, String id, String confirmation, PrintStream progress)
            throws IOException, InterruptedException, ManifestException {
        return repair(registry, id, confirmation, progress,
                OperationContext.none(OperationType.UPDATE_APPLY));
    }

    public RepairResult repair(Path registry, String id, String confirmation, PrintStream progress,
                               OperationContext context)
            throws IOException, InterruptedException, ManifestException {
        if (!REPAIR_CONFIRM.equals(confirmation)) {
            throw new IOException("repair requires explicit " + REPAIR_CONFIRM + " confirmation");
        }
        // Repair cannot be abandoned after a journal is started. Unlike Apply, this entire
        // operation is non-cancellable, including acquisition and preflight.
        context.enterFinalization("Repair must run through completion or retain recovery state.");
        context.phase(OperationPhase.METADATA, "Checking exact release provenance");
        Snapshot expected = snapshot(registry, id);
        try (RootCoordinator.Lease gate = coordinator.acquire(expected.root(), true)) {
            gate.requireNoPendingUpdate();
            RegistryData data = registries.read(expected.registry());
            if (Files.exists(new AdoptionStateStore(receipts).path(expected.registry(),
                    expected.record().id()), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("imported copies are not eligible for exact-release repair");
            }
            if (Files.exists(UninstallService.pendingJournalPath(expected.registry(),
                    expected.record().id()), LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("uninstall recovery required before repair");
            }
            Snapshot current = snapshot(expected.registry(), expected.record().id());
            if (!current.equals(expected) || !registries.resolve(data, id).equals(current.record())) {
                throw new IOException("repair provenance changed after authorization");
            }
            CurrentUpdateState source = current.current();
            OfficialRepository repository = OfficialRepository.parse(source.repository());
            try (VerifiedPackageFetcher.Workspace workspace =
                         new VerifiedPackageFetcher(transport).fetch(repository, source.tag(),
                                 receipts.metadataDirectory(current.registry()),
                                 ".repair-download-", progress,
                                 new VerifiedPackageFetcher.ExpectedAsset(source.assetName(),
                                         source.assetSize(), null), context)) {
                // Published digest metadata may be absent. The persisted computed digest is
                // always mandatory; it binds the downloaded compressed bytes to this copy.
                if (!source.assetSha256().equals(workspace.resolvedDigest().hex())) {
                    throw new IOException("repair archive digest differs from persisted provenance");
                }
                context.phase(OperationPhase.PLAN, "Checking official installation files");
                Inspection targetInspection = new InstallationInspector().inspect(
                        workspace.extracted());
                if (targetInspection.products().stream().noneMatch(
                        product -> product.key().equals(repository.requiredProduct()))) {
                    throw new IOException("repair archive lacks the receipt application");
                }
                OwnershipPolicy.Build target = new OwnershipPolicy().build(workspace.extracted(),
                        repository, source.tag());
                if (!source.officialManifest().equals(target.manifest())
                        || !source.excludedOfficialPaths().equals(target.excludedPaths())) {
                    throw new IOException("repair archive layout differs from persisted official "
                            + "inventory");
                }
                validateRepairTree(current.root());
                List<Decision> decisions = new ArrayList<>();
                int missing = 0;
                int modified = 0;
                for (FileEntry entry : source.officialManifest().files()) {
                    Path destination = RealUpdateFiles.resolve(current.root(), entry.path(), true);
                    String hash = RealUpdateFiles.hashIfRegular(destination);
                    Action action = hash == null ? Action.ADD
                            : hash.equals(entry.sha256()) ? Action.KEEP : Action.REPLACE;
                    if (action == Action.ADD) missing++;
                    if (action == Action.REPLACE) modified++;
                    decisions.add(new Decision(action, entry.path(), "exact-release repair"));
                }
                List<String> extraJars = OwnershipPolicy.extraLibJars(current.root(),
                        source.officialManifest(), target.manifest());
                context.phase(OperationPhase.PREPARE_INSTALL, "Checking repair destinations");
                preflight(current.root(), decisions, context);
                context.phase(OperationPhase.APPLY, "Restoring official installation files");
                ApplyResult result = transactRepair(current, workspace.extracted(),
                        targetInspection, decisions);
                context.cleanupPhase("Finishing repair and removing temporary package files");
                return new RepairResult(result.record(), missing, modified, extraJars,
                        "Rollback copies are temporary and are deleted after successful repair; "
                                + "make a separate backup of local edits before repair.");
            }
        }
    }

    public record RepairResult(InstallationRecord record, int missingRestored,
                               int modifiedRestored, List<String> extraLibJars,
                               String warning) {
        public RepairResult {
            extraLibJars = List.copyOf(extraLibJars);
        }
    }

    private static void validateRepairTree(Path root) throws IOException {
        Set<String> spellings = new HashSet<>();
        try (var walk = Files.walk(root)) {
            for (Path path : walk.skip(1).toList()) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                String folded = key(relative);
                if (OwnershipPolicy.PROTECTED_PATHS.stream().anyMatch(
                        protectedPath -> folded.equals(protectedPath)
                                || folded.startsWith(protectedPath + "/"))) {
                    continue;
                }
                if (!OwnershipPolicy.managed(relative)
                        && !folded.equals("lib") && !folded.equals("docs")
                        && !folded.equals("data") && !folded.equals("licenses")) {
                    continue;
                }
                var attrs = Files.readAttributes(path,
                        java.nio.file.attribute.BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (attrs.isSymbolicLink() || attrs.isOther()
                        || !attrs.isDirectory() && !attrs.isRegularFile()) {
                    throw new IOException("repair refuses linked/special managed-tree entry: "
                            + relative);
                }
                if (!spellings.add(folded)) {
                    throw new IOException("repair refuses case/Unicode alias: " + relative);
                }
            }
        }
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
        return apply(expected, targetTag, expectedAssetName, expectedSize, expectedDigest,
                confirmation, progress, OperationContext.none(OperationType.UPDATE_APPLY));
    }

    public ApplyResult apply(Snapshot expected, String targetTag, String expectedAssetName,
                             long expectedSize, String expectedDigest, String confirmation,
                             PrintStream progress, OperationContext context)
            throws IOException, InterruptedException, ManifestException {
        try (OperationContext.WorkerRegistration operationRegistration = context.activate()) {
            Objects.requireNonNull(expected, "expected snapshot");
            context.phase(OperationPhase.METADATA,
                    "Validating current update source and authorization");
            validateAuthorization(expected.current().tag(), targetTag, expectedSize, expectedDigest,
                    confirmation);
            try (RootCoordinator.Lease gate = coordinator.acquire(expected.root(), true)) {
                gate.requireNoPendingUpdate();
                Snapshot current = snapshot(expected.registry(), expected.record().id());
                if (!current.record().equals(expected.record())
                        || !current.receipt().equals(expected.receipt())
                        || !current.current().equals(expected.current())
                        || !current.root().equals(expected.root())) {
                    throw new IOException(
                            "selected record or verified provenance changed after consent");
                }
                OfficialRepository repository =
                        OfficialRepository.parse(current.current().repository());
                try (VerifiedPackageFetcher.Workspace workspace =
                             new VerifiedPackageFetcher(transport).fetch(repository, targetTag,
                                     receipts.metadataDirectory(current.registry()),
                                     ".apply-download-", progress,
                                     new VerifiedPackageFetcher.ExpectedAsset(
                                             expectedAssetName, expectedSize, expectedDigest),
                                     context)) {
                    context.phase(OperationPhase.PLAN,
                            "Inspecting package contents and planning local changes");
                    Inspection targetInspection = new InstallationInspector().inspect(
                            workspace.extracted());
                    if (targetInspection.products().stream().noneMatch(
                            product -> product.key().equals(repository.requiredProduct()))) {
                        throw new IOException("target package does not contain receipt application "
                                + repository.requiredProduct());
                    }
                    OwnershipPolicy.Build target = new OwnershipPolicy().build(
                            workspace.extracted(), repository, workspace.release().tag(), context,
                            OperationPhase.PLAN);
                    ManifestReader.PreviewPairValidation pair = new ManifestReader()
                            .validatePreviewPair(current.current().officialManifest(),
                                    target.manifest());
                    context.phase(OperationPhase.PLAN,
                            "Comparing local managed files with the verified target");
                    List<Decision> decisions = new UpdatePlanner().planPreview(
                            current.current().officialManifest(), target.manifest(), current.root(),
                            pair, trustedHashes(current.current()),
                            stickyPrefixes(current.current()), context, OperationPhase.PLAN);
                    validatePristineRuntime(current.root(), current.current(), target.manifest(),
                            decisions, context);
                    preflight(current.root(), decisions, context);
                    context.phase(OperationPhase.APPLY,
                            "Entering verified update transaction");
                    context.enterFinalization("The update transaction and recovery journal have "
                            + "begun; cancellation can no longer be performed safely.");
                    return transact(current, workspace.extracted(), workspace.release(),
                            workspace.asset(), workspace.resolvedDigest().hex(), target,
                            targetInspection, pair, decisions);
                }
            }
        }
    }

    ApplyResult applyPrepared(PreparedUpdate prepared, Object owner, String confirmation,
                              PrintStream progress)
            throws IOException, InterruptedException, ManifestException {
        return applyPrepared(prepared, owner, confirmation, progress,
                OperationContext.none(OperationType.UPDATE_APPLY));
    }

    ApplyResult applyPrepared(PreparedUpdate prepared, Object owner, String confirmation,
                              PrintStream progress, OperationContext context)
            throws IOException, InterruptedException, ManifestException {
        try (OperationContext.WorkerRegistration operationRegistration = context.activate()) {
            Objects.requireNonNull(prepared, "prepared update");
            UpdatePreviewService.Preview preview = prepared.capturedPreview();
            validateAuthorization(prepared.source().current().tag(), preview.targetRelease().tag(),
                    preview.targetAsset().size(), preview.resolvedAssetDigest(), confirmation);
            prepared.claim(owner);

            ApplyResult result;
            try {
                result = applyPreparedClaimed(prepared, progress, context);
            } catch (IOException | InterruptedException | ManifestException | RuntimeException
                     | Error failure) {
                context.cleanupPhase(
                        "Discarding temporary package data; recovery data is retained");
                IOException cleanup = prepared.finishClaim();
                if (cleanup != null) failure.addSuppressed(cleanup);
                throw failure;
            }
            context.cleanupPhase("Removing temporary package data");
            IOException cleanup = prepared.finishClaim();
            if (cleanup == null) return result;
            return new ApplyResult(result.record(), result.state(), result.decisions(),
                    result.skippedDecisions(), result.retainedOverrides(),
                    "Update completed, but temporary package cleanup failed: " + detail(cleanup)
                            + ". The installation is updated; do not retry Apply.");
        }
    }

    private ApplyResult applyPreparedClaimed(PreparedUpdate prepared, PrintStream progress,
                                             OperationContext context)
            throws IOException, InterruptedException, ManifestException {
        context.phase(OperationPhase.PREPARE_INSTALL,
                "Revalidating the retained package and selected installation");
        Snapshot expected = prepared.source();
        UpdatePreviewService.Preview preview = prepared.capturedPreview();
        try (RootCoordinator.Lease gate = coordinator.acquire(expected.root(), true)) {
            gate.requireNoPendingUpdate();
            Snapshot current = snapshot(expected.registry(), expected.record().id());
            if (!current.record().equals(expected.record())
                    || !current.receipt().equals(expected.receipt())
                    || !current.current().equals(expected.current())
                    || !current.root().equals(expected.root())) {
                throw new IOException("selected record or verified provenance changed; restart "
                        + "the update attempt");
            }
            ChannelUpdateChecker.Recommendation recommendation = prepared.recommendation();
            if (recommendation != null) {
                ChannelUpdateChecker.Result fresh =
                        new ChannelUpdateChecker(current.registry(), transport)
                                .check(current.record().id());
                if (!fresh.updateAvailable()
                        || !sameRecommendationExceptDigest(
                        recommendation, fresh.recommendation())
                        || !java.util.Objects.equals(
                        prepared.workspace().asset().digest(),
                        fresh.recommendation().assetDigest())) {
                    throw new IOException("channel source, preference, or target metadata changed; "
                            + "check again and restart the update attempt");
                }
            }
            OfficialRepository repository =
                    OfficialRepository.parse(current.current().repository());
            VerifiedPackageFetcher.ExpectedAsset expectedAsset =
                    new VerifiedPackageFetcher.ExpectedAsset(preview.targetAsset().name(),
                            preview.targetAsset().size(), preview.targetAsset().digest());
            Path extracted = new VerifiedPackageFetcher(transport).revalidateAndExtract(
                    repository, preview.targetRelease().tag(), prepared.workspace(),
                    expectedAsset, progress, context);

            Inspection inspection = new InstallationInspector().inspect(extracted);
            if (inspection.products().stream().noneMatch(
                    product -> product.key().equals(repository.requiredProduct()))) {
                throw new IOException("retained target package no longer contains receipt "
                        + "application " + repository.requiredProduct());
            }
            if (!inspection.observedBuild().equals(prepared.targetInspection().observedBuild())
                    || !inspection.products().equals(prepared.targetInspection().products())
                    || !inspection.confidence().equals(prepared.targetInspection().confidence())) {
                throw new IOException("fresh extraction differs from the statically inspected "
                        + "target; restart the update attempt");
            }
            OwnershipPolicy.Build rebuilt = new OwnershipPolicy().build(extracted, repository,
                    preview.targetRelease().tag(), context, OperationPhase.PREPARE_INSTALL);
            if (!rebuilt.equals(prepared.target())) {
                throw new IOException("fresh extraction differs from the trusted prepared "
                        + "manifest; restart the update attempt");
            }
            ManifestReader.PreviewPairValidation pair = new ManifestReader()
                    .validatePreviewPair(current.current().officialManifest(),
                            prepared.target().manifest());
            if (!pair.equals(prepared.pair())) {
                throw new IOException("prepared target manifest validation changed; restart the "
                        + "update attempt");
            }
            context.phase(OperationPhase.PREPARE_INSTALL,
                    "Replanning local managed files before finalization");
            List<Decision> decisions = new UpdatePlanner().planPreview(
                    current.current().officialManifest(), prepared.target().manifest(),
                    current.root(), pair, trustedHashes(current.current()),
                    stickyPrefixes(current.current()), context,
                    OperationPhase.PREPARE_INSTALL);
            validatePristineRuntime(current.root(), current.current(),
                    prepared.target().manifest(), decisions, context);
            preflight(current.root(), decisions, context);
            context.phase(OperationPhase.APPLY, "Entering verified update transaction");
            context.enterFinalization("The update transaction and recovery journal have begun; "
                    + "cancellation can no longer be performed safely.");
            return transact(current, extracted, preview.targetRelease(), preview.targetAsset(),
                    prepared.workspace().resolvedDigest().hex(), prepared.target(), inspection,
                    pair, decisions);
        }
    }

    public RecoveryResult recover(Path registry, String id, String confirmation)
            throws IOException, ManifestException {
        try {
            return recover(registry, id, confirmation,
                    OperationContext.none(OperationType.RECOVERY));
        } catch (InterruptedException impossible) {
            Thread.currentThread().interrupt();
            throw new IOException("recovery was interrupted before it began", impossible);
        }
    }

    public RecoveryResult recover(Path registry, String id, String confirmation,
                                  OperationContext context)
            throws IOException, ManifestException, InterruptedException {
        try (OperationContext.WorkerRegistration operationRegistration = context.activate()) {
            return recoverActivated(registry, id, confirmation, context);
        }
    }

    private RecoveryResult recoverActivated(Path registry, String id, String confirmation,
                                            OperationContext context)
            throws IOException, ManifestException, InterruptedException {
        if (!CONFIRM.equals(confirmation)) {
            throw new IOException("recovery confirmation must be exactly " + CONFIRM);
        }
        context.phase(OperationPhase.RECOVER, "Starting verified update recovery");
        context.enterFinalization("Recovery replay and cleanup have begun; cancellation is disabled.");
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

    private ApplyResult transact(Snapshot snapshot, Path extracted,
                                 ReleaseCatalog.Release release, ReleaseCatalog.Asset asset,
                                 String resolvedDigest,
                                 OwnershipPolicy.Build target, Inspection targetInspection,
                                 ManifestReader.PreviewPairValidation pair,
                                 List<Decision> decisions)
            throws IOException, ManifestException {
        return transactInternal(snapshot, extracted, target, targetInspection, decisions,
                false, release, asset, resolvedDigest, pair);
    }

    private ApplyResult transactRepair(Snapshot snapshot, Path extracted,
                                       Inspection targetInspection, List<Decision> decisions)
            throws IOException, ManifestException {
        return transactInternal(snapshot, extracted,
                new OwnershipPolicy.Build(snapshot.current().officialManifest(),
                        snapshot.current().excludedOfficialPaths()),
                targetInspection, decisions, true, null, null, null, null);
    }

    private ApplyResult transactInternal(Snapshot snapshot, Path extracted,
                                         OwnershipPolicy.Build target,
                                         Inspection targetInspection, List<Decision> decisions,
                                         boolean repair, ReleaseCatalog.Release release,
                                         ReleaseCatalog.Asset asset, String resolvedDigest,
                                         ManifestReader.PreviewPairValidation pair)
            throws IOException, ManifestException {
        String transactionId = UUID.randomUUID().toString();
        CurrentUpdateState next = repair
                ? repairState(snapshot.current(), transactionId)
                : nextState(snapshot.current(), target, release, asset,
                        resolvedDigest, pair, decisions, transactionId);
        Path namespace = createNamespace(snapshot, transactionId);
        Path transaction = transaction(namespace, transactionId);
        Path pendingFile = namespace.resolve("pending.json");
        boolean previousPublished = states.isPublished(snapshot.registry(), snapshot.record().id());
        List<String> createdDirectories = missingParents(snapshot.root(), decisions);
        List<RealUpdateJournal.Operation> operations = operations(snapshot.root(), decisions,
                target.manifest(), transactionId);
        RealUpdateJournal journal = new RealUpdateJournal(repair ? REPAIR_JOURNAL_SCHEMA
                : JOURNAL_SCHEMA, transactionId,
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
        stageAndBackup(snapshot.root(), extracted, target.manifest(), transaction,
                operations);
        failureHook.hit("AFTER_STAGING");
        verifyArtifacts(snapshot.root(), transaction, operations);
        verifyAtomicStorage(transaction);
        if (repair) {
            // No application mutation until every captured destination still matches the
            // journal. Per-operation rechecks below protect against subsequent external edits.
            for (RealUpdateJournal.Operation operation : operations) {
                if (!Objects.equals(operation.beforeHash(), RealUpdateFiles.hashIfRegular(
                        RealUpdateFiles.resolve(snapshot.root(), operation.path(), true)))) {
                    throw new ManifestException("repair destination changed after backup: "
                            + operation.path());
                }
            }
        }
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

    private static CurrentUpdateState repairState(CurrentUpdateState state, String id) {
        Set<String> restored = index(state.officialManifest()).keySet();
        return new CurrentUpdateState(state.schemaVersion(), state.ownershipPolicyVersion(),
                state.installationId(), state.canonicalRoot(), state.registeredAt(),
                state.repository(), state.tag(), state.assetName(), state.assetSize(),
                state.assetSha256(), state.officialManifest(), state.excludedOfficialPaths(),
                state.overrides().stream().filter(override ->
                        !restored.contains(key(override.path()))).toList(),
                state.caseOverrides(), id);
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
        // Check every required backup before touching any application file. In particular,
        // modified official runtime bytes have no manifest hash to reconstruct them from.
        for (RealUpdateJournal.Operation operation : reverse) {
            if (operation.beforeHash() == null) continue;
            String current = RealUpdateFiles.hashIfRegular(
                    RealUpdateFiles.resolve(root, operation.path(), true));
            if (Objects.equals(current, operation.beforeHash())) continue;
            Path backup = internal(transaction, "backups", operation.path());
            if (!Files.exists(backup, LinkOption.NOFOLLOW_LINKS)
                    || !operation.beforeHash().equals(RealUpdateFiles.hash(backup))) {
                throw new ManifestException("valid backup unavailable for " + operation.path());
            }
        }
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
                && journal.schemaVersion() != REPAIR_JOURNAL_SCHEMA
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
        boolean repair = journal.schemaVersion() == REPAIR_JOURNAL_SCHEMA;
        if (repair && (!journal.nextState().equals(repairState(journal.previousState(),
                journal.transactionId())))) {
            throw new IOException("repair journal changed exact-release provenance");
        }
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
            if (repair && (oldEntry == null || newEntry == null
                    || !oldEntry.path().equals(operation.path())
                    || !newEntry.path().equals(operation.path())
                    || !Set.of("ADD", "REPLACE").contains(operation.action()))) {
                throw new IOException("repair journal operation is not an exact official path");
            }
            if (oldEntry == null && !"ADD".equals(operation.action())
                    || newEntry == null && !"REMOVE".equals(operation.action())
                    || newEntry != null && "REMOVE".equals(operation.action())
                    || newEntry != null && operation.afterHash() == null
                    || newEntry != null && !newEntry.sha256().equals(operation.afterHash())
                    || operation.beforeHash() == null && !"ADD".equals(operation.action())
                    || operation.beforeHash() != null && "ADD".equals(operation.action())
                    || oldEntry == null && operation.beforeHash() != null
                    || operation.beforeHash() != null
                    && !operation.beforeHash().matches("[0-9a-f]{64}")
                    || operation.beforeHash() != null && oldEntry != null
                    && !operation.beforeHash().equals(oldEntry.sha256())
                    && !trusted.getOrDefault(operationKey, Set.of())
                    .contains(operation.beforeHash())
                    && !OwnershipPolicy.runtimePath(operation.path()) && !repair) {
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
            if (repair && (decision.path() == null || !before.containsKey(key(decision.path()))
                    || !before.get(key(decision.path())).path().equals(decision.path())
                    || !Set.of(Action.ADD, Action.REPLACE, Action.KEEP)
                    .contains(decision.action()))) {
                throw new IOException("repair journal plan is not exact-release restoration");
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
        if (repair) {
            for (Decision decision : journal.decisions()) {
                RealUpdateJournal.Operation operation = operations.get(key(decision.path()));
                if (decision.action() == Action.KEEP && operation != null
                        || decision.action() == Action.ADD && operation != null
                        && operation.beforeHash() != null
                        || decision.action() == Action.REPLACE && operation != null
                        && operation.beforeHash() == null) {
                    throw new IOException("repair journal action and backup disagree");
                }
            }
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
                                         List<Decision> decisions, OperationContext context)
            throws IOException, ManifestException, InterruptedException {
        for (Decision decision : decisions) {
            context.checkpoint();
            if (decision.action() == Action.SKIP && OwnershipPolicy.runtimePath(decision.path())) {
                throw new IOException("entire update refused: runtime customization/conflict at "
                        + decision.path());
            }
        }
        Map<String, FileEntry> old = index(current.officialManifest());
        Map<String, FileEntry> after = index(target);
        try (var walk = Files.walk(root)) {
            var iterator = walk.skip(1).iterator();
            while (iterator.hasNext()) {
                context.checkpoint();
                Path path = iterator.next();
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
                FileEntry oldEntry = old.get(folded);
                FileEntry newEntry = after.get(folded);
                if (oldEntry == null && newEntry == null
                        && portable.toLowerCase(Locale.ROOT).startsWith("lib/")
                        && portable.toLowerCase(Locale.ROOT).endsWith(".jar")) {
                    // Unowned mod JARs are not in the transaction allowlist. They may affect
                    // the game, but must never be removed by an ordinary update.
                    continue;
                }
                String hash = RealUpdateFiles.hash(path, context);
                if (oldEntry == null && newEntry == null
                        || oldEntry == null && !hash.equals(newEntry.sha256())
                        || oldEntry != null && newEntry != null
                        && !oldEntry.path().equals(portable) && !newEntry.path().equals(portable)) {
                    throw new IOException("entire update refused: unrecognized/customized runtime "
                            + portable);
                }
            }
        }
        // Missing official runtime files are ordinary ADD operations. The planner and
        // transaction journal still bind each destination to its manifest path.
    }

    private void preflight(Path root, List<Decision> decisions, OperationContext context)
            throws IOException, InterruptedException {
        if (!Files.isWritable(root)) throw new IOException("application root is not writable");
        FileStore rootStore = Files.getFileStore(root);
        for (Decision decision : decisions) {
            context.checkpoint();
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
                                         ReleaseCatalog.Release release,
                                         ReleaseCatalog.Asset asset,
                                         String resolvedDigest,
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
                previous.repository(), release.tag(), asset.name(), asset.size(),
                resolvedDigest,
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
        if (!RealUpdateFiles.emptyDirectory(transactions)) {
            throw new IOException("unknown update transactions appeared during cleanup");
        }
        Files.delete(transactions);
        if (!RealUpdateFiles.emptyDirectory(namespace)) {
            throw new IOException("unknown update namespace artifacts appeared during cleanup");
        }
        Files.delete(namespace);
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
                || digest != null && !DIGEST.matcher(digest).matches()) {
            throw new IOException("consent must bind the exact target byte size and any "
                    + "published SHA-256");
        }
    }

    private static boolean sameRecommendationExceptDigest(
            ChannelUpdateChecker.Recommendation first,
            ChannelUpdateChecker.Recommendation second) {
        return second != null
                && first.installationId().equals(second.installationId())
                && first.canonicalRoot().equals(second.canonicalRoot())
                && first.registeredAt().equals(second.registeredAt())
                && first.preference().equals(second.preference())
                && first.repository() == second.repository()
                && first.source().equals(second.source())
                && first.targetTag().equals(second.targetTag())
                && first.assetName().equals(second.assetName())
                && first.assetSize() == second.assetSize()
                && first.notesUrl().equals(second.notesUrl());
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
                              long retainedOverrides, String cleanupWarning) {
        public ApplyResult(InstallationRecord record, CurrentUpdateState state,
                           List<Decision> decisions, long skippedDecisions,
                           long retainedOverrides) {
            this(record, state, decisions, skippedDecisions, retainedOverrides, null);
        }
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
