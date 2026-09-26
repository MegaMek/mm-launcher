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

import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JList;
import javax.swing.UIManager;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicComboBoxUI;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.HeadlessException;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Function;

/**
 * Launcher-local combo treatment that retains Swing's combo model, popup, keyboard actions, and
 * accessibility while avoiding an operating-system-painted white arrow segment.
 */
final class StyledComboBox<E> extends JComboBox<E> {
    static final Color FIELD_BACKGROUND = new Color(19, 39, 43);
    static final Color ARROW_BACKGROUND = new Color(28, 53, 57);
    static final Color BORDER = new Color(90, 119, 120);
    static final Color FOCUS = FirstLaunchPanel.GOLD;
    private final GuiScale scale;
    private Palette palette;
    private boolean focusListenerInstalled;
    private Function<? super E, String> displayText = value ->
            value == null ? "" : value.toString();

    StyledComboBox(GuiScale scale) {
        this.scale = scale;
        applyStyle();
    }

    StyledComboBox(E[] values, GuiScale scale) {
        super(values);
        this.scale = scale;
        applyStyle();
    }

    @Override
    public void updateUI() {
        // JComboBox invokes updateUI from its constructor before this class has initialized scale.
        super.updateUI();
        if (scale != null) applyStyle();
    }

    private void applyStyle() {
        palette = palette();
        setUI(new StyledComboBoxUI());
        setOpaque(true);
        setBackground(palette.background());
        setForeground(palette.foreground());
        setFont(scale.font(getFont(), Font.PLAIN, 13f));
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(palette.border(), scale.scaleForGUI(1)),
                BorderFactory.createEmptyBorder(scale.scaleForGUI(1),
                        scale.scaleForGUI(2), scale.scaleForGUI(1), 0)));
        setRenderer(new StyledRenderer());
        setFocusable(true);
        if (!focusListenerInstalled) {
            addFocusListener(new FocusAdapter() {
                @Override
                public void focusGained(FocusEvent event) {
                    updateBorder();
                }

                @Override
                public void focusLost(FocusEvent event) {
                    updateBorder();
                }
            });
            focusListenerInstalled = true;
        }
    }

    void setDisplayText(Function<? super E, String> displayText) {
        this.displayText = java.util.Objects.requireNonNull(displayText, "displayText");
        repaint();
    }

    private void updateBorder() {
        if (palette == null) return;
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(isFocusOwner()
                        ? palette.focus() : palette.border(),
                        scale.scaleForGUI(isFocusOwner() ? 2 : 1)),
                BorderFactory.createEmptyBorder(
                        scale.scaleForGUI(isFocusOwner() ? 0 : 1),
                        scale.scaleForGUI(isFocusOwner() ? 1 : 2),
                        scale.scaleForGUI(isFocusOwner() ? 0 : 1), 0)));
        repaint();
    }

    private final class StyledRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean selected, boolean focused) {
            super.getListCellRendererComponent(list, value, index, selected, focused);
            @SuppressWarnings("unchecked")
            E typedValue = (E) value;
            setText(displayText.apply(typedValue));
            setOpaque(true);
            setFont(scale.font(getFont(), Font.PLAIN, 13f));
            setBorder(BorderFactory.createEmptyBorder(scale.scaleForGUI(7),
                    scale.scaleForGUI(9), scale.scaleForGUI(7),
                    scale.scaleForGUI(9)));
            setBackground(selected ? palette.selection() : palette.background());
            setForeground(!StyledComboBox.this.isEnabled()
                    ? palette.disabled()
                    : selected ? palette.selectionForeground() : palette.foreground());
            return this;
        }
    }

    private final class StyledComboBoxUI extends BasicComboBoxUI {
        @Override
        protected JButton createArrowButton() {
            return new ArrowButton();
        }

        @Override
        public void paintCurrentValueBackground(Graphics graphics,
                                                java.awt.Rectangle bounds,
                                                boolean hasFocus) {
            graphics.setColor(palette.background());
            graphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
        }
    }

    private final class ArrowButton extends JButton {
        private boolean hovered;

        private ArrowButton() {
            setName("styledComboArrowButton");
            setUI(new BasicButtonUI());
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setFocusable(false);
            setRolloverEnabled(true);
            getAccessibleContext().setAccessibleName("Open choices");
            MouseAdapter hover = new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent event) {
                    hovered = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent event) {
                    hovered = false;
                    repaint();
                }
            };
            addMouseListener(hover);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D canvas = (Graphics2D) graphics.create();
            try {
                canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                Color fill = !isEnabled() ? palette.background()
                        : getModel().isPressed() || hovered
                        ? palette.selection() : palette.arrowBackground();
                canvas.setColor(fill);
                canvas.fillRect(0, 0, getWidth(), getHeight());
                canvas.setColor(palette.border());
                canvas.drawLine(0, 0, 0, getHeight());
                int width = scale.scaleForGUI(10);
                int height = scale.scaleForGUI(6);
                int x = (getWidth() - width) / 2;
                int y = (getHeight() - height) / 2;
                Color arrow = getModel().isPressed() || hovered
                        ? palette.selectionForeground() : palette.foreground();
                if (!isEnabled()) arrow = palette.disabled();
                canvas.setColor(arrow);
                canvas.fill(new Polygon(new int[]{x, x + width, x + width / 2},
                        new int[]{y, y, y + height}, 3));
                if (StyledComboBox.this.isFocusOwner()) {
                    canvas.setColor(palette.focus());
                    canvas.setStroke(new BasicStroke(scale.scaleForGUI(1f)));
                    canvas.drawLine(1, 1, getWidth() - 2, 1);
                    canvas.drawLine(1, getHeight() - 2, getWidth() - 2, getHeight() - 2);
                }
            } finally {
                canvas.dispose();
            }
        }
    }

    private static Palette palette() {
        if (!highContrast()) {
            return new Palette(FIELD_BACKGROUND, FirstLaunchPanel.TEXT,
                    FirstLaunchPanel.MUTED, ARROW_BACKGROUND,
                    HomeLaunchSplitButton.POPUP_SELECTION,
                    HomeLaunchSplitButton.POPUP_SELECTION_FOREGROUND, BORDER, FOCUS);
        }
        Color background = uiColor("ComboBox.background", Color.BLACK);
        Color foreground = uiColor("ComboBox.foreground", Color.WHITE);
        return new Palette(background, foreground,
                uiColor("ComboBox.disabledForeground", Color.LIGHT_GRAY), background,
                uiColor("ComboBox.selectionBackground", Color.WHITE),
                uiColor("ComboBox.selectionForeground", Color.BLACK),
                uiColor("ComboBox.foreground", Color.WHITE),
                uiColor("Focus.color", foreground));
    }

    private static boolean highContrast() {
        if (UIManager.getBoolean("Theme.highContrast")
                || UIManager.getBoolean("win.highContrast.on")) {
            return true;
        }
        try {
            return Boolean.TRUE.equals(
                    Toolkit.getDefaultToolkit().getDesktopProperty("win.highContrast.on"));
        } catch (HeadlessException ignored) {
            return false;
        }
    }

    private static Color uiColor(String key, Color fallback) {
        Color color = UIManager.getColor(key);
        return color == null ? fallback : color;
    }

    private record Palette(Color background, Color foreground, Color disabled,
                           Color arrowBackground, Color selection,
                           Color selectionForeground, Color border, Color focus) {
    }
}
