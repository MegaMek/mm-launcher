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

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.megamek.launcher.onboarding.Product;
import org.megamek.launcher.registry.InstallationRecord;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JMenuItem;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.GraphicsEnvironment;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HomeLaunchSplitButtonTest {
    @Test
    @org.junit.jupiter.api.Tag("native-gui")
    void alternativesOpenAboveWhenTheScreenHasNoRoomBelow() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        JFrame frame = new JFrame();
        HomeLaunchSplitButton[] holder = new HomeLaunchSplitButton[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                List<HomeLaunchSplitButton.Option> options = new java.util.ArrayList<>();
                for (int index = 0; index < 4; index++) {
                    options.add(new HomeLaunchSplitButton.Option("Copy " + index + " · MegaMek (1.0." + index + ")",
                            () -> { }));
                }
                holder[0] = new HomeLaunchSplitButton(GuiScale.DEFAULT, "megamek", "MegaMek",
                        "1.0.0", "Milestone", () -> { }, options);
                frame.setUndecorated(true);
                frame.setContentPane(holder[0]);
                frame.pack();
                java.awt.GraphicsConfiguration configuration = frame.getGraphicsConfiguration();
                java.awt.Rectangle screen = configuration.getBounds();
                java.awt.Insets reserved = java.awt.Toolkit.getDefaultToolkit().getScreenInsets(configuration);
                frame.setLocation(screen.x + 400,
                        screen.y + screen.height - reserved.bottom - frame.getHeight() - 10);
                frame.setVisible(true);
            });
            HomeLaunchSplitButton split = holder[0];
            SwingUtilities.invokeAndWait(split::openPopup);
            assertTrue(split.popupMenu().isVisible());
            int popupBottom = SwingTestSupport.onEdt(() -> split.popupMenu().getLocationOnScreen().y
                    + split.popupMenu().getHeight());
            int controlTop = SwingTestSupport.onEdt(() -> split.getLocationOnScreen().y);
            assertTrue(popupBottom <= controlTop, "the list opens upward instead of covering the control");
            SwingUtilities.invokeAndWait(split::closePopup);
        } finally {
            SwingUtilities.invokeAndWait(frame::dispose);
        }
    }

    @Test
    @org.junit.jupiter.api.Tag("native-gui")
    void joinedControlKeepsPerApplicationPreferenceAndRoutesOrderedCapturedAlternates()
            throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        Product megamek = new Product("megamek", "MegaMek.jar", "megamek.MegaMek",
                "1.0.0", List.of());
        InstallationRecord main = record("1", "Main", "1.0.0", megamek);
        InstallationRecord older = record("2", "Campaign copy", "0.49.20", megamek);
        InstallationRecord development = record("3", "Development", "0.51.0", megamek);
        AtomicReference<InstallationRecord> defaultRecord = new AtomicReference<>(main);
        AtomicReference<InstallationRecord> launched = new AtomicReference<>();
        JFrame frame = new JFrame();
        HomeLaunchSplitButton[] holder = new HomeLaunchSplitButton[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                holder[0] = new HomeLaunchSplitButton(GuiScale.DEFAULT, "megamek", "MegaMek",
                        "1.0.0", "Milestone", () -> launched.set(main), List.of(
                        new HomeLaunchSplitButton.Option(
                                "Campaign copy · MegaMek Milestone (0.49.20)",
                                () -> launched.set(older)),
                        new HomeLaunchSplitButton.Option(
                                "Development · MegaMek Development (0.51.0)",
                                () -> launched.set(development))));
                frame.setContentPane(holder[0]);
                frame.pack();
                frame.setLocation(400, 200);
                frame.setVisible(true);
            });
            HomeLaunchSplitButton split = holder[0];
            assertEquals("Launch MegaMek", split.primaryButton().getText());
            assertEquals("Milestone · 1.0.0", ButtonDetail.detail(split.primaryButton()));
            assertEquals("Launch MegaMek Milestone (1.0.0)",
                    split.primaryButton().getAccessibleContext().getAccessibleName());
            assertFalse(split.primaryButton().isContentAreaFilled());
            assertFalse(split.optionsButton().isContentAreaFilled());
            assertEquals(List.of("Campaign copy · MegaMek Milestone (0.49.20)",
                            "Development · MegaMek Development (0.51.0)"),
                    split.menuItems().stream().map(JMenuItem::getText).toList());
            assertEquals(UIManager.getColor("PopupMenu.background"),
                    split.popupMenu().getBackground());
            assertNotEquals(Color.WHITE, split.popupMenu().getBackground());

            invokeKeyBinding(split.primaryButton(), KeyStroke.getKeyStroke(
                    KeyEvent.VK_DOWN, InputEvent.ALT_DOWN_MASK));
            assertTrue(split.popupMenu().isVisible());
            assertEquals("Other installations",
                    ((javax.swing.JLabel) split.popupMenu().getComponent(0)).getAccessibleContext()
                            .getAccessibleName());
            assertFalse(split.popupMenu().isLightWeightPopupEnabled(),
                    "home content cannot paint over the alternatives");
            assertTrue(split.popupMenu().getWidth() >= split.getWidth(),
                    "the alternatives are at least as wide as the control");
            assertEquals(split.getLocationOnScreen().x + split.getWidth(),
                    split.popupMenu().getLocationOnScreen().x + split.popupMenu().getWidth(),
                    "the alternatives line up with the control's right edge");
            JMenuItem selected = split.menuItems().get(1);
            SwingUtilities.invokeAndWait(selected::doClick);
            assertSame(development, launched.get(),
                    "the row invokes its exact captured record");
            assertSame(main, defaultRecord.get(),
                    "an alternate launch never changes the application preference");

            SwingUtilities.invokeAndWait(split.primaryButton()::doClick);
            assertSame(main, launched.get());
            SwingUtilities.invokeAndWait(() -> split.setEnabled(false));
            assertFalse(split.primaryButton().isEnabled());
            assertFalse(split.optionsButton().isEnabled());
            assertFalse((split.menuItems().get(0)).isEnabled());
        } finally {
            SwingUtilities.invokeAndWait(frame::dispose);
        }
    }

    @Test
    void unavailablePreferredAndAlternateCopiesDoNotBlockUsableAlternates() throws Exception {
        AtomicReference<String> launched = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            HomeLaunchSplitButton split = new HomeLaunchSplitButton(GuiScale.DEFAULT,
                    "megamek", "MegaMek", () -> launched.set("preferred"), List.of(
                    new HomeLaunchSplitButton.Option("Missing · Not found",
                            () -> launched.set("missing"), false),
                    new HomeLaunchSplitButton.Option("Available",
                            () -> launched.set("available"))));
            split.setPrimaryAvailable(false);
            JMenuItem missing = split.menuItems().get(0);
            JMenuItem available = split.menuItems().get(1);
            assertFalse(split.primaryButton().isEnabled());
            assertTrue(split.optionsButton().isEnabled());
            assertFalse(missing.isEnabled());
            assertTrue(available.isEnabled());
            split.primaryButton().doClick();
            missing.doClick();
            assertEquals(null, launched.get());
            available.doClick();
            assertEquals("available", launched.get());
            split.setEnabled(false);
            assertFalse(split.optionsButton().isEnabled());
            assertFalse(available.isEnabled());
            split.setEnabled(true);
            assertFalse(split.primaryButton().isEnabled());
            assertTrue(split.optionsButton().isEnabled());
            assertFalse(missing.isEnabled());
            assertTrue(available.isEnabled());
            split.setPrimaryAvailable(true);
            split.primaryButton().doClick();
            assertEquals("preferred", launched.get());
            split.disposePopup();
        });
    }

    @Test
    void noAlternatesLeavesOneStyledFocusableLaunchSegment() throws Exception {
        HomeLaunchSplitButton split = new HomeLaunchSplitButton(GuiScale.DEFAULT,
                "lab", "MegaMekLab", () -> {
                }, List.of());
        assertTrue(split.primaryButton().isFocusable());
        assertFalse(split.optionsButton().isVisible());
        assertFalse(split.optionsButton().isEnabled());
        assertFalse(split.optionsButton().isFocusable());
        assertEquals(0, split.popupMenu().getComponentCount(), "no header without alternatives");
    }



    private static InstallationRecord record(String id, String name, String version,
                                             Product product) {
        return new InstallationRecord(id, name, "C:\\fixture\\" + id, version,
                List.of(product), null, false,
                "2026-09-18T00:00:00Z");
    }

    private static void invokeKeyBinding(javax.swing.JButton button, KeyStroke stroke)
            throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Object key = button.getInputMap(JComponent.WHEN_FOCUSED).get(stroke);
            assertNotNull(key);
            assertNotNull(button.getActionMap().get(key));
            button.getActionMap().get(key).actionPerformed(
                    new ActionEvent(button, ActionEvent.ACTION_PERFORMED, key.toString()));
        });
    }
}
