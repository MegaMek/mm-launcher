package org.megamek.launcher.update;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.megamek.launcher.channel.ChannelPreferenceStore;
import org.megamek.launcher.channel.FollowChannel;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.release.FreshInstaller;
import org.megamek.launcher.release.OfficialRepository;
import org.megamek.launcher.release.ReleaseTransport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreviewFailureMaterialTest {
    @TempDir Path temp;

    @Test
    void repeatedBadTargetShaFailuresPreserveEveryPersistentByteAndMtime() throws Exception {
        Fixture fixture = fixture("bad-sha");
        byte[] target = megamekArchive("MegaMek-new");
        Snapshot before = snapshot(fixture.area);
        for (int attempt = 0; attempt < 2; attempt++) {
            QueueTransport network = transportFor("v-new", target, "0".repeat(64));
            IOException failure = assertThrows(IOException.class, () ->
                    preview(fixture, network));
            assertTrue(failure.getMessage().contains("SHA-256 mismatch"), failure.getMessage());
            assertEquals(2, network.uris.size(), "failure must reach hash verification");
            assertSnapshot(before, fixture.area);
            assertNoScratch(fixture);
        }
    }

    @Test
    void repeatedWrongExtractedProductFailuresPreserveEveryPersistentByteAndMtime()
            throws Exception {
        Fixture fixture = fixture("wrong-product");
        byte[] target = archive("MegaMek-new", Map.of(
                "MekHQ.jar", jar("mekhq.MekHQ"),
                "data/file.txt", bytes("target"),
                "lib/file.txt", bytes("target"),
                "mmconf/clientsettings.xml", bytes("target preferences")));
        Snapshot before = snapshot(fixture.area);
        for (int attempt = 0; attempt < 2; attempt++) {
            QueueTransport network = transportFor("v-new", target, sha(target));
            IOException failure = assertThrows(IOException.class, () ->
                    preview(fixture, network));
            assertTrue(failure.getMessage().contains(
                    "target package does not contain receipt application megamek"),
                    failure.getMessage());
            assertEquals(2, network.uris.size(), "failure must occur after verified extraction");
            assertSnapshot(before, fixture.area);
            assertNoScratch(fixture);
        }
    }

    @Test
    void capturedSourceRemovalAndReregistrationAreRejectedBeforeNetwork() throws Exception {
        for (boolean reregister : List.of(false, true)) {
            Fixture fixture = fixture(reregister ? "reregister" : "remove");
            RegistryStore store = new RegistryStore();
            store.remove(fixture.registry, fixture.record.id());
            if (reregister) {
                var replacement = store.register(fixture.registry, "Replacement", fixture.root, null);
                assertFalse(replacement.id().equals(fixture.record.id()));
                assertFalse(replacement.updateEligible());
            }
            Snapshot before = snapshot(fixture.area);
            QueueTransport network = new QueueTransport();

            IOException failure = assertThrows(IOException.class, () ->
                    new UpdatePreviewService(network).preview(fixture.registry, fixture.record,
                            fixture.receipt, "v-new", quiet()));

            assertTrue(failure.getMessage().contains("unknown installation id"),
                    failure.getMessage());
            assertTrue(network.uris.isEmpty());
            assertSnapshot(before, fixture.area);
            assertNoScratch(fixture);
        }
    }

    private void preview(Fixture fixture, QueueTransport network) throws Exception {
        new UpdatePreviewService(network).preview(
                fixture.registry, fixture.record.id(), "v-new", quiet());
    }

    private Fixture fixture(String name) throws Exception {
        Path area = Files.createDirectory(temp.resolve(name));
        Path registry = area.resolve("registry.json");
        Path root = area.resolve("installed");
        byte[] baseline = megamekArchive("MegaMek-old");
        var result = new FreshInstaller(transportFor("v-old", baseline, sha(baseline))).install(
                OfficialRepository.MEGAMEK, "v-old", root, registry, "Official", quiet());
        new ChannelPreferenceStore().initializeManaged(registry, result.record(),
                result.ownershipReceipt(), FollowChannel.MILESTONE, false);
        assertFalse(result.record().updateEligible());
        return new Fixture(area, root, registry, result.record(), result.ownershipReceipt());
    }

    private static byte[] megamekArchive(String folder) throws Exception {
        return archive(folder, Map.of(
                "MegaMek.jar", jar("megamek.MegaMek"),
                "data/file.txt", bytes("official"),
                "lib/file.txt", bytes("official"),
                "mmconf/clientsettings.xml", bytes("official preferences")));
    }

    private static QueueTransport transportFor(String tag, byte[] archive, String digest) {
        String json = """
                {"tag_name":"%s","name":"%s","draft":false,"prerelease":false,
                "html_url":"https://github.com/MegaMek/megamek/releases/tag/%s",
                "assets":[{"name":"MegaMek-%s.tar.gz","size":%d,"digest":"sha256:%s",
                "browser_download_url":"https://github.com/MegaMek/megamek/releases/download/%s/MegaMek-%s.tar.gz"}]}
                """.formatted(tag, tag, tag, tag, archive.length, digest, tag, tag);
        return new QueueTransport(response(bytes(json)), response(archive));
    }

    private static byte[] archive(String root, Map<String, byte[]> files) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(output);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            TarArchiveEntry rootEntry = new TarArchiveEntry(root + "/");
            tar.putArchiveEntry(rootEntry);
            tar.closeArchiveEntry();
            for (var item : files.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()) {
                TarArchiveEntry entry = new TarArchiveEntry(root + "/" + item.getKey());
                entry.setSize(item.getValue().length);
                tar.putArchiveEntry(entry);
                tar.write(item.getValue());
                tar.closeArchiveEntry();
            }
        }
        return output.toByteArray();
    }

    private static byte[] jar(String mainClass) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(output, manifest)) {
            jar.putNextEntry(new JarEntry("megamek/Version.properties"));
            jar.write(bytes("major=1\nminor=2\npatch=3\n"));
            jar.closeEntry();
        }
        return output.toByteArray();
    }

    private static PrintStream quiet() {
        return new PrintStream(new ByteArrayOutputStream());
    }
    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
    private static String sha(byte[] value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    }
    private static Supplier<ReleaseTransport.Response> response(byte[] body) {
        return () -> new ReleaseTransport.Response(200,
                Map.of("content-length", List.of(Integer.toString(body.length))),
                new ByteArrayInputStream(body));
    }

    private static Snapshot snapshot(Path root) throws Exception {
        Map<String, Stamp> entries = new LinkedHashMap<>();
        try (var walk = Files.walk(root)) {
            for (Path path : walk.filter(Files::isRegularFile).sorted().toList()) {
                String relative = root.relativize(path).toString();
                entries.put(relative, new Stamp(
                        Files.readAllBytes(path), Files.getLastModifiedTime(path)));
            }
        }
        return new Snapshot(entries);
    }

    private static void assertSnapshot(Snapshot expected, Path root) throws Exception {
        Snapshot actual = snapshot(root);
        assertEquals(expected.entries.keySet(), actual.entries.keySet());
        expected.entries.forEach((path, stamp) -> {
            Stamp found = actual.entries.get(path);
            assertEquals(stamp.modified, found.modified, path);
            assertArrayEquals(stamp.bytes, found.bytes, path);
        });
    }

    private static void assertNoScratch(Fixture fixture) throws IOException {
        try (var children = Files.list(fixture.registry.resolveSibling("registry.json.metadata"))) {
            assertFalse(children.anyMatch(path ->
                    path.getFileName().toString().startsWith(".preview-")));
        }
    }

    private record Fixture(Path area, Path root, Path registry,
                           org.megamek.launcher.registry.InstallationRecord record,
                           OwnershipReceipt receipt) {}
    private record Snapshot(Map<String, Stamp> entries) {}
    private record Stamp(byte[] bytes, FileTime modified) {}

    private static final class QueueTransport implements ReleaseTransport {
        private final ArrayDeque<Supplier<Response>> responses;
        private final java.util.ArrayList<URI> uris = new java.util.ArrayList<>();
        @SafeVarargs QueueTransport(Supplier<Response>... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }
        @Override public Response get(URI uri, String accept) throws IOException {
            uris.add(uri);
            Supplier<Response> response = responses.poll();
            if (response == null) throw new IOException("unexpected network request");
            return response.get();
        }
    }
}
