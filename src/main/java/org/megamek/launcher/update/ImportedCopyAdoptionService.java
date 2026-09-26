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

import org.megamek.launcher.channel.ChannelPreference;
import org.megamek.launcher.channel.ChannelPreferenceStore;
import org.megamek.launcher.channel.FollowChannel;
import org.megamek.launcher.manifest.FileEntry;
import org.megamek.launcher.manifest.ManifestException;
import org.megamek.launcher.onboarding.Inspection;
import org.megamek.launcher.onboarding.InstallationInspector;
import org.megamek.launcher.onboarding.Product;
import org.megamek.launcher.operation.OperationContext;
import org.megamek.launcher.operation.OperationPhase;
import org.megamek.launcher.operation.OperationType;
import org.megamek.launcher.operation.ProgressUnit;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryData;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.release.OfficialRepository;
import org.megamek.launcher.release.ReleaseCatalog;
import org.megamek.launcher.release.ReleaseTransport;
import org.megamek.launcher.release.VerifiedPackageFetcher;
import org.megamek.launcher.sandbox.OverrideEntry;

import java.awt.EventQueue;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Verifies whether an imported launch-only copy has an exact official ancestor, then publishes
 * only launcher-owned metadata. No method in this service writes beneath an application root.
 */
public final class ImportedCopyAdoptionService {
    private static final Pattern CREDIBLE_VERSION =
            Pattern.compile("[0-9]+\\.[0-9]+(?:\\.[0-9]+)?(?:[-.][A-Za-z0-9.-]+)?");
    private static final int DIAGNOSTIC_PATH_LIMIT = 40;
    public static final String AUTOMATIC_MATCH_UNAVAILABLE =
            "We couldn’t find the matching official version. "
                    + "This copy will remain launch-only.";

    private final Path registry;
    private final ReleaseTransport transport;
    private final RegistryStore registries;
    private final ReceiptStore receipts;
    private final CurrentStateStore states;
    private final ChannelPreferenceStore channels;
    private final AdoptionStateStore adoptions;
    private final InstallationInspector inspector;
    private final org.megamek.launcher.launch.RootCoordinator coordinator;
    private final FailureHook failureHook;
    private final Object owner = new Object();
    private final Set<String> active = ConcurrentHashMap.newKeySet();

    public ImportedCopyAdoptionService(
            Path registry, ReleaseTransport transport,
            org.megamek.launcher.launch.RootCoordinator coordinator) {
        this(registry, transport, new RegistryStore(), new ReceiptStore(),
                new ChannelPreferenceStore(), new InstallationInspector(), coordinator,
                point -> {});
    }

    ImportedCopyAdoptionService(
            Path registry, ReleaseTransport transport, RegistryStore registries,
            ReceiptStore receipts, ChannelPreferenceStore channels,
            InstallationInspector inspector,
            org.megamek.launcher.launch.RootCoordinator coordinator,
            FailureHook failureHook) {
        this.registry = registry.toAbsolutePath().normalize();
        this.transport = Objects.requireNonNull(transport);
        this.registries = Objects.requireNonNull(registries);
        this.receipts = Objects.requireNonNull(receipts);
        this.states = new CurrentStateStore(receipts);
        this.channels = Objects.requireNonNull(channels);
        this.adoptions = new AdoptionStateStore(receipts);
        this.inspector = Objects.requireNonNull(inspector);
        this.coordinator = Objects.requireNonNull(coordinator);
        this.failureHook = Objects.requireNonNull(failureHook);
    }

    /**
     * Static suggestion only. The observed version and product choose what to try; they never
     * authorize publication.
     */
    public Suggestion suggest(InstallationRecord record) throws IOException {
        Objects.requireNonNull(record, "record");
        OfficialRepository repository = repositoryFor(record.products());
        String version = record.observedBuild();
        String tag = version != null && CREDIBLE_VERSION.matcher(version).matches()
                ? "v" + version : null;
        return new Suggestion(repository, tag, displayProducts(record.products()),
                version == null || version.isBlank() ? "Unknown" : version);
    }

    /**
     * Resolves the observed build from bounded list metadata, then enters the ordinary exact-tag
     * preparation path. The exact endpoint is therefore used only after one list candidate exists.
     */
    public PreparedAdoption prepareAutomatically(
            InstallationRecord expected, OfficialRepository requestedRepository,
            FollowChannel fixedChannel, PrintStream diagnostics, OperationContext context)
            throws IOException, InterruptedException, ManifestException {
        String exactTag = resolveAutomaticTag(expected, requestedRepository,
                fixedChannel, diagnostics, context);
        return prepare(expected, requestedRepository, exactTag, fixedChannel,
                diagnostics, context);
    }

