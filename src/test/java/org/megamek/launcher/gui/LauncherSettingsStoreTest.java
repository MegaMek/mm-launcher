package org.megamek.launcher.gui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LauncherSettingsStoreTest {
    @TempDir Path temp;

    @Test
    void absentSettingDefaultsTrueOnlyForFutureInstallsAndCanBeTurnedOff() throws Exception {
        Path registry = temp.resolve("registry.json");
        LauncherSettingsStore store = new LauncherSettingsStore(registry);

        LauncherSettingsStore.CheckConfiguration initial = store.readCheckConfiguration();
        assertTrue(store.read().checkNewInstallsOnOpen());
        assertTrue(store.read().checkInstalledVersionsOnOpen());
        assertTrue(initial.settings().checkNewInstallsOnOpen());
        assertEquals("absent", initial.revision());
        assertFalse(Files.exists(store.path()), "reading the default is side-effect free");
        assertFalse(Files.exists(registry));

        store.write(false);
        LauncherSettingsStore.CheckConfiguration disabled = store.readCheckConfiguration();
        assertFalse(disabled.settings().checkNewInstallsOnOpen());
        assertTrue(disabled.revision().startsWith("sha256:"));
        assertFalse(disabled.revision().equals(initial.revision()));
        assertFalse(Files.exists(registry), "launcher settings do not synthesize a registry");
    }

    @Test
    void globalMasterIsImmediateAndPreservesFutureCopyDefaultAndJava() throws Exception {
        Path registry = temp.resolve("registry.json");
        LauncherSettingsStore store = new LauncherSettingsStore(registry);
        Path java = temp.resolve("jdk").resolve("bin").resolve("java.exe")
                .toAbsolutePath().normalize();
        store.write(false);
        store.writeDefaultJava(java, 21);

        LauncherSettingsStore.Settings disabled = store.writeAutomaticChecks(false);

        assertFalse(disabled.checkInstalledVersionsOnOpen());
        assertFalse(disabled.checkNewInstallsOnOpen(),
                "global gate never rewrites per-copy defaults");
        assertEquals(java.toString(), disabled.defaultJavaExecutable());
        assertEquals(21, disabled.defaultJavaFeature());
    }

    @Test
    void schemaOneMigratesGlobalMasterEnabledWithoutRewriting() throws Exception {
        Path registry = temp.resolve("registry.json");
        LauncherSettingsStore store = new LauncherSettingsStore(registry);
        Files.writeString(store.path(), """
                {
                  "schemaVersion" : 1,
                  "checkNewInstallsOnOpen" : false
                }
                """);
        String before = Files.readString(store.path());

        LauncherSettingsStore.Settings migrated = store.read();

        assertTrue(migrated.checkInstalledVersionsOnOpen());
        assertFalse(migrated.checkNewInstallsOnOpen());
        assertEquals(before, Files.readString(store.path()));
    }

    @Test
    void invalidDefaultJavaDoesNotReplacePreviousSettings() throws Exception {
        Path registry = temp.resolve("registry.json");
        LauncherSettingsStore store = new LauncherSettingsStore(registry);
        store.writeAutomaticChecks(false);
        byte[] before = Files.readAllBytes(store.path());

        assertThrows(IOException.class,
                () -> store.writeDefaultJava(Path.of("relative-java"), 21));
        assertArrayEquals(before, Files.readAllBytes(store.path()));
    }

    @Test
    void corruptExistingSettingIsNotReset() throws Exception {
        Path registry = temp.resolve("registry.json");
        LauncherSettingsStore store = new LauncherSettingsStore(registry);
        Path settings = Files.writeString(store.path(), "{broken");

        IOException read = assertThrows(IOException.class, store::read);
        assertTrue(read.getMessage().contains("not reset"));
        assertThrows(IOException.class, () -> store.write(true));
        assertEquals("{broken", Files.readString(settings));
    }

    @Test
    void wrongTypedMasterValueIsRejectedWithoutCoercionOrReset() throws Exception {
        LauncherSettingsStore store =
                new LauncherSettingsStore(temp.resolve("registry.json"));
        String malformed = """
                {
                  "schemaVersion" : 2,
                  "checkInstalledVersionsOnOpen" : "false",
                  "checkNewInstallsOnOpen" : true,
                  "defaultJavaExecutable" : null,
                  "defaultJavaFeature" : null
                }
                """;
        Files.writeString(store.path(), malformed);

        assertThrows(IOException.class, store::read);
        assertEquals(malformed, Files.readString(store.path()));
    }
}
