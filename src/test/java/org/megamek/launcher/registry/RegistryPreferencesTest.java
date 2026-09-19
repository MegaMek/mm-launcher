package org.megamek.launcher.registry;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.megamek.launcher.onboarding.Product;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RegistryPreferencesTest {
    @TempDir Path temp;

    @Test
    void schemaOneMainMigratesOnlyProductsItContainsWithoutWriting() throws Exception {
        InstallationRecord suite = record(temp.resolve("suite"), "Suite",
                product("megamek"), product("lab"));
        Path registry = temp.resolve("registry.json");
        Files.write(registry, JsonMapper.builder().build().writeValueAsBytes(Map.of(
                "schemaVersion", 1,
                "defaultInstallationId", suite.id(),
                "installations", List.of(suite))));
        byte[] before = Files.readAllBytes(registry);

        RegistryData migrated = new RegistryStore().read(registry);

        assertEquals(RegistryStore.SCHEMA, migrated.schemaVersion());
        assertEquals(Map.of("megamek", suite.id(), "lab", suite.id()),
                migrated.preferredInstallationIds());
        assertFalse(migrated.preferredInstallationIds().containsKey("mekhq"));
        assertArrayEquals(before, Files.readAllBytes(registry),
                "side-effect-free migration does not silently rewrite the registry");
    }

    @Test
    void stalePreferenceFallsBackInRegistryOrderWithoutBeingPersisted() throws Exception {
        InstallationRecord first = record(temp.resolve("first"), "First",
                product("mekhq"));
        InstallationRecord second = record(temp.resolve("second"), "Second",
                product("mekhq"));
        String stale = UUID.randomUUID().toString();
        Path registry = write(new RegistryData(RegistryStore.SCHEMA, first.id(),
                Map.of("mekhq", stale), List.of(first, second)));
        byte[] before = Files.readAllBytes(registry);

        RegistryStore store = new RegistryStore();
        RegistryData data = store.read(registry);
        assertEquals(first, store.resolvePreferred(data, "mekhq"));
        assertEquals(stale, data.preferredInstallationIds().get("mekhq"));
        assertArrayEquals(before, Files.readAllBytes(registry));
    }

    @Test
    void malformedOrUnknownPreferenceIsRejectedAndNotReset() throws Exception {
        InstallationRecord record = record(temp.resolve("copy"), "Copy",
                product("megamek"));
        Path registry = write(new RegistryData(RegistryStore.SCHEMA, record.id(),
                Map.of("unknown-product", record.id()), List.of(record)));
        byte[] before = Files.readAllBytes(registry);

        assertThrows(IOException.class, () -> new RegistryStore().read(registry));
        assertArrayEquals(before, Files.readAllBytes(registry));

        Path malformed = temp.resolve("malformed-preference.json");
        Files.write(malformed, JsonMapper.builder().build().writeValueAsBytes(Map.of(
                "schemaVersion", RegistryStore.SCHEMA,
                "defaultInstallationId", record.id(),
                "preferredInstallationIds", Map.of("megamek", true),
                "installations", List.of(record))));
        byte[] malformedBefore = Files.readAllBytes(malformed);
        assertThrows(IOException.class, () -> new RegistryStore().read(malformed));
        assertArrayEquals(malformedBefore, Files.readAllBytes(malformed));
    }

    @Test
    void removalClearsOnlyPreferencesPointingToRemovedRecord() throws Exception {
        InstallationRecord first = record(temp.resolve("first"), "First",
                product("megamek"), product("lab"));
        InstallationRecord second = record(temp.resolve("second"), "Second",
                product("mekhq"));
        Path registry = write(new RegistryData(RegistryStore.SCHEMA, first.id(),
                Map.of("megamek", first.id(), "lab", first.id(), "mekhq", second.id()),
                List.of(first, second)));

        new RegistryStore().remove(registry, first.id());

        RegistryData remaining = new RegistryStore().read(registry);
        assertEquals(List.of(second), remaining.installations());
        assertEquals(Map.of("mekhq", second.id()), remaining.preferredInstallationIds());
        assertEquals(second.id(), remaining.defaultInstallationId());
    }

    @Test
    void explicitPerApplicationChoicePreservesDefaultAndAllRecordFields() throws Exception {
        InstallationRecord first = record(temp.resolve("first"), "First",
                product("megamek"));
        InstallationRecord second = record(temp.resolve("second"), "Second",
                product("megamek"));
        RegistryData original = new RegistryData(RegistryStore.SCHEMA, first.id(),
                Map.of("megamek", first.id()), List.of(first, second));
        Path registry = write(original);

        new RegistryStore().selectPreferred(registry, "megamek", second);

        RegistryData changed = new RegistryStore().read(registry);
        assertEquals(first.id(), changed.defaultInstallationId(),
                "legacy default/CLI compatibility is independent");
        assertEquals(second.id(), changed.preferredInstallationIds().get("megamek"));
        assertEquals(original.installations(), changed.installations());
    }

    @Test
    void laterRegistrationFillsOnlyStillUnsetApplications() throws Exception {
        Path registry = temp.resolve("registry.json");
        Path firstRoot = suite(temp.resolve("first-suite"), false);
        Path secondRoot = suite(temp.resolve("second-suite"), true);
        RegistryStore store = new RegistryStore();

        InstallationRecord first = store.register(registry, "First", firstRoot, null);
        InstallationRecord second = store.register(registry, "Second", secondRoot, null);
        RegistryData data = store.read(registry);

        assertEquals(first.id(), data.preferredInstallationIds().get("megamek"),
                "later registration never overwrites an established preference");
        assertEquals(second.id(), data.preferredInstallationIds().get("mekhq"),
                "newly available applications establish only their unset preference");
    }

    private Path write(RegistryData data) throws IOException {
        Path registry = temp.resolve("registry-" + UUID.randomUUID() + ".json");
        Files.write(registry, JsonMapper.builder().build().writeValueAsBytes(data));
        return registry;
    }

    private static InstallationRecord record(Path root, String name, Product... products) {
        return new InstallationRecord(UUID.randomUUID().toString(), name,
                root.toAbsolutePath().normalize().toString(), "0.51.0",
                List.of(products), "C:\\Java\\bin\\java.exe", null, false,
                "2026-09-18T00:00:00Z");
    }

    private static Product product(String key) {
        return new Product(key, key + ".jar", key + ".Main", "0.51.0", List.of());
    }

    private static Path suite(Path root, boolean withMekhq) throws IOException {
        Files.createDirectories(root.resolve("data"));
        Files.createDirectories(root.resolve("mmconf"));
        Files.createDirectories(root.resolve("lib"));
        jar(root.resolve("MegaMek.jar"), "megamek.MegaMek");
        if (withMekhq) jar(root.resolve("MekHQ.jar"), "mekhq.MekHQ");
        return root;
    }

    private static void jar(Path file, String mainClass) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
        try (JarOutputStream ignored =
                     new JarOutputStream(Files.newOutputStream(file), manifest)) {
            // A manifest-only JAR is sufficient for static package inspection.
        }
    }
}
