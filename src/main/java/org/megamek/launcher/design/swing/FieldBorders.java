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
import java.awt.Paint;

import com.formdev.flatlaf.ui.FlatRoundBorder;
import com.formdev.flatlaf.ui.FlatScrollPaneBorder;
import com.formdev.flatlaf.ui.FlatTextBorder;
import com.formdev.flatlaf.util.UIScale;

/**
 * FlatLaf's input borders with the design system's hover and read-only edges ({@link FieldEdge}). The MegaMek look and
 * feels install them by class name, so they stay public.
 */
public final class FieldBorders {
    private FieldBorders() {
    }

    /** Text fields, password fields and formatted text fields. */
    public static class Text extends FlatTextBorder {
        @Override
        protected Paint getBorderColor(Component c) {
            return FieldEdge.color(c, super.getBorderColor(c), isEnabled(c), isFocused(c));
        }
    }

    /** Spinners and combo boxes. */
    public static class Round extends FlatRoundBorder {
        @Override
        protected Paint getBorderColor(Component c) {
            return FieldEdge.color(c, super.getBorderColor(c), isEnabled(c), isFocused(c));
        }
    }

    /** Scroll panes; only those around a text area or other text get the input edges. */
    public static class ScrollPane extends FlatScrollPaneBorder {
        @Override
        protected Paint getBorderColor(Component c) {
            return FieldEdge.color(c, super.getBorderColor(c), isEnabled(c), isFocused(c));
        }
    }

    /**
     * The inline select ({@link TokenSelects#inline}): no box, only a dotted underline that turns strong under the
     * pointer. Keyboard focus draws the ring around the whole select instead.
     */
    public static class InlineSelect extends FlatRoundBorder {
        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            if (isFocused(c)) {
                super.paintBorder(c, g, x, y, width, height);
                return;
            }
            Paint color = isEnabled(c) ? FieldEdge.color(c, borderColor, true, false) : disabledBorderColor;
            if (color == null) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setPaint(color);
                int line = Math.max(1, Math.round(UIScale.scale(borderWidth)));
                int dash = UIScale.scale(2);
                int baseline = y + height - line - Math.round(UIScale.scale(getFocusWidth(c))) - dash;
                for (int dx = x; dx < x + width; dx += dash * 2) {
                    g2.fillRect(dx, baseline, Math.min(dash, x + width - dx), line);
                }
            } finally {
                g2.dispose();
            }
        }
    }
}
