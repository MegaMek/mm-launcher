package org.megamek.launcher.gui;

import org.megamek.launcher.channel.ChannelPreference;
import org.megamek.launcher.channel.ChannelPreferenceStore;
import org.megamek.launcher.channel.ChannelUpdateChecker;
import org.megamek.launcher.channel.FollowChannel;
import org.megamek.launcher.channel.OfficialYamlChannelCatalog;
import org.megamek.launcher.diagnostics.OperationLogStore;
import org.megamek.launcher.diagnostics.SanitizedErrors;
import org.megamek.launcher.launch.ApplicationLauncher;
import org.megamek.launcher.launch.JavaRuntime;
import org.megamek.launcher.launch.RootCoordinator;
import org.megamek.launcher.onboarding.ExistingImportService;
import org.megamek.launcher.onboarding.Inspection;
import org.megamek.launcher.onboarding.InstallationInspector;
import org.megamek.launcher.onboarding.NormalInstallService;
import org.megamek.launcher.operation.OperationContext;
import org.megamek.launcher.operation.OperationOutcome;
import org.megamek.launcher.operation.OperationPhase;
import org.megamek.launcher.operation.OperationProgressListener;
import org.megamek.launcher.operation.OperationType;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryData;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.release.FreshInstaller;
import org.megamek.launcher.release.JavaReleaseTransport;
import org.megamek.launcher.release.OfficialRepository;
import org.megamek.launcher.release.ReleaseCatalog;
import org.megamek.launcher.release.ReleaseTransport;
import org.megamek.launcher.update.UpdatePreviewService;
import org.megamek.launcher.update.OwnershipReceipt;
import org.megamek.launcher.update.CurrentUpdateState;
import org.megamek.launcher.update.PreparedUpdate;
import org.megamek.launcher.update.PreparedUpdateService;
import org.megamek.launcher.update.RealUpdateService;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;

/**
 * GUI-facing boundary around the accepted backends.  It contains no Swing code, so consent and
 * process tests can exercise it with fake transports/runners and disposable registries.
 */
public class LauncherServices {
    private final Path registry;
    private final RegistryStore store;
    private final InstallationInspector inspector;
    private final ReleaseTransport transport;
    private final JavaRuntime javaRuntime;
    private final ApplicationLauncher applicationLauncher;
    private final RootCoordinator updateCoordinator;
    private final PreparedUpdateService preparedUpdates;
    private final OperationLogStore operationLogs;
    private final NormalInstallService normalInstalls;
    private final ExistingImportService existingImports;
    private final LauncherSettingsStore settings;

    public LauncherServices(Path registry) {
        this(registry, new RegistryStore(), new InstallationInspector(),
                new JavaReleaseTransport(), new JavaRuntime(), new ApplicationLauncher());
    }

    public LauncherServices(Path registry, RegistryStore store, InstallationInspector inspector,
                            ReleaseTransport transport, JavaRuntime javaRuntime,
                            ApplicationLauncher applicationLauncher) {
        this(registry, store, inspector, transport, javaRuntime, applicationLauncher,
                new RootCoordinator());
    }

    public LauncherServices(Path registry, RegistryStore store, InstallationInspector inspector,
                            ReleaseTransport transport, JavaRuntime javaRuntime,
                            ApplicationLauncher applicationLauncher,
                            RootCoordinator updateCoordinator) {
        this.registry = registry.toAbsolutePath().normalize();
        this.store = store;
        this.inspector = inspector;
        this.transport = transport;
        this.javaRuntime = javaRuntime;
        this.applicationLauncher = applicationLauncher;
        this.updateCoordinator = updateCoordinator;
        this.preparedUpdates = new PreparedUpdateService(transport, updateCoordinator);
        this.operationLogs = new OperationLogStore(this.registry);
        this.settings = new LauncherSettingsStore(this.registry);
        this.normalInstalls = new NormalInstallService(this.registry, transport, javaRuntime,
                () -> {
                    LauncherSettingsStore.CheckConfiguration current =
                            settings.readCheckConfiguration();
                    return new NormalInstallService.CheckConfiguration(
                            current.settings().checkNewInstallsOnOpen(), current.revision());
                });
        this.existingImports = new ExistingImportService(this.registry, store, inspector,
                javaRuntime);
    }

