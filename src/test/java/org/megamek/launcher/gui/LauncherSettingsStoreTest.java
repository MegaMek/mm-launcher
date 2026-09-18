package org.megamek.launcher.gui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
    void corruptExistingSettingIsNotReset() throws Exception {
        Path registry = temp.resolve("registry.json");
        LauncherSettingsStore store = new LauncherSettingsStore(registry);
        Path settings = Files.writeString(store.path(), "{broken");

        IOException read = assertThrows(IOException.class, store::read);
        assertTrue(read.getMessage().contains("not reset"));
        assertThrows(IOException.class, () -> store.write(true));
        assertEquals("{broken", Files.readString(settings));
    }
}