    private String resolveAutomaticTag(
            InstallationRecord expected, OfficialRepository requestedRepository,
            FollowChannel fixedChannel, PrintStream diagnostics, OperationContext context)
            throws IOException, InterruptedException {
        requireWorkerThread();
        if (expected == null) throw new IOException("selected imported copy is required");
        requireChannel(fixedChannel);
        Objects.requireNonNull(diagnostics, "diagnostics");
        Objects.requireNonNull(context, "context");
        try (OperationContext.WorkerRegistration ignored = context.activate()) {
            context.phase(OperationPhase.METADATA,
                    "Finding the matching official version");
            RegistryData data = registries.read(registry);
            InstallationRecord record = requireUnchangedRecord(data, expected);
            Path root = requireMatchingRepository(data, record, requestedRepository);
            try (var gate = coordinator.acquire(root, false)) {
                gate.requireNoPendingUpdate();
                requireRecognizedImport(inspector.inspect(root), record);
            }
            AutomaticAdoptionResolver.Resolution resolution =
                    new AutomaticAdoptionResolver(transport).resolve(requestedRepository,
                            record.observedBuild(), fixedChannel, diagnostics, context);
            if (resolution.matched()) return resolution.release().tag();
            if (resolution.status()
                    == AutomaticAdoptionResolver.Status.CHANNEL_MISMATCH) {
                AutomaticAdoptionResolver.ChannelMismatch mismatch =
                        resolution.channelMismatch();
                throw new ChannelMismatchException(mismatch.currentVersion(),
                        mismatch.selectedChannel(), mismatch.requiredChannel());
            }
            Throwable technical = resolution.failure() == null
                    ? new IOException(resolution.diagnostic())
                    : resolution.failure();
            throw new CandidateResolutionException(AUTOMATIC_MATCH_UNAVAILABLE, technical);
        }
    }

    /** Classifies local sidecar presence without contacting any release service. */
    public Availability availability(RegistryData data, InstallationRecord record,
                                     boolean pending) {
        try {
            if (pending) return Availability.INCOMPLETE;
            Path receipt = receipts.receiptPath(registry, record.id());
            Path current = states.path(registry, record.id());
            Path channel = channels.path(registry, record.id());
            Path adoption = adoptions.path(registry, record.id());
            boolean receiptPresent = exists(receipt);
            boolean currentPresent = exists(current);
            boolean channelPresent = exists(channel);
            boolean adoptionPresent = exists(adoption);
            boolean staging = exists(current.resolveSibling(current.getFileName() + ".new"))
                    || exists(channel.resolveSibling(channel.getFileName() + ".new"))
                    || exists(adoption.resolveSibling(adoption.getFileName() + ".new"))
                    || hasReceiptStaging(receipt);
            boolean orphanLock =
                    exists(receipt.resolveSibling(receipt.getFileName() + ".lock"))
                    || exists(channel.resolveSibling(channel.getFileName() + ".lock"));
            if (!receiptPresent && !currentPresent && !channelPresent
                    && !adoptionPresent && !staging && !orphanLock) {
                return Availability.TRUE_IMPORTED;
            }
            if (!receiptPresent || !channelPresent || staging) {
                return Availability.INCOMPLETE;
            }
            OwnershipReceipt ownership = receipts.read(registry, data, record);
            CurrentUpdateState latest = states.read(registry, data, record, ownership);
            ChannelPreferenceStore.ReadResult fixed = channels.read(registry, data, record);
            if (fixed.status() != ChannelPreferenceStore.Status.CONFIGURED
                    || fixed.preference() == null) {
                return Availability.INCOMPLETE;
            }
            adoptions.validateForUse(registry, record, ownership, latest,
                    fixed.preference());
            return Availability.MANAGED;
        } catch (IOException | RuntimeException error) {
            return Availability.INCOMPLETE;
        }
    }

