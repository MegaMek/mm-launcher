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
import java.awt.Graphics2D;

import org.megamek.launcher.design.DesignTokens;
import org.megamek.launcher.design.host.UIUtil;

/**
 * Paints the frame that dialog cards, status cards and panels share (design system, Panels): a surface,
 * a 1px edge and corner ticks, the edge along the top and bottom only when there are ticks (a paper roll). Every part is
 * optional, so the plain frame and the focused panel use the same code.
 */
final class TokenFrame {
    private TokenFrame() {
    }

    /** The {@code border} token, scaled; at least 1 pixel. */
    static int line() {
        return scaled("border");
    }

    /** The {@code tick.width} token, scaled; at least 1 pixel. */
    static int tickWidth() {
        return scaled("tick.width");
    }

    /** The {@code tick.panel} token, scaled. */
    static int tickLength() {
        return UIUtil.scaleForGUI(Math.round(DesignTokens.shipped().size().get("tick.panel")));
    }

    private static int scaled(String size) {
        return Math.max(1, UIUtil.scaleForGUI(Math.round(DesignTokens.shipped().size().get(size))));
    }

    /** The card frame of the active theme: the panel surface, a subtle edge and strong ticks. */
    static void paintCard(Graphics2D g, int w, int h) {
        paint(g, w, h, TokenDialogPane.token("surface.panel"), TokenDialogPane.token("border.subtle"), line(),
              TokenDialogPane.token("border.strong"));
    }

    /**
     * Paints a frame over {@code 0, 0, w, h}: {@code fill}, then an {@code edgeWidth} wide edge inside the bounds, then
     * the ticks at the four corners. A null color skips its part. A ticked frame is a paper roll: its edge runs along
     * the top and bottom only, and the ticks close the sides.
     */
    static void paint(Graphics2D g, int w, int h, Color fill, Color edge, int edgeWidth, Color ticks) {
        if (fill != null) {
            g.setColor(fill);
            g.fillRect(0, 0, w, h);
        }
        edge(g, w, h, edge, edgeWidth, ticks == null);
        if (ticks != null) {
            int stroke = tickWidth();
            int length = Math.min(tickLength(), Math.min(w, h) / 2);
            g.setColor(ticks);
            g.fillRect(0, 0, length, stroke);
            g.fillRect(0, 0, stroke, length);
            g.fillRect(w - length, 0, length, stroke);
            g.fillRect(w - stroke, 0, stroke, length);
            g.fillRect(0, h - stroke, length, stroke);
            g.fillRect(0, h - length, stroke, length);
            g.fillRect(w - length, h - stroke, length, stroke);
            g.fillRect(w - stroke, h - length, stroke, length);
        }
    }

    /** An {@code width} wide edge inside {@code 0, 0, w, h}: top and bottom, and the sides unless it's a paper roll. */
    static void edge(Graphics2D g, int w, int h, Color edge, int width, boolean sides) {
        if (edge == null) {
            return;
        }
        g.setColor(edge);
        g.fillRect(0, 0, w, width);
        g.fillRect(0, h - width, w, width);
        if (sides) {
            g.fillRect(0, width, width, h - 2 * width);
            g.fillRect(w - width, width, width, h - 2 * width);
        }
    }
}
