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

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Area;
import java.awt.geom.Rectangle2D;
import javax.swing.JComponent;
import javax.swing.UIManager;
import javax.swing.plaf.ComponentUI;

import com.formdev.flatlaf.ui.FlatTabbedPaneUI;
import com.formdev.flatlaf.ui.FlatUIUtils;
import com.formdev.flatlaf.util.UIScale;
import org.megamek.launcher.design.DesignTokens;

/**
 * Tabs with the design-system focus ring: a {@code focus.width} ring inside the selected tab while the tab strip has
 * keyboard focus. FlatLaf only tints the focused tab, which reads the same as hover. A tab's face and text ease between
 * states ({@link TokenMotion}).
 */
public class TokenTabbedPaneUI extends FlatTabbedPaneUI {
    private static final String BACKGROUND = "tab.background.";
    private static final String FOREGROUND = "tab.foreground.";
    public static ComponentUI createUI(JComponent c) {
        return new TokenTabbedPaneUI();
    }

    @Override
    protected Color getTabBackground(int tabPlacement, int tabIndex, boolean isSelected) {
        Color target = super.getTabBackground(tabPlacement, tabIndex, isSelected);
        return ease(BACKGROUND + tabIndex, target, tabIndex);
    }

    @Override
    protected Color getTabForeground(int tabPlacement, int tabIndex, boolean isSelected) {
        Color target = super.getTabForeground(tabPlacement, tabIndex, isSelected);
        return ease(FOREGROUND + tabIndex, target, tabIndex);
    }

    // FlatLaf skips the fill when the color is the pane's own, so a finished fade hands that very color back
    private Color ease(String key, Color target, int tabIndex) {
        Color color = TokenMotion.color(tabPane, key, target, TokenMotion.hover(getRolloverTab() == tabIndex));
        Color pane = tabPane.getBackground();
        return pane != null && pane.equals(color) ? pane : color;
    }

    @Override
    protected void paintTabBorder(Graphics g, int tabPlacement, int tabIndex, int x, int y, int w, int h,
          boolean isSelected) {
        super.paintTabBorder(g, tabPlacement, tabIndex, x, y, w, h, isSelected);
        if (isSelected && tabPane.isEnabled() && FlatUIUtils.isPermanentFocusOwner(tabPane)) {
            float ring = UIScale.scale(DesignTokens.shipped().size().get("focus.width"));
            Area area = new Area(new Rectangle2D.Float(x, y, w, h));
            area.subtract(new Area(new Rectangle2D.Float(x + ring, y + ring, w - ring * 2, h - ring * 2)));
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setColor(UIManager.getColor("Component.focusColor"));
                g2.fill(area);
            } finally {
                g2.dispose();
            }
        }
    }
}
