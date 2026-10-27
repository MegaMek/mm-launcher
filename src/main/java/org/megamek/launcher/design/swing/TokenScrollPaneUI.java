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
import java.awt.Insets;
import javax.swing.JComponent;
import javax.swing.plaf.ComponentUI;

import com.formdev.flatlaf.ui.FlatScrollPaneUI;
import com.formdev.flatlaf.ui.FlatUIUtils;

/**
 * Scroll panes of the MegaMek look and feels: they fill behind their scroll bars and the corner between them with the
 * color of the content they scroll, so a list's bars sit on the list's surface (design system,
 * Scrollbars).
 */
public class TokenScrollPaneUI extends FlatScrollPaneUI {
    public static ComponentUI createUI(JComponent c) {
        return new TokenScrollPaneUI();
    }

    @Override
    public void update(Graphics g, JComponent c) {
        Color content = TokenScrollBarUI.contentBackground(scrollpane);
        if (!c.isOpaque() || content == null || content.equals(c.getBackground())) {
            super.update(g, c);
            return;
        }
        FlatUIUtils.paintParentBackground(g, c);
        Insets insets = c.getInsets();
        g.setColor(content);
        g.fillRect(insets.left, insets.top, c.getWidth() - insets.left - insets.right,
              c.getHeight() - insets.top - insets.bottom);
        paint(g, c);
    }
}
