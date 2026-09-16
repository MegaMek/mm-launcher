package org.megamek.launcher.release;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.megamek.launcher.onboarding.InstallationInspector;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.update.OwnershipPolicy;
import org.megamek.launcher.update.OwnershipReceipt;
import org.megamek.launcher.update.ReceiptStore;
import org.megamek.launcher.update.UpdatePreviewService;

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
import java.util.HexFormat;
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

class FreshReceiptFailureMaterialTest {
    @TempDir Path temp;

    @Test
    void postRegistrationReceiptFailureRetainsTruthfulUsableRegistration() throws Exception {
        byte[] archive = archive("MegaMek-v1", Map.of(
                "MegaMek.jar", jar("megamek.MegaMek"),
                "data/base.txt", bytes("data"),
                "lib/base.txt", bytes("lib"),
                "mmconf/clientsettings.xml", bytes("preferences")));
        QueueTransport transport = transportFor("v1", archive);
        FailingReceiptStore receipts = new FailingReceiptStore();
        Path target = temp.resolve("installed");
        Path registry = temp.resolve("registry.json");
        FreshInstaller installer = new FreshInstaller(transport, new RegistryStore(),
                new InstallationInspector(), new SafeTarExtractor(), receipts);

        IOException failure = assertThrows(IOException.class, () -> installer.install(
                OfficialRepository.MEGAMEK, "v1", target, registry, "Official", quiet()));

        assertTrue(failure.getMessage().contains("valid and REGISTERED"), failure.getMessage());
        assertTrue(failure.getMessage().contains("PREVIEW UNAVAILABLE"), failure.getMessage());
        assertFalse(failure.getMessage().contains("NOT REGISTERED"), failure.getMessage());
        assertTrue(failure.getMessage().contains("Keep using it for launch/manage"));
        assertEquals(1, receipts.writes);
        assertEquals(2, transport.uris.size());
        var inspection = new InstallationInspector().inspect(target);
        assertTrue(inspection.products().stream().anyMatch(product -> product.key().equals("megamek")));
        var data = new RegistryStore().read(registry);
        assertEquals(1, data.installations().size());
        var record = data.installations().getFirst();
        assertEquals(target.toAbsolutePath().normalize().toString(), record.canonicalRoot());
        assertFalse(record.updateEligible(), "real update apply remains disabled");
        var eligibility = new UpdatePreviewService(uriFailing()).eligibility(registry, record.id());
        assertFalse(eligibility.available());
        assertTrue(eligibility.reason().contains("receipt is absent"));
        try (var children = Files.list(temp)) {
            assertFalse(children.anyMatch(path ->
                    path.getFileName().toString().startsWith(".mm-launcher-install-")));
        }
    }

    private static ReleaseTransport uriFailing() {
        return (uri, accept) -> {
            throw new IOException("eligibility must not use network");
        };
    }

    private static PrintStream quiet() {
        return new PrintStream(new ByteArrayOutputStream());
    }

    private static QueueTransport transportFor(String tag, byte[] archive) throws Exception {
        String json = """
                {"tag_name":"%s","name":"%s","draft":false,"prerelease":false,
                "html_url":"https://github.com/MegaMek/megamek/releases/tag/%s",
                "assets":[{"name":"MegaMek-%s.tar.gz","size":%d,"digest":"sha256:%s",
                "browser_download_url":"https://github.com/MegaMek/megamek/releases/download/%s/MegaMek-%s.tar.gz"}]}
                """.formatted(tag, tag, tag, tag, archive.length, sha(archive), tag, tag);
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

    private static final class FailingReceiptStore extends ReceiptStore {
        private int writes;
        @Override
        public OwnershipReceipt write(Path registry,
                                      org.megamek.launcher.registry.RegistryData data,
                                      org.megamek.launcher.registry.InstallationRecord record,
                                      OfficialRepository repository, String tag, String assetName,
                                      long assetSize, String assetDigest,
                                      OwnershipPolicy.Build build) throws IOException {
            writes++;
            throw new IOException("forced receipt disk failure");
        }
    }
}
