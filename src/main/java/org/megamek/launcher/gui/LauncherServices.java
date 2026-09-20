package org.megamek.launcher.gui;

import org.megamek.launcher.channel.ChannelPreference;
import org.megamek.launcher.channel.ChannelPreferenceStore;
import org.megamek.launcher.channel.ChannelUpdateChecker;
import org.megamek.launcher.channel.FollowChannel;
import org.megamek.launcher.channel.OfficialYamlChannelCatalog;
import org.megamek.launcher.channel.QuickInstallOption;
import org.megamek.launcher.channel.QuickInstallSnapshot;
import org.megamek.launcher.diagnostics.OperationLogStore;
import org.megamek.launcher.diagnostics.SanitizedErrors;
import org.megamek.launcher.launch.ApplicationLauncher;
import org.megamek.launcher.launch.JavaRuntime;
import org.megamek.launcher.launch.RootCoordinator;
import org.megamek.launcher.onboarding.ExistingImportService;
import org.megamek.launcher.onboarding.Inspection;
import org.megamek.launcher.onboarding.InstallationInspector;
import org.megamek.launcher.onboarding.NormalInstallService;
import org.megamek.launcher.onboarding.PlatformInstallLocations;
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
import org.megamek.launcher.update.ImportedCopyAdoptionService;
import org.megamek.launcher.update.PreparedAdoption;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
    private final ImportedCopyAdoptionService adoptions;
    private final LauncherSettingsStore settings;

    public LauncherServices(Path registry) {
        this(registry, new RegistryStore(), new InstallationInspector(),
                new JavaReleaseTransport(), new JavaRuntime(), new ApplicationLauncher());
    }

    public LauncherServices(Path registry, PlatformInstallLocations installLocations) {
        this(registry, new RegistryStore(), new InstallationInspector(),
                new JavaReleaseTransport(), new JavaRuntime(), new ApplicationLauncher(),
                new RootCoordinator(), installLocations);
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
        this(registry, store, inspector, transport, javaRuntime, applicationLauncher,
                updateCoordinator, PlatformInstallLocations.customRegistry());
    }

    public LauncherServices(Path registry, RegistryStore store, InstallationInspector inspector,
                            ReleaseTransport transport, JavaRuntime javaRuntime,
                            ApplicationLauncher applicationLauncher,
                            RootCoordinator updateCoordinator,
                            PlatformInstallLocations installLocations) {
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
                }, installLocations);
        this.existingImports = new ExistingImportService(this.registry, store, inspector,
                javaRuntime);
        this.adoptions = new ImportedCopyAdoptionService(this.registry, transport,
                updateCoordinator);
    }

    public Path registry() {
        return registry;
    }

    public QuickInstallSnapshot quickInstallSnapshot()
            throws IOException, InterruptedException {
        return new OfficialYamlChannelCatalog(transport).quickInstallSnapshot();
    }

    /**
     * Resolves only the requested current product/channel target: one bounded website YAML read
     * followed by one exact repository/tag lookup, with no historical release enumeration.
     */
    public QuickInstallOption quickInstallOption(OfficialRepository repository,
                                                  FollowChannel channel)
            throws IOException, InterruptedException {
        if (repository == null || channel == null) {
            throw new IOException("product and channel are required");
        }
        QuickInstallOption.Key key = new QuickInstallOption.Key(repository, channel);
        return new QuickInstallOption(key,
                new OfficialYamlChannelCatalog(transport).target(channel, repository));
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

    public Path normalInstallDestination(OfficialRepository repository, FollowChannel channel)
            throws IOException {
        return normalInstalls.defaultDestination(repository, channel);
    }

    public NormalInstallService.Plan prepareNormalInstall(Path destination, Path selectedJava)
            throws IOException, InterruptedException {
        return prepareNormalInstall(FollowChannel.MILESTONE, destination, selectedJava);
    }

    public NormalInstallService.Plan prepareNormalInstall(FollowChannel channel, Path destination,
                                                          Path selectedJava)
            throws IOException, InterruptedException {
        return prepareNormalInstall(OfficialRepository.MEKHQ, channel, destination, selectedJava);
    }

    public NormalInstallService.Plan prepareNormalInstall(OfficialRepository repository,
                                                          FollowChannel channel,
                                                          Path destination, Path selectedJava)
            throws IOException, InterruptedException {
        Path selected = selectedJava == null ? configuredDefaultJava() : selectedJava;
        return normalInstalls.prepare(repository, channel, destination, selected);
    }

    public NormalInstallService.Plan prepareCapturedNormalInstall(QuickInstallOption option,
                                                                  Path destination,
                                                                  Path selectedJava)
            throws IOException, InterruptedException {
        Path selected = selectedJava == null ? configuredDefaultJava() : selectedJava;
        return normalInstalls.prepareCapturedCurrent(option, destination, selected);
    }

    public NormalInstallService.Plan prepareExactNormalInstall(OfficialRepository repository,
                                                               FollowChannel futureChannel,
                                                               String tag, Path destination,
                                                               Path selectedJava)
            throws IOException, InterruptedException {
        Path selected = selectedJava == null ? configuredDefaultJava() : selectedJava;
        return normalInstalls.prepareExact(repository, futureChannel, tag, destination, selected);
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

    public LauncherSettingsStore.Settings setCheckInstalledVersionsOnOpen(boolean enabled)
            throws IOException {
        ensureRegistryParent();
        return settings.writeAutomaticChecks(enabled);
    }

    public SettingsView settingsView() throws IOException, InterruptedException {
        LauncherSettingsStore.Settings current = settings.read();
        Path java;
        int feature;
        boolean persisted = current.defaultJavaExecutable() != null;
        if (persisted) {
            java = javaRuntime.resolve(current.defaultJavaExecutable());
            feature = javaRuntime.validate(java, java.getParent());
            if (feature != current.defaultJavaFeature()) {
                throw new IOException("configured default Java version changed; choose it again");
            }
        } else {
            java = javaRuntime.currentExecutable();
            feature = javaRuntime.validate(java, java.getParent());
        }
        return new SettingsView(current, java, feature, persisted);
    }

    public LauncherSettingsStore.Settings selectDefaultJava(Path selected)
            throws IOException, InterruptedException {
        Path executable = javaRuntime.resolve(selected.toString());
        int feature = javaRuntime.validate(executable, executable.getParent());
        ensureRegistryParent();
        return settings.writeDefaultJava(executable, feature);
    }

    private Path configuredDefaultJava() throws IOException {
        LauncherSettingsStore.Settings current = settings.read();
        return current.defaultJavaExecutable() == null
                ? null : Path.of(current.defaultJavaExecutable());
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
            return new RegistryData(RegistryStore.SCHEMA, null, Map.of(), List.of());
        }
        return store.read(registry);
    }

    public HomeState loadHome() throws IOException {
        RegistryData data = readRegistry();
        if (data.defaultInstallationId() == null) {
            return new HomeState(data, null, null, null, null, false, null,
                    Map.of(), Map.of(), automaticChecksEnabled());
        }
        HomeState legacy = loadInstallation(data, store.resolve(data, null));
        Map<String, InstallationRecord> preferredApplications = new LinkedHashMap<>();
        for (String productKey : List.of("megamek", "mekhq", "lab")) {
            if (data.installations().stream().noneMatch(record -> record.products().stream()
                    .anyMatch(product -> productKey.equals(product.key())))) continue;
            preferredApplications.put(productKey, store.resolvePreferred(data, productKey));
        }
        Map<String, InstallationStatus> statuses = new LinkedHashMap<>();
        for (InstallationRecord record : data.installations()) {
            ChannelPreferenceStore.ReadResult channel = null;
            UpdatePreviewService.Eligibility eligibility = null;
            boolean pending = pending(record);
            ImportedCopyAdoptionService.Availability adoption =
                    adoptions.availability(data, record, pending);
            try {
                channel = new ChannelPreferenceStore().read(registry, data, record);
                eligibility = new UpdatePreviewService(transport)
                        .eligibility(registry, record.id());
                Inspection observed = inspector.inspect(Path.of(record.canonicalRoot()));
                if (!observed.canonicalRoot().equals(record.canonicalRoot())
                        || !observed.observedBuild().equals(record.observedBuild())
                        || !observed.products().equals(record.products())) {
                    throw new IOException("registered build or application layout changed");
                }
                statuses.put(record.id(), new InstallationStatus(
                        channel, eligibility, pending, null, adoption));
            } catch (IOException | RuntimeException error) {
                statuses.put(record.id(), new InstallationStatus(
                        channel, eligibility, pending, detail(error), adoption));
            }
        }
        return new HomeState(data, legacy.preferred(), legacy.currentInspection(),
                legacy.preferredError(), legacy.previewEligibility(), legacy.pendingUpdate(),
                legacy.channelPreference(), Map.copyOf(preferredApplications),
                Map.copyOf(statuses), automaticChecksEnabled());
    }

    private boolean automaticChecksEnabled() {
        try {
            return settings.read().checkInstalledVersionsOnOpen();
        } catch (IOException error) {
            return false; // Invalid settings never become an implicit permission to check.
        }
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
        return existingImports.prepare(root, configuredDefaultJava(), context);
    }

    public ExistingImportService.Result importExisting(ExistingImportService.Plan plan,
                                                       String name,
                                                       OperationContext context)
            throws IOException, InterruptedException {
        return existingImports.register(plan, name, context);
    }

    public ImportedCopyAdoptionService.Suggestion adoptionSuggestion(
            InstallationRecord record) throws IOException {
        RegistryData data = readRegistry();
        InstallationRecord current = store.resolve(data, record.id());
        if (!current.equals(record)) {
            throw new IOException("selected imported copy changed; reopen Installations");
        }
        return adoptions.suggest(current);
    }

    public PreparedAdoption prepareAdoption(
            InstallationRecord record, OfficialRepository repository, String exactTag,
            FollowChannel channel, PrintStream progress, OperationContext context)
            throws IOException, InterruptedException,
            org.megamek.launcher.manifest.ManifestException {
        return adoptions.prepare(record, repository, exactTag, channel, progress, context);
    }

    public ImportedCopyAdoptionService.CommitResult commitAdoption(
            PreparedAdoption prepared)
            throws IOException, InterruptedException,
            org.megamek.launcher.manifest.ManifestException {
        return adoptions.commit(prepared);
    }

    public void select(InstallationRecord record) throws IOException {
        store.select(registry, record.id());
    }

    public void selectPreferred(String productKey, InstallationRecord record)
            throws IOException {
        store.selectPreferred(registry, productKey, record);
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
        return applicationLauncher.command(requireCurrentLaunchRecord(record), product);
    }

    public int launch(InstallationRecord record, String product)
            throws IOException, InterruptedException {
        return applicationLauncher.launch(requireCurrentLaunchRecord(record), product);
    }

    private InstallationRecord requireCurrentLaunchRecord(InstallationRecord expected)
            throws IOException {
        RegistryData current = readRegistry();
        List<InstallationRecord> records = current.installations();
        if (records == null) {
            throw new IOException("the launcher registry has no installation records");
        }
        InstallationRecord registered = records.stream()
                .filter(record -> record.id().equals(expected.id()))
                .findFirst()
                .orElseThrow(() -> new IOException(
                        "the selected installation is no longer registered"));
        if (!registered.equals(expected)) {
            throw new IOException(
                    "the selected installation record changed; return Home and try again");
        }
        return registered;
    }

    public ReleaseCatalog.Page releases(OfficialRepository repository, int page)
            throws IOException, InterruptedException {
        return new ReleaseCatalog(transport).list(repository, page, 10);
    }

    public ReleaseCatalog.Assessment assess(OfficialRepository repository,
                                             ReleaseCatalog.Release release) {
        return new ReleaseCatalog(transport).assess(repository, release);
    }

    private FreshInstaller.Result install(OfficialRepository repository, String tag,
                                          Path destination, String name, PrintStream progress)
            throws IOException, InterruptedException {
        ensureRegistryParent();
        return new FreshInstaller(transport).install(repository, tag, destination, registry,
                name, progress);
    }

    private FreshInstaller.Result install(OfficialRepository repository, String tag,
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
            new ChannelPreferenceStore().initializeManaged(registry, result.record(),
                    result.ownershipReceipt(), channel,
                    settings.read().checkNewInstallsOnOpen());
        } catch (IOException error) {
            throw new IOException("installation is valid and REGISTERED at "
                    + result.destination() + " but FIXED CHANNEL PROVENANCE WAS NOT PERSISTED: "
                    + detail(error) + ". Keep the retained copy launch-only until setup is "
                    + "repaired; its channel cannot be assigned or changed manually.", error);
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
            new ChannelPreferenceStore().initializeManaged(registry, result.record(),
                    result.ownershipReceipt(), channel,
                    settings.read().checkNewInstallsOnOpen());
        } catch (IOException error) {
            throw new IOException("installation is valid and REGISTERED at "
                    + result.destination() + " but FIXED CHANNEL PROVENANCE WAS NOT PERSISTED: "
                    + detail(error) + ". Keep the retained copy launch-only until setup is "
                    + "repaired; its channel cannot be assigned or changed manually.", error);
        }
        return result;
    }

    public ChannelPreference setCheckOnOpen(InstallationRecord record,
                                            ChannelPreference expectedPreference,
                                            boolean checkOnOpen) throws IOException {
        return new ChannelPreferenceStore().setCheckOnOpen(
                registry, record, expectedPreference, checkOnOpen);
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
        if (!settings.read().checkInstalledVersionsOnOpen()) return false;
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
                            ChannelPreferenceStore.ReadResult channelPreference,
                            Map<String, InstallationRecord> preferredApplications,
                            Map<String, InstallationStatus> installationStatuses,
                            boolean automaticChecksEnabled) {
        public HomeState(RegistryData registry, InstallationRecord preferred,
                         Inspection currentInspection, String preferredError,
                         UpdatePreviewService.Eligibility previewEligibility,
                         boolean pendingUpdate) {
            this(registry, preferred, currentInspection, preferredError, previewEligibility,
                    pendingUpdate, null, Map.of(), Map.of(), true);
        }

        public HomeState(RegistryData registry, InstallationRecord preferred,
                         Inspection currentInspection, String preferredError) {
            this(registry, preferred, currentInspection, preferredError, null, false, null,
                    Map.of(), Map.of(), true);
        }

        public HomeState(RegistryData registry, InstallationRecord preferred,
                         Inspection currentInspection, String preferredError,
                         UpdatePreviewService.Eligibility previewEligibility,
                         boolean pendingUpdate,
                         ChannelPreferenceStore.ReadResult channelPreference) {
            this(registry, preferred, currentInspection, preferredError, previewEligibility,
                    pendingUpdate, channelPreference, Map.of(), Map.of(), true);
        }
    }

    public record InstallationStatus(ChannelPreferenceStore.ReadResult channelPreference,
                                     UpdatePreviewService.Eligibility previewEligibility,
                                     boolean pendingUpdate, String error,
                                     ImportedCopyAdoptionService.Availability adoption) {
        public InstallationStatus(ChannelPreferenceStore.ReadResult channelPreference,
                                  UpdatePreviewService.Eligibility previewEligibility,
                                  boolean pendingUpdate, String error) {
            this(channelPreference, previewEligibility, pendingUpdate, error,
                    ImportedCopyAdoptionService.Availability.INCOMPLETE);
        }
    }

    public record SettingsView(LauncherSettingsStore.Settings settings, Path defaultJava,
                               int defaultJavaFeature, boolean defaultJavaPersisted) {
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
