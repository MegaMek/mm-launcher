package org.megamek.launcher.update;

import org.megamek.launcher.channel.ChannelPreference;
import org.megamek.launcher.channel.ChannelPreferenceStore;
import org.megamek.launcher.channel.FollowChannel;
import org.megamek.launcher.manifest.FileEntry;
import org.megamek.launcher.manifest.ManifestException;
import org.megamek.launcher.manifest.ManifestReader;
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
import org.megamek.launcher.release.SafeTarExtractor;
import org.megamek.launcher.release.VerifiedPackageFetcher;
import org.megamek.launcher.sandbox.OverrideEntry;

import java.awt.EventQueue;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
            InstallationRecord record = registries.resolve(data, expected.id());
            if (!record.equals(expected)) {
                throw new IOException("the selected installation changed; reopen Installations");
            }
            OfficialRepository repository = repositoryFor(record.products());
            if (repository != requestedRepository) {
                throw new IOException("the detected application mapping changed");
            }
            Path root = requireTrueImported(data, record);
            try (var gate = coordinator.acquire(root, false)) {
                gate.requireNoPendingUpdate();
                Inspection observed = inspector.inspect(root);
                if (!matchesRecord(observed, record)) {
                    throw new IOException("the imported application changed after registration");
                }
                if (observed.observedBuild().isBlank()
                        || "unknown".equalsIgnoreCase(observed.observedBuild())
                        || !observed.confidence().startsWith("recognized-packaging")) {
                    throw new IOException("the detected application version is not strong enough "
                            + "to resolve an official release");
                }
            }
            AutomaticAdoptionResolver.Resolution resolution =
                    new AutomaticAdoptionResolver(transport).resolve(repository,
                            record.observedBuild(), diagnostics, context);
            if (resolution.matched()) return resolution.release().tag();
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
        Path attemptDirectory = null;
        boolean retained = false;
        Throwable preparationFailure = null;
        try (OperationContext.WorkerRegistration ignored = context.activate()) {
            context.phase(OperationPhase.METADATA,
                    "Checking that this copy is ready for verification");
            RegistryData before = registries.read(registry);
            InstallationRecord record = registries.resolve(before, expected.id());
            if (!record.equals(expected)) {
                throw new IOException("the selected installation changed; reopen Installations");
            }
            OfficialRepository repository = repositoryFor(record.products());
            if (repository != requestedRepository) {
                throw new IOException("the selected official product does not match the detected "
                        + "applications");
            }
            Path root = requireTrueImported(before, record);
            Inspection observed;
            RootSnapshot local;
            try (var gate = coordinator.acquire(root, false)) {
                gate.requireNoPendingUpdate();
                context.checkpoint();
                observed = inspector.inspect(root);
                if (!matchesRecord(observed, record)) {
                    throw new IOException("the imported application changed after registration");
                }
                if (observed.observedBuild().isBlank()
                        || "unknown".equalsIgnoreCase(observed.observedBuild())
                        || !observed.confidence().startsWith("recognized-packaging")) {
                    throw new IOException("the detected application version is not strong enough "
                            + "to verify an official ancestor");
                }
                local = snapshot(root, context, OperationPhase.METADATA);
            }
            Path workspaceParent = requireWorkspaceParent(before);
            attemptDirectory = Files.createTempDirectory(workspaceParent,
                    ".adoption-attempt-");
            StrictPathSafety.requireDirectory(attemptDirectory,
                    "adoption attempt directory");
            requireNoRootOverlap(attemptDirectory, before);

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
                        repository, release.tag(), attemptDirectory, "package-",
                        diagnostics, expectedAsset, context);
            } catch (IOException error) {
                throw new CandidateResolutionException(
                        AUTOMATIC_MATCH_UNAVAILABLE, error);
            }
            context.phase(OperationPhase.PLAN,
                    "Comparing the existing copy with the verified official package");
            Inspection officialInspection = inspector.inspect(workspace.extracted());
            OwnershipPolicy.Build ownership = new OwnershipPolicy().build(
                    workspace.extracted(), repository, release.tag(), context,
                    OperationPhase.PLAN);
            RootSnapshot officialSnapshot = snapshot(
                    workspace.extracted(), context, OperationPhase.PLAN);
            Comparison comparison = compare(record, officialInspection, ownership,
                    officialSnapshot, local);
            logComparison(diagnostics, context, comparison);
            String application = displayProducts(record.products());
            String version = displayVersion(record.observedBuild());
            String message = comparison.eligible()
                    ? "This copy can be managed safely. Existing files will not be changed."
                    : "This copy could not be verified and will remain launch-only.";
            PreparedAdoption.Report report = new PreparedAdoption.Report(comparison.eligible(),
                    application, version, fixedChannel, message);
            VerifiedPackageFetcher.Workspace retainedWorkspace = workspace;
            Path retainedDirectory = attemptDirectory;
            PreparedAdoption prepared = new PreparedAdoption(owner, record, repository,
                    fixedChannel, release, asset, retainedWorkspace, ownership,
                    officialInspection, officialSnapshot, local, comparison, report, () -> {
                IOException cleanupFailure = null;
                try {
                    retainedWorkspace.close();
                } catch (IOException error) {
                    cleanupFailure = error;
                } finally {
                    active.remove(record.id());
                }
                try {
                    Files.deleteIfExists(retainedDirectory);
                } catch (IOException error) {
                    cleanupFailure = add(cleanupFailure, error);
                }
                if (cleanupFailure != null) throw cleanupFailure;
            });
            context.phase(OperationPhase.AWAIT_CONSENT,
                    comparison.eligible() ? "Copy verified; waiting for confirmation"
                            : "Copy remains launch-only");
            retained = true;
            return prepared;
        } catch (IOException | InterruptedException | ManifestException | RuntimeException error) {
            preparationFailure = error;
            throw error;
        } finally {
            if (!retained) {
                active.remove(expected.id());
                IOException cleanupFailure = null;
                if (workspace != null) {
                    try {
                        workspace.close();
                    } catch (IOException error) {
                        cleanupFailure = error;
                    }
                }
                if (attemptDirectory != null) {
                    try {
                        Files.deleteIfExists(attemptDirectory);
                    } catch (IOException error) {
                        cleanupFailure = add(cleanupFailure, error);
                    }
                }
                if (cleanupFailure != null) {
                    if (preparationFailure != null) {
                        preparationFailure.addSuppressed(cleanupFailure);
                    } else {
                        throw cleanupFailure;
                    }
                }
            }
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
            try (var gate = coordinator.acquire(root, false)) {
                gate.requireNoPendingUpdate();
                RegistryData currentRegistry = registries.read(registry);
                InstallationRecord current = registries.resolve(currentRegistry, expected.id());
                if (!current.equals(expected)) {
                    throw new IOException("the selected installation record changed");
                }
                requireTrueImported(currentRegistry, current);
                Inspection observed = inspector.inspect(root);
                if (!matchesRecord(observed, current)) {
                    throw new IOException("the imported application changed before confirmation");
                }
                RootSnapshot now = snapshot(root, context, OperationPhase.METADATA);
                if (!prepared.localSnapshot().equals(now)) {
                    throw new IOException("the imported copy changed while it was being verified");
                }

                VerifiedPackageFetcher.ExpectedAsset expectedAsset =
                        new VerifiedPackageFetcher.ExpectedAsset(prepared.asset().name(),
                                prepared.asset().size(), prepared.asset().digest(),
                                prepared.asset().url());
                Path extracted = new VerifiedPackageFetcher(transport).revalidateAndExtract(
                        prepared.repository(), prepared.release().tag(), prepared.workspace(),
                        expectedAsset, diagnostics, context);
                Inspection officialInspection = inspector.inspect(extracted);
                OwnershipPolicy.Build ownership = new OwnershipPolicy().build(extracted,
                        prepared.repository(), prepared.release().tag(), context,
                        OperationPhase.PREPARE_INSTALL);
                RootSnapshot officialSnapshot = snapshot(
                        extracted, context, OperationPhase.PREPARE_INSTALL);
                if (!sameApplication(officialInspection, prepared.officialInspection())
                        || !ownership.equals(prepared.ownership())
                        || !sameContents(officialSnapshot, prepared.officialSnapshot())) {
                    throw new IOException("the retained verified package identity changed");
                }
                RootSnapshot finalLocal = snapshot(
                        root, context, OperationPhase.PREPARE_INSTALL);
                if (!now.equals(finalLocal)) {
                    throw new IOException("the imported copy changed during final verification");
                }
                Comparison comparison = compare(current, officialInspection, ownership,
                        officialSnapshot, finalLocal);
                if (!comparison.equals(prepared.comparison()) || !comparison.eligible()) {
                    throw new IOException("verification evidence changed before confirmation");
                }

                context.checkpoint();
                context.enterFinalization("Managed-update metadata publication has begun; "
                        + "cancellation can no longer be performed safely.");
                context.phase(OperationPhase.APPLY,
                        "Publishing managed-update metadata outside the application folder");
                publish(currentRegistry, current, prepared, ownership, comparison);
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
                    ownership.manifest(), ownership.excludedPaths(), overrides, List.of(),
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
        int exact = 0;
        for (FileEntry file : ownership.manifest().files()) {
            String alias = key(file.path());
            officialAliases.add(alias);
            EntryEvidence evidence = localByAlias.get(alias);
            if (evidence == null) {
                String conflictingParent = conflictingParent(file.path(), localByAlias);
                if (conflictingParent == null) missing.add(file.path());
                else conflicts.add(file.path());
                continue;
            }
            if (!evidence.path().equals(file.path()) || !evidence.regularFile()) {
                conflicts.add(file.path());
            } else if (file.sha256().equals(evidence.sha256())) {
                exact++;
            } else {
                modified.add(new PathMatch(file.path(), file.sha256(),
                        criticalAliases.contains(alias)
                                || OwnershipPolicy.runtimePath(file.path())));
            }
        }
        for (EntryEvidence officialEntry : officialSnapshot.entries()) {
            String alias = key(officialEntry.path());
            officialAliases.add(alias);
            if (!officialEntry.regularFile() || !criticalAliases.contains(alias)
                    || ownership.manifest().files().stream()
                    .anyMatch(file -> key(file.path()).equals(alias))) {
                continue;
            }
            EntryEvidence evidence = localByAlias.get(alias);
            if (evidence == null) {
                missing.add(officialEntry.path());
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
        boolean eligible = identity && missing.isEmpty() && conflicts.isEmpty()
                && !criticalModified;
        String reason;
        if (!identity) {
            reason = "The detected application and version do not match this official release.";
        } else if (!conflicts.isEmpty()) {
            reason = "The copy contains an unsafe file or folder conflict.";
        } else if (!missing.isEmpty()) {
            reason = "An official application file is missing.";
        } else if (criticalModified) {
            reason = "An application or dependency file differs from the official release.";
        } else {
            reason = "Verified";
        }
        return new Comparison(eligible, identity, exact, List.copyOf(modified),
                List.copyOf(missing), List.copyOf(unknown), List.copyOf(conflicts),
                protectedFiles, reason);
    }

    private static Set<String> criticalAliases(Inspection official,
                                               RootSnapshot officialSnapshot) {
        Set<String> critical = new HashSet<>();
        for (Product product : official.products()) {
            critical.add(key(product.jar()));
            product.classPath().forEach(path -> critical.add(key(path)));
        }
        for (EntryEvidence entry : officialSnapshot.entries()) {
            if (!entry.regularFile() || protectedPath(entry.path())) continue;
            String path = entry.path().toLowerCase(Locale.ROOT);
            if (path.endsWith(".jar") || path.endsWith(".exe") || path.endsWith(".sh")
                    || path.endsWith(".bat") || path.endsWith(".cmd")) {
                critical.add(key(entry.path()));
            }
        }
        return Set.copyOf(critical);
    }

    private static boolean sameContents(RootSnapshot first, RootSnapshot second) {
        if (first.entries().size() != second.entries().size()) return false;
        for (int index = 0; index < first.entries().size(); index++) {
            EntryEvidence left = first.entries().get(index);
            EntryEvidence right = second.entries().get(index);
            if (!left.path().equals(right.path())
                    || left.regularFile() != right.regularFile()
                    || left.directory() != right.directory()
                    || left.size() != right.size()
                    || !Objects.equals(left.sha256(), right.sha256())) {
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

    private static RootSnapshot snapshot(Path requested, OperationContext context,
                                         OperationPhase phase)
            throws IOException, InterruptedException, ManifestException {
        Path root = StrictPathSafety.requireDirectory(requested, "imported application root");
        BasicFileAttributes rootBefore = attributes(root);
        List<Path> paths;
        try (var walk = Files.walk(root)) {
            paths = walk.skip(1).limit((long) SafeTarExtractor.MAX_ENTRIES + 1).toList();
        }
        if (paths.size() > SafeTarExtractor.MAX_ENTRIES) {
            throw new IOException("imported copy contains too many entries to verify safely");
        }
        paths = paths.stream().sorted(Comparator.comparing(
                path -> portable(root.relativize(path)))).toList();
        Set<String> aliases = new HashSet<>();
        List<EntryEvidence> entries = new ArrayList<>();
        int completed = 0;
        long totalBytes = 0;
        for (Path path : paths) {
            context.checkpoint();
            BasicFileAttributes before = attributes(path);
            if (before.isSymbolicLink() || before.isOther()
                    || !before.isDirectory() && !before.isRegularFile()) {
                throw new IOException("imported copy contains a link or special entry");
            }
            String relative = portable(root.relativize(path));
            ManifestReader.validatePortablePath(relative, "local path", "imported copy");
            if (!aliases.add(key(relative))) {
                throw new IOException("imported copy contains a case or Unicode path collision");
            }
            if (before.isRegularFile()
                    && (before.size() < 0 || before.size() > SafeTarExtractor.MAX_FILE_SIZE
                    || totalBytes > SafeTarExtractor.MAX_EXPANDED_SIZE - before.size())) {
                throw new IOException("imported copy exceeds safe verification size limits");
            }
            if (before.isRegularFile()) totalBytes += before.size();
            String hash = before.isRegularFile() ? sha256(path, context) : null;
            BasicFileAttributes after = attributes(path);
            if (before.size() != after.size()
                    || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || before.fileKey() != null && !before.fileKey().equals(after.fileKey())) {
                throw new IOException("imported copy changed while it was being verified");
            }
            entries.add(new EntryEvidence(relative, before.isRegularFile(),
                    before.isDirectory(), before.size(), before.lastModifiedTime().toMillis(),
                    before.fileKey() == null ? null : before.fileKey().toString(), hash));
            context.progress(phase, ++completed, paths.size(),
                    ProgressUnit.FILES, "Verified " + completed + " local entries");
        }
        BasicFileAttributes rootAfter = attributes(root);
        RootIdentity identity = new RootIdentity(
                rootBefore.fileKey() == null ? null : rootBefore.fileKey().toString(),
                rootBefore.creationTime().toMillis(), rootBefore.lastModifiedTime().toMillis());
        RootIdentity finalIdentity = new RootIdentity(
                rootAfter.fileKey() == null ? null : rootAfter.fileKey().toString(),
                rootAfter.creationTime().toMillis(), rootAfter.lastModifiedTime().toMillis());
        if (!identity.equals(finalIdentity)) {
            throw new IOException("imported copy changed while it was being verified");
        }
        return new RootSnapshot(root.toString(), identity, List.copyOf(entries));
    }

    private static BasicFileAttributes attributes(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
    }

    private static String sha256(Path path, OperationContext context)
            throws IOException, InterruptedException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
                byte[] buffer = new byte[128 * 1024];
                while (true) {
                    context.checkpoint();
                    int read = input.read(buffer);
                    if (read < 0) break;
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java runtime lacks SHA-256", impossible);
        }
    }

    private static void logComparison(PrintStream diagnostics, OperationContext context,
                                      Comparison comparison)
            throws org.megamek.launcher.operation.OperationCancelledException {
        diagnostics.printf("ADOPTION comparison exact=%d modified=%d missing=%d unknown=%d "
                        + "protected=%d conflicts=%d identity=%s eligible=%s%n",
                comparison.exact(), comparison.modified().size(), comparison.missing().size(),
                comparison.unknown().size(), comparison.protectedFiles(),
                comparison.conflicts().size(), comparison.productIdentity(),
                comparison.eligible());
        List<String> details = new ArrayList<>();
        comparison.modified().forEach(item -> details.add("modified " + item.path()));
        comparison.missing().forEach(path -> details.add("missing " + path));
        comparison.unknown().forEach(path -> details.add("unknown " + path));
        comparison.conflicts().forEach(path -> details.add("conflict " + path));
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

    private static boolean sameApplication(Inspection first, Inspection second) {
        return first.observedBuild().equals(second.observedBuild())
                && first.products().equals(second.products());
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

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
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

    record RootSnapshot(String canonicalRoot, RootIdentity rootIdentity,
                        List<EntryEvidence> entries) {
    }

    record RootIdentity(String fileKey, long creationMillis, long modifiedMillis) {
    }

    record EntryEvidence(String path, boolean regularFile, boolean directory, long size,
                         long modifiedMillis, String fileKey, String sha256) {
    }

    record PathMatch(String path, String officialSha256, boolean identityCritical) {
    }

    record Comparison(boolean eligible, boolean productIdentity, int exact,
                      List<PathMatch> modified, List<String> missing, List<String> unknown,
                      List<String> conflicts, int protectedFiles, String reason) {
    }

    @FunctionalInterface
    interface FailureHook {
        void hit(String point) throws IOException;
    }
}
