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
import javax.swing.DefaultListCellRenderer;
import javax.swing.Icon;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.UIManager;
import javax.swing.plaf.UIResource;

import com.formdev.flatlaf.FlatClientProperties;
import org.megamek.launcher.design.DesignTokens;
import org.megamek.launcher.design.swing.TokenButtons.Size;
import org.megamek.launcher.design.host.FontHandler;
import org.megamek.launcher.design.host.UIUtil;

/**
 * The design system's select for Swing (design system, Select): a combo box with the input face, or the
 * inline variant, a value with a dotted underline that sits in running text or a dense header. Both open the shared
 * popup list: hover and keyboard rows get a surface wash and the current value a check in the selection color, so that
 * color keeps meaning selected. The MegaMek look and feels draw every combo box this way; this adds the sizes, the inline variant and a
 * placeholder.
 */
public final class TokenSelects {
    /** Client property marking an inline select; set through {@link #inline(JComboBox)}. */
    static final String INLINE = "MegaMek.select.inline";
    static final String CHECK_COLOR = "MegaMek.select.checkColor";
    static final String ROW_HEIGHT = "MegaMek.select.rowHeight";
    static final String INLINE_FOREGROUND = "MegaMek.select.inlineForeground";
    static final String INLINE_HOVER_FOREGROUND = "MegaMek.select.inlineHoverForeground";
    static final String PLACEHOLDER_FOREGROUND = "MegaMek.select.placeholderForeground";
    /** Material Symbols code point of the check mark. */
    static final int ICON_CHECK = 0xE5CA;

    private TokenSelects() {
    }

    /** A select of {@code size} offering {@code items}, the first one chosen. */
    @SafeVarargs
    public static <E> JComboBox<E> select(Size size, E... items) {
        return TokenFields.size(new JComboBox<>(items), size);
    }

    /** An inline select offering {@code items}, the first one chosen. */
    @SafeVarargs
    public static <E> JComboBox<E> inline(E... items) {
        return inline(new JComboBox<>(items));
    }

    /**
     * Turns {@code combo} into an inline select: no box, a secondary value that brightens under the pointer and a
     * dotted underline. Keyboard focus still shows the ring.
     */
    public static <T extends JComboBox<?>> T inline(T combo) {
        combo.putClientProperty(INLINE, true);
        combo.putClientProperty(FlatClientProperties.STYLE, "padding: 0,2,0,0");
        combo.setBorder(new FieldBorders.InlineSelect());
        combo.setOpaque(false);
        return combo;
    }

    /** Shows {@code text} in the muted color while nothing is chosen. */
    public static <T extends JComboBox<?>> T placeholder(T combo, String text) {
        combo.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, text);
        combo.getAccessibleContext().setAccessibleName(text);
        return combo;
    }

    static boolean isInline(Component component) {
        return component instanceof JComponent c && Boolean.TRUE.equals(c.getClientProperty(INLINE));
    }

    static String placeholderOf(JComboBox<?> combo) {
        return combo.getClientProperty(FlatClientProperties.PLACEHOLDER_TEXT) instanceof String text ? text : null;
    }

    /**
     * The value renderer of the MegaMek look and feels: popup rows get a leading check slot, filled for the current
     * value, and the row height; the closed face shows the value, or the placeholder while nothing is chosen.
     */
    static final class CheckRenderer extends DefaultListCellRenderer implements UIResource {
        private final JComboBox<?> combo;
        private boolean row;

        CheckRenderer(JComboBox<?> combo) {
            this.combo = combo;
        }

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected,
              boolean cellHasFocus) {
            Object shown = (value == null && index < 0) ? placeholderOf(combo) : value;
            super.getListCellRendererComponent(list, shown, index, isSelected, false);
            row = index >= 0;
            if (row && !(value instanceof Icon)) {
                boolean current = value != null && value.equals(combo.getSelectedItem());
                setIcon(new Slot(current ? check() : null));
                setIconTextGap(gap());
            }
            return this;
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            int height = UIManager.getInt(ROW_HEIGHT);
            if (row && height > 0) {
                size.height = Math.max(size.height, UIUtil.scaleForGUI(height));
            }
            return size;
        }

        private static Icon check() {
            Color color = UIManager.getColor(CHECK_COLOR);
            return FontHandler.symbolIcon(ICON_CHECK, iconSize(), color == null ? Color.GRAY : color);
        }

        /** The width a row's check slot adds before the text. */
        static int slotWidth() {
            return iconSize() + gap();
        }

        private static int gap() {
            return UIUtil.scaleForGUI(Math.round(DesignTokens.shipped().space().get("sm")));
        }

        private static int iconSize() {
            return UIUtil.scaleForGUI(Size.COMPACT.iconSize());
        }

        /**
         * The check slot: a fixed square, the check centered in it or left empty, so every row's text lines up however
         * narrow the check glyph is.
         */
        private static final class Slot implements Icon {
            private final Icon mark;

            Slot(Icon mark) {
                this.mark = mark;
            }

            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                if (mark != null) {
                    mark.paintIcon(c, g, x + (getIconWidth() - mark.getIconWidth()) / 2,
                          y + (getIconHeight() - mark.getIconHeight()) / 2);
                }
            }

            @Override
            public int getIconWidth() {
                return iconSize();
            }

            @Override
            public int getIconHeight() {
                return mark == null ? iconSize() : Math.max(iconSize(), mark.getIconHeight());
            }
        }
    }
}
