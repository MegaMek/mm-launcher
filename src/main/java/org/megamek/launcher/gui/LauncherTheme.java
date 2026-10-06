/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek Launcher.
 *
 * MegaMek Launcher is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 */

package org.megamek.launcher.gui;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JToolTip;
import javax.swing.UIManager;
import javax.swing.plaf.UIResource;
import javax.swing.text.JTextComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics2D;
import java.awt.HeadlessException;
import java.awt.Toolkit;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.geom.Rectangle2D;

/**
 * Presentation primitives copied from MekHQ's Interstellar Map and ImmersiveControlStyle.
 * No campaign, game installation, or MegaMek preference dependencies belong here.
 */
final class LauncherTheme {
    static final Color BACKGROUND = new Color(5, 12, 21);
    static final Color PANEL = new Color(5, 13, 23);
    static final Color TEXT = new Color(218, 231, 235);
    static final Color MUTED = new Color(132, 153, 161);
    static final Color ACCENT = new Color(65, 210, 224);
    static final Color SELECTED = new Color(235, 166, 66);
    static final Color BORDER = new Color(35, 66, 82);
    static final Color CONTROL_BACKGROUND = new Color(12, 29, 42);
    static final Color INPUT_BACKGROUND = new Color(17, 64, 78);
    static final Color DISABLED_BACKGROUND = new Color(19, 29, 34);
    static final Color ACTIVE_BACKGROUND = new Color(18, 45, 56);
    static final Color PRESSED_BACKGROUND = new Color(33, 73, 82);
    static final Color BUTTON_BACKGROUND = new Color(15, 30, 43);
    static final Color BUTTON_ICON = new Color(125, 230, 238);
    static final Color BUTTON_BORDER = new Color(65, 210, 224, 105);
    static final Color POPUP_BACKGROUND = new Color(7, 16, 27);
    static final Color SCROLLBAR_THUMB = new Color(54, 101, 113);
    static final int CONTROL_HEIGHT = 30;

    private LauncherTheme() {
    }

    static void paintButton(Graphics2D graphics, int width, int height, GuiScale scale,
                            boolean enabled, boolean pressed, boolean hovered, boolean focused) {
        Color background = !enabled ? DISABLED_BACKGROUND
                : pressed ? PRESSED_BACKGROUND : hovered ? ACTIVE_BACKGROUND : BUTTON_BACKGROUND;
        Color border = hovered || focused ? BUTTON_ICON : BUTTON_BORDER;
        if (highContrast()) {
            background = uiColor(pressed || hovered ? "Button.select" : "Button.background", Color.BLACK);
            border = uiColor(focused ? "Focus.color" : "Button.foreground", Color.WHITE);
        }
        graphics.setPaint(background);
        graphics.fillRect(0, 0, width, height);
        paintOutline(graphics, width, height, scale, border);
    }

    static void paintOutline(Graphics2D graphics, int width, int height, GuiScale scale, Color border) {
        graphics.setPaint(border);
        float strokeWidth = Math.max(1f, scale.scaleForGUI(1f));
        double inset = strokeWidth / 2.0;
        graphics.setStroke(new BasicStroke(strokeWidth));
        graphics.draw(new Rectangle2D.Double(inset, inset,
                Math.max(0, width - strokeWidth), Math.max(0, height - strokeWidth)));
    }

    static Color buttonText(boolean primary, boolean enabled) {
        if (highContrast()) {
            return uiColor(enabled ? "Button.foreground" : "Button.disabledText",
                    enabled ? Color.WHITE : Color.LIGHT_GRAY);
        }
        return !enabled ? MUTED : TEXT;
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
        if (!highContrast()) {
            if (pane.getVerticalScrollBar() != null) {
                MapScrollBarStyle.apply(pane.getVerticalScrollBar(), scale);
            }
            if (pane.getHorizontalScrollBar() != null) {
                MapScrollBarStyle.apply(pane.getHorizontalScrollBar(), scale);
            }
        }
    }

    static JToolTip toolTip(JComponent component, GuiScale scale) {
        JToolTip tooltip = new JToolTip();
        tooltip.setComponent(component);
        tooltip.setOpaque(true);
        tooltip.setBackground(highContrast() ? uiColor("ToolTip.background", Color.BLACK) : POPUP_BACKGROUND);
        tooltip.setForeground(highContrast() ? uiColor("ToolTip.foreground", Color.WHITE) : TEXT);
        tooltip.setFont(component.getFont());
        tooltip.setBorder(javax.swing.BorderFactory.createCompoundBorder(
                javax.swing.BorderFactory.createLineBorder(
                        highContrast() ? uiColor("ToolTip.foreground", Color.WHITE) : BUTTON_BORDER,
                        Math.max(1, scale.scaleForGUI(1))),
                javax.swing.BorderFactory.createEmptyBorder(scale.scaleForGUI(5), scale.scaleForGUI(8),
                        scale.scaleForGUI(5), scale.scaleForGUI(8))));
        return tooltip;
    }

    static void styleContent(Component component, GuiScale scale) {
        if (component instanceof JScrollPane pane) {
            styleScrollPane(pane, scale);
            if (!highContrast()) {
                pane.getViewport().setBackground(PANEL);
                if (pane.getBorder() instanceof UIResource) {
                    pane.setBorder(javax.swing.BorderFactory.createLineBorder(BORDER, scale.scaleForGUI(1)));
                }
            }
        }
        if (!highContrast()) {
            // Preserve explicit selection/warning colors; replace only look-and-feel defaults.
            if (component instanceof JPanel && component.getBackground() instanceof UIResource) {
                component.setBackground(BACKGROUND);
            }
            if (component instanceof JLabel && component.getForeground() instanceof UIResource) {
                component.setForeground(TEXT);
            }
            if (component instanceof JList<?> list) {
                list.setBackground(PANEL);
                list.setForeground(TEXT);
                list.setSelectionBackground(ACTIVE_BACKGROUND);
                list.setSelectionForeground(ACCENT);
            }
            if (component instanceof JTextComponent text) {
                if (text.getBackground() instanceof UIResource) text.setBackground(INPUT_BACKGROUND);
                if (text.getForeground() instanceof UIResource) text.setForeground(TEXT);
                text.setCaretColor(ACCENT);
                text.setSelectionColor(ACTIVE_BACKGROUND);
                text.setSelectedTextColor(ACCENT);
            }
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) styleContent(child, scale);
        }
    }

    static boolean highContrast() {
        if (UIManager.getBoolean("Theme.highContrast") || UIManager.getBoolean("win.highContrast.on")) {
            return true;
        }
        try {
            return Boolean.TRUE.equals(Toolkit.getDefaultToolkit().getDesktopProperty("win.highContrast.on"));
        } catch (HeadlessException ignored) {
            return false;
        }
    }

    static Color uiColor(String key, Color fallback) {
        Color color = UIManager.getColor(key);
        return color == null ? fallback : color;
    }
}