    public Path registry() {
        return registry;
    }

    public String latestMilestoneVersion() throws IOException, InterruptedException {
        return new OfficialYamlChannelCatalog(transport)
                .target(FollowChannel.MILESTONE, OfficialRepository.MEKHQ).version();
    }

    public LoggedOperation beginOperation(OperationType type,
                                          OperationProgressListener listener,
                                          List<Path> potentialInstallationRoots) {
        OperationLogStore.Collector collector =
                operationLogs.collector(potentialInstallationRoots);
        OperationContext context = new OperationContext(type,
                OperationProgressListener.combine(collector, listener));
        return new LoggedOperation(context, collector);
    }

    public String operationLogsForViewer() throws IOException {
        return operationLogs.renderForViewer();
    }

    public Path operationLogLocation() {
        return operationLogs.defaultDirectory();
    }

    public Path normalInstallDestination() throws IOException {
        return normalInstalls.defaultDestination();
    }

    public NormalInstallService.Plan prepareNormalInstall(Path destination, Path selectedJava)
            throws IOException, InterruptedException {
        return prepareNormalInstall(FollowChannel.MILESTONE, destination, selectedJava);
    }

    public NormalInstallService.Plan prepareNormalInstall(FollowChannel channel, Path destination,
                                                          Path selectedJava)
            throws IOException, InterruptedException {
        return normalInstalls.prepare(channel, destination, selectedJava);
    }

    public NormalInstallService.Result installNormal(NormalInstallService.Plan plan,
                                                     PrintStream progress,
                                                     OperationContext context)
            throws IOException, InterruptedException {
        return normalInstalls.install(plan, progress, context);
    }

    public LauncherSettingsStore.Settings settings() throws IOException {
        return settings.read();
    }

    public LauncherSettingsStore.Settings setCheckNewInstallsOnOpen(boolean enabled)
            throws IOException {
        ensureRegistryParent();
        return settings.write(enabled);
    }

    public String recordGuiError(String title, Throwable error) {
        OperationLogStore.Collector collector = operationLogs.collector(List.of());
        OperationContext context = new OperationContext(OperationType.GUI_ERROR, collector);
        try {
            context.phase(OperationPhase.METADATA, SanitizedErrors.text(title));
        } catch (InterruptedException impossible) {
            Thread.currentThread().interrupt();
        }
        context.finish(OperationOutcome.FAILED, "Launcher error");
        try {
            return "Saved local diagnostics to " + collector.persist(error);
        } catch (IOException | RuntimeException loggingFailure) {
            return "Local diagnostics could not be saved: "
                    + SanitizedErrors.display(loggingFailure);
        }
    }

