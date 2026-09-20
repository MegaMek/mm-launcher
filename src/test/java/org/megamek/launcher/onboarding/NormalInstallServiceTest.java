package org.megamek.launcher.onboarding;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.megamek.launcher.channel.ChannelPreferenceStore;
import org.megamek.launcher.channel.FollowChannel;
import org.megamek.launcher.channel.QuickInstallOption;
import org.megamek.launcher.channel.QuickInstallSnapshot;
import org.megamek.launcher.gui.LauncherServices;
import org.megamek.launcher.launch.JavaRuntime;
import org.megamek.launcher.launch.ApplicationLauncher;
import org.megamek.launcher.launch.ProcessRunner;
import org.megamek.launcher.operation.OperationContext;
import org.megamek.launcher.operation.OperationPhase;
import org.megamek.launcher.operation.OperationType;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryData;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.release.OfficialRepository;
import org.megamek.launcher.release.ReleaseTransport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NormalInstallServiceTest {
    @TempDir Path temp;

    @Test
    void milestonePlanIsMetadataOnlyAndConfirmedInstallReturnsLatestReadyMain()
            throws Exception {
        Fixture fixture = fixture("1.2.3");
        NormalInstallService.Plan plan =
                fixture.launcherServices.prepareNormalInstall(fixture.destination, null);

        assertEquals(FollowChannel.MILESTONE, plan.channel());
        assertTrue(plan.checkConfiguration().checkOnOpen());
        assertEquals("MegaMek/mekhq", plan.repository().slug());
        assertEquals(NormalInstallService.SUITE_PRODUCTS, plan.requiredProducts());
        assertEquals("v1.2.3", plan.release().tag());
        assertEquals(fixture.transport.assetName, plan.asset().name());
        assertEquals(fixture.destination, plan.destination());
        assertEquals(0, fixture.transport.binaryRequests);
        assertFalse(Files.exists(fixture.destination, LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(fixture.registry, LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(settingsPath(fixture.registry)),
                "the initial true default remains side-effect free");

        NormalInstallService.Result result = fixture.launcherServices.installNormal(plan, quiet(),
                OperationContext.none(OperationType.FRESH_INSTALL));

        assertEquals(1, fixture.transport.binaryRequests);
        RegistryData data = fixture.registries.read(fixture.registry);
        InstallationRecord latest = fixture.registries.resolve(data, null);
        assertEquals(result.record(), latest, "result must not return the pre-Java record");
        assertTrue(result.becameMain());
        assertEquals(plan.javaExecutable().toString(), latest.javaExecutable());
        assertEquals("Default", latest.name());
        assertEquals(NormalInstallService.SUITE_PRODUCTS,
                latest.products().stream().map(Product::key)
                        .collect(java.util.stream.Collectors.toSet()));
        var preference = new ChannelPreferenceStore().read(fixture.registry, data, latest);
        assertEquals(FollowChannel.MILESTONE, preference.preference().channel());
        assertTrue(preference.preference().checkOnOpen());
        assertTrue(Files.isRegularFile(fixture.destination.resolve("MegaMek.jar")));
        ApplicationLauncher launcher = new ApplicationLauncher(
                new FakeJava(Integer.MAX_VALUE));
        assertTrue(launcher.command(latest, "megamek").contains("megamek.MegaMek"));
        assertTrue(launcher.command(latest, "mekhq").contains("mekhq.MekHQ"));
        assertTrue(launcher.command(latest, "lab").contains("megameklab.MegaMekLab"));
    }

    @Test
    void configuredDefaultGameJavaIsUsedByNewNormalPlanAndImport() throws Exception {
        Fixture fixture = fixture("6.7.8");
        Path selectedJava = Files.writeString(
                fixture.state.resolve("java.exe"), "fixture").toRealPath();
        fixture.launcherServices.selectDefaultJava(selectedJava);

        NormalInstallService.Plan install =
                fixture.launcherServices.prepareNormalInstall(fixture.destination, null);
        assertEquals(selectedJava, install.javaExecutable());

        Path existing = createSuite(fixture.state.resolve("existing-copy"), "6.7.8");
        var imported = fixture.launcherServices.prepareExistingImport(existing,
                OperationContext.none(OperationType.IMPORT_EXISTING));
        assertEquals(selectedJava, imported.java().executable());
    }

    @Test
    void developmentPlanUsesExactDevTargetAndPersistsDevelopmentAfterOneTransfer()
            throws Exception {
        Fixture fixture = fixture("1.2.3");

        NormalInstallService.Plan plan = fixture.launcherServices.prepareNormalInstall(
                FollowChannel.DEVELOPMENT, fixture.destination, null);

        assertEquals(FollowChannel.DEVELOPMENT, plan.channel());
        assertEquals("9.9.9", plan.version());
        assertEquals("v9.9.9", plan.release().tag());
        assertEquals("MekHQ-9.9.9.tar.gz", plan.asset().name());
        assertEquals(0, fixture.transport.binaryRequests);
        assertFalse(Files.exists(fixture.destination, LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(fixture.registry, LinkOption.NOFOLLOW_LINKS));

        NormalInstallService.Result result = fixture.launcherServices.installNormal(
                plan, quiet(), OperationContext.none(OperationType.FRESH_INSTALL));

        assertEquals(1, fixture.transport.binaryRequests);
        RegistryData data = fixture.registries.read(fixture.registry);
        InstallationRecord main = fixture.registries.resolve(data, null);
        assertEquals(result.record(), main);
        assertTrue(result.becameMain());
        assertEquals("Default", main.name());
        assertEquals("9.9.9", main.observedBuild());
        assertEquals(FollowChannel.DEVELOPMENT,
                new ChannelPreferenceStore().read(fixture.registry, data, main)
                        .preference().channel());
        assertTrue(new ChannelPreferenceStore().read(fixture.registry, data, main)
                .preference().checkOnOpen());
    }

    @Test
    void capturedCurrentPlanNeverRereadsPointerAndInstallsOnlyCapturedTarget()
            throws Exception {
        Fixture fixture = fixture("1.2.3");
        QuickInstallSnapshot snapshot = fixture.launcherServices.quickInstallSnapshot();
        QuickInstallOption captured = snapshot.option(QuickInstallSnapshot.DEFAULT_KEY);
        int yamlAfterSnapshot = fixture.transport.yamlRequests;
        int apiAfterSnapshot = fixture.transport.apiRequests;

        NormalInstallService.Plan plan =
                fixture.launcherServices.prepareCapturedNormalInstall(
                        captured, fixture.destination, null);

        assertTrue(plan.currentChannelTarget());
        assertTrue(plan.capturedCurrentTarget());
        assertEquals(NormalInstallService.TargetKind.CAPTURED_CURRENT, plan.targetKind());
        assertEquals(yamlAfterSnapshot, fixture.transport.yamlRequests);
        assertEquals(apiAfterSnapshot, fixture.transport.apiRequests,
                "captured planning performs only fresh local validation");

        fixture.transport.version = "8.8.8";
        NormalInstallService.Result result = fixture.launcherServices.installNormal(
                plan, quiet(), OperationContext.none(OperationType.FRESH_INSTALL));

        assertEquals("v1.2.3", result.release().tag());
        assertEquals(yamlAfterSnapshot, fixture.transport.yamlRequests,
                "install-time validation must not reread the moving current pointer");
        assertEquals(apiAfterSnapshot + 2, fixture.transport.apiRequests,
                "the exact quoted release is checked before transfer and by the fetcher");
        assertEquals(1, fixture.transport.binaryRequests);
    }

    @Test
    void capturedTargetReplanningRefreshesLocalDestinationAndSettingsOnly()
            throws Exception {
        Fixture fixture = fixture("1.3.0");
        QuickInstallOption captured = fixture.launcherServices.quickInstallSnapshot()
                .option(QuickInstallSnapshot.DEFAULT_KEY);
        int yamlAfterSnapshot = fixture.transport.yamlRequests;
        int apiAfterSnapshot = fixture.transport.apiRequests;
        Path occupied = Files.createDirectory(fixture.state.resolve("occupied"));
        assertThrows(IOException.class,
                () -> fixture.launcherServices.prepareCapturedNormalInstall(
                        captured, occupied, null));
        assertEquals(yamlAfterSnapshot, fixture.transport.yamlRequests);
        assertEquals(apiAfterSnapshot, fixture.transport.apiRequests,
                "a local planning failure does not refresh the captured target");
        NormalInstallService.Plan initial =
                fixture.launcherServices.prepareCapturedNormalInstall(
                        captured, fixture.destination, null);
        Path changedDestination = fixture.state.resolve("installations")
                .resolve("Changed MekHQ Milestone");
        fixture.launcherServices.setCheckNewInstallsOnOpen(false);

        NormalInstallService.Plan changed =
                fixture.launcherServices.prepareCapturedNormalInstall(
                        captured, changedDestination, null);

        assertEquals(fixture.destination, initial.destination());
        assertTrue(initial.checkConfiguration().checkOnOpen());
        assertEquals(changedDestination, changed.destination());
        assertFalse(changed.checkConfiguration().checkOnOpen());
        assertFalse(initial.checkConfiguration().revision()
                .equals(changed.checkConfiguration().revision()));
        assertEquals(initial.release(), changed.release());
        assertEquals(initial.asset(), changed.asset());
        assertEquals(yamlAfterSnapshot, fixture.transport.yamlRequests);
        assertEquals(apiAfterSnapshot, fixture.transport.apiRequests);
        assertEquals(0, fixture.transport.binaryRequests);
    }

    @Test
    void capturedCurrentAssetOrReleaseDriftFailsBeforeBytesOrRootWrites()
            throws Exception {
        int sequence = 0;
        for (MetadataDrift drift : List.of(
                MetadataDrift.NAME, MetadataDrift.SIZE, MetadataDrift.DIGEST,
                MetadataDrift.URL, MetadataDrift.REMOVED, MetadataDrift.TAG)) {
            Fixture fixture = fixture("2.4." + sequence++);
            QuickInstallOption captured = fixture.launcherServices.quickInstallSnapshot()
                    .option(QuickInstallSnapshot.DEFAULT_KEY);
            NormalInstallService.Plan plan =
                    fixture.launcherServices.prepareCapturedNormalInstall(
                            captured, fixture.destination, null);
            int yamlAfterSnapshot = fixture.transport.yamlRequests;
            fixture.transport.metadataDrift = drift;

            assertThrows(IOException.class,
                    () -> fixture.launcherServices.installNormal(plan, quiet(),
                            OperationContext.none(OperationType.FRESH_INSTALL)));

            assertEquals(yamlAfterSnapshot, fixture.transport.yamlRequests);
            assertEquals(0, fixture.transport.binaryRequests);
            assertFalse(Files.exists(fixture.destination, LinkOption.NOFOLLOW_LINKS));
            assertFalse(Files.exists(fixture.destination.getParent(), LinkOption.NOFOLLOW_LINKS));
            assertFalse(Files.exists(fixture.registry, LinkOption.NOFOLLOW_LINKS));
        }
    }

    @Test
    void historicalExactPlanSkipsYamlAndFixesChannelOnlyForCreatedInstallation()
            throws Exception {
        Fixture fixture = fixture("1.2.3");

        NormalInstallService.Plan plan = fixture.service.prepareExact(
                OfficialRepository.MEKHQ, FollowChannel.MILESTONE, "v9.9.9",
                fixture.destination, null);

        assertFalse(plan.currentChannelTarget());
        assertEquals("9.9.9", plan.version());
        assertEquals("v9.9.9", plan.release().tag());
        assertEquals(FollowChannel.MILESTONE, plan.channel());
        assertTrue(plan.source().endsWith("/releases/tags/v9.9.9"));
        assertEquals(0, fixture.transport.yamlRequests,
                "history selection must not be mislabeled through a current channel pointer");
        assertEquals(1, fixture.transport.apiRequests);
        assertEquals(0, fixture.transport.binaryRequests);

        NormalInstallService.Result result = fixture.service.install(
                plan, quiet(), OperationContext.none(OperationType.FRESH_INSTALL));

        assertEquals(0, fixture.transport.yamlRequests);
        assertEquals(3, fixture.transport.apiRequests,
                "the planner and package fetch each revalidate the exact tag before transfer");
        assertEquals(1, fixture.transport.binaryRequests);
        RegistryData data = fixture.registries.read(fixture.registry);
        assertEquals(FollowChannel.MILESTONE,
                new ChannelPreferenceStore().read(fixture.registry, data, result.record())
                        .preference().channel());
    }

    @Test
    void everyOfficialRepositoryChannelPairPersistsItsChannelAndExactProductLayout()
            throws Exception {
        for (OfficialRepository repository : OfficialRepository.values()) {
            for (FollowChannel channel : FollowChannel.values()) {
                Fixture fixture = fixture("4." + repository.ordinal() + "."
                        + channel.ordinal());
                NormalInstallService.Plan plan = fixture.service.prepare(
                        repository, channel, fixture.destination, null);

                NormalInstallService.Result result = fixture.service.install(plan, quiet(),
                        OperationContext.none(OperationType.FRESH_INSTALL));

                assertEquals(repository, plan.repository());
                assertEquals(channel, plan.channel());
                assertEquals(repository.key(), result.ownershipReceipt().repository());
                assertEquals(plan.requiredProducts(),
                        result.record().products().stream().map(Product::key)
                                .collect(java.util.stream.Collectors.toSet()));
                RegistryData data = fixture.registries.read(fixture.registry);
                assertEquals(channel, new ChannelPreferenceStore().read(
                        fixture.registry, data, result.record()).preference().channel());
                assertEquals(result.record().id(), data.defaultInstallationId());
            }
        }
    }

    @Test
    void defaultFoldersAreDistinctAndNameTheSelectedProductAndChannel() throws Exception {
        Fixture fixture = fixture("5.0.0");
        assertEquals(fixture.state.resolve("installations").resolve("MekHQ Milestone"),
                fixture.service.defaultDestination(OfficialRepository.MEKHQ,
                        FollowChannel.MILESTONE));
        assertEquals(fixture.state.resolve("installations").resolve("MegaMek Development"),
                fixture.service.defaultDestination(OfficialRepository.MEGAMEK,
                        FollowChannel.DEVELOPMENT));
        assertEquals(fixture.state.resolve("installations").resolve("MegaMekLab Milestone"),
                fixture.service.defaultDestination(OfficialRepository.LAB,
                        FollowChannel.MILESTONE));
    }

    @Test
    void managedDefaultQuotesSeparateStateAndApplicationDataParentsWithoutWriting()
            throws Exception {
        Fixture fixture = fixture("5.0.2");
        Path stateParent = fixture.state.resolve("new-state");
        Path registry = stateParent.resolve("registry.json");
        Path dataHome = fixture.state.resolve("new-data");
        PlatformInstallLocations locations = new PlatformInstallLocations(
                new PlatformInstallLocations.PlatformSource() {
                    @Override
                    public String operatingSystem() {
                        return "Linux";
                    }

                    @Override
                    public String environment(String name) {
                        return name.equals("XDG_DATA_HOME") ? dataHome.toString() : null;
                    }

                    @Override
                    public String userHome() {
                        return fixture.state.toString();
                    }

                    @Override
                    public Path defaultRegistry() {
                        return registry;
                    }
                });
        NormalInstallService service = new NormalInstallService(registry, fixture.transport,
                new JavaRuntime(new FakeJava(Integer.MAX_VALUE)),
                () -> new NormalInstallService.CheckConfiguration(true, "test:true"),
                locations);
        Path destination = dataHome.resolve("MegaMek").resolve("MekHQ Milestone");

        assertEquals(destination, service.defaultDestination());
        NormalInstallService.Plan plan = service.prepare(destination, null);
        assertEquals(List.of(stateParent, dataHome, dataHome.resolve("MegaMek")),
                plan.missingParents());
        for (Path parent : plan.missingParents()) {
            assertFalse(Files.exists(parent, LinkOption.NOFOLLOW_LINKS),
                    "planning does not create state or application-data parents");
        }
        assertFalse(Files.exists(registry, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void quoteAllowsFourSafeMissingParentsButRejectsADeeperUncreatedChain()
            throws Exception {
        Fixture fixture = fixture("5.0.1");
        Path first = fixture.state.resolve("one");
        Path second = first.resolve("two");
        Path third = second.resolve("three");
        Path fourth = third.resolve("four");
        Path destination = fourth.resolve("MekHQ Milestone");

        NormalInstallService.Plan plan =
                fixture.service.prepare(destination, null);

        assertEquals(List.of(first, second, third, fourth), plan.missingParents());
        for (Path parent : plan.missingParents()) {
            assertFalse(Files.exists(parent, LinkOption.NOFOLLOW_LINKS),
                    "planning does not create quoted parents");
        }

        IOException tooDeep = assertThrows(IOException.class,
                () -> fixture.service.prepare(
                        fourth.resolve("five").resolve("MekHQ Development"), null));
        assertTrue(tooDeep.getMessage().contains("too many new parent"));
    }

    @Test
    void nullChannelAndDevelopmentFeedDriftFailBeforePackageOrStateWrite()
            throws Exception {
        Fixture invalid = fixture("1.2.3");
        IOException missing = assertThrows(IOException.class,
                () -> invalid.service.prepare(null, invalid.destination, null));
        assertTrue(missing.getMessage().contains("Milestone or Development"));
        assertEquals(0, invalid.transport.yamlRequests);
        assertEquals(0, invalid.transport.apiRequests);
        assertEquals(0, invalid.transport.binaryRequests);
        assertFalse(Files.exists(invalid.registry, LinkOption.NOFOLLOW_LINKS));
        IOException missingRepository = assertThrows(IOException.class,
                () -> invalid.service.prepare(null, FollowChannel.MILESTONE,
                        invalid.destination, null));
        assertTrue(missingRepository.getMessage().contains(
                "MekHQ, MegaMek, or MegaMekLab"));
        assertEquals(0, invalid.transport.yamlRequests);

        Fixture drift = fixture("2.3.4");
        NormalInstallService.Plan plan = drift.service.prepare(
                FollowChannel.DEVELOPMENT, drift.destination, null);
        drift.transport.developmentVersion = "9.9.8";
        IOException changed = assertThrows(IOException.class,
                () -> drift.service.install(plan, quiet(),
                        OperationContext.none(OperationType.FRESH_INSTALL)));
        assertTrue(changed.getMessage().contains("metadata changed"));
        assertEquals(0, drift.transport.binaryRequests);
        assertFalse(Files.exists(drift.destination, LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(drift.registry, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void javaDriftIsRejectedBySharedRevalidationBeforeEitherChannelTransfers()
            throws Exception {
        for (FollowChannel channel : FollowChannel.values()) {
            FakeJava changedJava = new FakeJava(2);
            Fixture fixture = fixture("3.4." + (channel.ordinal() + 1), changedJava);
            NormalInstallService.Plan plan =
                    fixture.service.prepare(channel, fixture.destination, null);

            IOException changed = assertThrows(IOException.class,
                    () -> fixture.service.install(plan, quiet(),
                            OperationContext.none(OperationType.FRESH_INSTALL)));

            assertTrue(changed.getMessage().toLowerCase(java.util.Locale.ROOT)
                    .contains("java"));
            assertEquals(0, fixture.transport.binaryRequests);
            assertFalse(Files.exists(fixture.destination, LinkOption.NOFOLLOW_LINKS));
            assertFalse(Files.exists(fixture.registry, LinkOption.NOFOLLOW_LINKS));
        }
    }

    @Test
    void cancelBeforeTransferCreatesNoDestinationRegistryOrBinaryRequest() throws Exception {
        Fixture fixture = fixture("1.2.3");
        NormalInstallService.Plan plan = fixture.service.prepare(fixture.destination, null);
        OperationContext context = new OperationContext(OperationType.FRESH_INSTALL, progress -> {
        });
        assertTrue(context.requestCancellation().accepted());

        assertThrows(InterruptedException.class,
                () -> fixture.service.install(plan, quiet(), context));

        assertEquals(0, fixture.transport.binaryRequests);
        assertFalse(Files.exists(fixture.destination, LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(fixture.registry, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void metadataDriftExistingDestinationAndLogOverlapRequireFreshChoiceBeforeBinary()
            throws Exception {
        Fixture drift = fixture("1.2.3");
        NormalInstallService.Plan driftPlan = drift.service.prepare(drift.destination, null);
        drift.transport.version = "1.2.4";
        IOException changed = assertThrows(IOException.class,
                () -> drift.service.install(driftPlan, quiet(),
                        OperationContext.none(OperationType.FRESH_INSTALL)));
        assertTrue(changed.getMessage().contains("metadata changed"));
        assertEquals(0, drift.transport.binaryRequests);

        Fixture collision = fixture("2.0.0");
        NormalInstallService.Plan collisionPlan =
                collision.service.prepare(collision.destination, null);
        Files.createDirectories(collision.destination);
        IOException appeared = assertThrows(IOException.class,
                () -> collision.service.install(collisionPlan, quiet(),
                        OperationContext.none(OperationType.FRESH_INSTALL)));
        assertTrue(appeared.getMessage().contains("wholly nonexistent"));
        assertEquals(0, collision.transport.binaryRequests);

        Fixture overlap = fixture("3.0.0");
        Path logs = overlap.registry.resolveSibling(
                overlap.registry.getFileName() + ".launcher-logs");
        IOException unsafe = assertThrows(IOException.class,
                () -> overlap.service.prepare(logs.resolve("Main"), null));
        assertTrue(unsafe.getMessage().contains("diagnostics"));
        assertEquals(0, overlap.transport.binaryRequests);
    }

    @Test
    void malformedFeedAndThirdMetadataReadAssetDriftNeverReachBinary() throws Exception {
        Fixture malformed = fixture("1.2.3");
        malformed.transport.malformedFeed = true;
        assertThrows(IOException.class,
                () -> malformed.service.prepare(malformed.destination, null));
        assertEquals(0, malformed.transport.binaryRequests);
        assertFalse(Files.exists(malformed.registry, LinkOption.NOFOLLOW_LINKS));

        Fixture assetDrift = fixture("2.3.4");
        NormalInstallService.Plan plan =
                assetDrift.service.prepare(assetDrift.destination, null);
        assetDrift.transport.driftOnThirdApi = true;
        IOException changed = assertThrows(IOException.class,
                () -> assetDrift.service.install(plan, quiet(),
                        OperationContext.none(OperationType.FRESH_INSTALL)));
        assertTrue(changed.getMessage().contains("expected")
                || changed.getMessage().contains("changed"));
        assertEquals(0, assetDrift.transport.binaryRequests);
        assertFalse(Files.exists(assetDrift.destination, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void staleRegistryAndSymlinkParentAreRejectedWithoutTransfer() throws Exception {
        Fixture stale = fixture("1.2.3");
        NormalInstallService.Plan plan = stale.service.prepare(stale.destination, null);
        Path imported = createSuite(stale.state.resolve("Imported"), "9.9.9");
        stale.registries.register(stale.registry, "Imported", imported, null);

        IOException changed = assertThrows(IOException.class,
                () -> stale.service.install(plan, quiet(),
                        OperationContext.none(OperationType.FRESH_INSTALL)));
        assertTrue(changed.getMessage().contains("changed"));
        assertEquals(0, stale.transport.binaryRequests);

        Fixture linked = fixture("1.2.3");
        Path real = Files.createDirectory(linked.state.resolve("real-parent"));
        Path link = linked.state.resolve("linked-parent");
        try {
            Files.createSymbolicLink(link, real);
        } catch (IOException | UnsupportedOperationException error) {
            if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
                Assumptions.abort("symbolic links unavailable for this fixture: " + error);
            }
            String command = System.getenv("ComSpec");
            if (command == null || command.isBlank()) command = "cmd.exe";
            Process junction = new ProcessBuilder(command, "/d", "/c", "mklink", "/J",
                    link.toString(), real.toString()).redirectErrorStream(true).start();
            String output = new String(junction.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            if (junction.waitFor() != 0 || !Files.exists(link, LinkOption.NOFOLLOW_LINKS)) {
                Assumptions.abort("links unavailable for this fixture: " + output);
            }
        }
        IOException linkError = assertThrows(IOException.class,
                () -> linked.service.prepare(link.resolve("Main"), null));
        assertTrue(linkError.getMessage().contains("linked")
                || linkError.getMessage().contains("noncanonical"));
        assertEquals(0, linked.transport.binaryRequests);
    }

    @Test
    void persistedFalseAppliesToNormalInstallAndLeavesImportedCopyWithoutChannel()
            throws Exception {
        Fixture fixture = fixture("1.2.3");
        Path imported = createSuite(fixture.state.resolve("Imported"), "8.0.0");
        InstallationRecord old = fixture.registries.register(
                fixture.registry, "Existing", imported, "keep-pin");
        fixture.registries.selectJava(fixture.registry, old.id(), planJava().toString());
        old = fixture.registries.resolve(fixture.registries.read(fixture.registry), old.id());
        InstallationRecord expectedOld = old;
        fixture.launcherServices.setCheckNewInstallsOnOpen(false);

        NormalInstallService.Plan plan =
                fixture.launcherServices.prepareNormalInstall(fixture.destination, null);
        assertFalse(plan.checkConfiguration().checkOnOpen());
        assertTrue(plan.checkConfiguration().revision().startsWith("sha256:"));
        NormalInstallService.Result result = fixture.launcherServices.installNormal(plan,
                quiet(),
                OperationContext.none(OperationType.FRESH_INSTALL));

        RegistryData data = fixture.registries.read(fixture.registry);
        InstallationRecord unchanged = fixture.registries.resolve(data, expectedOld.id());
        assertEquals(expectedOld, unchanged);
        assertEquals("keep-pin", unchanged.pin());
        assertEquals(expectedOld.id(), data.defaultInstallationId(),
                "normal install must make Main only when the captured default is empty");
        assertFalse(result.becameMain(),
                "the result reports the actual locked Main outcome, not the plan-time default");
        assertEquals(ChannelPreferenceStore.Status.UNKNOWN,
                new ChannelPreferenceStore().read(fixture.registry, data, unchanged).status());
        assertFalse(new ChannelPreferenceStore().read(fixture.registry, data, result.record())
                .preference().checkOnOpen());
        int metadataRequests = fixture.transport.apiRequests;
        LauncherServices.HomeState home = fixture.launcherServices.loadHome();
        assertEquals(expectedOld.id(), home.preferred().id());
        assertEquals(ChannelPreferenceStore.Status.UNKNOWN,
                home.channelPreference().status());
        assertFalse(fixture.launcherServices.canCheckOnOpen(result.record()));
        assertEquals(metadataRequests, fixture.transport.apiRequests,
                "an off copy must not request update metadata on open");
        assertEquals(1, fixture.transport.binaryRequests);
    }

    @Test
    void settingChangeAfterQuoteRejectsBeforeTransferOrPublicationAndIsRetained()
            throws Exception {
        Fixture fixture = fixture("5.6.7");
        NormalInstallService.Plan plan =
                fixture.launcherServices.prepareNormalInstall(fixture.destination, null);
        assertTrue(plan.checkConfiguration().checkOnOpen());

        fixture.launcherServices.setCheckNewInstallsOnOpen(true);
        IOException changed = assertThrows(IOException.class,
                () -> fixture.launcherServices.installNormal(plan, quiet(),
                        OperationContext.none(OperationType.FRESH_INSTALL)));

        assertTrue(changed.getMessage().contains("automatic-check setting changed"));
        assertTrue(changed.getMessage().contains("fresh install confirmation"));
        assertEquals(0, fixture.transport.binaryRequests);
        assertFalse(Files.exists(fixture.destination, LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(fixture.destination.getParent(), LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(fixture.registry, LinkOption.NOFOLLOW_LINKS));
        assertTrue(Files.readString(settingsPath(fixture.registry)).contains("true"),
                "even a same-value configuration change requires a fresh quote");
    }

    @Test
    void corruptSettingAfterQuoteBlocksWithoutFallbackAndIsRetained() throws Exception {
        Fixture fixture = fixture("6.7.8");
        NormalInstallService.Plan plan =
                fixture.launcherServices.prepareNormalInstall(fixture.destination, null);
        Path settings = Files.writeString(settingsPath(fixture.registry), "{broken");

        IOException corrupt = assertThrows(IOException.class,
                () -> fixture.launcherServices.installNormal(plan, quiet(),
                        OperationContext.none(OperationType.FRESH_INSTALL)));

        assertTrue(corrupt.getMessage().contains("automatic-check setting"));
        assertTrue(corrupt.getMessage().contains("fresh install confirmation"));
        assertEquals(0, fixture.transport.binaryRequests);
        assertFalse(Files.exists(fixture.destination, LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(fixture.destination.getParent(), LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(fixture.registry, LinkOption.NOFOLLOW_LINKS));
        assertEquals("{broken", Files.readString(settings));
    }

    @Test
    void cancellationDuringPreparationNeverPublishesAndPostPublishJavaFailureIsTruthful()
            throws Exception {
        Fixture cancelled = fixture("1.2.3");
        NormalInstallService.Plan cancelledPlan =
                cancelled.service.prepare(cancelled.destination, null);
        AtomicReference<OperationContext> holder = new AtomicReference<>();
        OperationContext context = new OperationContext(OperationType.FRESH_INSTALL, progress -> {
            if (progress.phase() == OperationPhase.PLAN) {
                holder.get().requestCancellation();
            }
        });
        holder.set(context);
        assertThrows(InterruptedException.class,
                () -> cancelled.service.install(cancelledPlan, quiet(),
                        context));
        assertEquals(1, cancelled.transport.binaryRequests);
        assertFalse(Files.exists(cancelled.destination, LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(cancelled.registry, LinkOption.NOFOLLOW_LINKS));

        FakeJava failingJava = new FakeJava(3);
        Fixture retained = fixture("2.0.0", failingJava);
        NormalInstallService.Plan retainedPlan =
                retained.service.prepare(retained.destination, null);
        NormalInstallService.PublishedInstallationException failure = assertThrows(
                NormalInstallService.PublishedInstallationException.class,
                () -> retained.service.install(retainedPlan, quiet(),
                        OperationContext.none(OperationType.FRESH_INSTALL)));
        assertTrue(failure.getMessage().contains("valid and registered"));
        assertTrue(Files.isRegularFile(retained.destination.resolve("MekHQ.jar")));
        assertEquals(1, retained.registries.read(retained.registry).installations().size());
        assertEquals(1, retained.transport.binaryRequests);
    }

    @Test
    void registryChangeDuringDownloadRetainsRegisteredCopyWithoutOverwritingNewMain()
            throws Exception {
        Fixture fixture = fixture("4.5.6");
        NormalInstallService.Plan plan = fixture.service.prepare(fixture.destination, null);
        Path concurrentRoot = createSuite(fixture.state.resolve("Concurrent"), "7.8.9");
        AtomicReference<InstallationRecord> concurrent = new AtomicReference<>();
        fixture.transport.onBinary = () -> {
            try {
                concurrent.set(fixture.registries.register(
                        fixture.registry, "Concurrent", concurrentRoot, null));
            } catch (IOException error) {
                throw new RuntimeException(error);
            }
        };

        NormalInstallService.PublishedInstallationException failure = assertThrows(
                NormalInstallService.PublishedInstallationException.class,
                () -> fixture.service.install(plan, quiet(),
                        OperationContext.none(OperationType.FRESH_INSTALL)));

        assertTrue(failure.getMessage().contains("default selection"));
        RegistryData data = fixture.registries.read(fixture.registry);
        assertEquals(concurrent.get().id(), data.defaultInstallationId());
        assertEquals(2, data.installations().size());
        assertTrue(Files.isRegularFile(fixture.destination.resolve("MegaMek.jar")));
    }

    private Fixture fixture(String version) throws Exception {
        return fixture(version, new FakeJava(Integer.MAX_VALUE));
    }

    private static PrintStream quiet() {
        return new PrintStream(java.io.OutputStream.nullOutputStream());
    }

    private Fixture fixture(String version, FakeJava java) throws Exception {
        Path state = Files.createDirectory(temp.resolve("state-" + version + "-"
                + Integer.toUnsignedString(System.identityHashCode(java))));
        Path registry = state.resolve("registry.json");
        Map<OfficialRepository, byte[]> archives = archives(version);
        String developmentVersion = "9.9.9";
        Map<OfficialRepository, byte[]> developmentArchives = archives(developmentVersion);
        FixtureTransport transport = new FixtureTransport(version, archives,
                developmentVersion, developmentArchives);
        RegistryStore registries = new RegistryStore();
        JavaRuntime runtime = new JavaRuntime(java);
        NormalInstallService service = new NormalInstallService(registry, transport, runtime);
        LauncherServices launcherServices = new LauncherServices(registry, registries,
                new InstallationInspector(), transport, runtime, new ApplicationLauncher(java));
        return new Fixture(state, registry, state.resolve("installations").resolve("Main"),
                transport, registries, service, launcherServices);
    }

    private static Path settingsPath(Path registry) {
        return registry.resolveSibling(registry.getFileName() + ".settings.json");
    }

    private static Path planJava() throws IOException {
        return new JavaRuntime().launcherJava();
    }

    private static Path createSuite(Path root, String version) throws Exception {
        Files.createDirectories(root.resolve("data"));
        Files.createDirectories(root.resolve("mmconf"));
        Files.createDirectories(root.resolve("lib"));
        Files.write(root.resolve("MegaMek.jar"), jar("megamek.MegaMek", version));
        return root;
    }

    private static Map<OfficialRepository, byte[]> archives(String version) throws Exception {
        Map<OfficialRepository, byte[]> archives = new java.util.EnumMap<>(
                OfficialRepository.class);
        for (OfficialRepository repository : OfficialRepository.values()) {
            archives.put(repository, applicationArchive(repository, version));
        }
        return Map.copyOf(archives);
    }

    private static byte[] applicationArchive(OfficialRepository repository, String version)
            throws Exception {
        String root = repository.assetPrefix() + version;
        List<Entry> entries = new ArrayList<>();
        for (String directory : List.of(root, root + "/data", root + "/mmconf",
                root + "/lib")) {
            entries.add(new Entry(directory, new byte[0], true));
        }
        if (repository == OfficialRepository.MEKHQ
                || repository == OfficialRepository.MEGAMEK) {
            entries.add(new Entry(root + "/MegaMek.jar",
                    jar("megamek.MegaMek", version), false));
            entries.add(new Entry(root + "/lib/MegaMek.jar",
                    jar("megamek.MegaMek", version), false));
        }
        if (repository == OfficialRepository.MEKHQ) {
            entries.add(new Entry(root + "/MekHQ.jar",
                    jar("mekhq.MekHQ", version), false));
        }
        if (repository == OfficialRepository.MEKHQ
                || repository == OfficialRepository.LAB) {
            entries.add(new Entry(root + "/MegaMekLab.jar",
                    jar("megameklab.MegaMekLab", version), false));
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(bytes);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            for (Entry item : entries) {
                TarArchiveEntry entry = new TarArchiveEntry(
                        item.directory ? item.name + "/" : item.name);
                entry.setSize(item.directory ? 0 : item.content.length);
                if (item.directory) entry.setMode(0755);
                tar.putArchiveEntry(entry);
                if (!item.directory) tar.write(item.content);
                tar.closeArchiveEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] jar(String mainClass, String version) throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes, manifest)) {
            jar.putNextEntry(new JarEntry("megamek/Version.properties"));
            String[] parts = version.split("\\.");
            jar.write(("major=" + parts[0] + "\nminor=" + parts[1] + "\npatch="
                    + parts[2] + "\n").getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private record Fixture(Path state, Path registry, Path destination,
                           FixtureTransport transport, RegistryStore registries,
                           NormalInstallService service, LauncherServices launcherServices) {
    }

    private record Entry(String name, byte[] content, boolean directory) {
    }

    private static final class FakeJava implements ProcessRunner {
        private final int failAt;
        private int calls;

        private FakeJava(int failAt) {
            this.failAt = failAt;
        }

        @Override
        public Result run(List<String> command, Path workingDirectory, Duration timeout,
                          boolean inheritIo) {
            calls++;
            return calls == failAt
                    ? new Result(1, "failed", false)
                    : new Result(0, "openjdk version \"21.0.4\"", false);
        }
    }

    private static final class FixtureTransport implements ReleaseTransport {
        private String version;
        private final Map<OfficialRepository, byte[]> archives;
        private String developmentVersion;
        private final Map<OfficialRepository, byte[]> developmentArchives;
        private int binaryRequests;
        private final String assetName;
        private int apiRequests;
        private int yamlRequests;
        private boolean malformedFeed;
        private boolean driftOnThirdApi;
        private MetadataDrift metadataDrift = MetadataDrift.NONE;
        private Runnable onBinary = () -> {
        };

        private FixtureTransport(String version, Map<OfficialRepository, byte[]> archives,
                                 String developmentVersion,
                                 Map<OfficialRepository, byte[]> developmentArchives) {
            this.version = version;
            this.archives = archives;
            this.developmentVersion = developmentVersion;
            this.developmentArchives = developmentArchives;
            this.assetName = "MekHQ-" + version + ".tar.gz";
        }

        @Override
        public Response get(URI uri, String accept) throws IOException {
            try {
                if (uri.getHost().equals("raw.githubusercontent.com")) {
                    yamlRequests++;
                    String yaml = malformedFeed ? "stable: [unsafe]\ndev: 9.9.9\n"
                            : "stable: " + version + "\ndev: "
                            + developmentVersion + "\n";
                    return response(yaml.getBytes(StandardCharsets.UTF_8), "text/yaml");
                }
                if (uri.getHost().equals("api.github.com")) {
                    apiRequests++;
                    String requested = requestedVersion(uri);
                    OfficialRepository repository = repository(uri);
                    return response(releaseJson(repository, requested,
                                    archiveFor(repository, requested),
                                    metadataDrift,
                                    driftOnThirdApi && apiRequests >= 3)
                                    .getBytes(StandardCharsets.UTF_8),
                            "application/json");
                }
                if (uri.getHost().equals("github.com")) {
                    binaryRequests++;
                    onBinary.run();
                    return response(archiveFor(repository(uri), requestedVersion(uri)),
                            "application/octet-stream");
                }
                throw new IOException("unexpected fixture URI: " + uri);
            } catch (Exception error) {
                if (error instanceof IOException io) throw io;
                throw new IOException(error);
            }
        }

        private byte[] archiveFor(OfficialRepository repository, String requested) {
            return requested.equals(developmentVersion)
                    ? developmentArchives.get(repository) : archives.get(repository);
        }

        private static OfficialRepository repository(URI uri) throws IOException {
            String path = uri.getPath().toLowerCase(java.util.Locale.ROOT);
            for (OfficialRepository repository : OfficialRepository.values()) {
                if (path.contains("/" + repository.slug().toLowerCase(
                        java.util.Locale.ROOT) + "/")) {
                    return repository;
                }
            }
            throw new IOException("fixture URI has no official repository: " + uri);
        }

        private static String requestedVersion(URI uri) throws IOException {
            String path = uri.getPath();
            int marker = path.lastIndexOf("/v");
            if (marker < 0) throw new IOException("fixture URI has no release tag: " + uri);
            int start = marker + 2;
            int slash = path.indexOf('/', start);
            return slash < 0 ? path.substring(start) : path.substring(start, slash);
        }

        private String releaseJson(OfficialRepository repository, String requested,
                                   byte[] requestedArchive, MetadataDrift metadataDrift,
                                   boolean timedDrift)
                throws Exception {
            String currentName = repository.assetPrefix() + requested + ".tar.gz";
            String tag = metadataDrift == MetadataDrift.TAG
                    ? "v" + requested + "-changed" : "v" + requested;
            String assetName = metadataDrift == MetadataDrift.NAME
                    ? "Other-" + currentName : currentName;
            String urlName = metadataDrift == MetadataDrift.URL
                    ? "different-" + currentName : currentName;
            boolean sizeDrift = timedDrift || metadataDrift == MetadataDrift.SIZE;
            boolean digestDrift = timedDrift || metadataDrift == MetadataDrift.DIGEST;
            String assets = metadataDrift == MetadataDrift.REMOVED ? "[]" : """
                    [{"name":"%s","size":%d,"digest":"sha256:%s",
                    "browser_download_url":"https://github.com/%s/releases/download/v%s/%s"}]
                    """.formatted(assetName,
                    requestedArchive.length + (sizeDrift ? 1 : 0),
                    digestDrift ? "0".repeat(64) : sha(requestedArchive),
                    repository.slug(), requested, urlName);
            return """
                    {"tag_name":"%s","name":"Milestone %s","draft":false,
                    "prerelease":false,
                    "html_url":"https://github.com/%s/releases/tag/v%s",
                    "assets":%s}
                    """.formatted(tag, requested, repository.slug(), requested, assets);
        }

        private static Response response(byte[] bytes, String type) {
            return new Response(200, Map.of(
                    "content-length", List.of(Integer.toString(bytes.length)),
                    "content-type", List.of(type)), new ByteArrayInputStream(bytes));
        }
    }

    private enum MetadataDrift {
        NONE,
        NAME,
        SIZE,
        DIGEST,
        URL,
        REMOVED,
        TAG
    }
}