    public PreparedAdoption prepare(InstallationRecord expected,
                                    OfficialRepository requestedRepository,
                                    String exactTag, FollowChannel fixedChannel,
                                    PrintStream diagnostics, OperationContext context)
            throws IOException, InterruptedException, ManifestException {
        requireWorkerThread();
        if (expected == null) throw new IOException("selected imported copy is required");
        requireChannel(fixedChannel);
        Objects.requireNonNull(diagnostics, "diagnostics");
        Objects.requireNonNull(context, "context");
        if (!active.add(expected.id())) {
            throw new IOException("another adoption attempt is already active for this copy");
        }
        VerifiedPackageFetcher.Workspace workspace = null;
        AttemptDirectory attemptDirectory = null;
        boolean retained = false;
        Throwable preparationFailure = null;
        try (OperationContext.WorkerRegistration ignored = context.activate()) {
            context.phase(OperationPhase.METADATA,
                    "Checking that this copy is ready for verification");
            RegistryData before = registries.read(registry);
            InstallationRecord record = requireUnchangedRecord(before, expected);
            Path root = requireMatchingRepository(before, record, requestedRepository);
            OfficialRepository repository = requestedRepository;
            RootSnapshot local;
            try (var gate = coordinator.acquire(root, false)) {
                gate.requireNoPendingUpdate();
                context.checkpoint();
                requireRecognizedImport(inspector.inspect(root), record);
                local = RootSnapshot.capture(root, context, OperationPhase.METADATA,
                        RootSnapshot.Scan.INITIAL_IMPORTED_COPY);
            }
            Path workspaceParent = requireWorkspaceParent(before);
            attemptDirectory = AttemptDirectory.create(workspaceParent);
            requireNoRootOverlap(attemptDirectory.root(), before);

            context.phase(OperationPhase.METADATA,
                    "Finding the exact official release");
            ReleaseCatalog catalog = new ReleaseCatalog(transport);
            ReleaseCatalog.Release release;
            try {
                release = catalog.exact(repository, exactTag);
            } catch (IOException error) {
                throw new CandidateResolutionException(AUTOMATIC_MATCH_UNAVAILABLE, error);
            }
            ReleaseCatalog.Assessment assessment = catalog.assess(repository, release);
            if (!assessment.eligible()) {
                throw new CandidateResolutionException(AUTOMATIC_MATCH_UNAVAILABLE,
                        new IOException("selected exact release is no longer eligible: "
                                + assessment.reason()));
            }
            ReleaseCatalog.Asset asset = assessment.asset();
            VerifiedPackageFetcher.ExpectedAsset expectedAsset =
                    new VerifiedPackageFetcher.ExpectedAsset(asset.name(), asset.size(),
                            asset.digest(), asset.url());
            try {
                workspace = new VerifiedPackageFetcher(transport).fetch(
                        repository, release.tag(), attemptDirectory.root(), "package-",
                        diagnostics, expectedAsset, context);
            } catch (IOException error) {
                throw new CandidateResolutionException(
                        AUTOMATIC_MATCH_UNAVAILABLE, error);
            }
            context.phase(OperationPhase.PLAN,
                    "Comparing the existing copy with the verified official package");
            Inspection officialInspection = inspector.inspect(workspace.extracted());
            RootSnapshot officialSnapshot = RootSnapshot.capture(
                    workspace.extracted(), context, OperationPhase.PLAN,
                    RootSnapshot.Scan.OFFICIAL_PACKAGE);
            OwnershipPolicy.Build ownership = new OwnershipPolicy().build(
                    officialSnapshot, repository, release.tag());
            Comparison comparison = compare(record, officialInspection, ownership,
                    officialSnapshot, local);
            logComparison(diagnostics, context, comparison);
            String application = displayProducts(record.products());
            String version = displayVersion(record.observedBuild());
            PreparedAdoption.Report report = new PreparedAdoption.Report(comparison.eligible(),
                    application, version, fixedChannel, comparison.reason());
            VerifiedPackageFetcher.Workspace retainedWorkspace = workspace;
            AttemptDirectory retainedDirectory = attemptDirectory;
            PreparedAdoption prepared = new PreparedAdoption(owner, record, repository,
                    fixedChannel, release, asset, retainedWorkspace, ownership,
                    officialInspection, officialSnapshot, local, comparison, report, () -> {
                try {
                    IOException cleanupFailure =
                            cleanupAttempt(retainedWorkspace, retainedDirectory);
                    if (cleanupFailure != null) throw cleanupFailure;
                } finally {
                    active.remove(record.id());
                }
            });
            retained = true;
            return prepared;
        } catch (IOException | InterruptedException | ManifestException | RuntimeException error) {
            preparationFailure = error;
            throw error;
        } finally {
            if (!retained) {
                active.remove(expected.id());
                IOException cleanupFailure = cleanupAttempt(workspace, attemptDirectory);
                if (cleanupFailure != null) {
                    if (preparationFailure != null) {
                        preparationFailure.addSuppressed(cleanupFailure);
                        diagnostics.println("ADOPTION preparation; temporary cleanup warning: "
                                + detail(cleanupFailure));
                    } else {
                        throw cleanupFailure;
                    }
                }
            }
        }
    }

    private IOException cleanupAttempt(VerifiedPackageFetcher.Workspace workspace,
                                       AttemptDirectory attemptDirectory) {
        IOException failure = null;
        boolean owned = true;
        if (attemptDirectory != null) {
            try {
                owned = attemptDirectory.stillOwned();
            } catch (IOException | RuntimeException error) {
                failure = cleanupError(error);
                owned = false;
            }
        }
        // Workspace.close resolves its nested staging directory by path. Check the parent
        // identity first so a replaced attempt root cannot redirect that cleanup elsewhere.
        if (workspace != null && owned) {
            try {
                workspace.close();
            } catch (IOException | RuntimeException error) {
                failure = add(failure, cleanupError(error));
            }
        }
        if (attemptDirectory != null && owned) {
            try {
                attemptDirectory.delete();
            } catch (IOException | RuntimeException error) {
                failure = add(failure, cleanupError(error));
            }
            try {
                failureHook.hit("AFTER_ATTEMPT_CLEANUP");
            } catch (IOException | RuntimeException error) {
                failure = add(failure, cleanupError(error));
            }
        }
        return failure;
    }

    private static IOException cleanupError(Exception error) {
        return error instanceof IOException io ? io
                : new IOException("adoption attempt cleanup failed", error);
    }

    /**
     * Captures only the newly created, private attempt root. Refuse replacement of that root
     * or a linked ancestor; the existing update-owned tree deleter checks every child without
     * following links. Neither the workspace parent nor an installation root is a cleanup target.
     */
    private record AttemptDirectory(Path root, Object identity, Path marker) {
        static AttemptDirectory create(Path parent) throws IOException {
            Path root = Files.createTempDirectory(parent, ".adoption-attempt-");
            StrictPathSafety.requireDirectory(root, "adoption attempt directory");
            Object identity = Files.readAttributes(root, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS).fileKey();
            // Some filesystems do not supply fileKey. A private marker also prevents an
            // ordinary replacement directory at the same pathname from being claimed.
            Path marker = Files.createTempFile(root, ".adoption-owner-", ".marker");
            return new AttemptDirectory(root, identity, marker);
        }

