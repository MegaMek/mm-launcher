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

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JScrollBar;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;

/** MekHQ ImmersiveScrollBarStyle with launcher scaling and both scrollbar orientations. */
final class MapScrollBarStyle extends BasicScrollBarUI {
    private final GuiScale scale;
    private final int width;

    private MapScrollBarStyle(GuiScale scale) {
        this.scale = scale;
        width = scale.scaleForGUI(10);
    }

    static void apply(JScrollBar scrollbar, GuiScale scale) {
        scrollbar.setUI(new MapScrollBarStyle(scale));
        boolean vertical = scrollbar.getOrientation() == JScrollBar.VERTICAL;
        int width = scale.scaleForGUI(10);
        scrollbar.setPreferredSize(vertical ? new Dimension(width, 0) : new Dimension(0, width));
        scrollbar.setUnitIncrement(scale.scaleForGUI(16));
        scrollbar.setOpaque(true);
        scrollbar.setBackground(LauncherTheme.PANEL);
        scrollbar.setBorder(BorderFactory.createMatteBorder(
                vertical ? 0 : 1, vertical ? 1 : 0, 0, 0, LauncherTheme.BORDER));
    }

    @Override
    protected void configureScrollBarColors() {
        trackColor = LauncherTheme.PANEL;
        thumbColor = LauncherTheme.SCROLLBAR_THUMB;
        thumbHighlightColor = LauncherTheme.BUTTON_ICON;
        thumbDarkShadowColor = LauncherTheme.BORDER;
        thumbLightShadowColor = LauncherTheme.BORDER;
    }

    @Override
    protected JButton createDecreaseButton(int orientation) {
        return hiddenButton();
    }

    @Override
    protected JButton createIncreaseButton(int orientation) {
        return hiddenButton();
    }

    @Override
    protected void paintTrack(Graphics graphics, JComponent component, Rectangle bounds) {
        graphics.setColor(LauncherTheme.PANEL);
        graphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
    }

    @Override
    protected void paintThumb(Graphics graphics, JComponent component, Rectangle bounds) {
        if (bounds.isEmpty() || !scrollbar.isEnabled()) return;
        Graphics2D canvas = (Graphics2D) graphics.create();
        try {
            canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            canvas.setColor(isThumbRollover() ? LauncherTheme.BUTTON_ICON : LauncherTheme.SCROLLBAR_THUMB);
            int inset = Math.max(1, scale.scaleForGUI(2));
            int arc = Math.max(2, scale.scaleForGUI(3));
            canvas.fillRoundRect(bounds.x + inset, bounds.y + inset,
                    Math.max(1, bounds.width - inset * 2), Math.max(1, bounds.height - inset * 2), arc, arc);
        } finally {
            canvas.dispose();
        }
    }

    @Override
    protected Dimension getMinimumThumbSize() {
        int length = scale.scaleForGUI(28);
        return scrollbar.getOrientation() == JScrollBar.VERTICAL
                ? new Dimension(width, length) : new Dimension(length, width);
    }

    private static JButton hiddenButton() {
        JButton button = new JButton();
        Dimension size = new Dimension(0, 0);
        button.setMinimumSize(size);
        button.setPreferredSize(size);
        button.setMaximumSize(size);
        return button;
    }
}
