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

package org.megamek.launcher.design.host;

/**
 * The GUI scale the design system multiplies its logical sizes by. The launcher keeps the
 * application scale at 1 and leaves display DPI to Java's per-monitor transform, as {@code GuiScale}
 * documents; the setter exists so a future scale preference has one place to land.
 */
public final class UIUtil {
    private static volatile float guiScale = 1f;

    private UIUtil() {
    }

    public static int scaleForGUI(int value) {
        return Math.round(scaleForGUI((float) value));
    }

    public static float scaleForGUI(float value) {
        return guiScale * value;
    }

    public static float guiScale() {
        return guiScale;
    }

    public static void setGuiScale(float scale) {
        if (!Float.isFinite(scale) || scale < 0.5f || scale > 4f) {
            throw new IllegalArgumentException("GUI scale must be between 0.5 and 4");
        }
        guiScale = scale;
    }
}