        boolean stillOwned() throws IOException {
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return false;
            StrictPathSafety.requireDirectory(root, "adoption attempt directory");
            Object current = Files.readAttributes(root, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS).fileKey();
            if (identity != null && !identity.equals(current)) {
                throw new IOException("adoption attempt directory was replaced: " + root);
            }
            if (!Files.isRegularFile(marker, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("adoption attempt ownership marker is missing: " + root);
            }
            return true;
        }

        void delete() throws IOException {
            if (!stillOwned()) return;
            RealUpdateFiles.deleteOwnedTree(root);
        }
    }

    public CommitResult commit(PreparedAdoption prepared)
            throws IOException, InterruptedException, ManifestException {
        return commit(prepared, OperationContext.none(OperationType.ADOPT_EXISTING),
                System.out);
    }

    public CommitResult commit(PreparedAdoption prepared, OperationContext context,
                               PrintStream diagnostics)
            throws IOException, InterruptedException, ManifestException {
        requireWorkerThread();
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(diagnostics, "diagnostics");
        prepared.claim(owner);
        boolean published = false;
        Throwable commitFailure = null;
        try (OperationContext.WorkerRegistration ignored = context.activate()) {
            if (!prepared.comparison().eligible()) {
                throw new IOException("this copy was not eligible for managed updates");
            }
            InstallationRecord expected = prepared.record();
            Path root = Path.of(expected.canonicalRoot()).toAbsolutePath().normalize();
            context.phase(OperationPhase.PREPARE_INSTALL,
                    "Revalidating local imported-copy state before publication");
            try (var gate = coordinator.acquire(root, false)) {
                gate.requireNoPendingUpdate();
                RegistryData currentRegistry = registries.read(registry);
                InstallationRecord current = requireUnchangedRecord(currentRegistry, expected);
                requireTrueImported(currentRegistry, current);
                RootSnapshot finalLocal = RootSnapshot.capture(
                        root, context, OperationPhase.PREPARE_INSTALL,
                        RootSnapshot.Scan.FINAL_IMPORTED_COPY);
                if (!prepared.localSnapshot().equals(finalLocal)) {
                    throw new IOException(
                            "the imported copy changed during download or verification");
                }

                context.checkpoint();
                context.enterFinalization("Managed-update metadata publication has begun; "
                        + "cancellation can no longer be performed safely.");
                context.phase(OperationPhase.APPLY,
                        "Publishing managed-update metadata outside the application folder");
                publish(currentRegistry, current, prepared,
                        prepared.ownership(), prepared.comparison());
                published = true;
            }
            return new CommitResult(prepared.record(), prepared.channel(), null);
        } catch (IOException | InterruptedException | ManifestException | RuntimeException error) {
            if (published) {
                diagnostics.println("ADOPTION enabled; final coordination warning: "
                        + detail(error));
                return new CommitResult(prepared.record(), prepared.channel(), detail(error));
            }
            commitFailure = error;
            throw error;
        } finally {
            IOException cleanupFailure = prepared.finishClaim();
            if (!published && cleanupFailure != null) {
                if (commitFailure != null) {
                    commitFailure.addSuppressed(cleanupFailure);
                } else {
                    throw cleanupFailure;
                }
            }
            if (published && cleanupFailure != null) {
                diagnostics.println("ADOPTION enabled; temporary cleanup warning: "
                        + detail(cleanupFailure));
            }
        }
    }

    private void publish(RegistryData data, InstallationRecord record,
                         PreparedAdoption prepared, OwnershipPolicy.Build ownership,
                         Comparison comparison) throws IOException {
        String attemptId = UUID.nameUUIDFromBytes(
                ("adoption:" + UUID.randomUUID()).getBytes(
                        java.nio.charset.StandardCharsets.UTF_8)).toString();
        boolean receiptPublished = false;
        boolean currentPublished = false;
        boolean markerPublished = false;
        OwnershipReceipt receipt = null;
        CurrentUpdateState current = null;
        AdoptionStateStore.Marker marker = null;
        ChannelPreference preference = new ChannelPreference(ChannelPreferenceStore.SCHEMA,
                record.id(), record.canonicalRoot(), record.registeredAt(),
                prepared.channel(), true);
        try {
            receipt = receipts.write(registry, data, record, prepared.repository(),
                    prepared.release().tag(), prepared.asset().name(),
                    prepared.asset().size(),
                    prepared.workspace().resolvedDigest().canonical(), ownership);
            receiptPublished = true;
            failureHook.hit("AFTER_RECEIPT");

            List<OverrideEntry> overrides = comparison.modified().stream()
                    .map(entry -> new OverrideEntry(entry.path(), OverrideEntry.Kind.MODIFIED,
                            List.of(entry.officialSha256())))
                    .toList();
            current = new CurrentUpdateState(CurrentStateStore.SCHEMA,
                    OwnershipPolicy.VERSION, record.id(), record.canonicalRoot(),
                    record.registeredAt(), prepared.repository().key(),
                    prepared.release().tag(), prepared.asset().name(),
                    prepared.asset().size(),
                    prepared.workspace().resolvedDigest().hex(),
                    ownership.manifest(), ownership.excludedPaths(), overrides,
                    comparison.caseOverrides(),
                    attemptId);
            states.write(registry, record, receipt, current);
            currentPublished = true;
            failureHook.hit("AFTER_CURRENT");

            marker = adoptions.write(registry, record, receipt, current, preference, attemptId);
            markerPublished = true;
            failureHook.hit("AFTER_MARKER");

            // The channel is the final visibility/authorization barrier. Existing update
            // services require it, so receipt/current/marker prefixes never appear managed.
            channels.initializeManaged(registry, record, receipt, prepared.channel(), true);
        } catch (IOException | RuntimeException error) {
            IOException rollback = rollbackPublication(record, preference, receipt, current,
                    marker, receiptPublished, currentPublished, markerPublished);
            if (rollback != null) error.addSuppressed(rollback);
            if (error instanceof IOException io) throw io;
            throw new IOException("managed-update metadata publication failed", error);
        }
    }

