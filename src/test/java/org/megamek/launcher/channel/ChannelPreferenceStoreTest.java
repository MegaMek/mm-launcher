package org.megamek.launcher.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.megamek.launcher.onboarding.Product;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryData;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.update.ReceiptStore;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelPreferenceStoreTest {
    @TempDir Path temp;

    @Test
    void choicesArePerInstallationAndDoNotRewriteRegistryOrApplications() throws Exception {
        Path firstRoot = Files.createDirectory(temp.resolve("first"));
        Path secondRoot = Files.createDirectory(temp.resolve("second"));
        Path app = Files.writeString(firstRoot.resolve("MegaMek.jar"), "application fixture");
        InstallationRecord first = record(firstRoot, "First", "C:\\Java\\bin\\java.exe", "keep-pin");
        InstallationRecord second = record(secondRoot, "Second", null, null);
        Path registry = registry(new RegistryData(RegistryStore.SCHEMA, first.id(),
                List.of(first, second)));
        byte[] registryBefore = Files.readAllBytes(registry);
        FileTime registryTime = Files.getLastModifiedTime(registry);
        byte[] appBefore = Files.readAllBytes(app);
        FileTime appTime = Files.getLastModifiedTime(app);

        ChannelPreferenceStore store = new ChannelPreferenceStore();
        assertEquals(ChannelPreferenceStore.Status.UNKNOWN,
                store.read(registry, new RegistryStore().read(registry), first).status());
        store.set(registry, first, FollowChannel.MILESTONE, false);
        store.set(registry, second, FollowChannel.DEVELOPMENT, true);

        RegistryData after = new RegistryStore().read(registry);
        assertEquals(FollowChannel.MILESTONE,
                store.read(registry, after, first).preference().channel());
        assertEquals(FollowChannel.DEVELOPMENT,
                store.read(registry, after, second).preference().channel());
        assertEquals("C:\\Java\\bin\\java.exe", after.installations().getFirst().javaExecutable());
        assertEquals("keep-pin", after.installations().getFirst().pin());
        assertEquals(first.id(), after.defaultInstallationId());
        assertArrayEquals(registryBefore, Files.readAllBytes(registry));
        assertEquals(registryTime, Files.getLastModifiedTime(registry));
        assertArrayEquals(appBefore, Files.readAllBytes(app));
        assertEquals(appTime, Files.getLastModifiedTime(app));
    }

    @Test
    void corruptAndStaleSettingsAreUnavailableAndCannotBeResetBySet() throws Exception {
        Path root = Files.createDirectory(temp.resolve("copy"));
        InstallationRecord record = record(root, "Copy", null, null);
        Path registry = registry(new RegistryData(RegistryStore.SCHEMA, record.id(),
                List.of(record)));
        RegistryData data = new RegistryStore().read(registry);
        ChannelPreferenceStore store = new ChannelPreferenceStore();
        store.set(registry, record, FollowChannel.MILESTONE, false);
        Path sidecar = store.path(registry, record.id());
        Files.writeString(sidecar, "{\"schemaVersion\":1,\"schemaVersion\":1}");

        ChannelPreferenceStore.ReadResult corrupt = store.read(registry, data, record);
        assertEquals(ChannelPreferenceStore.Status.UNAVAILABLE, corrupt.status());
        assertTrue(corrupt.reason().contains("not reset"));
        assertThrows(java.io.IOException.class,
                () -> store.set(registry, record, FollowChannel.DEVELOPMENT, false));
        assertEquals("{\"schemaVersion\":1,\"schemaVersion\":1}", Files.readString(sidecar));

        // A valid sidecar copied beside a record with a different root/time cannot bind to it.
        Files.delete(sidecar);
        store.set(registry, record, FollowChannel.MILESTONE, false);
        InstallationRecord replacement = new InstallationRecord(record.id(), record.name(),
                temp.resolve("replacement").toAbsolutePath().normalize().toString(),
                record.observedBuild(), record.products(), record.javaExecutable(), record.pin(),
                false, "2026-09-16T01:02:04Z");
        assertEquals(ChannelPreferenceStore.Status.UNAVAILABLE,
                store.read(registry, new RegistryData(1, replacement.id(), List.of(replacement)),
                        replacement).status());
    }

    @Test
    void lockContentionFailsWithoutChangingExistingChoice() throws Exception {
        Path root = Files.createDirectory(temp.resolve("copy"));
        InstallationRecord record = record(root, "Copy", null, null);
        Path registry = registry(new RegistryData(1, record.id(), List.of(record)));
        ChannelPreferenceStore store = new ChannelPreferenceStore();
        store.set(registry, record, FollowChannel.MILESTONE, false);
        Path directory = new ReceiptStore().metadataDirectory(registry);
        Path lockPath = directory.resolve(record.id() + ".channel.json.lock");

        try (FileChannel file = FileChannel.open(lockPath, StandardOpenOption.WRITE);
             FileLock ignored = file.lock()) {
            assertThrows(java.io.IOException.class,
                    () -> store.set(registry, record, FollowChannel.DEVELOPMENT, true));
        }
        assertEquals(FollowChannel.MILESTONE,
                store.read(registry, new RegistryStore().read(registry), record)
                        .preference().channel());
    }

    private Path registry(RegistryData data) throws Exception {
        Path registry = temp.resolve("registry-" + UUID.randomUUID() + ".json");
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(registry.toFile(), data);
        return registry;
    }

    private static InstallationRecord record(Path root, String name, String java, String pin) {
        Product product = new Product("megamek", "MegaMek.jar", "megamek.MegaMek",
                "0.51.0", List.of());
        return new InstallationRecord(UUID.randomUUID().toString(), name,
                root.toAbsolutePath().normalize().toString(), "0.51.0", List.of(product),
                java, pin, false, "2026-09-16T01:02:03Z");
    }
}
