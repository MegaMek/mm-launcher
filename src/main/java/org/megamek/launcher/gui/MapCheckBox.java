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

import javax.swing.Icon;
import javax.swing.JCheckBox;
import javax.swing.JToolTip;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/** MekHQ ImmersiveCheckBox with launcher scaling and retained system high-contrast colors. */
final class MapCheckBox extends JCheckBox {
    private final GuiScale scale;

    MapCheckBox(String text, boolean selected, GuiScale scale) {
        super(text, selected);
        this.scale = scale;
        setOpaque(false);
        setForeground(LauncherTheme.highContrast()
                ? LauncherTheme.uiColor("CheckBox.foreground", Color.WHITE) : LauncherTheme.TEXT);
        setFont(getFont().deriveFont(Font.PLAIN));
        setFocusPainted(false);
        Icon icon = new CheckBoxIcon();
        setIcon(icon);
        setSelectedIcon(icon);
        setDisabledIcon(icon);
        setDisabledSelectedIcon(icon);
        setRolloverIcon(icon);
        setRolloverSelectedIcon(icon);
        setRolloverEnabled(true);
        setIconTextGap(scale.scaleForGUI(8));
    }

    @Override
    public JToolTip createToolTip() {
        return LauncherTheme.toolTip(this, scale);
    }

    private final class CheckBoxIcon implements Icon {
        private final int size = scale.scaleForGUI(18);

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            boolean enabled = isEnabled();
            boolean selected = isSelected();
            boolean highlighted = getModel().isRollover() || hasFocus();
            boolean contrast = LauncherTheme.highContrast();
            Color background = contrast
                    ? LauncherTheme.uiColor("CheckBox.background", Color.BLACK) : LauncherTheme.CONTROL_BACKGROUND;
            Color selection = contrast
                    ? LauncherTheme.uiColor("CheckBox.foreground", Color.WHITE) : LauncherTheme.SELECTED;
            Color border = contrast ? selection : selected ? LauncherTheme.SELECTED.brighter()
                    : highlighted ? LauncherTheme.ACCENT : LauncherTheme.BORDER;
            Graphics2D canvas = (Graphics2D) graphics.create();
            try {
                canvas.translate(x, y);
                canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                canvas.setComposite(AlphaComposite.SrcOver.derive(enabled ? 1.0f : 0.45f));
                canvas.setColor(selected ? selection : background);
                canvas.fillRect(1, 1, size - 2, size - 2);
                canvas.setColor(border);
                canvas.setStroke(new BasicStroke(scale.scaleForGUI(1.5f)));
                canvas.drawRect(1, 1, size - 3, size - 3);
                if (selected) {
                    canvas.setColor(background);
                    canvas.setStroke(new BasicStroke(scale.scaleForGUI(2.2f),
                            BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    canvas.drawLine(size / 4, size / 2, size * 2 / 5, size * 2 / 3);
                    canvas.drawLine(size * 2 / 5, size * 2 / 3, size * 3 / 4, size / 3);
                }
            } finally {
                canvas.dispose();
            }
        }

        @Override public int getIconWidth() {
            return size;
        }

        @Override public int getIconHeight() {
            return size;
        }
    }
}
