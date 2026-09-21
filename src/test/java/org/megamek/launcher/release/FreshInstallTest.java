package org.megamek.launcher.release;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.megamek.launcher.Main;
import org.megamek.launcher.channel.ChannelPreferenceStore;
import org.megamek.launcher.channel.FollowChannel;
import org.megamek.launcher.registry.RegistryData;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.update.ReceiptStore;

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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FreshInstallTest {
    @TempDir Path temp;

    @Test
    void cliListsExplicitPageAndCompletesDownloadExtractInspectRegister() throws Exception {
        byte[] archive = suiteArchive("MegaMek-1.2.3");
        String metadata = releaseJson(archive, sha(archive));
        QueueTransport listing = new QueueTransport(response(200, "[" + metadata + "]"));
        ByteArrayOutputStream listed = new ByteArrayOutputStream();
        assertEquals(0, Main.run(new String[]{"releases", "--application", "megamek",
                        "--page", "2", "--per-page", "1"}, new PrintStream(listed),
                new PrintStream(new ByteArrayOutputStream()), listing));
        assertTrue(listed.toString(StandardCharsets.UTF_8).contains("tag=v1.2.3"));
        assertTrue(listed.toString(StandardCharsets.UTF_8).contains("MORE-MAY-EXIST"));
        assertTrue(listing.uris.getFirst().getQuery().contains("page=2"));

        Path registry = temp.resolve("registry.json");
        Path destination = temp.resolve("installed");
        configureJava(registry);
        QueueTransport installing = new QueueTransport(response(200, metadata),
                binary(200, Map.of(), archive));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int result = Main.run(new String[]{"install-release", "--application", "megamek",
                        "--tag", "v1.2.3", "--destination", destination.toString(),
                        "--registry", registry.toString(), "--name", "Downloaded",
                        "--channel", "milestone"},
                new PrintStream(output), new PrintStream(new ByteArrayOutputStream()), installing);

        assertEquals(0, result);
        assertTrue(Files.isRegularFile(destination.resolve("MegaMek.jar")));
        RegistryData data = new RegistryStore().read(registry);
        assertEquals(1, data.installations().size());
        assertEquals("Downloaded", data.installations().getFirst().name());
        assertFalse(data.installations().getFirst().updateEligible());
        assertTrue(data.installations().getFirst().products().stream()
                .anyMatch(product -> product.key().equals("megamek")));
        var preference = new ChannelPreferenceStore()
                .read(registry, data, data.installations().getFirst()).preference();
        assertEquals(FollowChannel.MILESTONE, preference.channel());
        assertTrue(preference.checkOnOpen());
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("checkOnOpen=true"));
        assertTrue(output.toString(StandardCharsets.UTF_8).contains("NOT LAUNCHED"));
    }

    @Test
    void cliRequiresFixedChannelBeforeAnyInstallRequestOrWrite() {
        Path registry = temp.resolve("missing-channel-registry.json");
        Path destination = temp.resolve("missing-channel-install");
        QueueTransport network = new QueueTransport();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();

        int result = Main.run(new String[]{"install-release", "--application", "megamek",
                        "--tag", "v1.2.3", "--destination", destination.toString(),
                        "--registry", registry.toString(), "--name", "Downloaded"},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors), network);

        assertEquals(2, result);
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("--channel"));
        assertTrue(network.uris.isEmpty());
        assertFalse(Files.exists(registry));
        assertFalse(Files.exists(destination));
    }

    @Test
    void removedCheckOnOpenCliOptionIsRejectedBeforeNetworkOrWrite() {
        Path registry = temp.resolve("removed-option-registry.json");
        Path destination = temp.resolve("removed-option-install");
        QueueTransport network = new QueueTransport();
        ByteArrayOutputStream errors = new ByteArrayOutputStream();

        int result = Main.run(new String[]{"install-release", "--application", "megamek",
                        "--tag", "v1.2.3", "--destination", destination.toString(),
                        "--registry", registry.toString(), "--name", "Downloaded",
                        "--channel", "milestone", "--check-on-open", "false"},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors), network);

        assertEquals(2, result);
        assertTrue(errors.toString(StandardCharsets.UTF_8)
                .contains("unknown option: --check-on-open"));
        assertTrue(network.uris.isEmpty());
        assertFalse(Files.exists(registry));
        assertFalse(Files.exists(destination));
    }

    @Test
    void rejectsExistingDestinationHashSizeTruncationAndUnsafeRedirect() throws Exception {
        byte[] archive = suiteArchive("MegaMek-1.2.3");
        Path registry = temp.resolve("registry.json");
        Path existing = Files.createDirectory(temp.resolve("existing"));
        QueueTransport unused = new QueueTransport();
        assertEquals(2, runInstall(unused, registry, existing, new ByteArrayOutputStream()));
        assertTrue(unused.uris.isEmpty());

        ByteArrayOutputStream hashError = new ByteArrayOutputStream();
        QueueTransport hash = new QueueTransport(
                response(200, releaseJson(archive, "0".repeat(64))),
                binary(200, Map.of(), archive));
        assertEquals(2, runInstall(hash, registry, temp.resolve("bad-hash"), hashError));
        assertTrue(hashError.toString(StandardCharsets.UTF_8).contains("SHA-256 mismatch"));

        byte[] truncated = java.util.Arrays.copyOf(archive, archive.length - 2);
        ByteArrayOutputStream shortError = new ByteArrayOutputStream();
        QueueTransport shortDownload = new QueueTransport(
                response(200, releaseJson(archive, sha(archive))),
                binary(200, Map.of(), truncated));
        assertEquals(2, runInstall(shortDownload, registry, temp.resolve("short"), shortError));
        assertTrue(shortError.toString(StandardCharsets.UTF_8).contains("truncated download"));

        QueueTransport redirect = new QueueTransport(
                response(200, releaseJson(archive, sha(archive))),
                binary(302, Map.of("location", List.of("https://example.com/stolen")), new byte[0]));
        ByteArrayOutputStream redirectError = new ByteArrayOutputStream();
        assertEquals(2, runInstall(redirect, registry, temp.resolve("redirect"), redirectError));
        assertTrue(redirectError.toString(StandardCharsets.UTF_8).contains("unsafe release download host"));
    }

    @Test
    void officialAssetWithoutPublishedDigestDownloadsOnceAndPersistsComputedIdentity()
            throws Exception {
        byte[] archive = suiteArchive("MegaMek-1.2.3");
        String metadata = releaseJson(archive, sha(archive))
                .replace("\"digest\":\"sha256:" + sha(archive) + "\",", "");
        Path registry = temp.resolve("computed-registry.json");
        Path destination = temp.resolve("computed-install");
        QueueTransport transport = new QueueTransport(
                response(200, metadata),
                binary(200, Map.of("content-length",
                        List.of(Integer.toString(archive.length))), archive));

        assertEquals(0, runInstall(transport, registry, destination,
                new ByteArrayOutputStream()));
        assertEquals(2, transport.uris.size(), "one metadata request and one binary body");
        RegistryData data = new RegistryStore().read(registry);
        var receipt = new ReceiptStore().read(
                registry, data, data.installations().getFirst());
        assertEquals(sha(archive), receipt.assetSha256());
        assertTrue(Files.isRegularFile(destination.resolve("MegaMek.jar")));
    }

    @Test
    void absentDigestStillRejectsContentLengthAndBodySizeMismatchWithoutPublication()
            throws Exception {
        byte[] archive = suiteArchive("MegaMek-1.2.3");
        String metadata = releaseJson(archive, sha(archive))
                .replace("\"digest\":\"sha256:" + sha(archive) + "\",", "");

        Path lengthRegistry = temp.resolve("length-registry.json");
        Path lengthDestination = temp.resolve("length-install");
        QueueTransport lengthMismatch = new QueueTransport(
                response(200, metadata),
                binary(200, Map.of("content-length",
                        List.of(Integer.toString(archive.length + 1))), archive));
        assertEquals(2, runInstall(lengthMismatch, lengthRegistry, lengthDestination,
                new ByteArrayOutputStream()));
        assertFalse(Files.exists(lengthDestination));

        Path longRegistry = temp.resolve("long-registry.json");
        Path longDestination = temp.resolve("long-install");
        byte[] longer = java.util.Arrays.copyOf(archive, archive.length + 1);
        QueueTransport longBody = new QueueTransport(response(200, metadata),
                binary(200, Map.of(), longer));
        assertEquals(2, runInstall(longBody, longRegistry, longDestination,
                new ByteArrayOutputStream()));
        assertFalse(Files.exists(longDestination));
    }

    @Test
    void malformedPublishedDigestFailsWithoutDowngradingToAbsent() throws Exception {
        byte[] archive = suiteArchive("MegaMek-1.2.3");
        String malformed = releaseJson(archive, sha(archive))
                .replace("sha256:" + sha(archive), "sha256:not-hex");
        Path registry = temp.resolve("malformed-registry.json");
        Path destination = temp.resolve("malformed-install");
        QueueTransport transport = new QueueTransport(response(200, malformed));
        ByteArrayOutputStream errors = new ByteArrayOutputStream();

        assertEquals(2, runInstall(transport, registry, destination, errors));
        assertEquals(1, transport.uris.size(), "malformed metadata prevents binary transfer");
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains(
                "Published SHA-256 checksum is invalid"));
        assertFalse(Files.exists(destination));
    }

    @Test
    void archiveRejectsTraversalAliasesLinksBoundsAndTruncation() throws Exception {
        SafeTarExtractor extractor = new SafeTarExtractor();
        assertThrows(IOException.class, () -> extractor.extract(
                Files.write(temp.resolve("traversal.tar.gz"),
                        archiveWith(new Entry("../escape", new byte[]{1}, false, false))),
                temp.resolve("out-traversal")));
        assertThrows(IOException.class, () -> extractor.extract(
                Files.write(temp.resolve("alias.tar.gz"), archiveWith(
                        new Entry("Root", new byte[0], true, false),
                        new Entry("Root/File", new byte[]{1}, false, false),
                        new Entry("root/file", new byte[]{2}, false, false))),
                temp.resolve("out-alias")));
        assertThrows(IOException.class, () -> extractor.extract(
                Files.write(temp.resolve("link.tar.gz"), archiveWith(
                        new Entry("Root", new byte[0], true, false),
                        new Entry("Root/link", new byte[0], false, true))),
                temp.resolve("out-link")));
        assertThrows(IOException.class, () -> extractor.extract(
                Files.write(temp.resolve("broken.tar.gz"), new byte[]{31, -117, 8, 0}),
                temp.resolve("out-broken")));
        assertThrows(IOException.class, () -> extractor.extract(
                Files.write(temp.resolve("oversized.tar.gz"), oversizedHeaderArchive()),
                temp.resolve("out-oversized")));
    }

    @Test
    void preservesExistingDefaultRejectsDuplicateNameAndSalvagesFailedRegistration()
            throws Exception {
        byte[] archive = suiteArchive("MegaMek-1.2.3");
        Path archiveFile = Files.write(temp.resolve("existing.tar.gz"), archive);
        Path existing = new SafeTarExtractor().extract(archiveFile, temp.resolve("existing-expanded"));
        Path registry = temp.resolve("registry.json");
        RegistryStore store = new RegistryStore();
        String firstId = store.register(registry, "Existing", existing, null).id();
        configureJava(registry);

        QueueTransport duplicateUnused = new QueueTransport();
        ByteArrayOutputStream duplicateError = new ByteArrayOutputStream();
        int duplicate = Main.run(new String[]{"install-release", "--application", "megamek",
                        "--tag", "v1.2.3", "--destination", temp.resolve("duplicate").toString(),
                        "--registry", registry.toString(), "--name", "Existing",
                        "--channel", "milestone"},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(duplicateError),
                duplicateUnused);
        assertEquals(2, duplicate);
        assertTrue(duplicateUnused.uris.isEmpty());
        assertTrue(duplicateError.toString(StandardCharsets.UTF_8).contains("already registered"));

        Path second = temp.resolve("second");
        QueueTransport normal = new QueueTransport(
                response(200, releaseJson(archive, sha(archive))),
                binary(200, Map.of(), archive));
        assertEquals(0, runInstall(normal, registry, second, "Downloaded",
                new ByteArrayOutputStream()));
        RegistryData afterSecondInstall = store.read(registry);
        assertEquals(firstId, afterSecondInstall.defaultInstallationId());
        assertEquals(2, afterSecondInstall.installations().size());
        assertTrue(afterSecondInstall.installations().stream()
                .anyMatch(item -> item.id().equals(firstId) && item.name().equals("Existing")));
        assertTrue(afterSecondInstall.installations().stream()
                .anyMatch(item -> item.name().equals("Downloaded")
                        && Path.of(item.canonicalRoot()).equals(second.toAbsolutePath())));

        Path salvage = temp.resolve("salvage");
        AtomicBoolean downloadCallbackInvoked = new AtomicBoolean();
        Supplier<ReleaseTransport.Response> corruptDuringDownload = () -> {
            downloadCallbackInvoked.set(true);
            try {
                Files.writeString(registry, "{\"schemaVersion\":99}");
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            return new ReleaseTransport.Response(200, Map.of(), new ByteArrayInputStream(archive));
        };
        QueueTransport sabotage = new QueueTransport(
                response(200, releaseJson(archive, sha(archive))), corruptDuringDownload);
        ByteArrayOutputStream salvageError = new ByteArrayOutputStream();
        assertEquals(2, runInstall(sabotage, registry, salvage, "Salvaged", salvageError));
        assertTrue(downloadCallbackInvoked.get());
        assertEquals(2, sabotage.uris.size());
        assertTrue(Files.isRegularFile(salvage.resolve("MegaMek.jar")));
        assertTrue(Files.isDirectory(salvage.resolve("data")));
        assertTrue(Files.isDirectory(salvage.resolve("mmconf")));
        assertTrue(Files.isDirectory(salvage.resolve("lib")));
        String salvageErrorText = salvageError.toString(StandardCharsets.UTF_8);
        assertTrue(salvageErrorText.startsWith("ERROR: installation is valid at "
                + salvage.toAbsolutePath() + " but was NOT REGISTERED:"));
        assertEquals("{\"schemaVersion\":99}", Files.readString(registry));
        try (var children = Files.list(temp)) {
            assertFalse(children.anyMatch(path ->
                    path.getFileName().toString().startsWith(".mm-launcher-install-")));
        }
    }

    @Test
    void malformedMetadataDraftAmbiguityAndNetworkErrorsAreExplicit() throws Exception {
        ReleaseCatalog catalog = new ReleaseCatalog(new QueueTransport(response(200,
                "{\"tag_name\":\"v1\",\"tag_name\":\"v2\"}")));
        assertThrows(IOException.class, () -> catalog.exact(OfficialRepository.MEGAMEK, "v1"));

        byte[] archive = suiteArchive("MegaMek-1.2.3");
        String ambiguous = releaseJson(archive, sha(archive)).replace("]}",
                ",{\"name\":\"MegaMek-other.tar.gz\",\"size\":1,\"digest\":\"sha256:"
                        + "0".repeat(64) + "\",\"browser_download_url\":"
                        + "\"https://github.com/MegaMek/megamek/releases/download/v1.2.3/"
                        + "MegaMek-other.tar.gz\"}]}");
        ReleaseCatalog releases = new ReleaseCatalog(new QueueTransport(response(200, ambiguous)));
        var release = releases.exact(OfficialRepository.MEGAMEK, "v1.2.3");
        assertThrows(IOException.class,
                () -> releases.selectInstallAsset(OfficialRepository.MEGAMEK, release));

        ReleaseTransport failing = (uri, accept) -> { throw new IOException("simulated network down"); };
        IOException error = assertThrows(IOException.class,
                () -> new ReleaseCatalog(failing).list(OfficialRepository.MEGAMEK, 1, 10));
        assertTrue(error.getMessage().contains("simulated network down"));
    }

    private int runInstall(ReleaseTransport transport, Path registry, Path destination,
                           ByteArrayOutputStream errors) {
        return runInstall(transport, registry, destination, "Downloaded", errors);
    }

    private int runInstall(ReleaseTransport transport, Path registry, Path destination,
                           String name, ByteArrayOutputStream errors) {
        configureJava(registry);
        return Main.run(new String[]{"install-release", "--application", "megamek",
                        "--tag", "v1.2.3", "--destination", destination.toString(),
                        "--registry", registry.toString(), "--name", name,
                        "--channel", "milestone"},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors), transport);
    }

    private static void configureJava(Path registry) {
        Path executable = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
                        .contains("win") ? "java.exe" : "java");
        int result = Main.run(new String[]{"java-select", "--registry", registry.toString(),
                        "--java", executable.toString()},
                new PrintStream(java.io.OutputStream.nullOutputStream()),
                new PrintStream(java.io.OutputStream.nullOutputStream()),
                (uri, accept) -> {
                    throw new AssertionError("Game Java selection must not use the network");
                });
        if (result != 0) throw new AssertionError("could not configure test Game Java");
    }

    private static String releaseJson(byte[] archive, String hash) {
        return """
                {"tag_name":"v1.2.3","name":"Version 1.2.3","draft":false,"prerelease":false,
                "html_url":"https://github.com/MegaMek/megamek/releases/tag/v1.2.3",
                "unknown_future_field":{"safe":true},
                "assets":[{"name":"MegaMek-1.2.3.tar.gz","size":%d,
                "digest":"sha256:%s",
                "browser_download_url":"https://github.com/MegaMek/megamek/releases/download/v1.2.3/MegaMek-1.2.3.tar.gz"}]}
                """.formatted(archive.length, hash);
    }

    private static byte[] suiteArchive(String root) throws Exception {
        byte[] jar = jar();
        return archiveWith(new Entry(root, new byte[0], true, false),
                new Entry(root + "/data", new byte[0], true, false),
                new Entry(root + "/mmconf", new byte[0], true, false),
                new Entry(root + "/lib", new byte[0], true, false),
                new Entry(root + "/MegaMek.jar", jar, false, false));
    }

    private static byte[] jar() throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "megamek.MegaMek");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes, manifest)) {
            jar.putNextEntry(new JarEntry("megamek/Version.properties"));
            jar.write("major=1\nminor=2\npatch=3\n".getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static byte[] archiveWith(Entry... entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(bytes);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            for (Entry item : entries) {
                TarArchiveEntry entry = item.link
                        ? new TarArchiveEntry(item.name, TarArchiveEntry.LF_SYMLINK)
                        : new TarArchiveEntry(item.directory ? item.name + "/" : item.name);
                if (item.link) {
                    entry.setLinkName("target");
                } else {
                    entry.setSize(item.directory ? 0 : item.content.length);
                    if (item.directory) entry.setMode(0755);
                }
                tar.putArchiveEntry(entry);
                if (!item.directory && !item.link) tar.write(item.content);
                tar.closeArchiveEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] oversizedHeaderArchive() throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry("Root/huge.bin");
        entry.setSize(SafeTarExtractor.MAX_FILE_SIZE + 1);
        byte[] header = new byte[512];
        entry.writeEntryHeader(header);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(bytes)) {
            gzip.write(header);
            gzip.write(new byte[1024]);
        }
        return bytes.toByteArray();
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static Supplier<ReleaseTransport.Response> response(int status, String body) {
        return binary(status, Map.of(), body.getBytes(StandardCharsets.UTF_8));
    }

    private static Supplier<ReleaseTransport.Response> binary(
            int status, Map<String, List<String>> headers, byte[] body) {
        return () -> new ReleaseTransport.Response(status, headers, new ByteArrayInputStream(body));
    }

    private static final class QueueTransport implements ReleaseTransport {
        private final ArrayDeque<Supplier<Response>> responses;
        private final List<URI> uris = new ArrayList<>();

        @SafeVarargs
        QueueTransport(Supplier<Response>... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public Response get(URI uri, String accept) throws IOException {
            uris.add(uri);
            Supplier<Response> response = responses.poll();
            if (response == null) throw new IOException("unexpected request: " + uri);
            return response.get();
        }
    }

    private record Entry(String name, byte[] content, boolean directory, boolean link) {}
}
