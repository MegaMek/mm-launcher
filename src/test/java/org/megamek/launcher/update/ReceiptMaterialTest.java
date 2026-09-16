package org.megamek.launcher.update;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.release.OfficialRepository;
import org.megamek.launcher.release.ReleaseTransport;
import org.megamek.launcher.release.SafeTarExtractor;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReceiptMaterialTest {
    @TempDir Path temp;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void heldReceiptLockRefusesWriteWithoutClobberOrOwnedStageLeak() throws Exception {
        Fixture fixture = fixture("locked");
        Path metadata = fixture.receipt.getParent();
        Path lock = metadata.resolve(fixture.record.id() + ".json.lock");
        Path unrelated = metadata.resolve(fixture.record.id() + ".json.new-unrelated");
        Files.writeString(unrelated, "foreign");
        Snapshot before = snapshot(fixture);

        try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.WRITE);
             FileLock ignored = channel.lock()) {
            IOException failure = assertThrows(IOException.class, () -> writeAgain(fixture));
            assertTrue(failure.getMessage().contains("ownership receipt is busy"));
        }

        assertSnapshot(before, fixture);
        assertEquals("foreign", Files.readString(unrelated));
        assertEquals(List.of(unrelated.getFileName().toString()), stageNames(metadata, fixture));
    }

    @Test
    void directoryAtReceiptLockPathRefusesWriteWithoutMutation() throws Exception {
        Fixture fixture = fixture("lock-directory");
        Path metadata = fixture.receipt.getParent();
        Path lock = metadata.resolve(fixture.record.id() + ".json.lock");
        Files.delete(lock);
        Files.createDirectory(lock);
        Path unrelated = metadata.resolve(fixture.record.id() + ".json.new-unrelated");
        Files.writeString(unrelated, "foreign");
        Snapshot before = snapshot(fixture);

        IOException failure = assertThrows(IOException.class, () -> writeAgain(fixture));

        assertTrue(failure.getMessage().contains("lock has an unexpected type"));
        assertSnapshot(before, fixture);
        assertTrue(Files.isDirectory(lock));
        assertEquals(List.of(unrelated.getFileName().toString()), stageNames(metadata, fixture));
    }

    @Test
    void oversizedReceiptFileFailsBeforeNetworkAndRemainsByteAndMtimeIdentical() throws Exception {
        Fixture fixture = fixture("oversized");
        try (FileChannel channel = FileChannel.open(fixture.receipt,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            channel.position(32L * 1024 * 1024);
            channel.write(java.nio.ByteBuffer.wrap(new byte[]{1}));
        }
        Snapshot before = snapshot(fixture);
        CountingTransport network = new CountingTransport();

        IOException failure = assertThrows(IOException.class, () -> preview(fixture, network));

        assertTrue(failure.getMessage().contains("receipt size is invalid"));
        assertTrue(network.requests.isEmpty());
        assertSnapshot(before, fixture);
        assertNoPreviewScratch(fixture);
    }

    @Test
    void limitedReceiptStreamDetectsOneByteBeyondLimit() throws Exception {
        // The file-size precheck normally wins; reflection directly proves the private
        // defense-in-depth stream boundary used if a file changes while it is being read.
        Class<?> type = Class.forName(ReceiptStore.class.getName() + "$LimitedInputStream");
        Constructor<?> constructor = type.getDeclaredConstructor(InputStream.class, long.class);
        constructor.setAccessible(true);
        InputStream input = (InputStream) constructor.newInstance(
                new ByteArrayInputStream(new byte[]{1, 2, 3}), 2L);
        assertEquals(2, input.read(new byte[2]));
        IOException failure = assertThrows(IOException.class, input::read);
        assertTrue(failure.getMessage().contains("exceeds size limit while reading"));
    }

    @Test
    void excessiveManagedInventoryFailsBeforeNetworkWithoutMutation() throws Exception {
        Fixture fixture = fixture("managed-limit");
        ObjectNode receipt = (ObjectNode) mapper.readTree(fixture.receipt.toFile());
        ArrayNode files = ((ObjectNode) receipt.get("officialManifest")).putArray("files");
        for (int i = 0; i <= SafeTarExtractor.MAX_ENTRIES; i++) {
            ObjectNode entry = files.addObject();
            entry.put("path", "lib/f" + String.format("%06d", i));
            entry.put("sha256", "a".repeat(64));
        }
        Files.write(fixture.receipt, mapper.writeValueAsBytes(receipt));
        assertInvalidInventoryIsReadOnly(fixture, "managed inventory exceeds entry limit");
    }

    @Test
    void excessiveCombinedExcludedInventoryFailsBeforeNetworkWithoutMutation() throws Exception {
        Fixture fixture = fixture("excluded-limit");
        ObjectNode receipt = (ObjectNode) mapper.readTree(fixture.receipt.toFile());
        int managed = receipt.withObject("officialManifest").withArray("files").size();
        ArrayNode excluded = receipt.putArray("excludedOfficialPaths");
        for (int i = 0; i <= SafeTarExtractor.MAX_ENTRIES - managed; i++) {
            excluded.add("extras/f" + String.format("%06d", i));
        }
        Files.write(fixture.receipt, mapper.writeValueAsBytes(receipt));
        assertInvalidInventoryIsReadOnly(fixture, "exclusion inventory is invalid");
    }

    private void assertInvalidInventoryIsReadOnly(Fixture fixture, String message) throws Exception {
        Snapshot before = snapshot(fixture);
        CountingTransport network = new CountingTransport();
        IOException failure = assertThrows(IOException.class, () -> preview(fixture, network));
        assertTrue(failure.getMessage().contains(message), failure.getMessage());
        assertTrue(network.requests.isEmpty());
        assertSnapshot(before, fixture);
        assertNoPreviewScratch(fixture);
    }

    private void preview(Fixture fixture, CountingTransport network) throws Exception {
        new UpdatePreviewService(network).preview(fixture.registry, fixture.record.id(), "v-new",
                new PrintStream(new ByteArrayOutputStream()));
    }

    private OwnershipReceipt writeAgain(Fixture fixture) throws Exception {
        return fixture.receipts.write(fixture.registry, fixture.data, fixture.record,
                OfficialRepository.MEGAMEK, "v-old", "MegaMek-v-old.tar.gz", 123,
                "sha256:" + "a".repeat(64), fixture.build);
    }

    private Fixture fixture(String name) throws Exception {
        Path area = Files.createDirectory(temp.resolve(name));
        Path root = Files.createDirectory(area.resolve("installed"));
        Files.createDirectories(root.resolve("data"));
        Files.createDirectories(root.resolve("lib"));
        Files.createDirectories(root.resolve("mmconf"));
        createJar(root.resolve("MegaMek.jar"), "megamek.MegaMek");
        Path registry = area.resolve("registry.json");
        RegistryStore store = new RegistryStore();
        var record = store.register(registry, "Official", root, null);
        var data = store.read(registry);
        var build = new OwnershipPolicy().build(root, OfficialRepository.MEGAMEK, "v-old");
        ReceiptStore receipts = new ReceiptStore();
        receipts.write(registry, data, record, OfficialRepository.MEGAMEK, "v-old",
                "MegaMek-v-old.tar.gz", 123, "sha256:" + "a".repeat(64), build);
        return new Fixture(root, registry, receipts.receiptPath(registry, record.id()),
                record, data, build, receipts);
    }

    private static void createJar(Path path, String mainClass) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            jar.putNextEntry(new JarEntry("megamek/Version.properties"));
            jar.write("major=1\nminor=2\npatch=3\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }

    private static Snapshot snapshot(Fixture fixture) throws IOException {
        return new Snapshot(Files.readAllBytes(fixture.registry),
                Files.getLastModifiedTime(fixture.registry),
                Files.readAllBytes(fixture.receipt), Files.getLastModifiedTime(fixture.receipt),
                tree(fixture.root));
    }

    private static Map<String, Stamp> tree(Path root) throws IOException {
        Map<String, Stamp> result = new java.util.LinkedHashMap<>();
        try (var walk = Files.walk(root)) {
            for (Path path : walk.filter(Files::isRegularFile).sorted().toList()) {
                result.put(root.relativize(path).toString(), new Stamp(
                        Files.readAllBytes(path), Files.getLastModifiedTime(path)));
            }
        }
        return result;
    }

    private static void assertSnapshot(Snapshot expected, Fixture fixture) throws IOException {
        assertArrayEquals(expected.registryBytes, Files.readAllBytes(fixture.registry));
        assertEquals(expected.registryTime, Files.getLastModifiedTime(fixture.registry));
        assertArrayEquals(expected.receiptBytes, Files.readAllBytes(fixture.receipt));
        assertEquals(expected.receiptTime, Files.getLastModifiedTime(fixture.receipt));
        Map<String, Stamp> actual = tree(fixture.root);
        assertEquals(expected.root.keySet(), actual.keySet());
        expected.root.forEach((name, stamp) -> {
            assertArrayEquals(stamp.bytes, actual.get(name).bytes, name);
            assertEquals(stamp.time, actual.get(name).time, name);
        });
    }

    private static List<String> stageNames(Path metadata, Fixture fixture) throws IOException {
        String prefix = fixture.record.id() + ".json.new-";
        try (var children = Files.list(metadata)) {
            return children.filter(path -> path.getFileName().toString().startsWith(prefix))
                    .map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private static void assertNoPreviewScratch(Fixture fixture) throws IOException {
        try (var children = Files.list(fixture.receipt.getParent())) {
            assertFalse(children.anyMatch(path ->
                    path.getFileName().toString().startsWith(".preview-")));
        }
    }

    private record Fixture(Path root, Path registry, Path receipt,
                           org.megamek.launcher.registry.InstallationRecord record,
                           org.megamek.launcher.registry.RegistryData data,
                           OwnershipPolicy.Build build, ReceiptStore receipts) {}
    private record Snapshot(byte[] registryBytes, FileTime registryTime,
                            byte[] receiptBytes, FileTime receiptTime,
                            Map<String, Stamp> root) {}
    private record Stamp(byte[] bytes, FileTime time) {}

    private static final class CountingTransport implements ReleaseTransport {
        private final List<URI> requests = new ArrayList<>();
        @Override public Response get(URI uri, String accept) throws IOException {
            requests.add(uri);
            throw new IOException("unexpected network request");
        }
    }
}