    public RegistryData readRegistry() throws IOException {
        try {
            Files.readAttributes(registry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            verifyAbsentRegistryPath();
            return new RegistryData(RegistryStore.SCHEMA, null, List.of());
        }
        return store.read(registry);
    }

    public HomeState loadHome() throws IOException {
        RegistryData data = readRegistry();
        if (data.defaultInstallationId() == null) {
            return new HomeState(data, null, null, null, null, false, null);
        }
        return loadInstallation(data, store.resolve(data, null));
    }

    public HomeState loadInstallation(String id) throws IOException {
        RegistryData data = readRegistry();
        return loadInstallation(data, store.resolve(data, id));
    }

    private HomeState loadInstallation(RegistryData data, InstallationRecord record)
            throws IOException {
        boolean pending = pending(record);
        UpdatePreviewService.Eligibility eligibility =
                new UpdatePreviewService(transport).eligibility(registry, record.id());
        ChannelPreferenceStore.ReadResult channel =
                new ChannelPreferenceStore().read(registry, data, record);
        try {
            Inspection current = inspector.inspect(Path.of(record.canonicalRoot()));
            if (!current.canonicalRoot().equals(record.canonicalRoot())
                    || !current.observedBuild().equals(record.observedBuild())
                    || !current.products().equals(record.products())) {
                return new HomeState(data, record, null,
                        "The preferred copy no longer matches its registered build or program "
                                + "layout. Use Manage installations to remove it, select another "
                                + "copy, or recover an interrupted update.", eligibility, pending,
                        channel);
            }
            return new HomeState(data, record, current, null, eligibility, pending, channel);
        } catch (IOException | RuntimeException e) {
            return new HomeState(data, record, null,
                    "The preferred copy is unavailable: " + detail(e)
                            + ". Use recovery if an update was interrupted, or Manage "
                            + "installations.", eligibility, pending, channel);
        }
    }

    public Inspection inspect(Path root) throws IOException {
        return inspector.inspect(root);
    }

    public InstallationRecord register(String name, Path root) throws IOException {
        ensureRegistryParent();
        return store.register(registry, name, root, null);
    }

    public ExistingImportService.Plan prepareExistingImport(Path root, OperationContext context)
            throws IOException, InterruptedException {
        return existingImports.prepare(root, context);
    }

    public ExistingImportService.Result importExisting(ExistingImportService.Plan plan,
                                                       String name,
                                                       OperationContext context)
            throws IOException, InterruptedException {
        return existingImports.register(plan, name, context);
    }

    public void select(InstallationRecord record) throws IOException {
        store.select(registry, record.id());
    }

    public void remove(InstallationRecord record) throws IOException {
        store.remove(registry, record.id());
    }

    public List<Path> javaCandidates() {
        return javaRuntime.candidates();
    }

    public int selectJava(InstallationRecord record, Path selected)
            throws IOException, InterruptedException {
        Path executable = javaRuntime.resolve(selected.toString());
        if (executable.startsWith(Path.of(record.canonicalRoot()))) {
            throw new IOException("selected Java must be external to the application directory");
        }
        int feature = javaRuntime.validate(executable, Path.of(record.canonicalRoot()));
        store.selectJava(registry, record.id(), executable.toString());
        return feature;
    }

    public List<String> preview(InstallationRecord record, String product)
            throws IOException, InterruptedException {
        return applicationLauncher.command(record, product);
    }

    public int launch(InstallationRecord record, String product)
            throws IOException, InterruptedException {
        return applicationLauncher.launch(record, product);
    }

    public ReleaseCatalog.Page releases(OfficialRepository repository, int page)
            throws IOException, InterruptedException {
        return new ReleaseCatalog(transport).list(repository, page, 10);
    }

    public ReleaseCatalog.Assessment assess(OfficialRepository repository,
                                             ReleaseCatalog.Release release) {
        return new ReleaseCatalog(transport).assess(repository, release);
    }

    public FreshInstaller.Result install(OfficialRepository repository, String tag,
                                         Path destination, String name, PrintStream progress)
            throws IOException, InterruptedException {
        ensureRegistryParent();
        return new FreshInstaller(transport).install(repository, tag, destination, registry,
                name, progress);
    }

    public FreshInstaller.Result install(OfficialRepository repository, String tag,
                                         Path destination, String name, PrintStream progress,
                                         OperationContext context)
            throws IOException, InterruptedException {
        ensureRegistryParent();
        return new FreshInstaller(transport).install(repository, tag, destination, registry,
                name, progress, context);
    }

    public FreshInstaller.Result install(OfficialRepository repository, String tag,
                                         Path destination, String name, FollowChannel channel,
                                         PrintStream progress)
            throws IOException, InterruptedException {
        if (channel == null) throw new IOException("choose a channel for the new installation");
        FreshInstaller.Result result = install(repository, tag, destination, name, progress);
        try {
            new ChannelPreferenceStore().set(registry, result.record(), channel,
                    settings.read().checkNewInstallsOnOpen());
        } catch (IOException error) {
            throw new IOException("installation is valid and REGISTERED at "
                    + result.destination() + " but the CHANNEL SETTING WAS NOT PERSISTED: "
                    + detail(error) + ". Keep using the retained copy and choose its channel "
                    + "again.", error);
        }
        return result;
    }

    public FreshInstaller.Result install(OfficialRepository repository, String tag,
                                         Path destination, String name, FollowChannel channel,
                                         PrintStream progress, OperationContext context)
            throws IOException, InterruptedException {
        if (channel == null) throw new IOException("choose a channel for the new installation");
        FreshInstaller.Result result = install(
                repository, tag, destination, name, progress, context);
        try {
            new ChannelPreferenceStore().set(registry, result.record(), channel,
                    settings.read().checkNewInstallsOnOpen());
        } catch (IOException error) {
            throw new IOException("installation is valid and REGISTERED at "
                    + result.destination() + " but the CHANNEL SETTING WAS NOT PERSISTED: "
                    + detail(error) + ". Keep using the retained copy and choose its channel "
                    + "again.", error);
        }
        return result;
    }

    public ChannelPreference setChannel(InstallationRecord record, FollowChannel channel,
                                        boolean checkOnOpen) throws IOException {
        return new ChannelPreferenceStore().set(registry, record, channel, checkOnOpen);
    }

    public ChannelPreferenceStore.ReadResult channelPreference(InstallationRecord expected)
            throws IOException {
        RegistryData data = readRegistry();
        InstallationRecord current = store.resolve(data, expected.id());
        if (!current.equals(expected)) {
            throw new IOException("selected installation changed while reading its channel");
        }
        return new ChannelPreferenceStore().read(registry, data, current);
    }

    /** Local-only stale-result guard; it never requests release or channel metadata. */
    public boolean isCheckBindingCurrent(InstallationRecord expected,
                                         ChannelPreference expectedPreference)
            throws IOException {
        RegistryData data = readRegistry();
        InstallationRecord current = store.resolve(data, expected.id());
        if (!current.equals(expected)) return false;
        ChannelPreferenceStore.ReadResult preference =
                new ChannelPreferenceStore().read(registry, data, current);
        return preference.status() == ChannelPreferenceStore.Status.CONFIGURED
                && preference.preference().equals(expectedPreference);
    }

    /** Local provenance/preference gate for automatic checks; imported copies remain launch-only. */
    public boolean canCheckOnOpen(InstallationRecord expected) throws IOException {
        RegistryData data = readRegistry();
        InstallationRecord current = store.resolve(data, expected.id());
        if (!current.equals(expected)) return false;
        ChannelPreferenceStore.ReadResult preference =
                new ChannelPreferenceStore().read(registry, data, current);
        if (preference.status() != ChannelPreferenceStore.Status.CONFIGURED
                || !preference.preference().checkOnOpen()) return false;
        UpdatePreviewService.Eligibility eligibility =
                new UpdatePreviewService(transport).eligibility(registry, current.id());
        return eligibility.available();
    }

    public ChannelUpdateChecker.Result checkUpdates(InstallationRecord expected)
            throws IOException, InterruptedException {
        ChannelUpdateChecker.Result result =
                new ChannelUpdateChecker(registry, transport).check(expected.id());
        if (!result.record().equals(expected)) {
            throw new IOException("selected installation changed while checking for updates");
        }
        return result;
    }

    public UpdatePreviewService.Preview previewRecommended(
            InstallationRecord record, OwnershipReceipt receipt,
            ChannelUpdateChecker.Result expected, PrintStream progress)
            throws IOException, InterruptedException,
            org.megamek.launcher.manifest.ManifestException {
        ChannelUpdateChecker.Recommendation recommendation =
                requireCurrentRecommendation(record, expected);
        return new UpdatePreviewService(transport).preview(registry, record, receipt,
                recommendation.targetTag(), recommendation.assetName(),
                recommendation.assetSize(), recommendation.assetDigest(), progress);
    }

    public UpdatePreviewService.Preview previewUpdate(InstallationRecord record, String tag,
                                                       PrintStream progress)
            throws IOException, InterruptedException,
            org.megamek.launcher.manifest.ManifestException {
        return new UpdatePreviewService(transport).preview(registry, record.id(), tag, progress);
    }

    public UpdatePreviewService.Preview previewUpdate(InstallationRecord record,
                                                       OwnershipReceipt receipt, String tag,
                                                       PrintStream progress)
            throws IOException, InterruptedException,
            org.megamek.launcher.manifest.ManifestException {
        return new UpdatePreviewService(transport).preview(
                registry, record, receipt, tag, progress);
    }

    public UpdatePreviewService.Preview previewUpdate(InstallationRecord record,
                                                       OwnershipReceipt receipt, String tag,
                                                       PrintStream progress,
                                                       OperationContext context)
            throws IOException, InterruptedException,
            org.megamek.launcher.manifest.ManifestException {
        return new UpdatePreviewService(transport).preview(
                registry, record, receipt, tag, progress, context);
    }

    public PreparedUpdate prepareUpdate(InstallationRecord record, OwnershipReceipt receipt,
                                        CurrentUpdateState current, String tag,
                                        String assetName, long size, String digest,
                                        PrintStream progress)
            throws IOException, InterruptedException,
            org.megamek.launcher.manifest.ManifestException {
        return preparedUpdates.prepare(registry, record, receipt, current, tag, assetName, size,
                digest, progress);
    }

    public PreparedUpdate prepareUpdate(InstallationRecord record, OwnershipReceipt receipt,
                                        CurrentUpdateState current, String tag,
                                        String assetName, long size, String digest,
                                        PrintStream progress, OperationContext context)
            throws IOException, InterruptedException,
            org.megamek.launcher.manifest.ManifestException {
        return preparedUpdates.prepare(registry, record, receipt, current, tag, assetName, size,
                digest, progress, context);
    }

    public PreparedUpdate prepareRecommended(
            InstallationRecord record, OwnershipReceipt receipt, CurrentUpdateState current,
            ChannelUpdateChecker.Result expected, PrintStream progress)
            throws IOException, InterruptedException,
            org.megamek.launcher.manifest.ManifestException {
        return preparedUpdates.prepareRecommended(
                registry, record, receipt, current, expected, progress);
    }

    public PreparedUpdate prepareRecommended(
            InstallationRecord record, OwnershipReceipt receipt, CurrentUpdateState current,
            ChannelUpdateChecker.Result expected, PrintStream progress, OperationContext context)
            throws IOException, InterruptedException,
            org.megamek.launcher.manifest.ManifestException {
        return preparedUpdates.prepareRecommended(
                registry, record, receipt, current, expected, progress, context);
    }

    public RealUpdateService.ApplyResult applyPrepared(PreparedUpdate prepared,
                                                       String confirmation,
                                                       PrintStream progress)
            throws IOException, InterruptedException,
            org.megamek.launcher.manifest.ManifestException {
        return preparedUpdates.apply(prepared, confirmation, progress);
    }

    public RealUpdateService.ApplyResult applyPrepared(PreparedUpdate prepared,
                                                       String confirmation,
                                                       PrintStream progress,
                                                       OperationContext context)
            throws IOException, InterruptedException,
            org.megamek.launcher.manifest.ManifestException {
        return preparedUpdates.apply(prepared, confirmation, progress, context);
    }

    public RealUpdateService.ApplyResult applyUpdate(InstallationRecord expectedRecord,
                                                     CurrentUpdateState expectedState,
                                                     String tag, long size, String digest,
                                                     String confirmation, PrintStream progress)
            throws IOException, InterruptedException,
            org.megamek.launcher.manifest.ManifestException {
        RealUpdateService service = new RealUpdateService(transport, updateCoordinator);
        RealUpdateService.Snapshot snapshot = service.snapshot(registry, expectedRecord.id());
        if (!snapshot.record().equals(expectedRecord)
                || !snapshot.current().equals(expectedState)) {
            throw new IOException("selected update source changed; preview and consent again");
        }
        return service.apply(snapshot, tag, size, digest, confirmation, progress);
    }

    public RealUpdateService.ApplyResult applyRecommended(
            InstallationRecord expectedRecord, CurrentUpdateState expectedState,
            ChannelUpdateChecker.Result expected, String confirmation, PrintStream progress)
            throws IOException, InterruptedException,
            org.megamek.launcher.manifest.ManifestException {
        ChannelUpdateChecker.Recommendation recommendation =
                requireCurrentRecommendation(expectedRecord, expected);
        RealUpdateService service = new RealUpdateService(transport, updateCoordinator);
        RealUpdateService.Snapshot snapshot = service.snapshot(registry, expectedRecord.id());
        if (!snapshot.record().equals(expectedRecord)
                || !snapshot.current().equals(expectedState)) {
            throw new IOException("selected recommended-update source changed; check, preview, "
                    + "and consent again");
        }
        return service.apply(snapshot, recommendation.targetTag(), recommendation.assetName(),
                recommendation.assetSize(), recommendation.assetDigest(), confirmation, progress);
    }

    private ChannelUpdateChecker.Recommendation requireCurrentRecommendation(
            InstallationRecord record, ChannelUpdateChecker.Result expected)
            throws IOException, InterruptedException {
        if (expected == null || !expected.updateAvailable()
                || !expected.record().equals(record)) {
            throw new IOException("recommended update is missing or belongs to another installation");
        }
        ChannelUpdateChecker.Recommendation captured = expected.recommendation();
        if (!captured.installationId().equals(record.id())
                || !captured.canonicalRoot().equals(record.canonicalRoot())
                || !captured.registeredAt().equals(record.registeredAt())) {
            throw new IOException("recommended update installation binding is stale");
        }
        ChannelUpdateChecker.Result fresh =
                new ChannelUpdateChecker(registry, transport).check(record.id());
        if (!fresh.updateAvailable() || !fresh.recommendation().equals(captured)) {
            throw new IOException("channel source, preference, or target metadata changed; "
                    + "check again and consent to the new result");
        }
        return captured;
    }

    public RealUpdateService.RecoveryResult recoverUpdate(InstallationRecord record,
                                                           String confirmation)
            throws IOException, org.megamek.launcher.manifest.ManifestException {
        return new RealUpdateService(transport, updateCoordinator)
                .recover(registry, record.id(), confirmation);
    }

    public RealUpdateService.RecoveryResult recoverUpdate(InstallationRecord record,
                                                           String confirmation,
                                                           OperationContext context)
            throws IOException, InterruptedException,
            org.megamek.launcher.manifest.ManifestException {
        return new RealUpdateService(transport, updateCoordinator)
                .recover(registry, record.id(), confirmation, context);
    }

    private boolean pending(InstallationRecord record) {
        try {
            return new RealUpdateService(transport, updateCoordinator)
                    .hasPending(Path.of(record.canonicalRoot()));
        } catch (IOException | RuntimeException e) {
            return true; // unreadable reserved state is a blocker, never success-shaped absence
        }
    }

    private void ensureRegistryParent() throws IOException {
        Path parent = registry.getParent();
        if (parent == null) throw new IOException("registry has no parent directory: " + registry);
        if (Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(parent)) {
                throw new IOException("registry parent must be a real directory: " + parent);
            }
            return;
        }
        Path grandparent = parent.getParent();
        if (grandparent == null || !Files.isDirectory(grandparent, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(grandparent)) {
            throw new IOException("registry parent cannot be created safely; its parent must "
                    + "already be a real directory: " + grandparent);
        }
        Files.createDirectory(parent);
    }

    private void verifyAbsentRegistryPath() throws IOException {
        Path current = registry.getRoot();
        for (Path segment : registry) {
            current = current == null ? segment : current.resolve(segment);
            if (current.equals(registry)) break;
            try {
                BasicFileAttributes attributes = Files.readAttributes(current,
                        BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || !attributes.isDirectory()) {
                    throw new IOException("registry path traverses a link or non-directory: "
                            + current);
                }
            } catch (NoSuchFileException e) {
                return; // A missing component makes the registry genuinely absent.
            }
        }
    }

    private static String detail(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    public record HomeState(RegistryData registry, InstallationRecord preferred,
                            Inspection currentInspection, String preferredError,
                            UpdatePreviewService.Eligibility previewEligibility,
                            boolean pendingUpdate,
                            ChannelPreferenceStore.ReadResult channelPreference) {
        public HomeState(RegistryData registry, InstallationRecord preferred,
                         Inspection currentInspection, String preferredError,
                         UpdatePreviewService.Eligibility previewEligibility,
                         boolean pendingUpdate) {
            this(registry, preferred, currentInspection, preferredError, previewEligibility,
                    pendingUpdate, null);
        }

        public HomeState(RegistryData registry, InstallationRecord preferred,
                         Inspection currentInspection, String preferredError) {
            this(registry, preferred, currentInspection, preferredError, null, false, null);
        }
    }

    public static final class LoggedOperation {
        private final OperationContext context;
        private final OperationLogStore.Collector collector;

        private LoggedOperation(OperationContext context,
                                OperationLogStore.Collector collector) {
            this.context = context;
            this.collector = collector;
        }

        public OperationContext context() {
            return context;
        }

        public String finish(OperationOutcome outcome, String detail, Throwable error) {
            context.finish(outcome, detail);
            try {
                collector.persist(error);
                return null;
            } catch (IOException | RuntimeException loggingFailure) {
                return "Local operation log could not be saved: "
                        + SanitizedErrors.display(loggingFailure)
                        + ". The operation result above is unchanged.";
            }
        }
    }
}
