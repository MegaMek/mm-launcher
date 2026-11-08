/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.megamek.launcher.gui;

import org.junit.jupiter.api.Test;
import org.megamek.launcher.DesktopLauncher;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LauncherIconsTest {
    @Test void loadsEverySquareWindowIconSize() {
        var images = LauncherIcons.images();
        assertEquals(LauncherIcons.SIZES.length, images.size());
        for (int i = 0; i < images.size(); i++) {
            var image = (BufferedImage) images.get(i);
            assertEquals(LauncherIcons.SIZES[i], image.getWidth());
            assertEquals(LauncherIcons.SIZES[i], image.getHeight());
        }
    }

    @Test void linuxMenuEntryMatchesTheLauncherWindow() throws Exception {
        List<String> entry = Files.readAllLines(Path.of("src/distribution/linux/MegaMek Launcher.desktop"));
        assertEquals("[Desktop Entry]", entry.getFirst());
        // X11/XWayland names AWT windows after the main class with dots replaced by dashes.
        assertTrue(entry.contains("StartupWMClass=" + DesktopLauncher.class.getName().replace('.', '-')));
        assertTrue(entry.contains("Exec=APPLICATION_LAUNCHER"));
        assertTrue(entry.contains("Icon=APPLICATION_ICON"));
        assertTrue(entry.contains("Type=Application"));
        assertTrue(entry.contains("Terminal=false"));
    }
}
