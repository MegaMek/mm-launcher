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

import org.megamek.launcher.design.DesignTokens;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.plaf.UIResource;
import javax.swing.text.JTextComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.geom.RoundRectangle2D;

/**
 * The launcher's colours and control painting, expressed in MegaMek design-token roles.
 * Every colour resolves its role in the installed {@link LauncherAppearance} each time it is
 * painted, so switching between the light and dark looks needs only a repaint.
 */
final class LauncherTheme {
    static final Color BACKGROUND = role("surface.base");
    static final Color PANEL = role("surface.panel");
    static final Color TEXT = role("text.primary");
    static final Color SECONDARY_TEXT = role("text.secondary");
    static final Color MUTED = role("text.muted");
    static final Color DISABLED_TEXT = role("text.disabled");
    static final Color ACCENT = role("accent.primary");
    static final Color FOCUS = role("focus.ring");
    static final Color WARNING = role("status.warning");
    static final Color DANGER = role("status.danger");
    static final Color SUCCESS = role("status.success");
    static final Color BORDER = role("border.subtle");
    static final Color CONTROL_BACKGROUND = role("surface.raised");
    static final Color INPUT_BACKGROUND = role("surface.input");
    static final Color DISABLED_BACKGROUND = role("surface.panel");
    static final Color ACTIVE_BACKGROUND = washed("surface.raised", 1);
    static final Color PRESSED_BACKGROUND = washed("surface.raised", 2);
    static final Color SELECTION_BACKGROUND = layered("surface.panel", "selected.background");
    static final Color BUTTON_BACKGROUND = role("surface.raised");
    static final Color PRIMARY_BACKGROUND = role("surface.inverse");
    static final Color PRIMARY_ACTIVE_BACKGROUND = layered("surface.inverse", "surface.hover");
    static final Color PRIMARY_DISABLED_BACKGROUND = layered("surface.raised", "surface.inverseDisabled");
    static final Color ON_PRIMARY = role("text.onInverse");
    static final Color BUTTON_ICON = role("border.strong");
    static final Color BUTTON_BORDER = role("border.control");
    static final Color POPUP_BACKGROUND = role("surface.popover");
    /** Dialog and form controls (selects, buttons) share the design system's control.md height. */
    static final int CONTROL_HEIGHT = tokenSize("control.md");
    /** Primary launch-sized buttons use control.lg. */
    static final int LARGE_CONTROL_HEIGHT = tokenSize("control.lg");

    private LauncherTheme() {
    }

    private static int tokenSize(String key) {
        Float size = DesignTokens.shipped().size().get(key);
        if (size == null) throw new IllegalStateException("Missing design token size " + key);
        return Math.round(size);
    }

    static int radius(GuiScale scale) {
        return Math.max(1, scale.radius("md"));
    }

    static void paintButton(Graphics2D graphics, int width, int height, GuiScale scale,
                            boolean enabled, boolean pressed, boolean hovered, boolean focused) {
        paintButton(graphics, width, height, scale, false, enabled, pressed, hovered, focused);
    }

    static void paintButton(Graphics2D graphics, int width, int height, GuiScale scale, boolean primary,
                            boolean enabled, boolean pressed, boolean hovered, boolean focused) {
        Color background = primary && !enabled ? PRIMARY_DISABLED_BACKGROUND
                : !enabled ? DISABLED_BACKGROUND
                : primary ? (pressed || hovered ? PRIMARY_ACTIVE_BACKGROUND : PRIMARY_BACKGROUND)
                : pressed ? PRESSED_BACKGROUND : hovered ? ACTIVE_BACKGROUND : BUTTON_BACKGROUND;
        Color border = primary ? (enabled ? PRIMARY_BACKGROUND : PRIMARY_DISABLED_BACKGROUND)
                : !enabled ? BORDER
                : hovered || pressed ? BUTTON_ICON : BUTTON_BORDER;
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setPaint(background);
        graphics.fill(shape(width, height, scale, 0));
        paintOutline(graphics, width, height, scale, border);
        if (focused && enabled) {
            paintFocus(graphics, width, height, scale);
        }
    }

    static void paintOutline(Graphics2D graphics, int width, int height, GuiScale scale, Color border) {
        float stroke = Math.max(1f, scale.size("border"));
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setPaint(border);
        graphics.setStroke(new BasicStroke(stroke));
        graphics.draw(shape(width, height, scale, stroke / 2f));
    }

    /** The focus ring, drawn just inside the control edge so it never clips. */
    static void paintFocus(Graphics2D graphics, int width, int height, GuiScale scale) {
        float stroke = Math.max(1f, scale.size("focus.width"));
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setPaint(FOCUS);
        graphics.setStroke(new BasicStroke(stroke));
        graphics.draw(shape(width, height, scale, stroke / 2f));
    }

