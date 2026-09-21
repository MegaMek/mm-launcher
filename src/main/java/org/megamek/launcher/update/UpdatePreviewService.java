package org.megamek.launcher.update;

import org.megamek.launcher.channel.ChannelUpdateChecker;
import org.megamek.launcher.channel.ChannelPreferenceStore;
import org.megamek.launcher.manifest.ManifestException;
import org.megamek.launcher.manifest.Manifest;
import org.megamek.launcher.manifest.ManifestReader;
import org.megamek.launcher.operation.OperationContext;
import org.megamek.launcher.operation.OperationPhase;
import org.megamek.launcher.operation.OperationType;
import org.megamek.launcher.onboarding.InstallationInspector;
import org.megamek.launcher.plan.Action;
import org.megamek.launcher.plan.Decision;
import org.megamek.launcher.plan.UpdatePlanner;
import org.megamek.launcher.sandbox.OverrideEntry;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryData;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.release.OfficialRepository;
import org.megamek.launcher.release.ReleaseCatalog;
import org.megamek.launcher.release.ReleaseTransport;
import org.megamek.launcher.release.VerifiedPackageFetcher;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Set;
import java.util.List;
import java.util.Map;

/** Produces a point-in-time plan and never writes beneath an installation root. */
public final class UpdatePreviewService {
    private final ReleaseTransport transport;
    private final RegistryStore registryStore;
    private final ReceiptStore receiptStore;
    private final CurrentStateStore currentStateStore;
    private final ChannelPreferenceStore channelPreferences;

    public UpdatePreviewService(ReleaseTransport transport) {
        this(transport, new RegistryStore(), new ReceiptStore());
    }

    UpdatePreviewService(ReleaseTransport transport, RegistryStore registryStore,
                         ReceiptStore receiptStore) {
        this.transport = transport;
        this.registryStore = registryStore;
        this.receiptStore = receiptStore;
        this.currentStateStore = new CurrentStateStore(receiptStore);
        this.channelPreferences = new ChannelPreferenceStore();
    }

    public Eligibility eligibility(Path registry, String id) throws IOException {
        RegistryData data = registryStore.read(registry);
        InstallationRecord record = registryStore.resolve(data, id);
        OwnershipReceipt receipt = null;
        CurrentUpdateState current = null;
        try {
            receipt = receiptStore.read(registry, data, record);
            current = currentStateStore.read(registry, data, record, receipt);
            ChannelPreferenceStore.ReadResult fixed =
                    channelPreferences.read(registry, data, record);
            if (fixed.status() != ChannelPreferenceStore.Status.CONFIGURED
                    || fixed.preference() == null) {
                return new Eligibility(record, false,
                        "Fixed channel provenance is unavailable; this copy is launch-only: "
                                + fixed.reason(),
                        receipt, current);
            }
            new AdoptionStateStore(receiptStore).validateForUse(
                    registry, record, receipt, current, fixed.preference());
            validateLocalBoundary(record, registry, data);
            return new Eligibility(record, true, "Verified update provenance is valid", receipt,
                    current);
        } catch (IOException e) {
            return new Eligibility(record, false, e.getMessage(), receipt, current);
        }
    }

    public Preview preview(Path registry, String id, String targetTag, PrintStream progress)
            throws IOException, InterruptedException, ManifestException {
        Path registryPath = registry.toAbsolutePath().normalize();
        RegistryData data = registryStore.read(registryPath);
        InstallationRecord record = registryStore.resolve(data, id);
        // All local provenance and placement checks deliberately precede any network request.
        requireFixedChannel(registryPath, data, record);
        Path root = validateLocalBoundary(record, registryPath, data);
        OwnershipReceipt receipt = receiptStore.read(registryPath, data, record);
        CurrentUpdateState current = currentStateStore.read(
                registryPath, data, record, receipt);
        return previewValidated(registryPath, data, record, receipt, current, root, targetTag,
                progress, null, OperationContext.none(OperationType.UPDATE_PREVIEW));
    }

