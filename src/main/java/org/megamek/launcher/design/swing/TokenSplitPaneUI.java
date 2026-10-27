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
import java.awt.geom.Rectangle2D;
import javax.swing.JComponent;
import javax.swing.JSplitPane;
import javax.swing.UIManager;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.BasicSplitPaneDivider;
import javax.swing.plaf.basic.BasicSplitPaneUI;

import com.formdev.flatlaf.ui.FlatSplitPaneUI;
import com.formdev.flatlaf.util.UIScale;

/**
 * Split panes of the MegaMek look and feels (design system, Split panes): the divider is a subtle line
 * in the middle of a wider grab area, and turns into a thicker accent line under the pointer and while dragged.
 */
public class TokenSplitPaneUI extends FlatSplitPaneUI {
    /** Client property freezing the divider in its hover look, for the design gallery. */
    static final String FROZEN_HOVER = "MegaMek.splitPane.frozenHover";
    static final String LINE_COLOR = "MegaMek.splitPane.lineColor";
    static final String ACTIVE_COLOR = "MegaMek.splitPane.activeColor";
    static final String LINE_WIDTH = "MegaMek.splitPane.lineWidth";
    static final String ACTIVE_WIDTH = "MegaMek.splitPane.activeWidth";

    public static ComponentUI createUI(JComponent c) {
        return new TokenSplitPaneUI();
    }

    @Override
    public BasicSplitPaneDivider createDefaultDivider() {
        return new LineDivider(this);
    }

    /** The divider: a line along its middle, accent while the pointer is over it or it is dragged. */
    protected class LineDivider extends FlatSplitPaneDivider {
        protected LineDivider(BasicSplitPaneUI ui) {
            super(ui);
        }

        private boolean active() {
            return dragger != null || isMouseOver() || Boolean.TRUE.equals(splitPane.getClientProperty(FROZEN_HOVER));
        }

        @Override
        protected void paintStyle(Graphics g, int x, int y, int width, int height) {
            boolean active = splitPane.isEnabled() && active();
            Color color = UIManager.getColor(active ? ACTIVE_COLOR : LINE_COLOR);
            if (color == null) {
                super.paintStyle(g, x, y, width, height);
                return;
            }
            float line = UIScale.scale((float) Math.max(1, UIManager.getInt(active ? ACTIVE_WIDTH : LINE_WIDTH)));
            boolean vertical = splitPane.getOrientation() == JSplitPane.VERTICAL_SPLIT;
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setColor(color);
                g2.fill(vertical
                      ? new Rectangle2D.Float(x, y + (height - line) / 2f, width, line)
                      : new Rectangle2D.Float(x + (width - line) / 2f, y, line, height));
            } finally {
                g2.dispose();
            }
        }

        // FlatLaf repaints on these only when it has hover colors; the line changes in any case
        @Override
        protected void setMouseOver(boolean mouseOver) {
            super.setMouseOver(mouseOver);
            repaint();
        }

        @Override
        protected void prepareForDragging() {
            super.prepareForDragging();
            repaint();
        }

        @Override
        protected void finishDraggingTo(int location) {
            super.finishDraggingTo(location);
            repaint();
        }
    }
}