    private IOException rollbackPublication(
            InstallationRecord record, ChannelPreference preference, OwnershipReceipt receipt,
            CurrentUpdateState current, AdoptionStateStore.Marker marker,
            boolean receiptPublished, boolean currentPublished, boolean markerPublished) {
        IOException failure = null;
        try {
            channels.rollbackInitialization(registry, record, preference);
        } catch (IOException error) {
            failure = add(failure, error);
        }
        if (markerPublished) {
            try {
                adoptions.rollback(registry, record.id(), marker);
            } catch (IOException error) {
                failure = add(failure, error);
            }
        }
        if (currentPublished) {
            try {
                states.rollbackPublication(registry, record.id(), false, current);
            } catch (IOException error) {
                failure = add(failure, error);
            }
        }
        if (receiptPublished) {
            try {
                receipts.rollbackPublication(registry, record, receipt);
            } catch (IOException error) {
                failure = add(failure, error);
            }
        }
        try {
            cleanupAttemptArtifacts(record.id());
        } catch (IOException error) {
            failure = add(failure, error);
        }
        return failure;
    }

    /**
     * The single implementation of "is this still the exact registered installation the caller
     * expected", shared by every step (resolution, preparation, and commit) that must refuse to
     * act on a stale selection instead of duplicating this comparison at each call site.
     */
    private InstallationRecord requireUnchangedRecord(RegistryData data, InstallationRecord expected)
            throws IOException {
        InstallationRecord current = registries.resolve(data, expected.id());
        if (!current.equals(expected)) {
            throw new IOException("the selected installation changed; reopen Installations");
        }
        return current;
    }

    /**
     * The single implementation of "does this record's application still map to the requested
     * official product", returning its verified imported root on success.
     */
    private Path requireMatchingRepository(RegistryData data, InstallationRecord record,
                                           OfficialRepository requestedRepository)
            throws IOException {
        if (repositoryFor(record.products()) != requestedRepository) {
            throw new IOException("the detected application mapping changed");
        }
        return requireTrueImported(data, record);
    }

    /**
     * The single implementation of "is the freshly inspected folder still this record, with
     * enough confidence to trust its observed version", shared by resolution and preparation
     * instead of being copied at each call site.
     */
    private static void requireRecognizedImport(Inspection observed, InstallationRecord record)
            throws IOException {
        if (!matchesRecord(observed, record)) {
            throw new IOException("the imported application changed after registration");
        }
        if (observed.observedBuild().isBlank()
                || "unknown".equalsIgnoreCase(observed.observedBuild())
                || !observed.confidence().startsWith("recognized-packaging")) {
            throw new IOException("the detected application version is not strong enough "
                    + "to verify an official ancestor");
        }
    }

    private Path requireTrueImported(RegistryData data, InstallationRecord record)
            throws IOException {
        if (availability(data, record, coordinator.pending(
                Path.of(record.canonicalRoot()))) != Availability.TRUE_IMPORTED) {
            throw new IOException("only a complete launch-only imported copy with no managed "
                    + "metadata can be verified");
        }
        Path root = StrictPathSafety.requireDirectory(
                Path.of(record.canonicalRoot()), "imported application root");
        if (!root.toString().equals(record.canonicalRoot())) {
            throw new IOException("registered application root is no longer canonical");
        }
        ReceiptStore.validateMetadataPlacement(receipts.metadataDirectory(registry), data);
        return root;
    }

    private Path requireWorkspaceParent(RegistryData data) throws IOException {
        Path parent = registry.getParent();
        StrictPathSafety.requireDirectory(parent, "adoption workspace parent");
        for (InstallationRecord item : data.installations()) {
            Path root = Path.of(item.canonicalRoot()).toAbsolutePath().normalize();
            if (parent.equals(root) || parent.startsWith(root)) {
                throw new IOException("adoption workspace overlaps an application root");
            }
        }
        return parent;
    }

    private static void requireNoRootOverlap(Path workspace, RegistryData data)
            throws IOException {
        Path candidate = workspace.toAbsolutePath().normalize();
        for (InstallationRecord item : data.installations()) {
            Path root = Path.of(item.canonicalRoot()).toAbsolutePath().normalize();
            if (candidate.equals(root) || candidate.startsWith(root)
                    || root.startsWith(candidate)) {
                throw new IOException("adoption workspace overlaps an application root");
            }
        }
    }

