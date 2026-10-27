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
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.geom.Rectangle2D;
import javax.swing.UIManager;
import javax.swing.border.AbstractBorder;
import javax.swing.border.TitledBorder;
import javax.swing.plaf.UIResource;

import com.formdev.flatlaf.util.UIScale;

/**
 * A panel border that is a section header (design system, Sections): the upper-case title and its line
 * across the top, and the content below, flush with the title. Made by {@link TokenSections#titled}. The title's
 * position and justification are ignored; a title font or color set on the border replaces the theme's.
 */
public class TokenTitledBorder extends TitledBorder {
    public TokenTitledBorder(String title) {
        super(title);
    }

    @Override
    public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
        // The fields, not the getters: those fall back to the legacy TitledBorder keys
        Color color = titleColor != null ? titleColor : TokenSections.titleColor(c, false);
        TokenSections.paintHeader(g, c, getTitle(), titleFont != null ? titleFont : TokenSections.titleFont(c), color,
              x, y, width, TokenSections.height());
    }

    @Override
    public Insets getBorderInsets(Component c, Insets insets) {
        insets.set(TokenSections.height(), 0, 0, 0);
        return insets;
    }

    @Override
    public boolean isBorderOpaque() {
        return false;
    }

    @Override
    public Dimension getMinimumSize(Component c) {
        return new Dimension(TokenSections.minimumWidth(getTitle(), c), TokenSections.height());
    }

    @Override
    public int getBaseline(Component c, int width, int height) {
        return -1;
    }

    /**
     * The theme's {@code TitledBorder.border}: what legacy titled borders draw around their content, here only the
     * line through the middle of their title. It starts past the stub the JDK leaves before the title.
     */
    public static class LegacyLine extends AbstractBorder implements UIResource {
        /** The JDK's gap between the border's edge and the title's clip, in unscaled pixels as the JDK uses them. */
        private static final int STUB = 3;

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            Color color = UIManager.getColor(TokenSections.LINE_COLOR);
            if (color == null || width <= STUB) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setColor(color);
                boolean leftToRight = c == null || c.getComponentOrientation().isLeftToRight();
                g2.fill(new Rectangle2D.Float(leftToRight ? x + STUB : x, y, width - STUB, UIScale.scale(1f)));
            } finally {
                g2.dispose();
            }
        }

        @Override
        public Insets getBorderInsets(Component c, Insets insets) {
            insets.set(1, 0, 0, 0);
            return insets;
        }
    }
}
