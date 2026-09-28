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

import org.junit.jupiter.api.Test;
import org.megamek.launcher.channel.FollowChannel;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Component;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StyledComboBoxTest {
    @Test
    void vectorArrowAndSwingBindingsKeepDarkAccessibleComboBehavior() {
        StyledComboBox<FollowChannel> combo =
                new StyledComboBox<>(FollowChannel.values(), GuiScale.DEFAULT);
        combo.setName("fixtureCombo");
        combo.getAccessibleContext().setAccessibleName("Channel");
        combo.setSize(280, 42);
        combo.doLayout();

        JButton arrow = null;
        for (Component component : combo.getComponents()) {
            if (component instanceof JButton button
                    && "styledComboArrowButton".equals(button.getName())) {
                arrow = button;
            }
        }
        assertNotNull(arrow);
        assertFalse(arrow.isContentAreaFilled(),
                "the native look-and-feel must not paint a white arrow segment");
        assertNotEquals(Color.WHITE, combo.getBackground());
        assertEquals("Open choices",
                arrow.getAccessibleContext().getAccessibleName());
        assertEquals("Channel", combo.getAccessibleContext().getAccessibleName());

        BufferedImage image = new BufferedImage(
                Math.max(1, arrow.getWidth()), Math.max(1, arrow.getHeight()),
                BufferedImage.TYPE_INT_ARGB);
        arrow.paint(image.getGraphics());
        assertNotEquals(Color.WHITE.getRGB(), image.getRGB(1, 1),
                "the vector-painted arrow segment must remain dark");

        Object selectNext = combo.getInputMap(
                JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(
                KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0));
        assertNotNull(selectNext);
        assertNotNull(combo.getActionMap().get(selectNext),
                "BasicComboBoxUI keyboard selection behavior remains installed");
    }

    @Test
    void arrowBindingsSurviveUiRefresh() throws Exception {
        StyledComboBox<FollowChannel> combo =
                new StyledComboBox<>(FollowChannel.values(), GuiScale.DEFAULT);
        SwingUtilities.invokeAndWait(() -> {
            combo.updateUI();
            for (int keyCode : new int[]{KeyEvent.VK_DOWN, KeyEvent.VK_UP}) {
                Object binding = combo.getInputMap(
                        JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(
                        KeyStroke.getKeyStroke(keyCode, 0));
                assertNotNull(binding);
                assertNotNull(combo.getActionMap().get(binding),
                        "arrow key must resolve to an action after UI refresh");
            }
        });
    }

    @Test
    void highContrastUsesSystemComboColors() {
        Object previous = UIManager.get("Theme.highContrast");
        Color previousBackground = UIManager.getColor("ComboBox.background");
        Color previousForeground = UIManager.getColor("ComboBox.foreground");
        try {
            UIManager.put("Theme.highContrast", true);
            UIManager.put("ComboBox.background", Color.BLACK);
            UIManager.put("ComboBox.foreground", Color.WHITE);
            StyledComboBox<String> combo =
                    new StyledComboBox<>(new String[]{"One", "Two"}, GuiScale.DEFAULT);
            assertEquals(Color.BLACK, combo.getBackground());
            assertEquals(Color.WHITE, combo.getForeground());
            assertTrue(combo.isFocusable());
        } finally {
            restore("Theme.highContrast", previous);
            restore("ComboBox.background", previousBackground);
            restore("ComboBox.foreground", previousForeground);
        }
    }

    private static void restore(String key, Object value) {
        if (value == null) UIManager.getDefaults().remove(key);
        else UIManager.put(key, value);
    }
}