    private static Comparison compare(InstallationRecord record, Inspection official,
                                      OwnershipPolicy.Build ownership,
                                      RootSnapshot officialSnapshot, RootSnapshot local)
            throws IOException {
        boolean identity = record.observedBuild().equals(official.observedBuild())
                && record.products().equals(official.products());
        Map<String, EntryEvidence> localByAlias = new HashMap<>();
        local.entries().forEach(entry -> localByAlias.put(key(entry.path()), entry));
        Set<String> officialAliases = new HashSet<>();
        Set<String> criticalAliases = criticalAliases(official, officialSnapshot);
        List<PathMatch> modified = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        Map<String, CaseOverride> casePrefixes = new TreeMap<>();
        int exact = 0;
        // Empty managed directories matter too: if an official directory already exists
        // under another spelling, a later release must not traverse it as a fresh path.
        for (EntryEvidence directory : officialSnapshot.entries()) {
            if (!directory.directory() || !managedDirectory(directory.path())) continue;
            EntryEvidence localDirectory = localByAlias.get(key(directory.path()));
            if (localDirectory == null) continue;
            if (!localDirectory.directory()) {
                conflicts.add(directory.path());
            } else if (!localDirectory.path().equals(directory.path())) {
                recordCasePrefix(casePrefixes, directory.path(), localDirectory.path());
                if (key(directory.path()).equals("lib")
                        || OwnershipPolicy.runtimePath(directory.path())) {
                    conflicts.add(directory.path());
                }
            }
        }
        for (FileEntry file : ownership.manifest().files()) {
            String alias = key(file.path());
            officialAliases.add(alias);
            boolean criticalPath = criticalAliases.contains(alias)
                    || OwnershipPolicy.runtimePath(file.path());
            // Check every existing component, not just the leaf. A file in place of a
            // directory (or a directory in place of the official file) is never a rename.
            // The snapshots already reject links, special entries and duplicate aliases.
            boolean structuralConflict = false;
            int end = file.path().indexOf('/');
            while (end >= 0) {
                String prefix = file.path().substring(0, end);
                EntryEvidence parent = localByAlias.get(key(prefix));
                if (parent != null) {
                    if (!parent.directory()) structuralConflict = true;
                    else if (!parent.path().equals(prefix)) {
                        recordCasePrefix(casePrefixes, prefix, parent.path());
                        if (criticalPath) structuralConflict = true;
                    }
                }
                end = file.path().indexOf('/', end + 1);
            }
            EntryEvidence evidence = localByAlias.get(alias);
            if (evidence != null && evidence.regularFile()
                    && !evidence.path().equals(file.path())) {
                recordCasePrefix(casePrefixes, file.path(), evidence.path());
                if (criticalPath) structuralConflict = true;
            }
            if (structuralConflict || evidence != null && !evidence.regularFile()) {
                conflicts.add(file.path());
                continue;
            }
            if (evidence == null) {
                missing.add(file.path());
                continue;
            }
            if (file.sha256().equals(evidence.sha256())) {
                exact++;
            } else {
                modified.add(new PathMatch(file.path(), file.sha256(),
                        criticalPath));
            }
        }
        for (EntryEvidence officialEntry : officialSnapshot.entries()) {
            String alias = key(officialEntry.path());
            if (!officialEntry.regularFile() || !criticalAliases.contains(alias)
                    || ownership.manifest().files().stream()
                    .anyMatch(file -> key(file.path()).equals(alias))) {
                continue;
            }
            officialAliases.add(alias);
            EntryEvidence evidence = localByAlias.get(alias);
            if (evidence == null) {
                if (conflictingParent(officialEntry.path(), localByAlias) == null) {
                    missing.add(officialEntry.path());
                } else {
                    conflicts.add(officialEntry.path());
                }
            } else if (!evidence.path().equals(officialEntry.path())
                    || !evidence.regularFile()) {
                conflicts.add(officialEntry.path());
            } else if (officialEntry.sha256().equals(evidence.sha256())) {
                exact++;
            } else {
                modified.add(new PathMatch(officialEntry.path(),
                        officialEntry.sha256(), true));
            }
        }
        int protectedFiles = 0;
        List<String> unknown = new ArrayList<>();
        for (EntryEvidence entry : local.entries()) {
            if (!entry.regularFile()) continue;
            if (protectedPath(entry.path())) {
                protectedFiles++;
            } else if (!officialAliases.contains(key(entry.path()))) {
                unknown.add(entry.path());
            }
        }
        boolean criticalModified = modified.stream().anyMatch(PathMatch::identityCritical);
        // A missing file has no unknown content to trust; the ordinary update planner already
        // restores any official path that isn't present locally (see UpdatePlanner.decide), so
        // missing files carry no more risk here than they do for any later managed update.
        boolean eligible = identity && conflicts.isEmpty() && !criticalModified;
        String reason;
        if (!identity) {
            reason = "This installation does not match the official release for its version.";
        } else if (!conflicts.isEmpty()) {
            reason = "A file or folder conflict prevents this installation from being managed.";
        } else if (criticalModified) {
            reason = "Core application files differ from the official release.";
        } else if (missing.stream().anyMatch(path -> !affectedByCase(path, casePrefixes))) {
            long restorable = missing.stream()
                    .filter(path -> !affectedByCase(path, casePrefixes)).count();
            reason = "This copy can be managed safely. " + restorable
                    + " missing file(s) will be restored by the next update.";
        } else {
            reason = "This copy can be managed safely. Existing files will not be changed.";
        }
        return new Comparison(eligible, identity, exact, List.copyOf(modified),
                List.copyOf(missing), List.copyOf(unknown), List.copyOf(conflicts),
                List.copyOf(casePrefixes.values()), protectedFiles, reason);
    }

