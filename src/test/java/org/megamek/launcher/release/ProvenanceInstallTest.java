package org.megamek.launcher.release;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.megamek.launcher.registry.RegistryData;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.update.ReceiptStore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProvenanceInstallTest {
    @TempDir Path temp;

    @Test
    void metadataEqualToOrAncestorOfRegisteredRootFailsBeforeNetworkOrWrites() throws Exception {
        for (boolean equal : List.of(true, false)) {
            Path area = Files.createDirectory(temp.resolve(equal ? "equal" : "ancestor"));
            Path registry = area.resolve("registry.json");
            Path metadata = registry.resolveSibling("registry.json.metadata");
            Path root = equal ? metadata : metadata.resolve("existing");
            createInstallation(root);
            new RegistryStore().register(registry, "Existing", root, null);
            byte[] registryBefore = Files.readAllBytes(registry);
            CountingTransport network = new CountingTransport();
            Path target = area.resolve("new-install");

            IOException failure = assertThrows(IOException.class, () ->
                    new FreshInstaller(network).install(OfficialRepository.MEGAMEK, "v1",
                            target, registry, "New", quiet()));

            assertTrue(failure.getMessage().contains(
                    "receipt metadata directory overlaps registered installation"));
            assertTrue(network.requests.isEmpty());
            assertFalse(Files.exists(target));
            assertArrayEquals(registryBefore, Files.readAllBytes(registry));
        }
    }

    @Test
    void sharedPlacementRuleRejectsRegisteredRootAncestorOfMetadata() throws Exception {
        Path metadata = temp.resolve("root").resolve("registry.json.metadata")
                .toAbsolutePath().normalize();
        var record = recordForPlacement(metadata.getParent());
        RegistryData data = new RegistryData(RegistryStore.SCHEMA, record.id(), List.of(record));

        IOException failure = assertThrows(IOException.class,
                () -> ReceiptStore.validateMetadataPlacement(metadata, data));

        assertTrue(failure.getMessage().contains("overlaps registered installation"));
    }

    @Test
    void reservedTargetRelationsAndUnsafeMetadataObjectFailBeforeNetwork() throws Exception {
        Path area = Files.createDirectory(temp.resolve("reserved"));
        Path registry = area.resolve("registry.json");
        Path metadata = registry.resolveSibling("registry.json.metadata");
        for (Path target : List.of(metadata, metadata.resolve("nested"), area)) {
            boolean existed = Files.exists(target);
            CountingTransport network = new CountingTransport();
            assertThrows(IOException.class, () -> new FreshInstaller(network).install(
                    OfficialRepository.MEGAMEK, "v1", target, registry, "New", quiet()));
            assertTrue(network.requests.isEmpty());
            assertTrue(Files.exists(target) == existed);
        }

        Files.writeString(metadata, "not a directory");
        CountingTransport network = new CountingTransport();
        Path target = area.resolve("safe-target");
        assertThrows(IOException.class, () -> new FreshInstaller(network).install(
                OfficialRepository.MEGAMEK, "v1", target, registry, "New", quiet()));
        assertTrue(network.requests.isEmpty());
        assertFalse(Files.exists(target));
        assertTrue(Files.isRegularFile(metadata));
    }

    private static org.megamek.launcher.registry.InstallationRecord recordForPlacement(Path root) {
        return new org.megamek.launcher.registry.InstallationRecord(
                "00000000-0000-0000-0000-000000000001", "Existing",
                root.toAbsolutePath().normalize().toString(), "test",
                List.of(new org.megamek.launcher.onboarding.Product(
                        "megamek", "MegaMek.jar", "megamek.MegaMek", "test", List.of())),
                null, false, "2026-01-01T00:00:00Z");
    }

    private static void createInstallation(Path root) throws IOException {
        Files.createDirectories(root.resolve("data"));
        Files.createDirectories(root.resolve("lib"));
        Files.createDirectories(root.resolve("mmconf"));
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "megamek.MegaMek");
        try (JarOutputStream jar = new JarOutputStream(
                Files.newOutputStream(root.resolve("MegaMek.jar")), manifest)) {
            jar.putNextEntry(new JarEntry("megamek/Version.properties"));
            jar.write("major=1\nminor=2\npatch=3\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }

    private static PrintStream quiet() {
        return new PrintStream(new ByteArrayOutputStream());
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