    public Preview preview(Path registry, InstallationRecord expectedRecord,
                           OwnershipReceipt expectedReceipt, String targetTag, PrintStream progress)
            throws IOException, InterruptedException, ManifestException {
        Path registryPath = registry.toAbsolutePath().normalize();
        RegistryData data = registryStore.read(registryPath);
        InstallationRecord current = registryStore.resolve(data, expectedRecord.id());
        if (!current.equals(expectedRecord)) {
            throw new IOException("selected preview source changed after the dialog opened");
        }
        requireFixedChannel(registryPath, data, current);
        Path root = validateLocalBoundary(current, registryPath, data);
        OwnershipReceipt receipt = receiptStore.read(registryPath, data, current);
        if (!receipt.equals(expectedReceipt)) {
            throw new IOException("selected preview provenance changed after the dialog opened");
        }
        CurrentUpdateState state = currentStateStore.read(
                registryPath, data, current, receipt);
        return previewValidated(registryPath, data, current, receipt, state, root, targetTag,
                progress, null, OperationContext.none(OperationType.UPDATE_PREVIEW));
    }

    public Preview preview(Path registry, InstallationRecord expectedRecord,
                           OwnershipReceipt expectedReceipt, String targetTag,
                           PrintStream progress, OperationContext context)
            throws IOException, InterruptedException, ManifestException {
        try (OperationContext.WorkerRegistration ignored = context.activate()) {
            context.phase(OperationPhase.METADATA,
                    "Validating the selected installation and provenance");
            Path registryPath = registry.toAbsolutePath().normalize();
            RegistryData data = registryStore.read(registryPath);
            InstallationRecord current = registryStore.resolve(data, expectedRecord.id());
            if (!current.equals(expectedRecord)) {
                throw new IOException("selected preview source changed after the dialog opened");
            }
            requireFixedChannel(registryPath, data, current);
            Path root = validateLocalBoundary(current, registryPath, data);
            OwnershipReceipt receipt = receiptStore.read(registryPath, data, current);
            if (!receipt.equals(expectedReceipt)) {
                throw new IOException("selected preview provenance changed after the dialog opened");
            }
            CurrentUpdateState state = currentStateStore.read(
                    registryPath, data, current, receipt);
            return previewValidated(registryPath, data, current, receipt, state, root, targetTag,
                    progress, null, context);
        }
    }

    public Preview preview(Path registry, InstallationRecord expectedRecord,
                           OwnershipReceipt expectedReceipt, String targetTag,
                           String expectedAssetName, long expectedSize, String expectedDigest,
                           PrintStream progress)
            throws IOException, InterruptedException, ManifestException {
        Path registryPath = registry.toAbsolutePath().normalize();
        RegistryData data = registryStore.read(registryPath);
        InstallationRecord current = registryStore.resolve(data, expectedRecord.id());
        if (!current.equals(expectedRecord)) {
            throw new IOException("selected recommended-update source changed after consent");
        }
        requireFixedChannel(registryPath, data, current);
        Path root = validateLocalBoundary(current, registryPath, data);
        OwnershipReceipt receipt = receiptStore.read(registryPath, data, current);
        if (!receipt.equals(expectedReceipt)) {
            throw new IOException("recommended-update provenance changed after consent");
        }
        CurrentUpdateState state = currentStateStore.read(registryPath, data, current, receipt);
        return previewValidated(registryPath, data, current, receipt, state, root, targetTag,
                progress, new VerifiedPackageFetcher.ExpectedAsset(expectedAssetName,
                        expectedSize, expectedDigest),
                OperationContext.none(OperationType.UPDATE_PREVIEW));
    }

    private Preview previewValidated(Path registryPath, RegistryData data,
                                     InstallationRecord record, OwnershipReceipt receipt,
                                     CurrentUpdateState current, Path root, String targetTag,
                                     PrintStream progress,
                                     VerifiedPackageFetcher.ExpectedAsset expected,
                                     OperationContext context)
            throws IOException, InterruptedException, ManifestException {
        PreparedMaterial material = prepareMaterial(registryPath, record, current, root, targetTag,
                progress, expected, context);
        try (VerifiedPackageFetcher.Workspace workspace = material.workspace()) {
            return material.preview();
        } finally {
            context.cleanupPhase("Removing the read-only preview workspace");
        }
    }

