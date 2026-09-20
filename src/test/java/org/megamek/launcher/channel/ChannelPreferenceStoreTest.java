package org.megamek.launcher.channel;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.megamek.launcher.onboarding.Product;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryData;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.release.OfficialRepository;
import org.megamek.launcher.update.OwnershipPolicy;
import org.megamek.launcher.update.OwnershipReceipt;
import org.megamek.launcher.update.ReceiptStore;

import java.io.IOException;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelPreferenceStoreTest {
    @TempDir Path temp;

    @Test
    void fixedChannelsInitializeOnlyForTheirVerifiedManagedInstallations() throws Exception {
        Path firstRoot = Files.createDirectory(temp.resolve("first"));
        Path secondRoot = Files.createDirectory(temp.resolve("second"));
        Path app = Files.writeString(firstRoot.resolve("MegaMek.jar"), "application fixture");
        Files.writeString(secondRoot.resolve("MegaMek.jar"), "second application fixture");
        InstallationRecord first = record(firstRoot, "First", "C:\\Java\\bin\\java.exe", "keep-pin");
        InstallationRecord second = record(secondRoot, "Second", null, null);
        Path registry = registry(new RegistryData(RegistryStore.SCHEMA, first.id(),
                List.of(first, second)));
        RegistryData data = new RegistryStore().read(registry);
        OwnershipReceipt firstReceipt = receipt(registry, data, first);
        OwnershipReceipt secondReceipt = receipt(registry, data, second);
        byte[] registryBefore = Files.readAllBytes(registry);
        FileTime registryTime = Files.getLastModifiedTime(registry);
        byte[] appBefore = Files.readAllBytes(app);
        FileTime appTime = Files.getLastModifiedTime(app);

        ChannelPreferenceStore store = new ChannelPreferenceStore();
        assertEquals(ChannelPreferenceStore.Status.UNKNOWN,
                store.read(registry, data, first).status());
        store.initializeManaged(
                registry, first, firstReceipt, FollowChannel.MILESTONE, false);
        store.initializeManaged(
                registry, second, secondReceipt, FollowChannel.DEVELOPMENT, true);

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
    void sameChannelRetryIsIdempotentAndDifferentChannelIsRejectedWithoutChangingBytes()
            throws Exception {
        Path root = Files.createDirectory(temp.resolve("fixed-copy"));
        Files.writeString(root.resolve("MegaMek.jar"), "application fixture");
        InstallationRecord record = record(root, "Copy", null, null);
        Path registry = registry(new RegistryData(RegistryStore.SCHEMA, record.id(),
                List.of(record)));
        RegistryData data = new RegistryStore().read(registry);
        OwnershipReceipt receipt = receipt(registry, data, record);
        ChannelPreferenceStore store = new ChannelPreferenceStore();
        ChannelPreference original = store.initializeManaged(
                registry, record, receipt, FollowChannel.MILESTONE, false);
        Path sidecar = store.path(registry, record.id());
        byte[] before = Files.readAllBytes(sidecar);

        assertEquals(original, store.initializeManaged(
                registry, record, receipt, FollowChannel.MILESTONE, true),
                "a transaction retry preserves the already-published check preference");
        assertArrayEquals(before, Files.readAllBytes(sidecar));
        IOException rejected = assertThrows(IOException.class, () -> store.initializeManaged(
                registry, record, receipt, FollowChannel.DEVELOPMENT, false));
        assertTrue(rejected.getMessage().contains(
                "Channel is fixed; install another managed copy"));
        assertArrayEquals(before, Files.readAllBytes(sidecar));
    }

    @Test
    void importedRecordCannotBeInitializedWithoutPublishedOwnershipReceipt() throws Exception {
        Path root = Files.createDirectory(temp.resolve("imported-copy"));
        Files.writeString(root.resolve("MegaMek.jar"), "application fixture");
        InstallationRecord record = record(root, "Imported", null, null);
        Path registry = registry(new RegistryData(RegistryStore.SCHEMA, record.id(),
                List.of(record)));
        OwnershipReceipt claimed = new OwnershipReceipt(ReceiptStore.SCHEMA_VERSION,
                OwnershipPolicy.VERSION, record.id(), record.canonicalRoot(),
                record.registeredAt(), "megamek", "v1.0.0",
                "MegaMek-v1.0.0.tar.gz", 123, "a".repeat(64), null, List.of());
        ChannelPreferenceStore store = new ChannelPreferenceStore();

        assertThrows(IOException.class, () -> store.initializeManaged(
                registry, record, claimed, FollowChannel.MILESTONE, false));
        assertFalse(Files.exists(store.path(registry, record.id())));
        assertFalse(Files.exists(new ReceiptStore().metadataDirectory(registry)));
    }

    @Test
    void missingCorruptAndStaleSettingsCannotBeCreatedOrResetByCheckToggle()
            throws Exception {
        Path root = Files.createDirectory(temp.resolve("copy"));
        Files.writeString(root.resolve("MegaMek.jar"), "application fixture");
        InstallationRecord record = record(root, "Copy", null, null);
        Path registry = registry(new RegistryData(RegistryStore.SCHEMA, record.id(),
                List.of(record)));
        RegistryData data = new RegistryStore().read(registry);
        OwnershipReceipt receipt = receipt(registry, data, record);
        ChannelPreferenceStore store = new ChannelPreferenceStore();
        ChannelPreference missingExpected = new ChannelPreference(ChannelPreferenceStore.SCHEMA,
                record.id(), record.canonicalRoot(), record.registeredAt(),
                FollowChannel.MILESTONE, false);
        assertThrows(java.io.IOException.class,
                () -> store.setCheckOnOpen(registry, record, missingExpected, true));
        Path sidecar = store.path(registry, record.id());
        assertFalse(Files.exists(sidecar));

        ChannelPreference expected = store.initializeManaged(
                registry, record, receipt, FollowChannel.MILESTONE, false);
        Files.writeString(sidecar, "{\"schemaVersion\":1,\"schemaVersion\":1}");

        ChannelPreferenceStore.ReadResult corrupt = store.read(registry, data, record);
        assertEquals(ChannelPreferenceStore.Status.UNAVAILABLE, corrupt.status());
        assertTrue(corrupt.reason().contains("not reset"));
        ChannelPreference corruptExpected = expected;
        assertThrows(java.io.IOException.class,
                () -> store.setCheckOnOpen(registry, record, corruptExpected, true));
        assertEquals("{\"schemaVersion\":1,\"schemaVersion\":1}", Files.readString(sidecar));

        Files.delete(sidecar);
        ChannelPreference stale = new ChannelPreference(ChannelPreferenceStore.SCHEMA,
                record.id(), temp.resolve("replacement").toAbsolutePath().normalize().toString(),
                "2026-09-16T01:02:04Z", FollowChannel.MILESTONE, false);
        new ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValue(sidecar.toFile(), stale);
        byte[] staleBytes = Files.readAllBytes(sidecar);
        assertEquals(ChannelPreferenceStore.Status.UNAVAILABLE,
                store.read(registry, data, record).status());
        assertThrows(java.io.IOException.class,
                () -> store.setCheckOnOpen(registry, record, corruptExpected, true));
        assertArrayEquals(staleBytes, Files.readAllBytes(sidecar));
    }

    @Test
    void checkTogglePreservesFixedChannelAndEveryBindingField() throws Exception {
        Path root = Files.createDirectory(temp.resolve("toggle-copy"));
        Files.writeString(root.resolve("MegaMek.jar"), "application fixture");
        InstallationRecord record = record(root, "Copy", null, null);
        Path registry = registry(new RegistryData(RegistryStore.SCHEMA, record.id(),
                List.of(record)));
        RegistryData data = new RegistryStore().read(registry);
        OwnershipReceipt receipt = receipt(registry, data, record);
        ChannelPreferenceStore store = new ChannelPreferenceStore();
        ChannelPreference before = store.initializeManaged(
                registry, record, receipt, FollowChannel.DEVELOPMENT, false);
        byte[] beforeBytes = Files.readAllBytes(store.path(registry, record.id()));

        ChannelPreference after =
                store.setCheckOnOpen(registry, record, before, true);

        assertEquals(before.schemaVersion(), after.schemaVersion());
        assertEquals(before.installationId(), after.installationId());
        assertEquals(before.canonicalRoot(), after.canonicalRoot());
        assertEquals(before.registeredAt(), after.registeredAt());
        assertEquals(before.channel(), after.channel());
        assertTrue(after.checkOnOpen());
        assertNotEquals(new String(beforeBytes),
                Files.readString(store.path(registry, record.id())));
    }

    @Test
    void lockContentionFailsWithoutChangingExistingChoice() throws Exception {
        Path root = Files.createDirectory(temp.resolve("copy"));
        InstallationRecord record = record(root, "Copy", null, null);
        Files.writeString(root.resolve("MegaMek.jar"), "application fixture");
        Path registry = registry(new RegistryData(
                RegistryStore.SCHEMA, record.id(), List.of(record)));
        RegistryData data = new RegistryStore().read(registry);
        OwnershipReceipt receipt = receipt(registry, data, record);
        ChannelPreferenceStore store = new ChannelPreferenceStore();
        ChannelPreference preference = store.initializeManaged(
                registry, record, receipt, FollowChannel.MILESTONE, false);
        Path directory = new ReceiptStore().metadataDirectory(registry);
        Path lockPath = directory.resolve(record.id() + ".channel.json.lock");

        try (FileChannel file = FileChannel.open(lockPath, StandardOpenOption.WRITE);
             FileLock ignored = file.lock()) {
            assertThrows(java.io.IOException.class,
                    () -> store.setCheckOnOpen(registry, record, preference, true));
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

    private static OwnershipReceipt receipt(Path registry, RegistryData data,
                                            InstallationRecord record) throws Exception {
        Path root = Path.of(record.canonicalRoot());
        OwnershipPolicy.Build build = new OwnershipPolicy().build(
                root, OfficialRepository.MEGAMEK, "v1.0.0");
        return new ReceiptStore().write(registry, data, record, OfficialRepository.MEGAMEK,
                "v1.0.0", "MegaMek-v1.0.0.tar.gz", 123,
                "sha256:" + "a".repeat(64), build);
    }

    private static InstallationRecord record(Path root, String name, String java, String pin) {
        Product product = new Product("megamek", "MegaMek.jar", "megamek.MegaMek",
                "0.51.0", List.of());
        return new InstallationRecord(UUID.randomUUID().toString(), name,
                root.toAbsolutePath().normalize().toString(), "0.51.0", List.of(product),
                java, pin, false, "2026-09-16T01:02:03Z");
    }
}
