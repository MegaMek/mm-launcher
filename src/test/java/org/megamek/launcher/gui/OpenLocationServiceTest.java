/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek Launcher.
 *
 * MegaMek Launcher is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek Launcher is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's "Game Content Usage Rules"
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */

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