    PreparedUpdate prepare(Path registry, InstallationRecord expectedRecord,
                           OwnershipReceipt expectedReceipt,
                           CurrentUpdateState expectedCurrent, String targetTag,
                           VerifiedPackageFetcher.ExpectedAsset expected,
                           ChannelUpdateChecker.Recommendation recommendation,
                           Object owner, PrintStream progress)
            throws IOException, InterruptedException, ManifestException {
        return prepare(registry, expectedRecord, expectedReceipt, expectedCurrent, targetTag,
                expected, recommendation, owner, progress,
                OperationContext.none(OperationType.UPDATE_APPLY));
    }

    PreparedUpdate prepare(Path registry, InstallationRecord expectedRecord,
                           OwnershipReceipt expectedReceipt,
                           CurrentUpdateState expectedCurrent, String targetTag,
                           VerifiedPackageFetcher.ExpectedAsset expected,
                           ChannelUpdateChecker.Recommendation recommendation,
                           Object owner, PrintStream progress, OperationContext context)
            throws IOException, InterruptedException, ManifestException {
        try (OperationContext.WorkerRegistration ignored = context.activate()) {
            context.phase(OperationPhase.METADATA,
                    "Validating selected update source and provenance");
            Path registryPath = registry.toAbsolutePath().normalize();
            RegistryData data = registryStore.read(registryPath);
            InstallationRecord currentRecord = registryStore.resolve(data, expectedRecord.id());
            if (!currentRecord.equals(expectedRecord)) {
                throw new IOException("selected update source changed after download consent");
            }
            requireFixedChannel(registryPath, data, currentRecord);
            Path root = validateLocalBoundary(currentRecord, registryPath, data);
            OwnershipReceipt receipt = receiptStore.read(registryPath, data, currentRecord);
            if (!receipt.equals(expectedReceipt)) {
                throw new IOException("selected update provenance changed after download consent");
            }
            CurrentUpdateState current =
                    currentStateStore.read(registryPath, data, currentRecord, receipt);
            if (!current.equals(expectedCurrent)) {
                throw new IOException("selected update source changed after download consent");
            }
            PreparedMaterial material = prepareMaterial(
                    registryPath, currentRecord, current, root, targetTag, progress, expected,
                    context);
            OwnershipReceipt trustedReceipt = copyReceipt(receipt);
            CurrentUpdateState trustedCurrent = copyCurrent(current);
            OwnershipPolicy.Build trustedTarget = copyBuild(material.target());
            Preview publicPreview = copyPreview(material.preview());
            RealUpdateService.Snapshot source = new RealUpdateService.Snapshot(
                    registryPath, currentRecord, trustedReceipt, trustedCurrent, root);
            PreparedUpdate prepared = new PreparedUpdate(owner, source, publicPreview,
                    material.workspace(), trustedTarget, material.inspection(), material.pair(),
                    recommendation);
            context.phase(OperationPhase.AWAIT_CONSENT,
                    "Package verified; waiting for Apply confirmation");
            return prepared;
        }
    }