    /** Keep only the outermost case-affected managed prefixes, like preview-pair validation. */
    private static void recordCasePrefix(Map<String, CaseOverride> cases,
                                         String official, String local) {
        String folded = key(official);
        if (cases.keySet().stream().anyMatch(prefix ->
                folded.startsWith(prefix + "/"))) return;
        cases.keySet().removeIf(prefix -> prefix.startsWith(folded + "/"));
        cases.put(folded, new CaseOverride(folded, local,
                List.copyOf(new TreeSet<>(List.of(local, official)))));
    }

    private static boolean affectedByCase(String path, Map<String, CaseOverride> cases) {
        String folded = key(path);
        return cases.keySet().stream().anyMatch(prefix ->
                folded.equals(prefix) || folded.startsWith(prefix + "/"));
    }

    private static boolean managedDirectory(String path) {
        String folded = key(path);
        return OwnershipPolicy.managed(path)
                || Set.of("data", "docs", "licenses", "lib").contains(folded);
    }

    private static Set<String> criticalAliases(Inspection official,
                                               RootSnapshot officialSnapshot) {
        Set<String> critical = new HashSet<>();
        for (Product product : official.products()) {
            critical.add(key(product.jar()));
            product.classPath().forEach(path -> critical.add(key(path)));
        }
        for (EntryEvidence entry : officialSnapshot.entries()) {
            if (entry.regularFile() && OwnershipPolicy.managed(entry.path())
                    && OwnershipPolicy.runtimePath(entry.path())) {
                critical.add(key(entry.path()));
            }
        }
        return Set.copyOf(critical);
    }

    static boolean sameContents(RootSnapshot first, RootSnapshot second) {
        if (first.entries().size() != second.entries().size()) return false;
        for (int index = 0; index < first.entries().size(); index++) {
            EntryEvidence left = first.entries().get(index);
            EntryEvidence right = second.entries().get(index);
            if (!left.path().equals(right.path())
                    || left.regularFile() != right.regularFile()
                    || left.directory() != right.directory()) {
                return false;
            }
            if (left.regularFile() && (left.size() != right.size()
                    || !Objects.equals(left.sha256(), right.sha256()))) {
                return false;
            }
        }
        return true;
    }

    private static String conflictingParent(String path,
                                            Map<String, EntryEvidence> localByAlias) {
        int slash = path.indexOf('/');
        while (slash >= 0) {
            EntryEvidence parent = localByAlias.get(key(path.substring(0, slash)));
            if (parent != null && !parent.directory()) return parent.path();
            slash = path.indexOf('/', slash + 1);
        }
        return null;
    }

    private static void logComparison(PrintStream diagnostics, OperationContext context,
                                      Comparison comparison)
            throws org.megamek.launcher.operation.OperationCancelledException {
        diagnostics.printf("ADOPTION comparison exact=%d modified=%d missing=%d unknown=%d "
                        + "protected=%d conflicts=%d caseOverrides=%d identity=%s eligible=%s%n",
                comparison.exact(), comparison.modified().size(), comparison.missing().size(),
                comparison.unknown().size(), comparison.protectedFiles(),
                comparison.conflicts().size(), comparison.caseOverrides().size(),
                comparison.productIdentity(),
                comparison.eligible());
        List<String> details = new ArrayList<>();
        comparison.modified().forEach(item -> details.add("modified " + item.path()));
        comparison.missing().forEach(path -> details.add("missing " + path));
        comparison.unknown().forEach(path -> details.add("unknown " + path));
        comparison.conflicts().forEach(path -> details.add("conflict " + path));
        comparison.caseOverrides().forEach(item -> details.add("case override "
                + item.localPrefix() + " / " + item.officialSpellings()));
        for (String detail : details.stream().limit(DIAGNOSTIC_PATH_LIMIT).toList()) {
            context.progress(OperationPhase.PLAN, -1, -1, ProgressUnit.NONE,
                    "Adoption detail: " + detail);
            diagnostics.println("ADOPTION " + detail);
        }
        if (details.size() > DIAGNOSTIC_PATH_LIMIT) {
            diagnostics.println("ADOPTION additional detail entries omitted="
                    + (details.size() - DIAGNOSTIC_PATH_LIMIT));
        }
        if (!comparison.eligible()) diagnostics.println("ADOPTION blocked: " + comparison.reason());
    }

