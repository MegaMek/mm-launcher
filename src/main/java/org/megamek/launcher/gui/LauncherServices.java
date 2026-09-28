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

package org.megamek.launcher.gui;

import org.megamek.launcher.channel.ChannelPreference;
import org.megamek.launcher.channel.ChannelPreferenceStore;
import org.megamek.launcher.channel.ChannelUpdateChecker;
import org.megamek.launcher.channel.FollowChannel;
import org.megamek.launcher.channel.OfficialYamlChannelCatalog;
import org.megamek.launcher.channel.QuickInstallOption;
import org.megamek.launcher.channel.QuickInstallSnapshot;
import org.megamek.launcher.channel.SelectedChannelReleaseCatalog;
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
import org.megamek.launcher.update.UninstallService;

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
    private final UninstallService uninstalls;
    private final OpenLocationService locations;

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
        this.normalInstalls = new NormalInstallService(this.registry, transport,
                installLocations);
        this.existingImports = new ExistingImportService(this.registry, store, inspector);
        this.adoptions = new ImportedCopyAdoptionService(this.registry, transport,
                updateCoordinator);
        this.uninstalls = new UninstallService(this.registry, updateCoordinator);
        this.locations = new OpenLocationService(this.registry);
    }

    public Path registry() {
        return registry;
    }

    public QuickInstallSnapshot quickInstallSnapshot()
            throws IOException, InterruptedException {
        return new OfficialYamlChannelCatalog(transport).quickInstallSnapshot();
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

    public NormalInstallService.Plan prepareNormalInstall(Path destination)
            throws IOException, InterruptedException {
        return prepareNormalInstall(FollowChannel.MILESTONE, destination);
    }

    public NormalInstallService.Plan prepareNormalInstall(FollowChannel channel, Path destination)
            throws IOException, InterruptedException {
        return prepareNormalInstall(OfficialRepository.MEKHQ, channel, destination);
    }

    public NormalInstallService.Plan prepareNormalInstall(OfficialRepository repository,
                                                          FollowChannel channel,
                                                          Path destination)
            throws IOException, InterruptedException {
        return normalInstalls.prepare(repository, channel, destination);
    }

    public NormalInstallService.Plan prepareCapturedNormalInstall(QuickInstallOption option,
                                                                  Path destination)
            throws IOException, InterruptedException {
        return normalInstalls.prepareCapturedCurrent(option, destination);
    }

    public NormalInstallService.Plan prepareExactNormalInstall(OfficialRepository repository,
                                                               FollowChannel futureChannel,
                                                               String tag, Path destination)
            throws IOException, InterruptedException {
        return normalInstalls.prepareExact(repository, futureChannel, tag, destination);
    }

    public NormalInstallService.Result installNormal(NormalInstallService.Plan plan,
                                                     PrintStream progress,
                                                     OperationContext context)
            throws IOException, InterruptedException {
        return normalInstalls.install(plan, progress, context);
    }

    public SettingsView settingsView() throws IOException, InterruptedException {
        LauncherSettingsStore.Settings current = settings.read();
        if (current.defaultJavaExecutable() == null) {
            Path java = javaRuntime.currentExecutable();
            int feature = javaRuntime.validate(java, java.getParent());
            return new SettingsView(java, feature, false);
        }
        Path java = javaRuntime.resolve(current.defaultJavaExecutable());
        int feature = javaRuntime.validate(java, java.getParent());
        if (feature != current.defaultJavaFeature()) {
            throw new IOException("configured Game Java version changed; choose it again");
        }
        return new SettingsView(java, feature, true);
    }

    public void selectDefaultJava(Path selected)
            throws IOException, InterruptedException {
        Path executable = javaRuntime.resolve(selected.toString());
        RegistryData data = readRegistry();
        for (InstallationRecord record : data.installations()) {
            if (executable.startsWith(Path.of(record.canonicalRoot()))) {
                throw new IOException("Game Java must be outside every installation folder");
            }
        }
        int feature = javaRuntime.validate(executable, executable.getParent());
        ensureRegistryParent();
        settings.writeDefaultJava(executable, feature);
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

    public InstallationRecord renameInstallation(InstallationRecord record, String name)
            throws IOException {
        return store.rename(registry, record, name);
    }

    public PreferencesResetService.Plan planPreferencesReset(InstallationRecord record)
            throws IOException {
        return new PreferencesResetService(registry, store, inspector, updateCoordinator)
                .plan(record);
    }

    public PreferencesResetService.Result resetPreferences(PreferencesResetService.Plan plan)
            throws IOException {
        return new PreferencesResetService(registry, store, inspector, updateCoordinator)
                .reset(plan);
    }

    public HomeState loadHome() throws IOException {
        if (Files.exists(registry, LinkOption.NOFOLLOW_LINKS)) {
            uninstalls.recoverCommitted();
        }
        RegistryData data = readRegistry();
        if (data.defaultInstallationId() == null) {
            return new HomeState(data, null, null, null, null, false, null,
                    Map.of(), Map.of());
        }
        HomeState legacy = loadInstallation(data, store.resolve(data, null));
        Map<String, InstallationRecord> preferredApplications = new LinkedHashMap<>();
        for (String productKey : List.of("mekhq", "megamek", "lab")) {
            if (data.installations().stream().noneMatch(record -> record.products().stream()
                    .anyMatch(product -> productKey.equals(product.key())))) continue;
            preferredApplications.put(productKey, store.resolvePreferred(data, productKey));
        }
        Map<String, InstallationStatus> statuses = new LinkedHashMap<>();
        for (InstallationRecord record : data.installations()) {
            ChannelPreferenceStore.ReadResult channel = null;
            UpdatePreviewService.Eligibility eligibility = null;
            boolean pending = pending(record);
            boolean pendingUninstall = uninstalls.hasPending(record.id());
            ImportedCopyAdoptionService.Availability adoption =
                    adoptions.availability(data, record, pending || pendingUninstall);
            // MANAGED covers both freshly installed and adopted copies. Preserve the
            // adoption marker separately even when the other sidecars are incomplete.
            boolean adopted = Files.exists(new org.megamek.launcher.update.ReceiptStore()
                    .metadataDirectory(registry).resolve(record.id() + ".adoption.json"),
                    LinkOption.NOFOLLOW_LINKS);
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
                        channel, eligibility, pending, pendingUninstall, null, adoption, adopted));
            } catch (IOException | RuntimeException error) {
                statuses.put(record.id(), new InstallationStatus(
                        channel, eligibility, pending, pendingUninstall, detail(error), adoption,
                        adopted));
            }
        }
        return new HomeState(data, legacy.preferred(), legacy.currentInspection(),
                legacy.preferredError(), legacy.previewEligibility(), legacy.pendingUpdate(),
                legacy.channelPreference(), java.util.Collections.unmodifiableMap(preferredApplications),
                Map.copyOf(statuses));
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
                        channel, Map.of(), Map.of());
            }
            return new HomeState(data, record, current, null, eligibility, pending, channel,
                    Map.of(), Map.of());
        } catch (IOException | RuntimeException e) {
            return new HomeState(data, record, null,
                    "The preferred copy is unavailable: " + detail(e)
                            + ". Use recovery if an update was interrupted, or Manage "
                            + "installations.", eligibility, pending, channel, Map.of(), Map.of());
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

    public PreparedAdoption prepareAutomaticAdoption(
            InstallationRecord record, OfficialRepository repository,
            FollowChannel channel, PrintStream progress, OperationContext context)
            throws IOException, InterruptedException,
            org.megamek.launcher.manifest.ManifestException {
        return adoptions.prepareAutomatically(record, repository, channel, progress, context);
    }

    public ImportedCopyAdoptionService.CommitResult commitAdoption(
            PreparedAdoption prepared, OperationContext context, PrintStream progress)
            throws IOException, InterruptedException,
            org.megamek.launcher.manifest.ManifestException {
        return adoptions.commit(prepared, context, progress);
    }

    public void select(InstallationRecord record) throws IOException {
        store.select(registry, record.id());
    }

    public void selectPreferred(String productKey, InstallationRecord record)
            throws IOException {
        store.selectPreferred(registry, productKey, record);
    }

    public List<Path> javaCandidates() {
        return javaRuntime.candidates();
    }

    public List<String> preview(InstallationRecord record, String product)
            throws IOException, InterruptedException {
        InstallationRecord current = requireCurrentLaunchRecord(record);
        JavaRuntime.CurrentJava java = configuredGameJava(current);
        return applicationLauncher.command(current, product, java);
    }

    public int launch(InstallationRecord record, String product)
            throws IOException, InterruptedException {
        InstallationRecord current = requireCurrentLaunchRecord(record);
        JavaRuntime.CurrentJava java = configuredGameJava(current);
        return applicationLauncher.launch(current, product, java);
    }

    private JavaRuntime.CurrentJava configuredGameJava(InstallationRecord record)
            throws IOException, InterruptedException {
        LauncherSettingsStore.Settings configured = settings.read();
        if (configured.defaultJavaExecutable() == null) {
            return javaRuntime.validateCurrentExternal(Path.of(record.canonicalRoot()));
        }
        Path selected = Path.of(configured.defaultJavaExecutable());
        JavaRuntime.CurrentJava java = javaRuntime.validateExternal(
                selected, Path.of(record.canonicalRoot()));
        if (java.feature() != configured.defaultJavaFeature()) {
            throw new IOException("Game Java changed. Open Settings and choose it again.");
        }
        return java;
    }

    private InstallationRecord requireCurrentLaunchRecord(InstallationRecord expected)
            throws IOException {
        RegistryData current = readRegistry();
        List<InstallationRecord> records = current.installations();
        if (records == null) {
            throw new IOException("the launcher has no installations");
        }
        InstallationRecord registered = records.stream()
                .filter(record -> record.id().equals(expected.id()))
                .findFirst()
                .orElseThrow(() -> new IOException(
                        "the selected installation is no longer registered"));
        if (!registered.equals(expected)) {
            throw new IOException(
                    "the selected installation changed; return Home and try again");
        }
        if (uninstalls.hasPending(registered.id())) {
            throw new IOException("Uninstall recovery required");
        }
        return registered;
    }

    public void openLocation(InstallationRecord record) throws IOException {
        locations.open(record);
    }

    public UninstallService.Result removeFromLauncher(InstallationRecord record)
            throws IOException {
        return uninstalls.removeFromLauncher(record);
    }

    public UninstallService.Plan planUninstall(InstallationRecord record,
                                               OperationContext context)
            throws IOException, InterruptedException,
            org.megamek.launcher.manifest.ManifestException {
        return uninstalls.plan(record, context);
    }

    public UninstallService.Result uninstall(UninstallService.Plan plan,
                                             OperationContext context)
            throws IOException, InterruptedException,
            org.megamek.launcher.manifest.ManifestException {
        return uninstalls.uninstall(plan, UninstallService.CONFIRMATION, context);
    }

    public UninstallService.RecoveryResult recoverUninstall(
            InstallationRecord record, OperationContext context)
            throws IOException, InterruptedException {
        return uninstalls.recover(record, context);
    }

    public ReleaseCatalog.Page releases(OfficialRepository repository, int page)
            throws IOException, InterruptedException {
        return new ReleaseCatalog(transport).list(repository, page, 10);
    }

    /**
     * Loads one install-browser page for exactly one product/channel snapshot. This performs one
     * fixed YAML read, one history-page read, and only the selected exact lookup needed when the
     * history page cannot supply an eligible canonical current target.
     */
    public SelectedChannelReleaseCatalog.Result installableReleases(
            OfficialRepository repository, FollowChannel channel, int page)
            throws IOException, InterruptedException {
        return new SelectedChannelReleaseCatalog(transport).page(repository, channel, page, 10);
    }

    public ReleaseCatalog.Assessment assess(OfficialRepository repository,
                                             ReleaseCatalog.Release release) {
        return new ReleaseCatalog(transport).assess(repository, release);
    }

    private FreshInstaller.Result install(OfficialRepository repository, String tag,
                                          Path destination, String name, PrintStream progress)
            throws IOException, InterruptedException {
        ensureRegistryParent();
        FreshInstaller.Result result = new FreshInstaller(transport).install(
                repository, tag, destination, registry,
                name, progress);
        return result;
    }

    private FreshInstaller.Result install(OfficialRepository repository, String tag,
                                          Path destination, String name, PrintStream progress,
                                          OperationContext context)
            throws IOException, InterruptedException {
        ensureRegistryParent();
        FreshInstaller.Result result = new FreshInstaller(transport).install(
                repository, tag, destination, registry,
                name, progress, context);
        return result;
    }

    public FreshInstaller.Result install(OfficialRepository repository, String tag,
                                         Path destination, String name, FollowChannel channel,
                                         PrintStream progress)
            throws IOException, InterruptedException {
        if (channel == null) throw new IOException("choose a channel for the new installation");
        FreshInstaller.Result result = install(repository, tag, destination, name, progress);
        try {
            new ChannelPreferenceStore().initializeManaged(registry, result.record(),
                    result.ownershipReceipt(), channel, true);
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
                    result.ownershipReceipt(), channel, true);
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
        requireNoPendingUninstall(expected);
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
        requireNoPendingUninstall(expected);
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
        requireNoPendingUninstall(record);
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
        requireNoPendingUninstall(record);
        return new UpdatePreviewService(transport).preview(registry, record.id(), tag, progress);
    }

    public UpdatePreviewService.Preview previewUpdate(InstallationRecord record,
                                                       OwnershipReceipt receipt, String tag,
                                                       PrintStream progress)
            throws IOException, InterruptedException,
            org.megamek.launcher.manifest.ManifestException {
        requireNoPendingUninstall(record);
        return new UpdatePreviewService(transport).preview(
                registry, record, receipt, tag, progress);
    }

    public UpdatePreviewService.Preview previewUpdate(InstallationRecord record,
                                                       OwnershipReceipt receipt, String tag,
                                                       PrintStream progress,
                                                       OperationContext context)
            throws IOException, InterruptedException,
            org.megamek.launcher.manifest.ManifestException {
        requireNoPendingUninstall(record);
        return new UpdatePreviewService(transport).preview(
                registry, record, receipt, tag, progress, context);
    }

    public PreparedUpdate prepareUpdate(InstallationRecord record, OwnershipReceipt receipt,
                                        CurrentUpdateState current, String tag,
                                        String assetName, long size, String digest,
                                        PrintStream progress)
            throws IOException, InterruptedException,
            org.megamek.launcher.manifest.ManifestException {
        requireNoPendingUninstall(record);
        return preparedUpdates.prepare(registry, record, receipt, current, tag, assetName, size,
                digest, progress);
    }

    public PreparedUpdate prepareUpdate(InstallationRecord record, OwnershipReceipt receipt,
                                        CurrentUpdateState current, String tag,
                                        String assetName, long size, String digest,
                                        PrintStream progress, OperationContext context)
            throws IOException, InterruptedException,
            org.megamek.launcher.manifest.ManifestException {
        requireNoPendingUninstall(record);
        return preparedUpdates.prepare(registry, record, receipt, current, tag, assetName, size,
                digest, progress, context);
    }

    public PreparedUpdate prepareRecommended(
            InstallationRecord record, OwnershipReceipt receipt, CurrentUpdateState current,
            ChannelUpdateChecker.Result expected, PrintStream progress)
            throws IOException, InterruptedException,
            org.megamek.launcher.manifest.ManifestException {
        requireNoPendingUninstall(record);
        return preparedUpdates.prepareRecommended(
                registry, record, receipt, current, expected, progress);
    }

    public PreparedUpdate prepareRecommended(
            InstallationRecord record, OwnershipReceipt receipt, CurrentUpdateState current,
            ChannelUpdateChecker.Result expected, PrintStream progress, OperationContext context)
            throws IOException, InterruptedException,
            org.megamek.launcher.manifest.ManifestException {
        requireNoPendingUninstall(record);
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

    /** Capture the exact local release to be shown before repair consent (no network request). */
    public RealUpdateService.Snapshot repairSource(InstallationRecord selected)
            throws IOException {
        RegistryData data = readRegistry();
        InstallationRecord current = store.resolve(data, selected.id());
        if (!current.equals(selected)) {
            throw new IOException("selected installation changed; reopen Installations");
        }
        requireNoPendingUninstall(current);
        RealUpdateService service = new RealUpdateService(transport, updateCoordinator);
        if (service.hasPending(Path.of(current.canonicalRoot()))) {
            throw new IOException("update recovery required before repair");
        }
        Path adoption = new org.megamek.launcher.update.ReceiptStore()
                .metadataDirectory(registry).resolve(current.id() + ".adoption.json");
        if (Files.exists(adoption, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("adopted/imported copies cannot be repaired");
        }
        return service.snapshot(registry, current.id());
    }

    /** Recheck the consented local identity before calling the transaction backend. */
    public RealUpdateService.RepairResult repair(RealUpdateService.Snapshot consented,
                                                  PrintStream progress)
            throws IOException, InterruptedException,
            org.megamek.launcher.manifest.ManifestException {
        return repair(consented, progress, OperationContext.none(OperationType.UPDATE_APPLY));
    }

    public RealUpdateService.RepairResult repair(RealUpdateService.Snapshot consented,
                                                  PrintStream progress, OperationContext context)
            throws IOException, InterruptedException,
            org.megamek.launcher.manifest.ManifestException {
        context.enterFinalization("Repair must run through completion or retain recovery state.");
        RealUpdateService.Snapshot fresh = repairSource(consented.record());
        if (!fresh.equals(consented)) {
            throw new IOException("repair source changed after consent; review and confirm again");
        }
        return new RealUpdateService(transport, updateCoordinator).repair(
                registry, consented.record().id(), RealUpdateService.REPAIR_CONFIRM, progress,
                context);
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

    private void requireNoPendingUninstall(InstallationRecord record) throws IOException {
        if (record == null || uninstalls.hasPending(record.id())) {
            throw new IOException("Uninstall recovery required");
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
                            Map<String, InstallationStatus> installationStatuses) {
        public HomeState(RegistryData registry, InstallationRecord preferred,
                         Inspection currentInspection, String preferredError,
                         UpdatePreviewService.Eligibility previewEligibility,
                         boolean pendingUpdate) {
            this(registry, preferred, currentInspection, preferredError, previewEligibility,
                    pendingUpdate, null, Map.of(), Map.of());
        }

        public HomeState(RegistryData registry, InstallationRecord preferred,
                         Inspection currentInspection, String preferredError) {
            this(registry, preferred, currentInspection, preferredError, null, false, null,
                    Map.of(), Map.of());
        }

        public HomeState(RegistryData registry, InstallationRecord preferred,
                         Inspection currentInspection, String preferredError,
                         UpdatePreviewService.Eligibility previewEligibility,
                         boolean pendingUpdate,
                         ChannelPreferenceStore.ReadResult channelPreference) {
            this(registry, preferred, currentInspection, preferredError, previewEligibility,
                    pendingUpdate, channelPreference, Map.of(), Map.of());
        }
    }

    public record InstallationStatus(ChannelPreferenceStore.ReadResult channelPreference,
                                     UpdatePreviewService.Eligibility previewEligibility,
                                     boolean pendingUpdate, boolean pendingUninstall, String error,
                                     ImportedCopyAdoptionService.Availability adoption,
                                     boolean adopted) {
        public InstallationStatus(ChannelPreferenceStore.ReadResult channelPreference,
                                  UpdatePreviewService.Eligibility previewEligibility,
                                  boolean pendingUpdate, boolean pendingUninstall, String error,
                                  ImportedCopyAdoptionService.Availability adoption) {
            this(channelPreference, previewEligibility, pendingUpdate, pendingUninstall,
                    error, adoption, false);
        }
        public InstallationStatus(ChannelPreferenceStore.ReadResult channelPreference,
                                  UpdatePreviewService.Eligibility previewEligibility,
                                  boolean pendingUpdate, String error) {
            this(channelPreference, previewEligibility, pendingUpdate, false, error,
                    ImportedCopyAdoptionService.Availability.INCOMPLETE, false);
        }

        public InstallationStatus(ChannelPreferenceStore.ReadResult channelPreference,
                                  UpdatePreviewService.Eligibility previewEligibility,
                                  boolean pendingUpdate, String error,
                                  ImportedCopyAdoptionService.Availability adoption) {
            this(channelPreference, previewEligibility, pendingUpdate, false, error, adoption,
                    false);
        }
    }

    public record SettingsView(Path defaultJava, Integer defaultJavaFeature,
                               boolean persisted) {
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
