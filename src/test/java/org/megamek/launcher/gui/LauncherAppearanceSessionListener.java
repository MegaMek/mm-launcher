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

import org.junit.platform.launcher.LauncherSession;
import org.junit.platform.launcher.LauncherSessionListener;

import javax.swing.SwingUtilities;

/**
 * Installs the launcher appearance once per test JVM, as {@link GuiLauncher} does at startup, so
 * Swing tests see the shipped look and feel. The {@code mm.launcher.theme} ({@code dark} or
 * {@code light}) and {@code mm.launcher.palette} ({@code standard} or {@code colorblind}) system
 * properties select the appearance for graphical review images.
 */
public final class LauncherAppearanceSessionListener implements LauncherSessionListener {
    static LauncherAppearance testAppearance() {
        return new LauncherAppearance(
                LauncherAppearance.Theme.fromId(System.getProperty("mm.launcher.theme", "dark")),
                LauncherAppearance.paletteFromId(System.getProperty("mm.launcher.palette", "standard")),
                false);
    }

    @Override
    public void launcherSessionOpened(LauncherSession session) {
        try {
            SwingUtilities.invokeAndWait(() -> LauncherAppearance.install(testAppearance()));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while installing the launcher appearance",
                    interrupted);
        } catch (java.lang.reflect.InvocationTargetException error) {
            throw new IllegalStateException("Cannot install the launcher appearance", error.getCause());
        }
    }
}