    private void cleanupAttemptArtifacts(String id) throws IOException {
        Path receipt = receipts.receiptPath(registry, id);
        Path channel = channels.path(registry, id);
        for (Path file : List.of(
                receipt.resolveSibling(receipt.getFileName() + ".lock"),
                channel.resolveSibling(channel.getFileName() + ".lock"))) {
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                StrictPathSafety.requireFile(file, "adoption lock");
                if (Files.size(file) == 0) Files.delete(file);
            }
        }
        Path directory = receipts.metadataDirectory(registry);
        if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(directory)) {
                if (!entries.iterator().hasNext()) Files.delete(directory);
            }
        }
    }

    private static boolean matchesRecord(Inspection inspection, InstallationRecord record) {
        return record.canonicalRoot().equals(inspection.canonicalRoot())
                && record.observedBuild().equals(inspection.observedBuild())
                && record.products().equals(inspection.products());
    }

    static OfficialRepository repositoryFor(List<Product> products) throws IOException {
        Set<String> keys = products.stream().map(Product::key).collect(
                java.util.stream.Collectors.toSet());
        if (keys.contains("mekhq")) return OfficialRepository.MEKHQ;
        if (keys.equals(Set.of("lab"))) return OfficialRepository.LAB;
        if (keys.equals(Set.of("megamek"))) return OfficialRepository.MEGAMEK;
        throw new IOException("the detected applications do not map to one supported official "
                + "package");
    }

    private static String displayProducts(List<Product> products) {
        return products.stream().map(Product::key).map(key -> switch (key) {
            case "megamek" -> "MegaMek";
            case "mekhq" -> "MekHQ";
            case "lab" -> "MegaMekLab";
            default -> key;
        }).collect(java.util.stream.Collectors.joining(", "));
    }

    private static String displayVersion(String value) {
        return value == null || value.isBlank() || "unknown".equalsIgnoreCase(value)
                ? "Unknown" : value;
    }

    private static boolean protectedPath(String path) {
        String folded = key(path);
        return OwnershipPolicy.PROTECTED_PATHS.stream()
                .map(ImportedCopyAdoptionService::key)
                .anyMatch(protectedPath -> folded.equals(protectedPath)
                        || folded.startsWith(protectedPath + "/"));
    }

    private static String key(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    private static boolean exists(Path path) {
        return Files.exists(path, LinkOption.NOFOLLOW_LINKS);
    }

    private static boolean hasReceiptStaging(Path receipt) throws IOException {
        Path directory = receipt.getParent();
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) return false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory,
                receipt.getFileName() + ".new-*")) {
            return stream.iterator().hasNext();
        }
    }

    private static void requireChannel(FollowChannel channel) throws IOException {
        if (channel != FollowChannel.MILESTONE && channel != FollowChannel.DEVELOPMENT) {
            throw new IOException("adoption channel must be Milestone or Development");
        }
    }

    private static void requireWorkerThread() throws IOException {
        if (EventQueue.isDispatchThread()) {
            throw new IOException("adoption verification must run outside the user-interface "
                    + "thread");
        }
    }

    private static IOException add(IOException aggregate, IOException error) {
        if (aggregate == null) return error;
        aggregate.addSuppressed(error);
        return aggregate;
    }

    private static String detail(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? error.getClass().getSimpleName() : message;
    }

    public enum Availability {
        TRUE_IMPORTED,
        MANAGED,
        INCOMPLETE
    }

    public record Suggestion(OfficialRepository repository, String suggestedTag,
                             String detectedApplication, String detectedVersion) {
    }

    public record CommitResult(InstallationRecord record, FollowChannel channel,
                               String cleanupWarning) {
    }

    public static final class CandidateResolutionException extends IOException {
        public CandidateResolutionException(String message) {
            super(message);
        }

        public CandidateResolutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static final class ChannelMismatchException extends IOException {
        private final String currentVersion;
        private final FollowChannel selectedChannel;
        private final FollowChannel requiredChannel;

        public ChannelMismatchException(String currentVersion,
                                        FollowChannel selectedChannel,
                                        FollowChannel requiredChannel) {
            super("version " + currentVersion + " is currently " + requiredChannel
                    + ", not " + selectedChannel);
            this.currentVersion = Objects.requireNonNull(currentVersion);
            this.selectedChannel = Objects.requireNonNull(selectedChannel);
            this.requiredChannel = Objects.requireNonNull(requiredChannel);
            if (selectedChannel == requiredChannel) {
                throw new IllegalArgumentException(
                        "channel mismatch requires distinct channels");
            }
        }

        public String currentVersion() {
            return currentVersion;
        }

        public FollowChannel selectedChannel() {
            return selectedChannel;
        }

        public FollowChannel requiredChannel() {
            return requiredChannel;
        }
    }

    record PathMatch(String path, String officialSha256, boolean identityCritical) {
    }

    record Comparison(boolean eligible, boolean productIdentity, int exact,
                      List<PathMatch> modified, List<String> missing, List<String> unknown,
                      List<String> conflicts, List<CaseOverride> caseOverrides,
                      int protectedFiles, String reason) {
        Comparison {
            modified = List.copyOf(modified);
            missing = List.copyOf(missing);
            unknown = List.copyOf(unknown);
            conflicts = List.copyOf(conflicts);
            caseOverrides = List.copyOf(caseOverrides);
        }
    }

    @FunctionalInterface
    interface FailureHook {
        void hit(String point) throws IOException;
    }
}
