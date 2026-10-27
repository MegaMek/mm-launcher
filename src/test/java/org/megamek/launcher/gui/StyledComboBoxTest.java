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

import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.event.KeyEvent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StyledComboBoxTest {
    @Test
    void lookAndFeelSelectKeepsAccessibleKeyboardBehavior() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            StyledComboBox<FollowChannel> combo =
                    new StyledComboBox<>(FollowChannel.values(), GuiScale.DEFAULT);
            combo.getAccessibleContext().setAccessibleName("Channel");
            assertInstanceOf(com.formdev.flatlaf.ui.FlatComboBoxUI.class, combo.getUI(),
                    "the design-system look and feel draws the select");
            assertNotEquals(Color.WHITE, combo.getBackground());
            assertEquals("Channel", combo.getAccessibleContext().getAccessibleName());
            assertTrue(combo.isFocusable());

            Object selectNext = combo.getInputMap(
                    JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(
                    KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0));
            assertNotNull(selectNext);
            assertNotNull(combo.getActionMap().get(selectNext),
                    "keyboard selection behavior remains installed");
        });
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
}