    private PreparedMaterial prepareMaterial(Path registryPath, InstallationRecord record,
                                             CurrentUpdateState current, Path root,
                                             String targetTag, PrintStream progress,
                                             VerifiedPackageFetcher.ExpectedAsset expected,
                                             OperationContext context)
            throws IOException, InterruptedException, ManifestException {
        OfficialRepository repository = OfficialRepository.parse(current.repository());
        Path workspaceParent = receiptStore.metadataDirectory(registryPath);
        VerifiedPackageFetcher.Workspace workspace = new VerifiedPackageFetcher(transport).fetch(
                repository, targetTag, workspaceParent, ".preview-", progress, expected, context);
        try {
            context.phase(OperationPhase.PLAN,
                    "Inspecting package contents and planning local changes");
            InstallationInspector inspector = new InstallationInspector();
            var targetInspection = inspector.inspect(workspace.extracted());
            if (targetInspection.products().stream()
                    .noneMatch(product -> product.key().equals(repository.requiredProduct()))) {
                throw new IOException("target package does not contain receipt application "
                        + repository.requiredProduct());
            }
            OwnershipPolicy.Build target = new OwnershipPolicy().build(
                    workspace.extracted(), repository, workspace.release().tag(), context,
                    OperationPhase.PLAN);
            ManifestReader reader = new ManifestReader();
            ManifestReader.PreviewPairValidation validation =
                    reader.validatePreviewPair(current.officialManifest(), target.manifest());
            Map<String, Set<String>> trusted = new HashMap<>();
            for (OverrideEntry override : current.overrides()) {
                trusted.put(CurrentStateStore.key(override.path()),
                        Set.copyOf(override.officialHashes()));
            }
            context.phase(OperationPhase.PLAN,
                    "Comparing local managed files with the verified package");
            List<Decision> decisions = new UpdatePlanner().planPreview(
                    current.officialManifest(), target.manifest(), root, validation,
                    Map.copyOf(trusted),
                    current.caseOverrides().stream().map(CaseOverride::foldedPrefix).toList(),
                    context, OperationPhase.PLAN);
            EnumMap<Action, Long> counts = new EnumMap<>(Action.class);
            for (Action action : Action.values()) counts.put(action, 0L);
            decisions.forEach(decision ->
                    counts.put(decision.action(), counts.get(decision.action()) + 1));
            Preview preview = new Preview(record, current.asReceipt(), workspace.release(),
                    workspace.asset(), workspace.resolvedDigest().canonical(), target.manifest(),
                    target.excludedPaths(), decisions, Map.copyOf(counts), current);
            return new PreparedMaterial(workspace, target, targetInspection, validation, preview);
        } catch (IOException | ManifestException | RuntimeException error) {
            try {
                workspace.close();
            } catch (IOException cleanup) {
                error.addSuppressed(cleanup);
            }
            throw error;
        }
    }

