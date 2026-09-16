package org.megamek.launcher.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.megamek.launcher.registry.RegistryData;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.release.OfficialRepository;
import org.megamek.launcher.release.ReleaseTransport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReceiptValidationTest {
    @TempDir Path temp;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger sequence = new AtomicInteger();

    @TestFactory
    Stream<DynamicTest> strictJsonFailuresAreReadOnlyAndPrecedeNetwork() {
        Map<String, BytesMutation> cases = new LinkedHashMap<>();
        cases.put("unknown field", (bytes, tree) -> {
            ((ObjectNode) tree).put("futureField", true);
            return mapper.writeValueAsBytes(tree);
        });
        cases.put("missing field", (bytes, tree) -> {
            ((ObjectNode) tree).remove("repository");
            return mapper.writeValueAsBytes(tree);
        });
        cases.put("null field", (bytes, tree) -> {
            ((ObjectNode) tree).putNull("repository");
            return mapper.writeValueAsBytes(tree);
        });
        cases.put("duplicate field", (bytes, tree) -> {
            String json = new String(bytes, StandardCharsets.UTF_8);
            return json.replaceFirst("\\{", "{\"schemaVersion\":1,")
                    .getBytes(StandardCharsets.UTF_8);
        });
        cases.put("trailing JSON", (bytes, tree) ->
                (new String(bytes, StandardCharsets.UTF_8) + "{}")
                        .getBytes(StandardCharsets.UTF_8));
        cases.put("malformed syntax", (bytes, tree) ->
                java.util.Arrays.copyOf(bytes, bytes.length - 1));
        cases.put("unsupported schema", json("schemaVersion", 2));
        cases.put("unsupported policy", json("ownershipPolicyVersion", 2));
        return cases.entrySet().stream().map(item -> DynamicTest.dynamicTest(item.getKey(),
                () -> assertRejectedBeforeNetwork(item.getValue())));
    }

    @TestFactory
    Stream<DynamicTest> sourceManifestAndInstallationBindingsAreEnforced() {
        Map<String, BytesMutation> cases = new LinkedHashMap<>();
        cases.put("installation id", json("installationId", UUID.randomUUID().toString()));
        cases.put("installation root", json("canonicalRoot", temp.resolve("elsewhere").toString()));
        cases.put("registration time", json("registeredAt", "2025-01-01T00:00:00Z"));
        cases.put("repository", json("repository", "unknown"));
        cases.put("tag grammar", json("tag", "bad tag"));
        cases.put("asset grammar", json("assetName", "not-an-official.zip"));
        cases.put("asset size", json("assetSize", 0));
        cases.put("asset hash", json("assetSha256", "xyz"));
        cases.put("manifest product", nested("officialManifest", node ->
                node.put("product", "mekhq")));
        cases.put("manifest package", nested("officialManifest", node ->
                node.put("packageId", "wrong")));
        cases.put("manifest release", nested("officialManifest", node ->
                node.put("releaseId", "v-other")));
        cases.put("protected policy", nested("officialManifest", node ->
                ((ArrayNode) node.withArray("protectedPaths")).removeAll().add("saves")));
        cases.put("managed path outside allowlist", nested("officialManifest", node ->
                ((ObjectNode) node.withArray("files").get(0)).put("path", "settings.properties")));
        cases.put("managed path protected", nested("officialManifest", node ->
                ((ObjectNode) node.withArray("files").get(0)).put("path", "mmconf/options.cfg")));
        cases.put("excluded overlap", (bytes, tree) -> {
            ((ObjectNode) tree).withArray("excludedOfficialPaths").removeAll().add("data/file.txt");
            return mapper.writeValueAsBytes(tree);
        });
        cases.put("excluded alias", (bytes, tree) -> {
            ((ObjectNode) tree).withArray("excludedOfficialPaths").removeAll()
                    .add("Unknown.cfg").add("unknown.cfg");
            return mapper.writeValueAsBytes(tree);
        });
        return cases.entrySet().stream().map(item -> DynamicTest.dynamicTest(item.getKey(),
                () -> assertRejectedBeforeNetwork(item.getValue())));
    }

    private void assertRejectedBeforeNetwork(BytesMutation mutation) throws Exception {
        Fixture fixture = fixture("case-" + sequence.incrementAndGet());
        byte[] valid = Files.readAllBytes(fixture.receipt);
        JsonNode tree = mapper.readTree(valid);
        byte[] invalid = mutation.apply(valid, tree);
        Files.write(fixture.receipt, invalid);
        byte[] registryBefore = Files.readAllBytes(fixture.registry);
        Map<String, byte[]> rootBefore = files(fixture.root);
        CountingTransport network = new CountingTransport();

        IOException failure = assertThrows(IOException.class, () ->
                new UpdatePreviewService(network).preview(fixture.registry, fixture.id, "v-new",
                        new PrintStream(new ByteArrayOutputStream())));

        assertTrue(failure.getMessage().contains("ownership receipt is invalid"));
        assertTrue(network.requests.isEmpty());
        assertArrayEquals(invalid, Files.readAllBytes(fixture.receipt));
        assertArrayEquals(registryBefore, Files.readAllBytes(fixture.registry));
        assertFileMapsEqual(rootBefore, files(fixture.root));
        try (var children = Files.list(fixture.registry.resolveSibling(
                fixture.registry.getFileName() + ".metadata"))) {
            assertFalse(children.anyMatch(path ->
                    path.getFileName().toString().startsWith(".preview-")));
        }
    }

    private Fixture fixture(String name) throws Exception {
        Path area = Files.createDirectory(temp.resolve(name));
        Path root = Files.createDirectory(area.resolve("installed"));
        Files.createDirectories(root.resolve("data"));
        Files.createDirectories(root.resolve("lib"));
        Files.createDirectories(root.resolve("mmconf"));
        Files.writeString(root.resolve("data/file.txt"), "official");
        createJar(root.resolve("MegaMek.jar"));
        Path registry = area.resolve("registry.json");
        RegistryStore store = new RegistryStore();
        var record = store.register(registry, "Official", root, null);
        RegistryData data = store.read(registry);
        OwnershipPolicy.Build build = new OwnershipPolicy().build(
                root, OfficialRepository.MEGAMEK, "v-old");
        ReceiptStore receipts = new ReceiptStore();
        receipts.write(registry, data, record, OfficialRepository.MEGAMEK, "v-old",
                "MegaMek-v-old.tar.gz", 123, "sha256:" + "a".repeat(64), build);
        return new Fixture(root, registry, receipts.receiptPath(registry, record.id()), record.id());
    }

    private BytesMutation json(String field, Object value) {
        return (bytes, tree) -> {
            ((ObjectNode) tree).set(field, mapper.valueToTree(value));
            return mapper.writeValueAsBytes(tree);
        };
    }

    private BytesMutation nested(String field, Consumer<ObjectNode> mutation) {
        return (bytes, tree) -> {
            mutation.accept((ObjectNode) tree.get(field));
            return mapper.writeValueAsBytes(tree);
        };
    }

    private static Map<String, byte[]> files(Path root) throws IOException {
        Map<String, byte[]> result = new LinkedHashMap<>();
        try (var walk = Files.walk(root)) {
            for (Path path : walk.filter(Files::isRegularFile).sorted().toList()) {
                result.put(root.relativize(path).toString(), Files.readAllBytes(path));
            }
        }
        return result;
    }

    private static void assertFileMapsEqual(Map<String, byte[]> expected,
                                            Map<String, byte[]> actual) {
        assertTrue(expected.keySet().equals(actual.keySet()));
        expected.forEach((path, bytes) -> assertArrayEquals(bytes, actual.get(path), path));
    }

    private static void createJar(Path path) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "megamek.MegaMek");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            jar.putNextEntry(new JarEntry("megamek/Version.properties"));
            jar.write("major=1\nminor=2\npatch=3\n".getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }

    @FunctionalInterface
    private interface BytesMutation {
        byte[] apply(byte[] original, JsonNode tree) throws Exception;
    }

    private record Fixture(Path root, Path registry, Path receipt, String id) {
    }

    private static final class CountingTransport implements ReleaseTransport {
        private final List<URI> requests = new ArrayList<>();

        @Override
        public Response get(URI uri, String accept) throws IOException {
            requests.add(uri);
            throw new IOException("unexpected network request");
        }
    }
}