    private static Shape shape(int width, int height, GuiScale scale, float inset) {
        float arc = 2f * radius(scale);
        return new RoundRectangle2D.Float(inset, inset, Math.max(0, width - 2 * inset),
                Math.max(0, height - 2 * inset), arc, arc);
    }

    static Color buttonText(boolean primary, boolean enabled) {
        return primary ? ON_PRIMARY : enabled ? TEXT : DISABLED_TEXT;
    }

    static void repaintOwnerOnFocusChange(JComponent component, JComponent owner) {
        // Transparent segments must repaint the whole shared outline, not just their own bounds.
        component.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent event) {
                owner.repaint();
            }

            @Override
            public void focusLost(FocusEvent event) {
                owner.repaint();
            }
        });
    }

    static JScrollPane scrollPane(Component view) {
        return scrollPane(view, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
    }

    static JScrollPane scrollPane(Component view, int verticalPolicy, int horizontalPolicy) {
        JScrollPane pane = new JScrollPane(view, verticalPolicy, horizontalPolicy);
        styleScrollPane(pane, GuiScale.DEFAULT);
        return pane;
    }

    static void styleScrollPane(JScrollPane pane, GuiScale scale) {
        int line = scrollLine(pane, scale);
        if (pane.getVerticalScrollBar() != null) {
            pane.getVerticalScrollBar().setUnitIncrement(line);
        }
        if (pane.getHorizontalScrollBar() != null) {
            pane.getHorizontalScrollBar().setUnitIncrement(line);
        }
    }

    /** One scroll step: a line of the design system's body text. */
    static int scrollLine(Component component, GuiScale scale) {
        Font base = javax.swing.UIManager.getFont("Label.font");
        if (base == null) base = component.getFont();
        return component.getFontMetrics(scale.font(base, "body")).getHeight();
    }

    static void styleContent(Component component, GuiScale scale) {
        if (component instanceof JScrollPane pane) {
            styleScrollPane(pane, scale);
            pane.getViewport().setBackground(PANEL);
            if (pane.getBorder() == null || pane.getBorder() instanceof UIResource) {
                pane.setBorder(BorderFactory.createLineBorder(BORDER, scale.size("border")));
            }
        }
        // Preserve explicit warning and status colours; replace only look-and-feel defaults.
        if (component instanceof JPanel && component.getBackground() instanceof UIResource) {
            component.setBackground(BACKGROUND);
        }
        if (component instanceof JLabel && component.getForeground() instanceof UIResource) {
            component.setForeground(TEXT);
        }
        if (component instanceof JList<?> list) {
            list.setBackground(PANEL);
            list.setForeground(TEXT);
            list.setSelectionBackground(SELECTION_BACKGROUND);
            list.setSelectionForeground(TEXT);
        }
        if (component instanceof JTextComponent text) {
            if (text.getBackground() instanceof UIResource) text.setBackground(INPUT_BACKGROUND);
            if (text.getForeground() instanceof UIResource) text.setForeground(TEXT);
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) styleContent(child, scale);
        }
    }

    private static Color role(String name) {
        return new RoleColor(() -> DesignTokens.shipped().color(name, LauncherAppearance.mode()));
    }

    private static Color layered(String base, String over) {
        return new RoleColor(() -> over(DesignTokens.shipped().color(base, LauncherAppearance.mode()),
                DesignTokens.shipped().color(over, LauncherAppearance.mode())));
    }

    private static Color washed(String base, int washes) {
        return new RoleColor(() -> {
            int color = DesignTokens.shipped().color(base, LauncherAppearance.mode());
            int hover = DesignTokens.shipped().color("surface.hover", LauncherAppearance.mode());
            for (int i = 0; i < washes; i++) color = over(color, hover);
            return color;
        });
    }

    /** {@code top} composited over the opaque {@code bottom}. */
    static int over(int bottom, int top) {
        int alpha = top >>> 24;
        int red = blend((bottom >> 16) & 0xFF, (top >> 16) & 0xFF, alpha);
        int green = blend((bottom >> 8) & 0xFF, (top >> 8) & 0xFF, alpha);
        int blue = blend(bottom & 0xFF, top & 0xFF, alpha);
        return 0xFF000000 | red << 16 | green << 8 | blue;
    }

    private static int blend(int bottom, int top, int alpha) {
        return (top * alpha + bottom * (255 - alpha) + 127) / 255;
    }

    /**
     * A colour whose value is a token role in the current appearance. {@link Color} derives its
     * channels, equality and paint context from {@link #getRGB()}, so Swing and Java2D see the
     * current value wherever the constant is used.
     */
    private static final class RoleColor extends Color {
        private final java.util.function.IntSupplier value;

        private RoleColor(java.util.function.IntSupplier value) {
            super(value.getAsInt(), true);
            this.value = value;
        }

        @Override
        public int getRGB() {
            return value.getAsInt();
        }

        @Override
        public int hashCode() {
            return getRGB();
        }
    }
}
