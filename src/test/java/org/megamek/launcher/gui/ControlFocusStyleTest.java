/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.megamek.launcher.gui;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.RepaintManager;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.GridLayout;
import java.awt.Rectangle;
import java.awt.event.FocusEvent;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ControlFocusStyleTest {
    @Test
    void customLinksInvalidateTheirWholeOutlineOnFocusGainAndLoss() throws Exception {
        SwingTestSupport.onEdt(() -> {
            LauncherLink control = new LauncherLink("All news", "linkFocus", GuiScale.DEFAULT);
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
                            "focus event " + eventId);
                }
            } finally {
                RepaintManager.setCurrentManager(previous);
            }
            return null;
        });
    }

    @Test
    @Tag("native-gui")
    void realFocusRingAppearsAndClearsForLinks() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        LauncherLink link = SwingTestSupport.onEdt(
                () -> new LauncherLink("All news", "linkFocus", GuiScale.DEFAULT));
        JButton outside = SwingTestSupport.onEdt(() -> new JButton("Outside"));
        JFrame frame = SwingTestSupport.createWindow(() -> {
            JFrame window = new JFrame("Control focus");
            JPanel content = new JPanel(new GridLayout(2, 1));
            content.setBackground(LauncherTheme.BACKGROUND);
            content.add(link);
            content.add(outside);
            window.setContentPane(content);
            window.setSize(420, 160);
            window.setVisible(true);
            return window;
        });
        try {
            focus(frame, link);
            assertOutline(link, true);
            focus(frame, outside);
            assertOutline(link, false);
        } finally {
            SwingTestSupport.onEdt(() -> {
                frame.dispose();
                return null;
            });
        }
    }

    private static void focus(JFrame frame, JComponent control) throws Exception {
        SwingTestSupport.onEdt(() -> {
            frame.toFront();
            control.requestFocusInWindow();
            return null;
        });
        SwingTestSupport.awaitCondition("keyboard focus on " + control.getName(), control::isFocusOwner);
    }

    private static void assertOutline(JComponent control, boolean focused) throws Exception {
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
            int ring = LauncherTheme.FOCUS.getRGB();
            boolean left = false;
            boolean right = false;
            for (int inset = 0; inset < 3; inset++) {
                left |= image.getRGB(inset, height / 2) == ring;
                right |= image.getRGB(width - 1 - inset, height / 2) == ring;
            }
            assertEquals(focused, left, "left edge of the focus ring");
            assertEquals(focused, right, "right edge of the focus ring");
            assertTrue(focused || image.getRGB(width / 2, 0) != ring, "lost focus must clear the outline");
            return null;
        });
    }
}