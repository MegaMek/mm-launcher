package org.megamek.launcher.update;

import org.megamek.launcher.manifest.ManifestException;
import org.megamek.launcher.manifest.ManifestReader;
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

    public UpdatePreviewService(ReleaseTransport transport) {
        this(transport, new RegistryStore(), new ReceiptStore());
    }

    UpdatePreviewService(ReleaseTransport transport, RegistryStore registryStore,
                         ReceiptStore receiptStore) {
        this.transport = transport;
        this.registryStore = registryStore;
        this.receiptStore = receiptStore;
        this.currentStateStore = new CurrentStateStore(receiptStore);
    }

    public Eligibility eligibility(Path registry, String id) throws IOException {
        RegistryData data = registryStore.read(registry);
        InstallationRecord record = registryStore.resolve(data, id);
        try {
            validateLocalBoundary(record, registry, data);
            OwnershipReceipt receipt = receiptStore.read(registry, data, record);
            CurrentUpdateState current = currentStateStore.read(registry, data, record, receipt);
            return new Eligibility(record, true, "Verified update provenance is valid", receipt,
                    current);
        } catch (IOException e) {
            return new Eligibility(record, false, e.getMessage(), null, null);
        }
    }

    public Preview preview(Path registry, String id, String targetTag, PrintStream progress)
            throws IOException, InterruptedException, ManifestException {
        Path registryPath = registry.toAbsolutePath().normalize();
        RegistryData data = registryStore.read(registryPath);
        InstallationRecord record = registryStore.resolve(data, id);
        // All local provenance and placement checks deliberately precede any network request.
        Path root = validateLocalBoundary(record, registryPath, data);
        OwnershipReceipt receipt = receiptStore.read(registryPath, data, record);
        CurrentUpdateState current = currentStateStore.read(
                registryPath, data, record, receipt);
        return previewValidated(registryPath, data, record, receipt, current, root, targetTag,
                progress, null);
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
        Path root = validateLocalBoundary(current, registryPath, data);
        OwnershipReceipt receipt = receiptStore.read(registryPath, data, current);
        if (!receipt.equals(expectedReceipt)) {
            throw new IOException("selected preview provenance changed after the dialog opened");
        }
        CurrentUpdateState state = currentStateStore.read(
                registryPath, data, current, receipt);
        return previewValidated(registryPath, data, current, receipt, state, root, targetTag,
                progress, null);
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
        Path root = validateLocalBoundary(current, registryPath, data);
        OwnershipReceipt receipt = receiptStore.read(registryPath, data, current);
        if (!receipt.equals(expectedReceipt)) {
            throw new IOException("recommended-update provenance changed after consent");
        }
        CurrentUpdateState state = currentStateStore.read(registryPath, data, current, receipt);
        return previewValidated(registryPath, data, current, receipt, state, root, targetTag,
                progress, new VerifiedPackageFetcher.ExpectedAsset(expectedAssetName,
                        expectedSize, expectedDigest));
    }

    private Preview previewValidated(Path registryPath, RegistryData data,
                                     InstallationRecord record, OwnershipReceipt receipt,
                                     CurrentUpdateState current, Path root, String targetTag,
                                     PrintStream progress,
                                     VerifiedPackageFetcher.ExpectedAsset expected)
            throws IOException, InterruptedException, ManifestException {
        OfficialRepository repository = OfficialRepository.parse(current.repository());
        Path workspaceParent = receiptStore.metadataDirectory(registryPath);

        try (VerifiedPackageFetcher.Workspace workspace =
                     new VerifiedPackageFetcher(transport).fetch(repository, targetTag,
                                      workspaceParent, ".preview-", progress, expected)) {
            if (new InstallationInspector().inspect(workspace.extracted()).products().stream()
                    .noneMatch(product -> product.key().equals(repository.requiredProduct()))) {
                throw new IOException("target package does not contain receipt application "
                        + repository.requiredProduct());
            }
            OwnershipPolicy.Build target = new OwnershipPolicy().build(
                    workspace.extracted(), repository, workspace.release().tag());
            ManifestReader reader = new ManifestReader();
            ManifestReader.PreviewPairValidation validation =
                    reader.validatePreviewPair(current.officialManifest(), target.manifest());
            Map<String, Set<String>> trusted = new HashMap<>();
            for (OverrideEntry override : current.overrides()) {
                trusted.put(CurrentStateStore.key(override.path()),
                        Set.copyOf(override.officialHashes()));
            }
            List<Decision> decisions = new UpdatePlanner().planPreview(
                    current.officialManifest(), target.manifest(), root, validation,
                    Map.copyOf(trusted),
                    current.caseOverrides().stream().map(CaseOverride::foldedPrefix).toList());
            EnumMap<Action, Long> counts = new EnumMap<>(Action.class);
            for (Action action : Action.values()) counts.put(action, 0L);
            decisions.forEach(decision ->
                    counts.put(decision.action(), counts.get(decision.action()) + 1));
            return new Preview(record, current.asReceipt(), workspace.release(), workspace.asset(),
                    target.manifest(), target.excludedPaths(), decisions, Map.copyOf(counts),
                    current);
        }
    }

    private Path validateLocalBoundary(InstallationRecord record, Path registry, RegistryData data)
            throws IOException {
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

    public record Eligibility(InstallationRecord record, boolean available, String reason,
                              OwnershipReceipt receipt, CurrentUpdateState current) {
    }

    public record Preview(InstallationRecord record, OwnershipReceipt baseline,
                          ReleaseCatalog.Release targetRelease, ReleaseCatalog.Asset targetAsset,
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
    }
}
