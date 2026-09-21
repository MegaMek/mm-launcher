package org.megamek.launcher.gui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.megamek.launcher.onboarding.InstallationInspector;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryStore;

import java.awt.EventQueue;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenLocationServiceTest {
    @TempDir Path temp;

    @Test
    void opensOnlyRevalidatedRealDirectoryOffEdt() throws Exception {
        RegistryStore store = new RegistryStore();
        Path registry = temp.resolve("registry.json");
        Path root = suite(temp.resolve("copy"));
        InstallationRecord record = store.register(registry, "Copy", root, null);
        AtomicReference<Path> opened = new AtomicReference<>();
        OpenLocationService service = new OpenLocationService(registry, store,
                new InstallationInspector(), new OpenLocationService.DesktopGateway() {
                    @Override public boolean supported() {
                        return true;
                    }

                    @Override public void open(Path directory) {
                        assertFalse(EventQueue.isDispatchThread());
                        opened.set(directory);
                    }
                });

        service.open(record);

        assertEquals(root.toRealPath(), opened.get());
    }

    @Test
    void unsupportedDesktopAndChangedLayoutFailExplicitly() throws Exception {
        RegistryStore store = new RegistryStore();
        Path registry = temp.resolve("registry.json");
        Path root = suite(temp.resolve("copy"));
        InstallationRecord record = store.register(registry, "Copy", root, null);
        OpenLocationService unsupported = new OpenLocationService(registry, store,
                new InstallationInspector(), new OpenLocationService.DesktopGateway() {
                    @Override public boolean supported() {
                        return false;
                    }

                    @Override public void open(Path directory) {
                        throw new AssertionError("unsupported desktop must not be called");
                    }
                });
        assertTrue(assertThrows(IOException.class, () -> unsupported.open(record))
                .getMessage().contains("not supported"));

        Files.delete(root.resolve("MegaMek.jar"));
        IOException changed = assertThrows(IOException.class, () -> unsupported.open(record));
        assertTrue(changed.getMessage().contains("changed")
                || changed.getMessage().contains("recognized"));
    }

    private static Path suite(Path root) throws Exception {
        Files.createDirectories(root.resolve("data"));
        Files.createDirectories(root.resolve("mmconf"));
        Files.createDirectories(root.resolve("lib"));
        java.util.jar.Manifest manifest = new java.util.jar.Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "megamek.MegaMek");
        try (JarOutputStream ignored = new JarOutputStream(
                Files.newOutputStream(root.resolve("MegaMek.jar")), manifest)) {
            // The manifest is sufficient for static product recognition.
        }
        return root;
    }
}
