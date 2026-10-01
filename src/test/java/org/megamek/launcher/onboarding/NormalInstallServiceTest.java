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
import org.megamek.launcher.channel.SuiteTestData;
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
import org.megamek.launcher.release.ReleaseCatalog;
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
    void independentlyVersionedBundlesInstallOwnVersionsAndRemainRecognizable() throws Exception {
        Map<OfficialRepository, String> versions = Map.of(
                OfficialRepository.MEGAMEK, "0.51.01", OfficialRepository.LAB, "0.51.02",
                OfficialRepository.MEKHQ, "0.51.03");
        Map<String, String> builds = Map.of("megamek", "0.51.01", "lab", "0.51.02", "mekhq", "0.51.03");
        var network = new SuiteTestData();
        Map<OfficialRepository, Long> sizes = new java.util.EnumMap<>(OfficialRepository.class);
        Map<OfficialRepository, String> hashes = new java.util.EnumMap<>(OfficialRepository.class);
        for (OfficialRepository repository : OfficialRepository.values()) {
            byte[] bytes = applicationArchive(repository, versions.get(repository), builds);
            sizes.put(repository, (long) bytes.length);
            hashes.put(repository, sha(bytes));
            network.put(SuiteTestData.packageUri(repository, versions.get(repository)), bytes);
        }
        network.suite("0.51.04", FollowChannel.WEEKLY, versions, sizes, hashes);
        Path registry = temp.resolve("independent-registry.json");
        var installs = new NormalInstallService(registry, network);
        for (OfficialRepository repository : OfficialRepository.values()) {
            String version = versions.get(repository);
            network.put(ReleaseCatalog.exactMetadataUri(repository, "v" + version),
                    SuiteTestData.bytes(network.product(repository, version)));
            var plan = installs.prepare(repository, FollowChannel.WEEKLY,
                    temp.resolve("independent-" + repository.key()));
            assertEquals(version, plan.version());
            var result = installs.install(plan, quiet(), OperationContext.none(OperationType.FRESH_INSTALL));
            var inspection = new InstallationInspector().inspect(Path.of(result.record().canonicalRoot()));
            assertEquals(version, inspection.observedBuild());
            for (Product product : inspection.products()) assertEquals(builds.get(product.key()), product.build());
            assertEquals(FollowChannel.WEEKLY, new ChannelPreferenceStore().read(registry,
                    new RegistryStore().read(registry), result.record()).preference().channel());
        }
        assertEquals(3, network.requests.stream().filter(uri -> uri.getPath().endsWith(".tar.gz")).count());
    }

    @Test
    void matchingRecordDigestCannotAuthorizeAnArchiveWithWrongPrimaryProductVersion() throws Exception {
        var network = new SuiteTestData();
        byte[] bytes = applicationArchive(OfficialRepository.MEKHQ, "0.51.03",
                Map.of("megamek", "0.51.01", "lab", "0.51.02", "mekhq", "0.51.02"));
        network.suite("0.51.04", FollowChannel.WEEKLY,
                Map.of(OfficialRepository.MEGAMEK, "0.51.01", OfficialRepository.LAB, "0.51.02",
                        OfficialRepository.MEKHQ, "0.51.03"),
                Map.of(OfficialRepository.MEKHQ, (long) bytes.length),
                Map.of(OfficialRepository.MEKHQ, sha(bytes)));
        network.put(SuiteTestData.packageUri(OfficialRepository.MEKHQ, "0.51.03"), bytes);
        network.put(ReleaseCatalog.exactMetadataUri(OfficialRepository.MEKHQ, "v0.51.03"),
                SuiteTestData.bytes(network.product(OfficialRepository.MEKHQ, "0.51.03")));
        Path registry = temp.resolve("wrong-primary-registry.json");
        Path destination = temp.resolve("wrong-primary");
        var installs = new NormalInstallService(registry, network);
        var plan = installs.prepare(FollowChannel.WEEKLY, destination);
        assertTrue(assertThrows(IOException.class, () -> installs.install(plan, quiet(),
                OperationContext.none(OperationType.FRESH_INSTALL))).getMessage().contains("primary product version"));
        assertFalse(Files.exists(destination));
        assertFalse(Files.exists(registry));
    }

    @Test
    void milestonePlanIsMetadataOnlyAndConfirmedInstallReturnsLatestReadyMain()
            throws Exception {
        Fixture fixture = fixture("1.02.03");
        NormalInstallService.Plan plan =
                fixture.launcherServices.prepareNormalInstall(fixture.destination);

        assertEquals(FollowChannel.MILESTONE, plan.channel());
        assertEquals("MegaMek/mekhq", plan.repository().slug());
        assertEquals(NormalInstallService.SUITE_PRODUCTS, plan.requiredProducts());
        assertEquals("v1.02.03", plan.release().tag());
        assertEquals(fixture.transport.assetName, plan.asset().name());
        assertEquals(fixture.destination, plan.destination());
        assertEquals(0, fixture.transport.binaryRequests);
        assertFalse(Files.exists(fixture.destination, LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(fixture.registry, LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(settingsPath(fixture.registry)),
                "installation planning must not create or require Java settings");

        NormalInstallService.Result result = fixture.launcherServices.installNormal(plan, quiet(),
                OperationContext.none(OperationType.FRESH_INSTALL));

        assertEquals(1, fixture.transport.binaryRequests);
        assertEquals(0, fixture.java.calls,
                "normal installation must not execute Java");
        RegistryData data = fixture.registries.read(fixture.registry);
        InstallationRecord latest = fixture.registries.resolve(data, null);
        assertEquals(result.record(), latest);
        assertTrue(result.becameMain());
        assertEquals("MekHQ Milestone (1.02.03)", latest.name());
        assertEquals(NormalInstallService.SUITE_PRODUCTS,
                latest.products().stream().map(Product::key)
                        .collect(java.util.stream.Collectors.toSet()));
        var preference = new ChannelPreferenceStore().read(fixture.registry, data, latest);
        assertEquals(FollowChannel.MILESTONE, preference.preference().channel());
        assertTrue(preference.preference().checkOnOpen());
        assertTrue(Files.isRegularFile(fixture.destination.resolve("MegaMek.jar")));
        ApplicationLauncher launcher = new ApplicationLauncher(
                new FakeJava(Integer.MAX_VALUE));
        JavaRuntime.CurrentJava java = new JavaRuntime(new FakeJava(Integer.MAX_VALUE))
                .validateCurrentExternal(latestRoot(latest));
        assertTrue(launcher.command(latest, "megamek", java).contains("megamek.MegaMek"));
        assertTrue(launcher.command(latest, "mekhq", java).contains("mekhq.MekHQ"));
        assertTrue(launcher.command(latest, "lab", java).contains("megameklab.MegaMekLab"));
    }

    @Test
    void configuredDefaultGameJavaIsIgnoredByNormalPlanAndImport() throws Exception {
        Fixture fixture = fixture("6.07.08");
        Path selectedJava = Files.writeString(
                fixture.state.resolve("java.exe"), "fixture").toRealPath();
        fixture.launcherServices.selectDefaultJava(selectedJava);
        int callsAfterSelection = fixture.java.calls;

        NormalInstallService.Plan install =
                fixture.launcherServices.prepareNormalInstall(fixture.destination);
        assertEquals(callsAfterSelection, fixture.java.calls,
                "install planning must not validate configured Java");

        Path existing = createSuite(fixture.state.resolve("existing-copy"), "6.07.08");
        var imported = fixture.launcherServices.prepareExistingImport(existing,
                OperationContext.none(OperationType.IMPORT_EXISTING));
        assertEquals(existing.toRealPath().toString(),
                imported.inspection().canonicalRoot());
    }

    @Test
    void developmentPlanUsesExactDevTargetAndPersistsDevelopmentAfterOneTransfer()
            throws Exception {
        Fixture fixture = fixture("1.02.03");

        NormalInstallService.Plan plan = fixture.launcherServices.prepareNormalInstall(
                FollowChannel.DEVELOPMENT, fixture.destination);

        assertEquals(FollowChannel.DEVELOPMENT, plan.channel());
        assertEquals("9.09.09", plan.version());
        assertEquals("v9.09.09", plan.release().tag());
        assertEquals("MekHQ-9.09.09.tar.gz", plan.asset().name());
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
        assertEquals("MekHQ Development (9.09.09)", main.name());
        assertEquals("9.09.09", main.observedBuild());
        assertEquals(FollowChannel.DEVELOPMENT,
                new ChannelPreferenceStore().read(fixture.registry, data, main)
                        .preference().channel());
        assertTrue(new ChannelPreferenceStore().read(fixture.registry, data, main)
                .preference().checkOnOpen());
    }

    @Test
    void capturedCurrentPlanNeverRereadsPointerAndInstallsOnlyCapturedTarget()
            throws Exception {
        Fixture fixture = fixture("1.02.03");
        QuickInstallSnapshot snapshot = fixture.launcherServices.quickInstallSnapshot();
        QuickInstallOption captured = snapshot.option(QuickInstallSnapshot.DEFAULT_KEY);
        int websiteAfterSnapshot = fixture.transport.websiteRequests;
        int apiAfterSnapshot = fixture.transport.apiRequests;

        NormalInstallService.Plan plan =
                fixture.launcherServices.prepareCapturedNormalInstall(
                        captured, fixture.destination);

        assertTrue(plan.currentChannelTarget());
        assertTrue(plan.capturedCurrentTarget());
        assertEquals(NormalInstallService.TargetKind.CAPTURED_CURRENT, plan.targetKind());
        assertEquals(websiteAfterSnapshot, fixture.transport.websiteRequests);
        assertEquals(apiAfterSnapshot, fixture.transport.apiRequests,
                "captured planning performs only fresh local validation");

        fixture.transport.version = "8.08.08";
        NormalInstallService.Result result = fixture.launcherServices.installNormal(
                plan, quiet(), OperationContext.none(OperationType.FRESH_INSTALL));

        assertEquals("v1.02.03", result.release().tag());
        assertEquals(websiteAfterSnapshot, fixture.transport.websiteRequests,
                "install-time validation must not reread the moving current pointer");
        assertEquals(apiAfterSnapshot + 5, fixture.transport.apiRequests,
                "the captured record, all three references, and fetched product are revalidated");
        assertEquals(1, fixture.transport.binaryRequests);
        assertEquals(0, fixture.java.calls,
                "captured-current installation must not execute Java");
    }

    @Test
    void capturedTargetReplanningRefreshesLocalDestinationOnly()
            throws Exception {
        Fixture fixture = fixture("1.03.00");
        QuickInstallOption captured = fixture.launcherServices.quickInstallSnapshot()
                .option(QuickInstallSnapshot.DEFAULT_KEY);
        int websiteAfterSnapshot = fixture.transport.websiteRequests;
        int apiAfterSnapshot = fixture.transport.apiRequests;
        Path occupied = Files.createDirectory(fixture.state.resolve("occupied"));
        assertThrows(IOException.class,
                () -> fixture.launcherServices.prepareCapturedNormalInstall(
                        captured, occupied));
        assertEquals(websiteAfterSnapshot, fixture.transport.websiteRequests);
        assertEquals(apiAfterSnapshot, fixture.transport.apiRequests,
                "a local planning failure does not refresh the captured target");
        NormalInstallService.Plan initial =
                fixture.launcherServices.prepareCapturedNormalInstall(
                        captured, fixture.destination);
        Path changedDestination = fixture.state.resolve("installations")
                .resolve("Changed MekHQ Milestone");
        NormalInstallService.Plan changed =
                fixture.launcherServices.prepareCapturedNormalInstall(
                        captured, changedDestination);

        assertEquals(fixture.destination, initial.destination());
        assertEquals(changedDestination, changed.destination());
        assertEquals(initial.release(), changed.release());
        assertEquals(initial.asset(), changed.asset());
        assertEquals(websiteAfterSnapshot, fixture.transport.websiteRequests);
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
                            captured, fixture.destination);
            int websiteAfterSnapshot = fixture.transport.websiteRequests;
            fixture.transport.metadataDrift = drift;

            assertThrows(IOException.class,
                    () -> fixture.launcherServices.installNormal(plan, quiet(),
                            OperationContext.none(OperationType.FRESH_INSTALL)));

            assertEquals(websiteAfterSnapshot, fixture.transport.websiteRequests);
            assertEquals(0, fixture.transport.binaryRequests);
            assertFalse(Files.exists(fixture.destination, LinkOption.NOFOLLOW_LINKS));
            assertFalse(Files.exists(fixture.destination.getParent(), LinkOption.NOFOLLOW_LINKS));
            assertFalse(Files.exists(fixture.registry, LinkOption.NOFOLLOW_LINKS));
        }
    }

    @Test
    void historicalExactPlanSkipsYamlAndFixesChannelOnlyForCreatedInstallation()
            throws Exception {
        Fixture fixture = fixture("1.02.03");

        NormalInstallService.Plan plan = fixture.service.prepareExact(
                OfficialRepository.MEKHQ, FollowChannel.MILESTONE, "v0.01.00",
                fixture.destination);

        assertFalse(plan.currentChannelTarget());
        assertEquals("0.01.00", plan.version());
        assertEquals("v0.01.00", plan.release().tag());
        assertEquals(FollowChannel.MILESTONE, plan.channel());
        assertEquals(SuiteTestData.recordUri("0.01.00").toString(), plan.source());
        assertEquals(0, fixture.transport.websiteRequests,
                "history selection must not be mislabeled through a current channel pointer");
        assertEquals(4, fixture.transport.apiRequests);
        assertEquals(0, fixture.transport.binaryRequests);

        NormalInstallService.Result result = fixture.service.install(
                plan, quiet(), OperationContext.none(OperationType.FRESH_INSTALL));

        assertEquals(0, fixture.transport.websiteRequests);
        assertEquals(9, fixture.transport.apiRequests,
                "the planner and package fetch revalidate the captured record and exact product");
        assertEquals(1, fixture.transport.binaryRequests);
        assertEquals(0, fixture.java.calls,
                "historical installation must not execute Java");
        RegistryData data = fixture.registries.read(fixture.registry);
        var preference = new ChannelPreferenceStore()
                .read(fixture.registry, data, result.record()).preference();
        assertEquals(FollowChannel.MILESTONE, preference.channel());
        assertTrue(preference.checkOnOpen());
    }

    @Test
    void browsedLocationReplanKeepsCapturedRecordWhenANewerRecordReusesTheProductTag()
            throws Exception {
        Fixture fixture = fixture("1.02.03");
        NormalInstallService.Plan initial = fixture.service.prepareExact(
                OfficialRepository.MEKHQ, FollowChannel.MILESTONE, "v0.01.00", fixture.destination);
        fixture.transport.addSuite("2.00.00", FollowChannel.MILESTONE, "0.01.00");
        Path changed = fixture.state.resolve("different-location");
        NormalInstallService.Plan replanned = fixture.service.prepareExact(
                initial.repository(), initial.channel(), initial.release().tag(),
                initial.source(), changed);
        assertEquals(initial.source(), replanned.source());
        assertEquals(initial.release(), replanned.release());
        assertEquals(initial.asset(), replanned.asset());
        assertEquals(changed, replanned.destination());
        assertEquals(SuiteTestData.recordUri("2.00.00").toString(),
                fixture.service.prepareExact(initial.repository(), initial.channel(),
                        initial.release().tag(), fixture.state.resolve("tag-only-location")).source());
        assertEquals(0, fixture.transport.binaryRequests);
        assertFalse(Files.exists(changed));
        assertFalse(Files.exists(fixture.registry));
    }

    @Test
    void historicalExactMetadataDriftFailsBeforePackageTransfer() throws Exception {
        Fixture fixture = fixture("1.02.03");
        NormalInstallService.Plan plan = fixture.service.prepareExact(
                OfficialRepository.MEKHQ, FollowChannel.DEVELOPMENT, "v9.09.09",
                fixture.destination);
        fixture.transport.metadataDrift = MetadataDrift.DIGEST;

        IOException changed = assertThrows(IOException.class,
                () -> fixture.service.install(plan, quiet(),
                        OperationContext.none(OperationType.FRESH_INSTALL)));

        assertTrue(changed.getMessage().contains("SHA-256"));
        assertEquals(FollowChannel.DEVELOPMENT, plan.channel());
        assertEquals(0, fixture.transport.binaryRequests);
        assertFalse(Files.exists(fixture.destination, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void everyOfficialRepositoryChannelPairPersistsItsChannelAndExactProductLayout()
            throws Exception {
        for (OfficialRepository repository : OfficialRepository.values()) {
            for (FollowChannel channel : FollowChannel.values()) {
                Fixture fixture = fixture("4." + repository.ordinal() + "."
                        + channel.ordinal());
                NormalInstallService.Plan plan = fixture.service.prepare(
                        repository, channel, fixture.destination);

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
        Fixture fixture = fixture("5.00.00");
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
        Fixture fixture = fixture("5.00.02");
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
                locations);
        Path destination = dataHome.resolve("MegaMek").resolve("MekHQ Milestone");

        assertEquals(destination, service.defaultDestination());
        NormalInstallService.Plan plan = service.prepare(destination);
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
        Fixture fixture = fixture("5.00.01");
        Path first = fixture.state.resolve("one");
        Path second = first.resolve("two");
        Path third = second.resolve("three");
        Path fourth = third.resolve("four");
        Path destination = fourth.resolve("MekHQ Milestone");

        NormalInstallService.Plan plan =
                fixture.service.prepare(destination);

        assertEquals(List.of(first, second, third, fourth), plan.missingParents());
        for (Path parent : plan.missingParents()) {
            assertFalse(Files.exists(parent, LinkOption.NOFOLLOW_LINKS),
                    "planning does not create quoted parents");
        }

        IOException tooDeep = assertThrows(IOException.class,
                () -> fixture.service.prepare(
                        fourth.resolve("five").resolve("MekHQ Development")));
        assertTrue(tooDeep.getMessage().contains("too many new parent"));
    }

    @Test
    void nullChannelAndDevelopmentFeedDriftFailBeforePackageOrStateWrite()
            throws Exception {
        Fixture invalid = fixture("1.02.03");
        IOException missing = assertThrows(IOException.class,
                () -> invalid.service.prepare(null, invalid.destination));
        assertTrue(missing.getMessage().contains("Milestone, Development, or Weekly"));
        assertEquals(0, invalid.transport.websiteRequests);
        assertEquals(0, invalid.transport.apiRequests);
        assertEquals(0, invalid.transport.binaryRequests);
        assertFalse(Files.exists(invalid.registry, LinkOption.NOFOLLOW_LINKS));
        IOException missingRepository = assertThrows(IOException.class,
                () -> invalid.service.prepare(null, FollowChannel.MILESTONE,
                        invalid.destination));
        assertTrue(missingRepository.getMessage().contains(
                "MekHQ, MegaMek, or MegaMekLab"));
        assertEquals(0, invalid.transport.websiteRequests);

        Fixture drift = fixture("2.03.04");
        NormalInstallService.Plan plan = drift.service.prepare(
                FollowChannel.DEVELOPMENT, drift.destination);
        drift.transport.developmentVersion = "9.09.08";
        IOException changed = assertThrows(IOException.class,
                () -> drift.service.install(plan, quiet(),
                        OperationContext.none(OperationType.FRESH_INSTALL)));
        assertTrue(changed.getMessage().contains("metadata changed"));
        assertEquals(0, drift.transport.binaryRequests);
        assertFalse(Files.exists(drift.destination, LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(drift.registry, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void unusableJavaCannotInvalidateEitherChannelInstall()
            throws Exception {
        for (FollowChannel channel : FollowChannel.values()) {
            FakeJava changedJava = new FakeJava(1);
            Fixture fixture = fixture("3.4." + (channel.ordinal() + 1), changedJava);
            NormalInstallService.Plan plan =
                    fixture.service.prepare(channel, fixture.destination);

            NormalInstallService.Result result = fixture.service.install(plan, quiet(),
                    OperationContext.none(OperationType.FRESH_INSTALL));

            assertEquals(fixture.destination, result.destination());
            assertEquals(1, fixture.transport.binaryRequests);
            assertEquals(0, changedJava.calls);
        }
    }

    @Test
    void cancelBeforeTransferCreatesNoDestinationRegistryOrBinaryRequest() throws Exception {
        Fixture fixture = fixture("1.02.03");
        NormalInstallService.Plan plan = fixture.service.prepare(fixture.destination);
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
        Fixture drift = fixture("1.02.03");
        NormalInstallService.Plan driftPlan = drift.service.prepare(drift.destination);
        drift.transport.version = "1.02.04";
        IOException changed = assertThrows(IOException.class,
                () -> drift.service.install(driftPlan, quiet(),
                        OperationContext.none(OperationType.FRESH_INSTALL)));
        assertTrue(changed.getMessage().contains("metadata changed"));
        assertEquals(0, drift.transport.binaryRequests);

        Fixture collision = fixture("2.00.00");
        NormalInstallService.Plan collisionPlan =
                collision.service.prepare(collision.destination);
        Files.createDirectories(collision.destination);
        IOException appeared = assertThrows(IOException.class,
                () -> collision.service.install(collisionPlan, quiet(),
                        OperationContext.none(OperationType.FRESH_INSTALL)));
        assertTrue(appeared.getMessage().contains("wholly nonexistent"));
        assertEquals(0, collision.transport.binaryRequests);

        Fixture overlap = fixture("3.00.00");
        Path logs = overlap.registry.resolveSibling(
                overlap.registry.getFileName() + ".launcher-logs");
        IOException unsafe = assertThrows(IOException.class,
                () -> overlap.service.prepare(logs.resolve("Main")));
        assertTrue(unsafe.getMessage().contains("diagnostics"));
        assertEquals(0, overlap.transport.binaryRequests);
    }

    @Test
    void malformedRecordsAndThirdMetadataReadAssetDriftNeverReachBinary() throws Exception {
        Fixture malformed = fixture("1.02.03");
        malformed.transport.malformedRecords = true;
        assertThrows(IOException.class,
                () -> malformed.service.prepare(malformed.destination));
        assertEquals(0, malformed.transport.binaryRequests);
        assertFalse(Files.exists(malformed.registry, LinkOption.NOFOLLOW_LINKS));

        Fixture assetDrift = fixture("2.03.04");
        NormalInstallService.Plan plan =
                assetDrift.service.prepare(assetDrift.destination);
        assetDrift.transport.driftOnThirdApi = true;
        IOException changed = assertThrows(IOException.class,
                () -> assetDrift.service.install(plan, quiet(),
                        OperationContext.none(OperationType.FRESH_INSTALL)));
        assertTrue(changed.getMessage().contains("expected")
                || changed.getMessage().contains("changed")
                || changed.getMessage().contains("identity"));
        assertEquals(0, assetDrift.transport.binaryRequests);
        assertFalse(Files.exists(assetDrift.destination, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void staleRegistryAndSymlinkParentAreRejectedWithoutTransfer() throws Exception {
        Fixture stale = fixture("1.02.03");
        NormalInstallService.Plan plan = stale.service.prepare(stale.destination);
        Path imported = createSuite(stale.state.resolve("Imported"), "9.09.09");
        stale.registries.register(stale.registry, "Imported", imported, null);

        IOException changed = assertThrows(IOException.class,
                () -> stale.service.install(plan, quiet(),
                        OperationContext.none(OperationType.FRESH_INSTALL)));
        assertTrue(changed.getMessage().contains("changed"));
        assertEquals(0, stale.transport.binaryRequests);

        Fixture linked = fixture("1.02.03");
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
                () -> linked.service.prepare(link.resolve("Main")));
        assertTrue(linkError.getMessage().contains("linked")
                || linkError.getMessage().contains("noncanonical"));
        assertEquals(0, linked.transport.binaryRequests);
    }

    @Test
    void managedInstallAlwaysOptsInAndLeavesImportedCopyWithoutChannel()
            throws Exception {
        Fixture fixture = fixture("1.02.03");
        Path imported = createSuite(fixture.state.resolve("Imported"), "8.00.00");
        InstallationRecord old = fixture.registries.register(
                fixture.registry, "Existing", imported, "keep-pin");
        InstallationRecord expectedOld = old;
        NormalInstallService.Plan plan =
                fixture.launcherServices.prepareNormalInstall(fixture.destination);
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
        assertTrue(new ChannelPreferenceStore().read(fixture.registry, data, result.record())
                .preference().checkOnOpen());
        int metadataRequests = fixture.transport.apiRequests;
        LauncherServices.HomeState home = fixture.launcherServices.loadHome();
        assertEquals(expectedOld.id(), home.preferred().id());
        assertEquals(ChannelPreferenceStore.Status.UNKNOWN,
                home.channelPreference().status());
        assertTrue(fixture.launcherServices.canCheckOnOpen(result.record()));
        assertEquals(metadataRequests, fixture.transport.apiRequests,
                "an off copy must not request update metadata on open");
        assertEquals(1, fixture.transport.binaryRequests);
    }

    @Test
    void unrelatedLauncherSettingChangeAfterQuoteDoesNotInvalidateInstall()
            throws Exception {
        Fixture fixture = fixture("5.06.07");
        NormalInstallService.Plan plan =
                fixture.launcherServices.prepareNormalInstall(fixture.destination);
        Path selectedJava = Files.writeString(Files.createDirectories(
                fixture.state.resolve("changed-java")).resolve("java.exe"),
                "fixture").toRealPath();
        fixture.launcherServices.selectDefaultJava(selectedJava);

        NormalInstallService.Result result = fixture.launcherServices.installNormal(
                plan, quiet(), OperationContext.none(OperationType.FRESH_INSTALL));

        assertEquals(plan.destination(), result.destination());
        assertEquals(1, fixture.transport.binaryRequests);
    }

    @Test
    void corruptSettingsAfterQuoteDoesNotAffectInstall() throws Exception {
        Fixture fixture = fixture("6.07.08");
        NormalInstallService.Plan plan =
                fixture.launcherServices.prepareNormalInstall(fixture.destination);
        Path settings = Files.writeString(settingsPath(fixture.registry), "{broken");

        NormalInstallService.Result result = fixture.launcherServices.installNormal(
                plan, quiet(), OperationContext.none(OperationType.FRESH_INSTALL));
        assertEquals(fixture.destination, result.destination());
        assertEquals(1, fixture.transport.binaryRequests);
        assertEquals("{broken", Files.readString(settings));
    }

    @Test
    void cancellationDuringPreparationNeverPublishesAndJavaFailureCannotAffectInstall()
            throws Exception {
        Fixture cancelled = fixture("1.02.03");
        NormalInstallService.Plan cancelledPlan =
                cancelled.service.prepare(cancelled.destination);
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

        FakeJava failingJava = new FakeJava(4);
        Fixture retained = fixture("2.00.00", failingJava);
        NormalInstallService.Plan retainedPlan =
                retained.service.prepare(retained.destination);
        NormalInstallService.Result installed = retained.service.install(retainedPlan, quiet(),
                OperationContext.none(OperationType.FRESH_INSTALL));
        assertEquals(retained.destination, installed.destination());
        assertTrue(Files.isRegularFile(retained.destination.resolve("MekHQ.jar")));
        assertEquals(1, retained.registries.read(retained.registry).installations().size());
        assertEquals(1, retained.transport.binaryRequests);
        assertEquals(0, failingJava.calls);
    }

    @Test
    void registryChangeDuringDownloadRetainsRegisteredCopyWithoutOverwritingNewMain()
            throws Exception {
        Fixture fixture = fixture("4.05.06");
        NormalInstallService.Plan plan = fixture.service.prepare(fixture.destination);
        Path concurrentRoot = createSuite(fixture.state.resolve("Concurrent"), "7.08.09");
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
        version = SuiteTestData.canonical(version);
        Path state = Files.createDirectory(temp.resolve("state-" + version + "-"
                + Integer.toUnsignedString(System.identityHashCode(java))));
        Path registry = state.resolve("registry.json");
        Map<OfficialRepository, byte[]> archives = archives(version);
        String developmentVersion = "9.09.09";
        Map<OfficialRepository, byte[]> developmentArchives = archives(developmentVersion);
        FixtureTransport transport = new FixtureTransport(version, archives,
                developmentVersion, developmentArchives);
        RegistryStore registries = new RegistryStore();
        JavaRuntime runtime = new JavaRuntime(java);
        NormalInstallService service = new NormalInstallService(registry, transport);
        LauncherServices launcherServices = new LauncherServices(registry, registries,
                new InstallationInspector(), transport, runtime, new ApplicationLauncher(java));
        return new Fixture(state, registry, state.resolve("installations").resolve("Main"),
                transport, registries, service, launcherServices, java);
    }

    private static Path settingsPath(Path registry) {
        return registry.resolveSibling(registry.getFileName() + ".settings.json");
    }

    private static Path latestRoot(InstallationRecord record) {
        return Path.of(record.canonicalRoot());
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
        return applicationArchive(repository, version,
                Map.of("megamek", version, "mekhq", version, "lab", version));
    }

    private static byte[] applicationArchive(OfficialRepository repository, String version,
                                             Map<String, String> builds) throws Exception {
        String root = repository.assetPrefix() + version;
        List<Entry> entries = new ArrayList<>();
        for (String directory : List.of(root, root + "/data", root + "/mmconf",
                root + "/lib")) {
            entries.add(new Entry(directory, new byte[0], true));
        }
        if (repository == OfficialRepository.MEKHQ
                || repository == OfficialRepository.MEGAMEK) {
            entries.add(new Entry(root + "/MegaMek.jar",
                    jar("megamek.MegaMek", builds.get("megamek")), false));
        }
        entries.add(new Entry(root + "/lib/MegaMek.jar",
                jar("megamek.MegaMek", builds.get("megamek")), false));
        if (repository == OfficialRepository.MEKHQ) {
            entries.add(new Entry(root + "/MekHQ.jar",
                    jar("mekhq.MekHQ", builds.get("mekhq")), false));
        }
        if (repository == OfficialRepository.MEKHQ
                || repository == OfficialRepository.LAB) {
            entries.add(new Entry(root + "/MegaMekLab.jar",
                    jar("megameklab.MegaMekLab", builds.get("lab")), false));
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
        if (!mainClass.equals("megamek.MegaMek")) {
            manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, version);
        }
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
                           NormalInstallService service, LauncherServices launcherServices,
                           FakeJava java) {
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
                    : new Result(0, "openjdk version \"21.00.04\"", false);
        }
    }

    private static final class FixtureTransport implements ReleaseTransport {
        private String version;
        private final Map<OfficialRepository, byte[]> archives;
        private String developmentVersion;
        private final Map<OfficialRepository, byte[]> developmentArchives;
        private final Map<OfficialRepository, byte[]> historicalArchives;
        private final SuiteTestData complete = new SuiteTestData();
        private final java.util.Set<String> knownVersions = new java.util.LinkedHashSet<>();
        private String recordedMilestone;
        private String recordedDevelopment;
        private int binaryRequests;
        private final String assetName;
        private int apiRequests;
        private int websiteRequests;
        private boolean malformedRecords;
        private boolean driftOnThirdApi;
        private MetadataDrift metadataDrift = MetadataDrift.NONE;
        private Runnable onBinary = () -> {
        };

        private FixtureTransport(String version, Map<OfficialRepository, byte[]> archives,
                                 String developmentVersion,
                                 Map<OfficialRepository, byte[]> developmentArchives) throws Exception {
            this.version = version;
            this.archives = archives;
            this.developmentVersion = developmentVersion;
            this.developmentArchives = developmentArchives;
            this.assetName = "MekHQ-" + version + ".tar.gz";
            historicalArchives = archives("0.01.00");
            addSuite("0.01.00", FollowChannel.MILESTONE, "0.01.00");
            addSuite(version, FollowChannel.MILESTONE, version);
            addSuite(developmentVersion, FollowChannel.DEVELOPMENT, developmentVersion);
            addSuite("10.00.00", FollowChannel.WEEKLY, developmentVersion);
            recordedMilestone = version;
            recordedDevelopment = developmentVersion;
        }

        private void addSuite(String suiteVersion, FollowChannel channel, String productVersion)
                throws Exception {
            Map<OfficialRepository, String> versions = new java.util.EnumMap<>(OfficialRepository.class);
            Map<OfficialRepository, Long> sizes = new java.util.EnumMap<>(OfficialRepository.class);
            Map<OfficialRepository, String> hashes = new java.util.EnumMap<>(OfficialRepository.class);
            for (OfficialRepository repository : OfficialRepository.values()) {
                byte[] bytes = archiveFor(repository, productVersion);
                versions.put(repository, productVersion);
                sizes.put(repository, (long) bytes.length);
                hashes.put(repository, sha(bytes));
            }
            complete.suite(suiteVersion, channel, versions, sizes, hashes);
            knownVersions.add(productVersion);
        }

        @Override
        public Response get(URI uri, String accept) throws IOException {
            try {
                if (uri.getHost().equals("raw.githubusercontent.com")) {
                    websiteRequests++;
                    throw new IOException("legacy website pointers must not be requested");
                }
                if (uri.getPath().endsWith(".json") && uri.getPath().contains("suite-record-")) {
                    return complete.get(uri, accept);
                }
                if (uri.getHost().equals("api.github.com")) {
                    apiRequests++;
                    if (uri.getQuery() != null) {
                        if (malformedRecords) return response("{}".getBytes(StandardCharsets.UTF_8),
                                "application/json");
                        if (!version.equals(recordedMilestone)) {
                            addSuite(version, FollowChannel.MILESTONE, version);
                            recordedMilestone = version;
                        }
                        if (!developmentVersion.equals(recordedDevelopment)) {
                            addSuite("10.00.01", FollowChannel.DEVELOPMENT, developmentVersion);
                            recordedDevelopment = developmentVersion;
                        }
                        return complete.get(uri, accept);
                    }
                    OfficialRepository repository = repository(uri);
                    String requested;
                    if (uri.getPath().matches(".*/releases/[0-9]+")) {
                        long id = Long.parseLong(uri.getPath().substring(uri.getPath().lastIndexOf('/') + 1));
                        requested = knownVersions.stream()
                                .filter(value -> SuiteTestData.releaseId(repository, value) == id)
                                .findFirst().orElseThrow(() -> new IOException("unknown fixture release ID"));
                    } else requested = requestedVersion(uri);
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
                    ? developmentArchives.get(repository) : requested.equals("0.01.00")
                    ? historicalArchives.get(repository) : archives.get(repository);
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
            String digestField = digestDrift
                    ? "\"digest\":\"sha256:" + "0".repeat(64) + "\"," : "";
            String assets = metadataDrift == MetadataDrift.REMOVED ? "[]" : """
                    [{"id":%d,"state":"uploaded","name":"%s","size":%d,%s
                    "browser_download_url":"https://github.com/%s/releases/download/v%s/%s"}]
                    """.formatted(SuiteTestData.assetId(repository, requested), assetName,
                    requestedArchive.length + (sizeDrift ? 1 : 0),
                    digestField,
                    repository.slug(), requested, urlName);
            return """
                    {"id":%d,"tag_name":"%s","name":"Milestone %s","draft":false,
                    "prerelease":false,
                    "html_url":"https://github.com/%s/releases/tag/v%s",
                    "assets":%s}
                    """.formatted(SuiteTestData.releaseId(repository, requested),
                    tag, requested, repository.slug(), requested, assets);
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
