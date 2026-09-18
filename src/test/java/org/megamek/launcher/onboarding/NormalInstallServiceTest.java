package org.megamek.launcher.onboarding;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.megamek.launcher.channel.ChannelPreferenceStore;
import org.megamek.launcher.channel.FollowChannel;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        assertEquals(plan.javaExecutable().toString(), latest.javaExecutable());
        assertEquals("Main", latest.name());
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
        assertEquals("Main", main.name());
        assertEquals("9.9.9", main.observedBuild());
        assertEquals(FollowChannel.DEVELOPMENT,
                new ChannelPreferenceStore().read(fixture.registry, data, main)
                        .preference().channel());
        assertTrue(new ChannelPreferenceStore().read(fixture.registry, data, main)
                .preference().checkOnOpen());
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
    void persistedFalseAppliesToNormalInstallAndLeavesExistingFalseCopyUnchanged()
            throws Exception {
        Fixture fixture = fixture("1.2.3");
        Path imported = createSuite(fixture.state.resolve("Imported"), "8.0.0");
        InstallationRecord old = fixture.registries.register(
                fixture.registry, "Existing", imported, "keep-pin");
        fixture.registries.selectJava(fixture.registry, old.id(), planJava().toString());
        old = fixture.registries.resolve(fixture.registries.read(fixture.registry), old.id());
        new ChannelPreferenceStore().set(fixture.registry, old, FollowChannel.DEVELOPMENT, false);
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
        assertEquals(result.record().id(), data.defaultInstallationId());
        assertNotEquals(expectedOld.id(), data.defaultInstallationId());
        assertFalse(new ChannelPreferenceStore().read(fixture.registry, data, unchanged)
                .preference().checkOnOpen());
        assertFalse(new ChannelPreferenceStore().read(fixture.registry, data, result.record())
                .preference().checkOnOpen());
        int metadataRequests = fixture.transport.apiRequests;
        LauncherServices.HomeState home = fixture.launcherServices.loadHome();
        assertEquals(result.record(), home.preferred());
        assertFalse(home.channelPreference().preference().checkOnOpen());
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

        assertTrue(failure.getMessage().contains("Main selection"));
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
        byte[] archive = suiteArchive(version);
        String developmentVersion = "9.9.9";
        byte[] developmentArchive = suiteArchive(developmentVersion);
        FixtureTransport transport = new FixtureTransport(version, archive,
                developmentVersion, developmentArchive);
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

    private static byte[] suiteArchive(String version) throws Exception {
        String root = "MekHQ-" + version;
        List<Entry> entries = new ArrayList<>();
        for (String directory : List.of(root, root + "/data", root + "/mmconf",
                root + "/lib")) {
            entries.add(new Entry(directory, new byte[0], true));
        }
        entries.add(new Entry(root + "/MegaMek.jar",
                jar("megamek.MegaMek", version), false));
        entries.add(new Entry(root + "/MekHQ.jar",
                jar("mekhq.MekHQ", version), false));
        entries.add(new Entry(root + "/MegaMekLab.jar",
                jar("megameklab.MegaMekLab", version), false));
        entries.add(new Entry(root + "/lib/MegaMek.jar",
                jar("megamek.MegaMek", version), false));
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
        private final byte[] archive;
        private String developmentVersion;
        private final byte[] developmentArchive;
        private int binaryRequests;
        private final String assetName;
        private int apiRequests;
        private int yamlRequests;
        private boolean malformedFeed;
        private boolean driftOnThirdApi;
        private Runnable onBinary = () -> {
        };

        private FixtureTransport(String version, byte[] archive,
                                 String developmentVersion, byte[] developmentArchive) {
            this.version = version;
            this.archive = archive;
            this.developmentVersion = developmentVersion;
            this.developmentArchive = developmentArchive;
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
                    return response(releaseJson(requested, archiveFor(requested),
                                    driftOnThirdApi && apiRequests >= 3)
                                    .getBytes(StandardCharsets.UTF_8),
                            "application/json");
                }
                if (uri.getHost().equals("github.com")) {
                    binaryRequests++;
                    onBinary.run();
                    return response(archiveFor(requestedVersion(uri)),
                            "application/octet-stream");
                }
                throw new IOException("unexpected fixture URI: " + uri);
            } catch (Exception error) {
                if (error instanceof IOException io) throw io;
                throw new IOException(error);
            }
        }

        private byte[] archiveFor(String requested) {
            return requested.equals(developmentVersion) ? developmentArchive : archive;
        }

        private static String requestedVersion(URI uri) throws IOException {
            String path = uri.getPath();
            int marker = path.lastIndexOf("/v");
            if (marker < 0) throw new IOException("fixture URI has no release tag: " + uri);
            int start = marker + 2;
            int slash = path.indexOf('/', start);
            return slash < 0 ? path.substring(start) : path.substring(start, slash);
        }

        private String releaseJson(String requested, byte[] requestedArchive, boolean drift)
                throws Exception {
            String currentName = "MekHQ-" + requested + ".tar.gz";
            return """
                    {"tag_name":"v%s","name":"Milestone %s","draft":false,
                    "prerelease":false,
                    "html_url":"https://github.com/MegaMek/mekhq/releases/tag/v%s",
                    "assets":[{"name":"%s","size":%d,"digest":"sha256:%s",
                    "browser_download_url":"https://github.com/MegaMek/mekhq/releases/download/v%s/%s"}]}
                    """.formatted(requested, requested, requested, currentName,
                    requestedArchive.length + (drift ? 1 : 0),
                    drift ? "0".repeat(64) : sha(requestedArchive),
                    requested, currentName);
        }

        private static Response response(byte[] bytes, String type) {
            return new Response(200, Map.of(
                    "content-length", List.of(Integer.toString(bytes.length)),
                    "content-type", List.of(type)), new ByteArrayInputStream(bytes));
        }
    }
}
