/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.megamek.launcher.gui;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.RepaintManager;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.event.FocusEvent;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ControlFocusStyleTest {
    @Test
    void customLinksAndBothCheckboxStatesInvalidateTheirWholeOutlineOnFocusGainAndLoss() throws Exception {
        SwingTestSupport.onEdt(() -> {
            for (AbstractButton control : List.of(
                    new LauncherLink("All news", "linkFocus", GuiScale.DEFAULT),
                    new MapCheckBox("Check for updates", false, GuiScale.DEFAULT),
                    new MapCheckBox("Check for updates", true, GuiScale.DEFAULT))) {
                control.setSize(320, 40);
                assertFalse(control.isFocusPainted());
                RepaintManager previous = RepaintManager.currentManager(control);
                FocusRepaintManager recording = new FocusRepaintManager(control);
                try {
                    RepaintManager.setCurrentManager(recording);
                    for (int eventId : new int[]{FocusEvent.FOCUS_GAINED, FocusEvent.FOCUS_LOST}) {
                        recording.dirty = null;
                        FocusEvent event = new FocusEvent(control, eventId);
                        for (var listener : control.getFocusListeners()) {
                            if (eventId == FocusEvent.FOCUS_GAINED) listener.focusGained(event);
                            else listener.focusLost(event);
                        }
                        assertEquals(new Rectangle(0, 0, 320, 40), recording.dirty,
                                control.getClass().getSimpleName() + " focus event " + eventId);
                    }
                } finally {
                    RepaintManager.setCurrentManager(previous);
                }
            }
            return null;
        });
    }

    @Test
    @Tag("native-gui")
    void realFocusOutlineAppearsAndClearsForLinksAndSelectedCheckboxesIncludingHighContrast()
            throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        Object[] previous = SwingTestSupport.onEdt(() -> new Object[]{
                UIManager.get("Theme.highContrast"), UIManager.get("Focus.color"),
                UIManager.get("CheckBox.foreground"), UIManager.get("CheckBox.background")});
        try {
            for (boolean contrast : new boolean[]{false, true}) {
                Controls controls = SwingTestSupport.onEdt(() -> {
                    UIManager.put("Theme.highContrast", contrast);
                    UIManager.put("Focus.color", Color.MAGENTA);
                    UIManager.put("CheckBox.foreground", Color.WHITE);
                    UIManager.put("CheckBox.background", Color.BLACK);
                    return new Controls(new LauncherLink("All news", "linkFocus", GuiScale.DEFAULT),
                            new MapCheckBox("Check for updates", false, GuiScale.DEFAULT), new JButton("Outside"));
                });
                JFrame frame = SwingTestSupport.createWindow(() -> {
                    JFrame window = new JFrame("Control focus");
                    JPanel content = new JPanel(new GridLayout(3, 1));
                    content.setBackground(LauncherTheme.BACKGROUND);
                    content.add(controls.link());
                    content.add(controls.checkbox());
                    content.add(controls.outside());
                    window.setContentPane(content);
                    window.setSize(420, 240);
                    window.setVisible(true);
                    return window;
                });
                try {
                    Color color = contrast ? Color.MAGENTA : LauncherTheme.BUTTON_ICON;
                    focus(frame, controls.link());
                    assertOutline(controls.link(), color, true);
                    focus(frame, controls.outside());
                    assertOutline(controls.link(), color, false);
                    for (boolean selected : new boolean[]{false, true}) {
                        SwingTestSupport.onEdt(() -> {
                            controls.checkbox().setSelected(selected);
                            return null;
                        });
                        focus(frame, controls.checkbox());
                        assertOutline(controls.checkbox(), color, true);
                        focus(frame, controls.outside());
                        assertOutline(controls.checkbox(), color, false);
                    }
                } finally {
                    SwingTestSupport.onEdt(() -> {
                        frame.dispose();
                        return null;
                    });
                }
            }
        } finally {
            SwingTestSupport.onEdt(() -> {
                UIManager.put("Theme.highContrast", previous[0]);
                UIManager.put("Focus.color", previous[1]);
                UIManager.put("CheckBox.foreground", previous[2]);
                UIManager.put("CheckBox.background", previous[3]);
                return null;
            });
        }
    }

    private static void focus(JFrame frame, AbstractButton control) throws Exception {
        SwingTestSupport.onEdt(() -> {
            frame.toFront();
            control.requestFocusInWindow();
            return null;
        });
        SwingTestSupport.awaitCondition("keyboard focus on " + control.getText(), control::isFocusOwner);
    }

    private static void assertOutline(JComponent control, Color color, boolean focused) throws Exception {
        SwingTestSupport.onEdt(() -> {
            int width = control.getWidth();
            int height = control.getHeight();
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            try {
                control.paint(graphics);
            } finally {
                graphics.dispose();
            }
            for (int[] point : new int[][]{
                    {width / 2, 0}, {width / 2, height - 1}, {0, height / 2}, {width - 1, height / 2}}) {
                int pixel = image.getRGB(point[0], point[1]);
                if (focused) assertEquals(color.getRGB(), pixel, control.getClass().getSimpleName());
                else assertNotEquals(color.getRGB(), pixel, "lost focus must clear the outline");
            }
            return null;
        });
    }

    private record Controls(LauncherLink link, MapCheckBox checkbox, JButton outside) {}
}
