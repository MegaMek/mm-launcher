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

package org.megamek.launcher.design.swing;

import java.awt.Insets;
import javax.swing.JTabbedPane;

import com.formdev.flatlaf.FlatClientProperties;
import org.megamek.launcher.design.DesignTokens;
import org.megamek.launcher.design.swing.TokenButtons.Size;

/**
 * The design system's tabs for Swing (design system, Tabs): secondary labels on the window surface, a
 * selection-color underline under the selected tab and a hairline under the strip. The MegaMek look and feels draw every tab
 * pane this way at the compact size; this gives a tab pane the regular size, for top-level page switching.
 */
public final class TokenTabs {
    private TokenTabs() {
    }

    public static JTabbedPane tabs(Size size) {
        return size(new JTabbedPane(), size);
    }

    /**
     * Gives a tab pane the tab height and side padding of {@code size}; its titles follow their screen's text case
     * mode ({@link TokenPresentation}).
     */
    public static <T extends JTabbedPane> T size(T tabs, Size size) {
        int side = Math.round(DesignTokens.shipped().space().get(size == Size.REGULAR ? "lg" : "md"));
        tabs.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_HEIGHT, size.height());
        tabs.putClientProperty(FlatClientProperties.TABBED_PANE_TAB_INSETS, new Insets(0, side, 0, side));
        return TokenPresentation.role(tabs, "tab");
    }
}
