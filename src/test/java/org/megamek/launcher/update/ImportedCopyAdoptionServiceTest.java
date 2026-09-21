package org.megamek.launcher.update;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.megamek.launcher.channel.ChannelPreferenceStore;
import org.megamek.launcher.channel.FollowChannel;
import org.megamek.launcher.launch.RootCoordinator;
import org.megamek.launcher.onboarding.InstallationInspector;
import org.megamek.launcher.operation.OperationContext;
import org.megamek.launcher.operation.OperationCancelledException;
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
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportedCopyAdoptionServiceTest {
    private static final byte[] DEPENDENCY = {1, 2, 3, 4};
    @TempDir Path temp;

    @Test
    void pristineImportPublishesExactAncestorWithoutChangingRootOrDownloadingTwice()
            throws Exception {
        byte[] jar = jar(null);
        byte[] archive = archive(jar, "official-data", "official-setting");
        QueueTransport transport = transport(archive);
        Fixture fixture = fixture(jar, "official-data", "local-setting", transport, point -> {});
        Map<String, Evidence> before = rootEvidence(fixture.root());

        try (PreparedAdoption prepared = fixture.service().prepare(fixture.record(),
                org.megamek.launcher.release.OfficialRepository.MEGAMEK, "v1.2.3",
                FollowChannel.MILESTONE, new PrintStream(new ByteArrayOutputStream()),
                OperationContext.none(OperationType.ADOPT_EXISTING))) {
            assertTrue(prepared.report().eligible());
            assertEquals("This copy can be managed safely. Existing files will not be changed.",
                    prepared.report().message());
            ImportedCopyAdoptionService.CommitResult result =
                    fixture.service().commit(prepared);
            assertEquals(FollowChannel.MILESTONE, result.channel());
            assertThrows(IOException.class, () -> fixture.service().commit(prepared),
                    "a committed handle is one-use");
        }

        assertEquals(before, rootEvidence(fixture.root()),
                "adoption must preserve every root byte and timestamp");
        RegistryData data = fixture.registries().read(fixture.registry());
        InstallationRecord record = fixture.registries().resolve(data, fixture.record().id());
        OwnershipReceipt receipt = fixture.receipts().read(fixture.registry(), data, record);
        CurrentUpdateState current = new CurrentStateStore(fixture.receipts()).read(
                fixture.registry(), data, record, receipt);
        assertEquals("v1.2.3", receipt.tag());
        assertEquals("v1.2.3", current.tag());
        assertTrue(current.overrides().isEmpty());
        var preference = new ChannelPreferenceStore()
                .read(fixture.registry(), data, record).preference();
        assertEquals(FollowChannel.MILESTONE, preference.channel());
        assertTrue(preference.checkOnOpen());
        assertTrue(new UpdatePreviewService(transport)
                .eligibility(fixture.registry(), record.id()).available());
        assertEquals(ImportedCopyAdoptionService.Availability.MANAGED,
                fixture.service().availability(data, record, false));
        assertEquals(1, transport.packageRequests);
        assertEquals(4, transport.requests.size(),
                "commit refreshes metadata but does not request another package");
        assertFalse(record.updateEligible(),
                "registry membership remains non-authoritative; sidecars gate updates");

        Files.delete(new AdoptionStateStore(fixture.receipts()).path(
                fixture.registry(), record.id()));
        assertFalse(new UpdatePreviewService(transport)
                .eligibility(fixture.registry(), record.id()).available(),
                "partial/corrupt adopted provenance must not authorize updates");
        assertEquals(ImportedCopyAdoptionService.Availability.INCOMPLETE,
                fixture.service().availability(data, record, false));
    }

    @Test
    void modifiedDataProtectedAndUnknownFilesArePreservedAndSeeded()
            throws Exception {
        byte[] jar = jar(null);
        byte[] archive = archive(jar, "official-data", "official-setting");
        QueueTransport transport = transport(archive);
        Fixture fixture = fixture(jar, "locally-modified", "my-setting", transport, point -> {});
        Files.writeString(fixture.root().resolve("notes.custom"), "keep");
        Map<String, Evidence> before = rootEvidence(fixture.root());

        PreparedAdoption prepared = fixture.service().prepare(fixture.record(),
                org.megamek.launcher.release.OfficialRepository.MEGAMEK, "v1.2.3",
                FollowChannel.DEVELOPMENT, new PrintStream(new ByteArrayOutputStream()),
                OperationContext.none(OperationType.ADOPT_EXISTING));
        assertTrue(prepared.report().eligible());
        fixture.service().commit(prepared);

        RegistryData data = fixture.registries().read(fixture.registry());
        OwnershipReceipt receipt = fixture.receipts().read(
                fixture.registry(), data, fixture.record());
        CurrentUpdateState current = new CurrentStateStore(fixture.receipts()).read(
                fixture.registry(), data, fixture.record(), receipt);
        assertEquals(List.of("data/base.txt"),
                current.overrides().stream().map(
                        org.megamek.launcher.sandbox.OverrideEntry::path).toList());
        assertEquals(before, rootEvidence(fixture.root()));
        assertEquals("my-setting", Files.readString(
                fixture.root().resolve("mmconf/user.cfg")));
        assertEquals("keep", Files.readString(fixture.root().resolve("notes.custom")));
    }

    @Test
    void modifiedRuntimeAndMissingManagedFilesRemainLaunchOnly() throws Exception {
        byte[] officialJar = jar(null);
        byte[] archive = archive(officialJar, "official-data", "official-setting");

        QueueTransport mixedTransport = transport(archive);
        Fixture mixed = fixture(jar("local-extra"), "official-data", "local-setting",
                mixedTransport, point -> {});
        try (PreparedAdoption prepared = mixed.service().prepare(mixed.record(),
                org.megamek.launcher.release.OfficialRepository.MEGAMEK, "v1.2.3",
                FollowChannel.MILESTONE, new PrintStream(new ByteArrayOutputStream()),
                OperationContext.none(OperationType.ADOPT_EXISTING))) {
            assertFalse(prepared.report().eligible());
            assertEquals("This copy could not be verified and will remain launch-only.",
                    prepared.report().message());
            assertThrows(IOException.class, () -> mixed.service().commit(prepared));
        }
        assertNoProvenance(mixed);

        QueueTransport dependencyTransport = transport(archive);
        Fixture dependency = fixture(officialJar, "official-data", "local-setting",
                dependencyTransport, point -> {});
        Files.write(dependency.root().resolve("lib/library.jar"), new byte[]{9, 8, 7});
        try (PreparedAdoption prepared = dependency.service().prepare(dependency.record(),
                org.megamek.launcher.release.OfficialRepository.MEGAMEK, "v1.2.3",
                FollowChannel.MILESTONE, new PrintStream(new ByteArrayOutputStream()),
                OperationContext.none(OperationType.ADOPT_EXISTING))) {
            assertFalse(prepared.report().eligible(),
                    "a mixed dependency JAR must block adoption");
        }
        assertNoProvenance(dependency);

        QueueTransport criticalMissingTransport = transport(archive);
        Fixture criticalMissing = fixture(officialJar, "official-data", "local-setting",
                criticalMissingTransport, point -> {});
        Files.delete(criticalMissing.root().resolve("lib/library.jar"));
        try (PreparedAdoption prepared = criticalMissing.service().prepare(
                criticalMissing.record(),
                org.megamek.launcher.release.OfficialRepository.MEGAMEK, "v1.2.3",
                FollowChannel.MILESTONE, new PrintStream(new ByteArrayOutputStream()),
                OperationContext.none(OperationType.ADOPT_EXISTING))) {
            assertFalse(prepared.report().eligible(),
                    "a missing dependency must block adoption");
        }
        assertNoProvenance(criticalMissing);

        QueueTransport missingTransport = transport(archive);
        Fixture missing = fixture(officialJar, "official-data", "local-setting",
                missingTransport, point -> {});
        Files.delete(missing.root().resolve("data/base.txt"));
        try (PreparedAdoption prepared = missing.service().prepare(missing.record(),
                org.megamek.launcher.release.OfficialRepository.MEGAMEK, "v1.2.3",
                FollowChannel.MILESTONE, new PrintStream(new ByteArrayOutputStream()),
                OperationContext.none(OperationType.ADOPT_EXISTING))) {
            assertFalse(prepared.report().eligible(),
                    "intentional missing managed files are blocked by explicit policy");
        }
        assertNoProvenance(missing);
    }

    @Test
    void driftCrossFacadeAndInjectedPublicationFailureCannotAuthorizeUpdates()
            throws Exception {
        byte[] jar = jar(null);
        byte[] archive = archive(jar, "official-data", "official-setting");

        QueueTransport driftTransport = transport(archive);
        Fixture drift = fixture(jar, "official-data", "local-setting",
                driftTransport, point -> {});
        PreparedAdoption changed = drift.service().prepare(drift.record(),
                org.megamek.launcher.release.OfficialRepository.MEGAMEK, "v1.2.3",
                FollowChannel.MILESTONE, new PrintStream(new ByteArrayOutputStream()),
                OperationContext.none(OperationType.ADOPT_EXISTING));
        Files.writeString(drift.root().resolve("custom-after-verify.txt"), "changed");
        assertThrows(IOException.class, () -> drift.service().commit(changed));
        assertNoProvenance(drift);

        QueueTransport ownerTransport = transport(archive);
        Fixture owner = fixture(jar, "official-data", "local-setting",
                ownerTransport, point -> {});
        PreparedAdoption owned = owner.service().prepare(owner.record(),
                org.megamek.launcher.release.OfficialRepository.MEGAMEK, "v1.2.3",
                FollowChannel.MILESTONE, new PrintStream(new ByteArrayOutputStream()),
                OperationContext.none(OperationType.ADOPT_EXISTING));
        assertThrows(IOException.class, () -> owner.service().prepare(owner.record(),
                org.megamek.launcher.release.OfficialRepository.MEGAMEK, "v1.2.3",
                FollowChannel.MILESTONE, new PrintStream(new ByteArrayOutputStream()),
                OperationContext.none(OperationType.ADOPT_EXISTING)),
                "one service must not prepare two attempts for the same record");
        ImportedCopyAdoptionService other = new ImportedCopyAdoptionService(owner.registry(),
                ownerTransport, new RootCoordinator(temp.resolve("other-coordination")));
        assertThrows(IOException.class, () -> other.commit(owned));
        owned.close();
        assertNoProvenance(owner);

        for (String failurePoint : List.of(
                "AFTER_RECEIPT", "AFTER_CURRENT", "AFTER_MARKER")) {
            QueueTransport failedTransport = transport(archive);
            Fixture failed = fixture(jar, "official-data", "local-setting",
                    failedTransport, point -> {
                        if (failurePoint.equals(point)) throw new IOException("injected");
                    });
            PreparedAdoption publication = failed.service().prepare(failed.record(),
                    org.megamek.launcher.release.OfficialRepository.MEGAMEK, "v1.2.3",
                    FollowChannel.MILESTONE, new PrintStream(new ByteArrayOutputStream()),
                    OperationContext.none(OperationType.ADOPT_EXISTING));
            assertThrows(IOException.class, () -> failed.service().commit(publication),
                    failurePoint);
            assertNoProvenance(failed);
        }
    }

    @Test
    void digestFailureCleansOwnedWorkspaceAndWritesNoMetadataOrRootFiles()
            throws Exception {
        byte[] jar = jar(null);
        byte[] archive = archive(jar, "official-data", "official-setting");
        String badMetadata = releaseJson(archive, "0".repeat(64));
        QueueTransport transport = new QueueTransport(response(badMetadata),
                response(badMetadata), binary(archive));
        Fixture fixture = fixture(jar, "official-data", "local-setting",
                transport, point -> {});
        Map<String, Evidence> before = rootEvidence(fixture.root());

        assertThrows(IOException.class, () -> fixture.service().prepare(fixture.record(),
                org.megamek.launcher.release.OfficialRepository.MEGAMEK, "v1.2.3",
                FollowChannel.MILESTONE, new PrintStream(new ByteArrayOutputStream()),
                OperationContext.none(OperationType.ADOPT_EXISTING)));

        assertEquals(before, rootEvidence(fixture.root()));
        assertNoProvenance(fixture);
        try (var children = Files.list(temp)) {
            assertFalse(children.anyMatch(path ->
                    path.getFileName().toString().startsWith(".adoption-")));
        }
    }

    @Test
    void pendingRecoveryAndCancellationBlockBeforeNetwork() throws Exception {
        byte[] jar = jar(null);
        QueueTransport pendingTransport = new QueueTransport();
        Fixture pending = fixture(jar, "official-data", "local-setting",
                pendingTransport, point -> {});
        Files.createDirectory(pending.root().resolve(RootCoordinator.UPDATE_NAMESPACE));
        assertThrows(IOException.class, () -> pending.service().prepare(pending.record(),
                org.megamek.launcher.release.OfficialRepository.MEGAMEK, "v1.2.3",
                FollowChannel.MILESTONE, new PrintStream(new ByteArrayOutputStream()),
                OperationContext.none(OperationType.ADOPT_EXISTING)));
        assertTrue(pendingTransport.requests.isEmpty());

        QueueTransport cancelledTransport = new QueueTransport();
        Fixture cancelled = fixture(jar, "official-data", "local-setting",
                cancelledTransport, point -> {});
        OperationContext context = new OperationContext(OperationType.ADOPT_EXISTING);
        assertTrue(context.requestCancellation().accepted());
        assertThrows(OperationCancelledException.class,
                () -> cancelled.service().prepare(cancelled.record(),
                        org.megamek.launcher.release.OfficialRepository.MEGAMEK, "v1.2.3",
                        FollowChannel.MILESTONE, new PrintStream(new ByteArrayOutputStream()),
                        context));
        assertTrue(cancelledTransport.requests.isEmpty());
        assertNoProvenance(cancelled);
    }

    @Test
    void officialPackageWithDifferentDetectedVersionIsIneligible() throws Exception {
        byte[] localJar = jar(null);
        byte[] wrongJar = jarVersion(9, 9, 9, null);
        byte[] archive = archive(wrongJar, "official-data", "official-setting");
        QueueTransport transport = transport(archive);
        Fixture fixture = fixture(localJar, "official-data", "local-setting",
                transport, point -> {});
        try (PreparedAdoption prepared = fixture.service().prepare(fixture.record(),
                org.megamek.launcher.release.OfficialRepository.MEGAMEK, "v1.2.3",
                FollowChannel.MILESTONE, new PrintStream(new ByteArrayOutputStream()),
                OperationContext.none(OperationType.ADOPT_EXISTING))) {
            assertFalse(prepared.report().eligible());
        }
        assertNoProvenance(fixture);
    }

    private Fixture fixture(byte[] localJar, String localData, String setting,
                            QueueTransport transport,
                            ImportedCopyAdoptionService.FailureHook failureHook)
            throws Exception {
        Path root = Files.createDirectory(temp.resolve("copy-" + System.nanoTime()));
        Files.createDirectories(root.resolve("data"));
        Files.createDirectories(root.resolve("mmconf"));
        Files.createDirectories(root.resolve("lib"));
        Files.write(root.resolve("MegaMek.jar"), localJar);
        Files.write(root.resolve("lib/library.jar"), DEPENDENCY);
        Files.writeString(root.resolve("data/base.txt"), localData);
        Files.writeString(root.resolve("mmconf/user.cfg"), setting);
        Path registry = temp.resolve("registry-" + System.nanoTime() + ".json");
        RegistryStore registries = new RegistryStore();
        InstallationRecord record = registries.register(registry, "Imported", root, null);
        ReceiptStore receipts = new ReceiptStore();
        ImportedCopyAdoptionService service = new ImportedCopyAdoptionService(
                registry, transport, registries, receipts, new ChannelPreferenceStore(),
                new InstallationInspector(),
                new RootCoordinator(temp.resolve("coord-" + System.nanoTime())), failureHook);
        return new Fixture(root, registry, record, registries, receipts, service);
    }

    private static QueueTransport transport(byte[] archive) throws Exception {
        String metadata = releaseJson(archive);
        return new QueueTransport(response(metadata), response(metadata),
                binary(archive), response(metadata));
    }

    private static void assertNoProvenance(Fixture fixture) throws Exception {
        RegistryData data = fixture.registries().read(fixture.registry());
        assertEquals(ImportedCopyAdoptionService.Availability.TRUE_IMPORTED,
                fixture.service().availability(data, fixture.record(), false));
        assertFalse(Files.exists(fixture.receipts().receiptPath(
                fixture.registry(), fixture.record().id())));
        assertFalse(Files.exists(new ChannelPreferenceStore().path(
                fixture.registry(), fixture.record().id())));
    }

    private static Map<String, Evidence> rootEvidence(Path root) throws Exception {
        Map<String, Evidence> result = new LinkedHashMap<>();
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted().toList()) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                result.put(relative, new Evidence(Files.isDirectory(path),
                        Files.getLastModifiedTime(path).toMillis(),
                        Files.isRegularFile(path)
                                ? HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                                .digest(Files.readAllBytes(path))) : null));
            }
        }
        return result;
    }

    private static byte[] jar(String extra) throws IOException {
        return jarVersion(1, 2, 3, extra);
    }

    private static byte[] jarVersion(int major, int minor, int patch, String extra)
            throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "megamek.MegaMek");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream output = new JarOutputStream(bytes, manifest)) {
            output.putNextEntry(new JarEntry("megamek/Version.properties"));
            output.write(("major=" + major + "\nminor=" + minor + "\npatch=" + patch + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            if (extra != null) {
                output.putNextEntry(new JarEntry("local.txt"));
                output.write(extra.getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] archive(byte[] jar, String data, String setting) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(bytes);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            add(tar, "MegaMek-1.2.3/", null);
            add(tar, "MegaMek-1.2.3/data/", null);
            add(tar, "MegaMek-1.2.3/mmconf/", null);
            add(tar, "MegaMek-1.2.3/lib/", null);
            add(tar, "MegaMek-1.2.3/MegaMek.jar", jar);
            add(tar, "MegaMek-1.2.3/lib/library.jar", DEPENDENCY);
            add(tar, "MegaMek-1.2.3/data/base.txt",
                    data.getBytes(StandardCharsets.UTF_8));
            add(tar, "MegaMek-1.2.3/mmconf/user.cfg",
                    setting.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    private static void add(TarArchiveOutputStream tar, String name, byte[] content)
            throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(content == null ? 0 : content.length);
        if (content == null) entry.setMode(0755);
        tar.putArchiveEntry(entry);
        if (content != null) tar.write(content);
        tar.closeArchiveEntry();
    }

    private static String releaseJson(byte[] archive) throws Exception {
        String digest = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(archive));
        return releaseJson(archive, digest);
    }

    private static String releaseJson(byte[] archive, String digest) {
        return """
                {"tag_name":"v1.2.3","name":"Version 1.2.3","draft":false,"prerelease":false,
                "html_url":"https://github.com/MegaMek/megamek/releases/tag/v1.2.3",
                "assets":[{"name":"MegaMek-1.2.3.tar.gz","size":%d,
                "digest":"sha256:%s",
                "browser_download_url":"https://github.com/MegaMek/megamek/releases/download/v1.2.3/MegaMek-1.2.3.tar.gz"}]}
                """.formatted(archive.length, digest);
    }

    private static Supplier<ReleaseTransport.Response> response(String body) {
        return () -> new ReleaseTransport.Response(200, Map.of(),
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static Supplier<ReleaseTransport.Response> binary(byte[] body) {
        return () -> new ReleaseTransport.Response(200, Map.of(),
                new ByteArrayInputStream(body));
    }

    private static final class QueueTransport implements ReleaseTransport {
        private final ArrayDeque<Supplier<Response>> responses;
        private final List<URI> requests = new ArrayList<>();
        private int packageRequests;

        @SafeVarargs
        private QueueTransport(Supplier<Response>... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public Response get(URI uri, String accept) throws IOException {
            requests.add(uri);
            if ("application/octet-stream".equals(accept)) packageRequests++;
            Supplier<Response> response = responses.poll();
            if (response == null) throw new IOException("unexpected request: " + uri);
            return response.get();
        }
    }

    private record Fixture(Path root, Path registry, InstallationRecord record,
                           RegistryStore registries, ReceiptStore receipts,
                           ImportedCopyAdoptionService service) {
    }

    private record Evidence(boolean directory, long modifiedMillis, String sha256) {
    }
}