    private Path validateLocalBoundary(InstallationRecord record, Path registry, RegistryData data)
            throws IOException {
        if (Files.exists(UninstallService.pendingJournalPath(registry, record.id()),
                LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Uninstall recovery required");
        }
        Path root = Path.of(record.canonicalRoot()).toAbsolutePath().normalize();
        StrictPathSafety.requireDirectory(root, "registered root");
        Path reserved = root.resolve(".mm-launcher");
        if (Files.exists(reserved, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("real-package preview refuses sandbox state in the installation");
        }
        if (Files.exists(root.resolve(org.megamek.launcher.launch.RootCoordinator.UPDATE_NAMESPACE),
                LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("an interrupted update must be recovered before preview");
        }
        Path metadata = receiptStore.metadataDirectory(registry);
        for (InstallationRecord item : data.installations()) {
            Path other = Path.of(item.canonicalRoot()).toAbsolutePath().normalize();
            if (metadata.equals(other) || metadata.startsWith(other) || other.startsWith(metadata)) {
                throw new IOException("preview workspace overlaps registered installation: " + other);
            }
        }
        return root;
    }

    private void requireFixedChannel(Path registry, RegistryData data,
                                     InstallationRecord record) throws IOException {
        ChannelPreferenceStore.ReadResult fixed =
                channelPreferences.read(registry, data, record);
        if (fixed.status() != ChannelPreferenceStore.Status.CONFIGURED
                || fixed.preference() == null) {
            throw new IOException("fixed channel provenance is unavailable; this copy is "
                    + "launch-only: " + fixed.reason());
        }
        OwnershipReceipt receipt = receiptStore.read(registry, data, record);
        CurrentUpdateState current = currentStateStore.read(registry, data, record, receipt);
        new AdoptionStateStore(receiptStore).validateForUse(
                registry, record, receipt, current, fixed.preference());
    }

    private static Preview copyPreview(Preview preview) {
        return new Preview(preview.record(), copyReceipt(preview.baseline()),
                preview.targetRelease(), preview.targetAsset(), preview.resolvedAssetDigest(),
                copyManifest(preview.targetManifest()), List.copyOf(preview.targetExcludedPaths()),
                List.copyOf(preview.decisions()), Map.copyOf(preview.counts()),
                copyCurrent(preview.currentState()));
    }

    private static OwnershipPolicy.Build copyBuild(OwnershipPolicy.Build build) {
        return new OwnershipPolicy.Build(copyManifest(build.manifest()),
                List.copyOf(build.excludedPaths()));
    }

    private static OwnershipReceipt copyReceipt(OwnershipReceipt receipt) {
        return new OwnershipReceipt(receipt.schemaVersion(), receipt.ownershipPolicyVersion(),
                receipt.installationId(), receipt.canonicalRoot(), receipt.registeredAt(),
                receipt.repository(), receipt.tag(), receipt.assetName(), receipt.assetSize(),
                receipt.assetSha256(), copyManifest(receipt.officialManifest()),
                List.copyOf(receipt.excludedOfficialPaths()));
    }

    private static CurrentUpdateState copyCurrent(CurrentUpdateState current) {
        List<OverrideEntry> overrides = current.overrides().stream()
                .map(item -> new OverrideEntry(item.path(), item.kind(),
                        List.copyOf(item.officialHashes())))
                .toList();
        List<CaseOverride> cases = current.caseOverrides().stream()
                .map(item -> new CaseOverride(item.foldedPrefix(), item.localPrefix(),
                        item.officialSpellings()))
                .toList();
        return new CurrentUpdateState(current.schemaVersion(),
                current.ownershipPolicyVersion(), current.installationId(),
                current.canonicalRoot(), current.registeredAt(), current.repository(),
                current.tag(), current.assetName(), current.assetSize(),
                current.assetSha256(), copyManifest(current.officialManifest()),
                List.copyOf(current.excludedOfficialPaths()), overrides, cases,
                current.lastTransactionId());
    }

    private static Manifest copyManifest(Manifest manifest) {
        return new Manifest(manifest.schemaVersion(), manifest.product(), manifest.packageId(),
                manifest.releaseId(), List.copyOf(manifest.protectedPaths()),
                List.copyOf(manifest.files()));
    }

    public record Eligibility(InstallationRecord record, boolean available, String reason,
                              OwnershipReceipt receipt, CurrentUpdateState current) {
    }

    public record Preview(InstallationRecord record, OwnershipReceipt baseline,
                          ReleaseCatalog.Release targetRelease, ReleaseCatalog.Asset targetAsset,
                          String resolvedAssetDigest,
                          org.megamek.launcher.manifest.Manifest targetManifest,
                          List<String> targetExcludedPaths, List<Decision> decisions,
                          Map<Action, Long> counts, CurrentUpdateState currentState) {
        public Preview(InstallationRecord record, OwnershipReceipt baseline,
                       ReleaseCatalog.Release targetRelease, ReleaseCatalog.Asset targetAsset,
                       org.megamek.launcher.manifest.Manifest targetManifest,
                       List<String> targetExcludedPaths, List<Decision> decisions,
                       Map<Action, Long> counts) {
            this(record, baseline, targetRelease, targetAsset, targetManifest,
                    targetExcludedPaths, decisions, counts, null);
        }

        public Preview(InstallationRecord record, OwnershipReceipt baseline,
                       ReleaseCatalog.Release targetRelease, ReleaseCatalog.Asset targetAsset,
                       org.megamek.launcher.manifest.Manifest targetManifest,
                       List<String> targetExcludedPaths, List<Decision> decisions,
                       Map<Action, Long> counts, CurrentUpdateState currentState) {
            this(record, baseline, targetRelease, targetAsset,
                    targetAsset.digest(), targetManifest, targetExcludedPaths, decisions,
                    counts, currentState);
        }
    }

    private record PreparedMaterial(VerifiedPackageFetcher.Workspace workspace,
                                    OwnershipPolicy.Build target,
                                    org.megamek.launcher.onboarding.Inspection inspection,
                                    ManifestReader.PreviewPairValidation pair,
                                    Preview preview) {
    }
}
