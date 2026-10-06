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

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.RepaintManager;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.FocusEvent;
import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SplitButtonStyleTest {
    @Test
    void primaryLabelsAreNeutralOnRegularAndBothSplitButtons() throws Exception {
        SwingTestSupport.onEdt(() -> {
            List<JButton> buttons = new java.util.ArrayList<>();
            buttons.add(new FirstLaunchButton("Launch", "primaryButton", true, GuiScale.DEFAULT));
            for (SplitFixture fixture : fixtures()) buttons.add(fixture.primary());
            for (JButton button : buttons) {
                button.setSize(620, 56);
                BufferedImage image = new BufferedImage(620, 56, BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = image.createGraphics();
                try {
                    button.paint(graphics);
                } finally {
                    graphics.dispose();
                }
                boolean neutralText = false;
                for (int y = 0; y < image.getHeight(); y++) {
                    for (int x = 0; x < image.getWidth(); x++) {
                        int pixel = image.getRGB(x, y);
                        if (pixel == LauncherTheme.TEXT.getRGB()) neutralText = true;
                        assertFalse(pixel == LauncherTheme.SELECTED.getRGB(), "button labels must not use amber");
                    }
                }
                org.junit.jupiter.api.Assertions.assertTrue(neutralText, button.getName());
            }
            return null;
        });
    }

    @Test
    void arrowsUseNormalTextColorInsteadOfPrimaryActionAmber() throws Exception {
        SwingTestSupport.onEdt(() -> {
            for (SplitFixture fixture : fixtures()) {
                JButton arrow = fixture.options();
                var icon = arrow.getIcon();
                BufferedImage image = new BufferedImage(icon.getIconWidth(), icon.getIconHeight(),
                        BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = image.createGraphics();
                try {
                    icon.paintIcon(arrow, graphics, 0, 0);
                    assertEquals(LauncherTheme.TEXT.getRGB(), image.getRGB(icon.getIconWidth() / 2, 2),
                            fixture.control().getName());
                    arrow.setEnabled(false);
                    icon.paintIcon(arrow, graphics, 0, 0);
                    assertEquals(LauncherTheme.MUTED.getRGB(), image.getRGB(icon.getIconWidth() / 2, 2),
                            fixture.control().getName());
                } finally {
                    graphics.dispose();
                }
            }
            return null;
        });
    }

    @Test
    void eitherSegmentsFocusGainAndLossInvalidateTheEntireSharedOutline() throws Exception {
        SwingTestSupport.onEdt(() -> {
            for (SplitFixture fixture : fixtures()) {
                fixture.control().setSize(620, 56);
                fixture.control().doLayout();
                RepaintManager previous = RepaintManager.currentManager(fixture.control());
                FocusRepaintManager recording = new FocusRepaintManager(fixture.control());
                try {
                    RepaintManager.setCurrentManager(recording);
                    for (JButton segment : List.of(fixture.primary(), fixture.options())) {
                        assertFalse(segment.isFocusPainted(), "segments must not draw separate focus boxes");
                        for (int eventId : new int[]{FocusEvent.FOCUS_GAINED, FocusEvent.FOCUS_LOST}) {
                            recording.dirty = null;
                            FocusEvent event = new FocusEvent(segment, eventId);
                            for (var listener : segment.getFocusListeners()) {
                                if (eventId == FocusEvent.FOCUS_GAINED) listener.focusGained(event);
                                else listener.focusLost(event);
                            }
                            assertEquals(new Rectangle(0, 0, 620, 56), recording.dirty,
                                    segment.getName() + " focus event " + eventId);
                        }
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
    void actualKeyboardFocusOnEitherSegmentHighlightsAllFourOuterEdges() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        List<SplitFixture> fixtures = SwingTestSupport.onEdt(SplitButtonStyleTest::fixtures);
        for (SplitFixture fixture : fixtures) {
            JFrame frame = SwingTestSupport.createWindow(() -> {
                JFrame window = new JFrame("Split control focus");
                JPanel content = new JPanel(new BorderLayout());
                fixture.control().setPreferredSize(new Dimension(620, 56));
                content.add(fixture.control(), BorderLayout.CENTER);
                content.add(new JButton("Outside control"), BorderLayout.SOUTH);
                window.setContentPane(content);
                window.pack();
                window.setVisible(true);
                return window;
            });
            try {
                for (JButton segment : List.of(fixture.primary(), fixture.options())) {
                    SwingTestSupport.onEdt(() -> {
                        frame.toFront();
                        segment.requestFocusInWindow();
                        return null;
                    });
                    SwingTestSupport.awaitCondition("keyboard focus on " + segment.getName(),
                            segment::isFocusOwner);
                    SwingTestSupport.onEdt(() -> {
                        JComponent control = fixture.control();
                        int width = control.getWidth();
                        int height = control.getHeight();
                        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
                        Graphics2D graphics = image.createGraphics();
                        try {
                            control.paint(graphics);
                        } finally {
                            graphics.dispose();
                        }
                        for (int x : new int[]{width / 4, width - 10}) {
                            assertEquals(LauncherTheme.BUTTON_ICON.getRGB(), image.getRGB(x, 0),
                                    "whole top outline for " + segment.getName());
                            assertEquals(LauncherTheme.BUTTON_ICON.getRGB(), image.getRGB(x, height - 1),
                                    "whole bottom outline for " + segment.getName());
                        }
                        assertEquals(LauncherTheme.BUTTON_ICON.getRGB(), image.getRGB(0, height / 2));
                        assertEquals(LauncherTheme.BUTTON_ICON.getRGB(), image.getRGB(width - 1, height / 2));
                        return null;
                    });
                }
            } finally {
                SwingTestSupport.dispose(frame);
            }
        }
    }

    private static List<SplitFixture> fixtures() {
        FirstLaunchSplitButton install = new FirstLaunchSplitButton(GuiScale.DEFAULT,
                () -> {}, key -> {}, () -> {});
        install.setOptions(QuickInstallTestData.snapshot("1.2.3", "1.3.0"));
        HomeLaunchSplitButton launch = new HomeLaunchSplitButton(GuiScale.DEFAULT,
                "megamek", "MegaMek", () -> {},
                List.of(new HomeLaunchSplitButton.Option("Another installation", () -> {})));
        return List.of(new SplitFixture(install, install.primaryButton(), install.optionsButton()),
                new SplitFixture(launch, launch.primaryButton(), launch.optionsButton()));
    }

    private record SplitFixture(JComponent control, JButton primary, JButton options) {}

}
