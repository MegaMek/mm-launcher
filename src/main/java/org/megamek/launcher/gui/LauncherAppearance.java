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

import com.formdev.flatlaf.FlatLaf;
import org.megamek.launcher.design.DesignTokens;
import org.megamek.launcher.design.DesignTokens.Mode;
import org.megamek.launcher.design.host.FontHandler;
import org.megamek.launcher.design.swing.MegaMekDarkLaf;
import org.megamek.launcher.design.swing.MegaMekLightLaf;
import org.megamek.launcher.design.swing.TokenMotion;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.UnsupportedLookAndFeelException;
import java.awt.Window;
import java.util.Locale;
import java.util.Objects;

/**
 * The user's appearance preference and the one place that applies it: the light or dark MegaMek
 * look and feel, always with the standard palette and full motion. Launcher colours are
 * token roles resolved while painting ({@link LauncherTheme}), so applying a change repaints every
 * window in the new look without rebuilding it.
 */
record LauncherAppearance(Theme theme) {
    static final LauncherAppearance DEFAULT = new LauncherAppearance(Theme.DARK);

    private static volatile LauncherAppearance current = DEFAULT;

    enum Theme {
        DARK("Dark", Mode.DARK),
        LIGHT("Light", Mode.LIGHT);

        private final String label;
        private final Mode mode;

        Theme(String label, Mode mode) {
            this.label = label;
            this.mode = mode;
        }

        Mode mode() {
            return mode;
        }

        String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        static Theme fromId(String id) {
            for (Theme theme : values()) {
                if (theme.id().equals(id)) return theme;
            }
            throw new IllegalArgumentException("unknown launcher theme: " + id);
        }

        @Override
        public String toString() {
            return label;
        }
    }

    LauncherAppearance {
        Objects.requireNonNull(theme, "theme");
    }

    LauncherAppearance withTheme(Theme value) {
        return new LauncherAppearance(value);
    }

    /** The appearance last installed, or the default before the first install. */
    static LauncherAppearance current() {
        return current;
    }

    /** The colour mode launcher colours resolve in. */
    static Mode mode() {
        return current.theme().mode();
    }

    /**
     * Installs {@code appearance}: registers the bundled fonts, selects the standard palette and motion,
     * sets the MegaMek look and feel and refreshes every open window. Call on the EDT.
     */
    static void install(LauncherAppearance appearance) {
        Objects.requireNonNull(appearance, "appearance");
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Install the launcher appearance on the EDT");
        }
        FontHandler.initialize();
        DesignTokens.setPalette(DesignTokens.Palette.STANDARD);
        TokenMotion.setReduced(false);
        current = appearance;
        try {
            UIManager.setLookAndFeel(appearance.theme() == Theme.LIGHT
                    ? new MegaMekLightLaf() : new MegaMekDarkLaf());
        } catch (UnsupportedLookAndFeelException error) {
            throw new IllegalStateException("The MegaMek look and feel is unavailable", error);
        }
        FlatLaf.updateUI();
        for (Window window : Window.getWindows()) {
            window.repaint();
        }
    }
}
