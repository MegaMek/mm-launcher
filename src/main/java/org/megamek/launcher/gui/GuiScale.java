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

import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsConfiguration;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;

/**
 * Scales application metrics in Swing's logical coordinate space. Java's per-monitor transform
 * supplies device DPI scaling; multiplying that transform into these metrics would scale twice.
 */
public final class GuiScale {
    public static final GuiScale DEFAULT = new GuiScale(1f);
    private final float factor;

    public GuiScale(float factor) {
        if (!Float.isFinite(factor) || factor < 0.5f || factor > 4f) {
            throw new IllegalArgumentException("GUI scale must be between 0.5 and 4");
        }
        this.factor = factor;
    }

    public int scaleForGUI(int value) {
        return Math.round(value * factor);
    }

    public float scaleForGUI(float value) {
        return value * factor;
    }

    public Dimension scaleForGUI(int width, int height) {
        return new Dimension(scaleForGUI(width), scaleForGUI(height));
    }

    public Insets insets(int top, int left, int bottom, int right) {
        return new Insets(scaleForGUI(top), scaleForGUI(left),
                scaleForGUI(bottom), scaleForGUI(right));
    }

    public Font font(Font base, int style, float points) {
        return base.deriveFont(style, scaleForGUI(points));
    }

    public static Rectangle usableBounds(GraphicsConfiguration configuration) {
        Rectangle bounds = new Rectangle(configuration.getBounds());
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        return new Rectangle(bounds.x + insets.left, bounds.y + insets.top,
                Math.max(1, bounds.width - insets.left - insets.right),
                Math.max(1, bounds.height - insets.top - insets.bottom));
    }

    public static Dimension fitWindow(Dimension requested, Rectangle workArea) {
        return new Dimension(Math.max(1, Math.min(requested.width, workArea.width * 94 / 100)),
                Math.max(1, Math.min(requested.height, workArea.height * 94 / 100)));
    }

    public static Rectangle fitImage(int imageWidth, int imageHeight, int width, int height) {
        if (imageWidth <= 0 || imageHeight <= 0) {
            throw new IllegalArgumentException("Image dimensions must be positive");
        }
        if (width <= 0 || height <= 0) {
            return new Rectangle();
        }
        double ratio = Math.min((double) width / imageWidth, (double) height / imageHeight);
        int fittedWidth = Math.min(width, (int) Math.round(imageWidth * ratio));
        int fittedHeight = Math.min(height, (int) Math.round(imageHeight * ratio));
        return new Rectangle((width - fittedWidth) / 2, (height - fittedHeight) / 2,
                fittedWidth, fittedHeight);
    }
}
