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

import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import javax.swing.JComponent;
import javax.swing.border.AbstractBorder;

/**
 * The panel frame as a border, for an existing container that keeps its own layout (design system,
 * Panels): the panel surface, a 1px edge and corner ticks, with the panel's padding as insets. A plain frame has the
 * edge only.
 * <p>
 * A border paints after its component, so the frame's surface would cover anything the component paints itself.
 * {@link #apply} makes the container transparent and lets the border paint the surface under its children; use it
 * only on containers that paint nothing of their own. {@link TokenPanel} adds the header, status and selection.
 */
public final class TokenFrameBorder extends AbstractBorder {
    private final TokenPanel.Padding padding;
    private final boolean ticked;

    private TokenFrameBorder(TokenPanel.Padding padding, boolean ticked) {
        this.padding = padding == null ? TokenPanel.Padding.REGULAR : padding;
        this.ticked = ticked;
    }

    /** The panel frame: surface, edge and ticks. */
    public static TokenFrameBorder panel(TokenPanel.Padding padding) {
        return new TokenFrameBorder(padding, true);
    }

    /** The plain frame: the edge alone, for a group inside a panel. */
    public static TokenFrameBorder plain(TokenPanel.Padding padding) {
        return new TokenFrameBorder(padding, false);
    }

    /** Frames {@code container} with {@code border}, making it transparent so the border's surface shows. */
    public static <T extends JComponent> T apply(T container, TokenFrameBorder border) {
        container.setOpaque(false);
        container.setBorder(border);
        return container;
    }

    @Override
    public void paintBorder(Component component, Graphics graphics, int x, int y, int width, int height) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.translate(x, y);
            boolean enabled = component.isEnabled();
            TokenFrame.paint(g, width, height, ticked ? TokenDialogPane.token("surface.panel") : null,
                  TokenDialogPane.token("border.subtle"), TokenFrame.line(),
                  ticked ? TokenDialogPane.token(enabled ? "border.strong" : "border.subtle") : null);
        } finally {
            g.dispose();
        }
    }

    @Override
    public Insets getBorderInsets(Component component, Insets insets) {
        int space = padding.pixels();
        insets.set(space, space, space, space);
        return insets;
    }

    @Override
    public boolean isBorderOpaque() {
        return ticked;
    }
}
