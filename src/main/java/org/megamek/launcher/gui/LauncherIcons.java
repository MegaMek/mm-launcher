/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.megamek.launcher.gui;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** The launcher's window icons, shown by the window manager in title bars, taskbars and docks. */
final class LauncherIcons {
    static final int[] SIZES = {16, 32, 48, 64, 128, 256};

    private LauncherIcons() {
    }

    /** Returns the icons that could be loaded; a missing icon must never stop the launcher. */
    static List<Image> images() {
        List<Image> images = new ArrayList<>();
        for (int size : SIZES) {
            try (var input = LauncherIcons.class.getResourceAsStream("icons/launcher-" + size + ".png")) {
                if (input == null) continue;
                Image image = ImageIO.read(input);
                if (image != null) images.add(image);
            } catch (IOException ignored) {
                // Fall back to the platform default for this size.
            }
        }
        return List.copyOf(images);
    }
}
