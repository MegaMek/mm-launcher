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

import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.UIManager;
import javax.swing.plaf.basic.BasicComboBoxUI;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LauncherThemeTest {
    @Test
    void paletteMatchesTheMekHqMapRatherThanThePreviousLauncherSkin() {
        assertEquals(new Color(5, 12, 21), LauncherTheme.BACKGROUND);
        assertEquals(new Color(5, 13, 23), LauncherTheme.PANEL);
        assertEquals(new Color(218, 231, 235), LauncherTheme.TEXT);
        assertEquals(new Color(65, 210, 224), LauncherTheme.ACCENT);
        assertEquals(new Color(235, 166, 66), LauncherTheme.SELECTED);
        assertEquals(new Color(35, 66, 82), LauncherTheme.BORDER);
        assertEquals(new Color(12, 29, 42), LauncherTheme.CONTROL_BACKGROUND);
        assertEquals(new Color(17, 64, 78), LauncherTheme.INPUT_BACKGROUND);
        assertEquals(new Color(18, 45, 56), LauncherTheme.ACTIVE_BACKGROUND);
        assertEquals(LauncherTheme.TEXT, LauncherTheme.buttonText(true, true));
        assertEquals(LauncherTheme.TEXT, LauncherTheme.buttonText(false, true));
    }

    @Test
    void buttonsUseTheMapsFlatRectangularSurfaceAndKeepTheirActions() throws Exception {
        SwingTestSupport.onEdt(() -> {
            JButton button = new FirstLaunchButton("", "fixtureButton", true, GuiScale.DEFAULT);
            button.setSize(240, 48);
            assertPixel(button, 3, 3, LauncherTheme.BUTTON_BACKGROUND);
            assertPixel(button, 30, 38, LauncherTheme.BUTTON_BACKGROUND);
            button.getModel().setRollover(true);
            assertPixel(button, 3, 3, LauncherTheme.ACTIVE_BACKGROUND);
            button.getModel().setArmed(true);
            button.getModel().setPressed(true);
            assertPixel(button, 3, 3, LauncherTheme.PRESSED_BACKGROUND);
            button.getModel().setArmed(false);
            button.getModel().setPressed(false);
            button.setEnabled(false);
            assertPixel(button, 3, 3, LauncherTheme.DISABLED_BACKGROUND);
            button.setEnabled(true);
            AtomicInteger clicks = new AtomicInteger();
            button.addActionListener(event -> clicks.incrementAndGet());
            button.doClick(0);
            assertEquals(1, clicks.get());
            return null;
        });
    }

    @Test
    void comboUsesMapInputsAndPopupSelectionWithoutChangingTheSelectedValue() throws Exception {
        SwingTestSupport.onEdt(() -> {
            StyledComboBox<String> combo = new StyledComboBox<>(new String[]{"Milestone", "Weekly"},
                    GuiScale.DEFAULT);
            assertEquals(LauncherTheme.INPUT_BACKGROUND, combo.getBackground());
            assertEquals("Milestone", combo.getSelectedItem());
            Component row = combo.getRenderer().getListCellRendererComponent(
                    new JList<>(), "Weekly", 1, true, false);
            assertEquals(LauncherTheme.ACTIVE_BACKGROUND, row.getBackground());
            assertEquals(LauncherTheme.ACCENT, row.getForeground());
            assertEquals("Milestone", combo.getSelectedItem());
            combo.setEnabled(false);
            assertEquals(LauncherTheme.DISABLED_BACKGROUND, combo.getBackground());
            combo.setEnabled(true);
            assertEquals(LauncherTheme.INPUT_BACKGROUND, combo.getBackground());
            assertEquals(LauncherTheme.POPUP_BACKGROUND, combo.createToolTip().getBackground());
            return null;
        });
    }

    @Test
    void focusedComboValueDoesNotRevertToTheNativeSelectionBackground() throws Exception {
        SwingTestSupport.onEdt(() -> {
            StyledComboBox<String> combo = new StyledComboBox<>(new String[]{"Milestone"}, GuiScale.DEFAULT);
            BasicComboBoxUI ui = assertInstanceOf(BasicComboBoxUI.class, combo.getUI());
            BufferedImage image = new BufferedImage(240, 32, BufferedImage.TYPE_INT_ARGB);
            Rectangle bounds = new Rectangle(0, 0, 240, 32);
            Graphics2D graphics = image.createGraphics();
            try {
                ui.paintCurrentValueBackground(graphics, bounds, true);
                ui.paintCurrentValue(graphics, bounds, true);
                assertEquals(LauncherTheme.INPUT_BACKGROUND.getRGB(), image.getRGB(2, 2));
                combo.setEnabled(false);
                ui.paintCurrentValueBackground(graphics, bounds, true);
                ui.paintCurrentValue(graphics, bounds, true);
                assertEquals(LauncherTheme.DISABLED_BACKGROUND.getRGB(), image.getRGB(2, 2));
            } finally {
                graphics.dispose();
            }
            return null;
        });
    }

    @Test
    void copiedCheckBoxKeepsSelectionAccessibilityAndScaledAmberIcon() throws Exception {
        SwingTestSupport.onEdt(() -> {
            MapCheckBox checkbox = new MapCheckBox("Check for updates", false, new GuiScale(1.5f));
            assertEquals("Check for updates", checkbox.getAccessibleContext().getAccessibleName());
            assertEquals(27, checkbox.getIcon().getIconWidth());
            assertEquals(12, checkbox.getIconTextGap());
            checkbox.doClick(0);
            assertTrue(checkbox.isSelected());
            BufferedImage image = new BufferedImage(27, 27, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            try {
                checkbox.getIcon().paintIcon(checkbox, graphics, 0, 0);
            } finally {
                graphics.dispose();
            }
            assertEquals(LauncherTheme.SELECTED.getRGB(), image.getRGB(4, 4));
            checkbox.setEnabled(false);
            checkbox.doClick(0);
            assertTrue(checkbox.isSelected());
            return null;
        });
    }

    @Test
    void scrollbarAndDefaultFieldsFollowTheMapWithoutOverwritingWarningColors() throws Exception {
        SwingTestSupport.onEdt(() -> {
            JPanel panel = new JPanel();
            JLabel normal = new JLabel("Description");
            JLabel warning = new JLabel("Warning");
            warning.setForeground(LauncherTheme.SELECTED);
            JTextField field = new JTextField("Example");
            JList<String> list = new JList<>(new String[]{"One", "Two"});
            JScrollPane scroll = new JScrollPane(list);
            panel.add(normal);
            panel.add(warning);
            panel.add(field);
            panel.add(scroll);
            LauncherTheme.styleContent(panel, new GuiScale(1.5f));
            assertEquals(LauncherTheme.BACKGROUND, panel.getBackground());
            assertEquals(LauncherTheme.TEXT, normal.getForeground());
            assertEquals(LauncherTheme.SELECTED, warning.getForeground());
            assertEquals(LauncherTheme.INPUT_BACKGROUND, field.getBackground());
            assertEquals(LauncherTheme.ACTIVE_BACKGROUND, list.getSelectionBackground());
            assertInstanceOf(MapScrollBarStyle.class, scroll.getVerticalScrollBar().getUI());
            assertInstanceOf(MapScrollBarStyle.class, scroll.getHorizontalScrollBar().getUI());
            assertEquals(15, scroll.getVerticalScrollBar().getPreferredSize().width);
            assertEquals(15, scroll.getHorizontalScrollBar().getPreferredSize().height);
            return null;
        });
    }

    @Test
    void horizontalScrollbarCanBeAbsentAsItIsInComboPopups() throws Exception {
        SwingTestSupport.onEdt(() -> {
            JScrollPane scroll = new JScrollPane(new JLabel("Example"));
            scroll.setHorizontalScrollBar(null);
            LauncherTheme.styleScrollPane(scroll, GuiScale.DEFAULT);
            assertInstanceOf(MapScrollBarStyle.class, scroll.getVerticalScrollBar().getUI());
            return null;
        });
    }

    @Test
    void copiedControlsRespectSystemHighContrastInsteadOfForcingMapColors() throws Exception {
        SwingTestSupport.onEdt(() -> {
            Object contrast = UIManager.get("Theme.highContrast");
            Object buttonBackground = UIManager.get("Button.background");
            Object buttonForeground = UIManager.get("Button.foreground");
            Object checkboxForeground = UIManager.get("CheckBox.foreground");
            try {
                UIManager.put("Theme.highContrast", true);
                UIManager.put("Button.background", Color.BLACK);
                UIManager.put("Button.foreground", Color.WHITE);
                UIManager.put("CheckBox.foreground", Color.WHITE);
                JButton button = new FirstLaunchButton("", "contrastButton", true, GuiScale.DEFAULT);
                button.setSize(160, 36);
                assertPixel(button, 3, 3, Color.BLACK);
                assertEquals(Color.WHITE, LauncherTheme.buttonText(true, true));
                assertEquals(Color.WHITE, new MapCheckBox("Check", false, GuiScale.DEFAULT).getForeground());
                JScrollPane scroll = new JScrollPane(new JLabel("Example"));
                Color viewportBackground = scroll.getViewport().getBackground();
                LauncherTheme.styleContent(scroll, GuiScale.DEFAULT);
                assertFalse(scroll.getVerticalScrollBar().getUI() instanceof MapScrollBarStyle);
                assertEquals(viewportBackground, scroll.getViewport().getBackground());
            } finally {
                UIManager.put("Theme.highContrast", contrast);
                UIManager.put("Button.background", buttonBackground);
                UIManager.put("Button.foreground", buttonForeground);
                UIManager.put("CheckBox.foreground", checkboxForeground);
            }
            return null;
        });
    }

    private static void assertPixel(JButton button, int x, int y, Color expected) {
        BufferedImage image = new BufferedImage(button.getWidth(), button.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            button.paint(graphics);
        } finally {
            graphics.dispose();
        }
        assertEquals(expected.getRGB(), image.getRGB(x, y));
    }
}
