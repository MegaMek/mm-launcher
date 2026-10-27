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

import javax.swing.AbstractButton;
import javax.swing.UIManager;
import javax.swing.plaf.basic.BasicGraphicsUtils;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;

/**
 * Keeps a token button's framing label short. In game mode the label is upper-cased, so the
 * value it frames (a version, channel or count) is shown separately in sentence case: either
 * as a detail line under the label or as a small badge after it.
 */
final class ButtonDetail {
    static final String DETAIL_PROPERTY = "launcher.buttonDetail";
    static final String BADGE_PROPERTY = "launcher.buttonBadge";

    private ButtonDetail() {
    }

    static void setDetail(AbstractButton button, String detail) {
        update(button, DETAIL_PROPERTY, detail);
    }

    static String detail(AbstractButton button) {
        return value(button, DETAIL_PROPERTY);
    }

    static void setBadge(AbstractButton button, String badge) {
        update(button, BADGE_PROPERTY, badge);
    }

    static String badge(AbstractButton button) {
        return value(button, BADGE_PROPERTY);
    }

    private static void update(AbstractButton button, String property, String text) {
        button.putClientProperty(property, text == null || text.isBlank() ? null : text);
        button.revalidate();
        button.repaint();
    }

    private static String value(AbstractButton button, String property) {
        return button.getClientProperty(property) instanceof String text ? text : null;
    }

    static Dimension grow(AbstractButton button, GuiScale scale, Dimension preferred) {
        String detail = detail(button);
        String badge = badge(button);
        int width = preferred.width;
        int height = preferred.height;
        if (detail != null) {
            FontMetrics metrics = button.getFontMetrics(detailFont(button, scale));
            Insets insets = button.getInsets();
            // The second line takes part of the vertical padding, keeping two-line buttons compact
            int spare = Math.max(0, insets.top + insets.bottom - 2 * scale.scaleForGUI(6));
            height += Math.max(0, metrics.getHeight() + gap(scale) - spare);
            width = Math.max(width, metrics.stringWidth(detail) + insets.left + insets.right);
        }
        if (badge != null) {
            width += badgeGap(scale) + badgeWidth(button, scale, badge);
        }
        return new Dimension(width, height);
    }

    /** Paints the label (with its mnemonic), then the detail line or badge it frames. */
    static void paint(Graphics2D graphics, AbstractButton button, GuiScale scale,
                      Rectangle textRectangle, String label, Color color) {
        String detail = detail(button);
        String badge = badge(button);
        Font detailFont = detailFont(button, scale);
        FontMetrics detailMetrics = button.getFontMetrics(detailFont);
        int lift = detail == null ? 0 : (detailMetrics.getHeight() + gap(scale)) / 2;
        int badgeWidth = badge == null ? 0 : badgeWidth(button, scale, badge);
        int shift = badge == null ? 0 : (badgeGap(scale) + badgeWidth) / 2;
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setColor(color);
        FontMetrics labelMetrics = graphics.getFontMetrics();
        int x = textRectangle.x - shift;
        int y = textRectangle.y - lift;
        BasicGraphicsUtils.drawStringUnderlineCharAt(graphics, label,
                button.getDisplayedMnemonicIndex(), x, y + labelMetrics.getAscent());
        if (detail != null) {
            graphics.setFont(detailFont);
            graphics.setColor(muted(color));
            int detailWidth = detailMetrics.stringWidth(detail);
            int detailX = Math.max(button.getInsets().left,
                    textRectangle.x + (textRectangle.width - detailWidth) / 2);
            graphics.drawString(detail, detailX,
                    y + textRectangle.height + gap(scale) + detailMetrics.getAscent());
        }
        if (badge != null) {
            Font badgeFont = badgeFont(button, scale);
            FontMetrics badgeMetrics = button.getFontMetrics(badgeFont);
            int badgeHeight = badgeMetrics.getHeight() + scale.scaleForGUI(2);
            int badgeX = x + textRectangle.width + badgeGap(scale);
            int badgeY = y + (textRectangle.height - badgeHeight) / 2;
            Color accent = button.isEnabled() ? LauncherTheme.ACCENT : color;
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setColor(accent);
            graphics.drawRoundRect(badgeX, badgeY, badgeWidth - 1, badgeHeight - 1,
                    badgeHeight, badgeHeight);
            graphics.setFont(badgeFont);
            graphics.drawString(badge, badgeX + (badgeWidth - badgeMetrics.stringWidth(badge)) / 2,
                    badgeY + (badgeHeight - badgeMetrics.getHeight()) / 2 + badgeMetrics.getAscent());
        }
    }

    private static Color muted(Color color) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(),
                Math.round(color.getAlpha() * 0.78f));
    }

    private static Font detailFont(AbstractButton button, GuiScale scale) {
        return scale.font(baseFont(button), Font.PLAIN, 12f);
    }

    private static Font badgeFont(AbstractButton button, GuiScale scale) {
        return scale.font(baseFont(button), Font.PLAIN, 11f);
    }

    private static Font baseFont(AbstractButton button) {
        Font font = UIManager.getFont("Label.font");
        return font == null ? button.getFont() : font;
    }

    private static int badgeWidth(AbstractButton button, GuiScale scale, String badge) {
        return button.getFontMetrics(badgeFont(button, scale)).stringWidth(badge)
                + scale.scaleForGUI(14);
    }

    private static int gap(GuiScale scale) {
        return scale.scaleForGUI(2);
    }

    private static int badgeGap(GuiScale scale) {
        return scale.scaleForGUI(8);
    }
}
