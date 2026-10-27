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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicButtonUI;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LauncherThemeTest {
    private static final LauncherAppearance DARK = LauncherAppearance.DEFAULT;

    @AfterEach
    void restoreAppearance() throws Exception {
        SwingTestSupport.onEdt(() -> {
            LauncherAppearance.install(LauncherAppearanceSessionListener.testAppearance());
            return null;
        });
    }

    @Test
    void coloursAreDesignTokenRolesOfTheInstalledMode() throws Exception {
        SwingTestSupport.onEdt(() -> {
            LauncherAppearance.install(DARK);
            assertEquals(new Color(0x0E1314), LauncherTheme.BACKGROUND);
            assertEquals(new Color(0x141A1C), LauncherTheme.PANEL);
            assertEquals(new Color(0xF1F4F0), LauncherTheme.TEXT);
            assertEquals(new Color(0x82E2CE), LauncherTheme.ACCENT);
            assertEquals(new Color(0xF2C14E), LauncherTheme.WARNING);
            assertEquals(new Color(0xF0F2EE), LauncherTheme.PRIMARY_BACKGROUND);
            assertEquals(new Color(0x16201E), LauncherTheme.ON_PRIMARY);
            assertEquals(LauncherTheme.ON_PRIMARY, LauncherTheme.buttonText(true, true));
            assertEquals(LauncherTheme.TEXT, LauncherTheme.buttonText(false, true));
            assertEquals(LauncherTheme.ON_PRIMARY, LauncherTheme.buttonText(true, false));
            assertEquals(LauncherTheme.DISABLED_TEXT, LauncherTheme.buttonText(false, false));

            LauncherAppearance.install(DARK.withTheme(LauncherAppearance.Theme.LIGHT));
            assertEquals(new Color(0xECEFEE), LauncherTheme.BACKGROUND);
            assertEquals(new Color(0x16201E), LauncherTheme.TEXT);
            assertEquals(new Color(0x0A5C4C), LauncherTheme.ACCENT);
            assertEquals(new Color(0x1C2426), LauncherTheme.PRIMARY_BACKGROUND);
            assertEquals(LauncherTheme.TEXT.getRGB(), LauncherTheme.TEXT.hashCode());
            return null;
        });
    }

    @Test
    void hoverAndPressWashesAreOpaqueAndDistinct() {
        assertEquals(255, LauncherTheme.ACTIVE_BACKGROUND.getAlpha());
        assertEquals(255, LauncherTheme.PRESSED_BACKGROUND.getAlpha());
        assertNotEquals(LauncherTheme.BUTTON_BACKGROUND, LauncherTheme.ACTIVE_BACKGROUND);
        assertNotEquals(LauncherTheme.ACTIVE_BACKGROUND, LauncherTheme.PRESSED_BACKGROUND);
        assertEquals(0xFF808080, LauncherTheme.over(0xFF000000, 0x80FFFFFF));
    }

    @Test
    void buttonsPaintTokenFacesAndKeepTheirActions() throws Exception {
        SwingTestSupport.onEdt(() -> {
            JButton button = new FirstLaunchButton("", "fixtureButton", false, GuiScale.DEFAULT);
            button.setSize(240, 48);
            assertPixel(button, 20, 24, LauncherTheme.BUTTON_BACKGROUND);
            button.getModel().setRollover(true);
            assertPixel(button, 20, 24, LauncherTheme.ACTIVE_BACKGROUND);
            button.getModel().setArmed(true);
            button.getModel().setPressed(true);
            assertPixel(button, 20, 24, LauncherTheme.PRESSED_BACKGROUND);
            button.getModel().setArmed(false);
            button.getModel().setPressed(false);
            button.getModel().setRollover(false);
            button.setEnabled(false);
            assertPixel(button, 20, 24, LauncherTheme.DISABLED_BACKGROUND);
            button.setEnabled(true);
            AtomicInteger clicks = new AtomicInteger();
            button.addActionListener(event -> clicks.incrementAndGet());
            button.doClick(0);
            assertEquals(1, clicks.get());

            JButton primary = new FirstLaunchButton("", "fixturePrimary", true, GuiScale.DEFAULT);
            primary.setSize(240, 48);
            assertPixel(primary, 20, 24, LauncherTheme.PRIMARY_BACKGROUND);
            primary.setEnabled(false);
            assertPixel(primary, 20, 24, LauncherTheme.PRIMARY_DISABLED_BACKGROUND);
            return null;
        });
    }

    @Test
    void customPaintedControlsSurviveALookAndFeelChange() throws Exception {
        SwingTestSupport.onEdt(() -> {
            JPanel root = new JPanel();
            JButton button = new FirstLaunchButton("Install", "fixtureButton", true, GuiScale.DEFAULT);
            LauncherLink link = new LauncherLink("Release notes", "fixtureLink", GuiScale.DEFAULT);
            root.add(button);
            root.add(link);
            LauncherAppearance.install(DARK.withTheme(LauncherAppearance.Theme.LIGHT));
            SwingUtilities.updateComponentTreeUI(root);
            assertInstanceOf(BasicButtonUI.class, button.getUI());
            assertInstanceOf(BasicButtonUI.class, link.getUI());
            button.setSize(240, 48);
            assertPixel(button, 20, 24, LauncherTheme.PRIMARY_BACKGROUND);
            assertEquals(new Color(0x1C2426), LauncherTheme.PRIMARY_BACKGROUND);
            return null;
        });
    }

    @Test
    void comboUsesTheLookAndFeelSelectWithDisplayText() throws Exception {
        SwingTestSupport.onEdt(() -> {
            StyledComboBox<String> combo = new StyledComboBox<>(new String[]{"Milestone", "Weekly"},
                    GuiScale.DEFAULT);
            assertInstanceOf(com.formdev.flatlaf.ui.FlatComboBoxUI.class, combo.getUI(),
                    combo.getUI().getClass().getName());
            combo.setDisplayText(value -> value + " channel");
            Component row = combo.getRenderer().getListCellRendererComponent(
                    new JList<>(), "Weekly", 1, true, false);
            assertEquals("Weekly channel", ((JLabel) row).getText());
            assertEquals("Milestone", combo.getSelectedItem());
            assertTrue(combo.getPreferredSize().height >= LauncherTheme.CONTROL_HEIGHT);
            return null;
        });
    }

    @Test
    void checkBoxUsesTheLookAndFeelBoxAndKeepsAccessibility() throws Exception {
        SwingTestSupport.onEdt(() -> {
            MapCheckBox checkbox = new MapCheckBox("Check for updates", false, new GuiScale(1.5f));
            assertEquals("Check for updates", checkbox.getAccessibleContext().getAccessibleName());
            assertNull(checkbox.getIcon(), "the look and feel draws the box");
            assertEquals(12, checkbox.getIconTextGap());
            checkbox.doClick(0);
            assertTrue(checkbox.isSelected());
            return null;
        });
    }

    @Test
    void defaultFieldsFollowTheTokensWithoutOverwritingWarningColours() throws Exception {
        SwingTestSupport.onEdt(() -> {
            JPanel panel = new JPanel();
            JLabel normal = new JLabel("Description");
            JLabel warning = new JLabel("Warning");
            warning.setForeground(LauncherTheme.WARNING);
            JTextField field = new JTextField("Example");
            JList<String> list = new JList<>(new String[]{"One", "Two"});
            JScrollPane scroll = new JScrollPane(list);
            JScrollPane noHorizontal = new JScrollPane(new JLabel("Example"));
            noHorizontal.setHorizontalScrollBar(null);
            panel.add(normal);
            panel.add(warning);
            panel.add(field);
            panel.add(scroll);
            panel.add(noHorizontal);
            LauncherTheme.styleContent(panel, new GuiScale(1.5f));
            assertEquals(LauncherTheme.BACKGROUND, panel.getBackground());
            assertEquals(LauncherTheme.TEXT, normal.getForeground());
            assertEquals(LauncherTheme.WARNING, warning.getForeground());
            assertEquals(LauncherTheme.INPUT_BACKGROUND, field.getBackground());
            assertEquals(LauncherTheme.SELECTION_BACKGROUND, list.getSelectionBackground());
            assertEquals(LauncherTheme.PANEL, scroll.getViewport().getBackground());
            assertEquals(24, scroll.getVerticalScrollBar().getUnitIncrement());
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
        assertEquals(Integer.toHexString(expected.getRGB()), Integer.toHexString(image.getRGB(x, y)));
    }
}
