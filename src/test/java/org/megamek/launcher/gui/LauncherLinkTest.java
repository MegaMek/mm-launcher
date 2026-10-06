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

import javax.accessibility.AccessibleRole;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.UIManager;
import javax.swing.plaf.basic.BasicHTML;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LauncherLinkTest {
    @Test
    void linksRenderUnboxedCyanPlainTextAndExposeAnAccessibleLink() throws Exception {
        SwingTestSupport.onEdt(() -> {
            String title = "<html><img src='https://example.invalid/image'> & news";
            LauncherLink link = new LauncherLink(title, "testLink", GuiScale.DEFAULT);
            assertEquals(title, link.getText());
            assertEquals(title, link.getAccessibleContext().getAccessibleName());
            assertEquals(AccessibleRole.HYPERLINK, link.getAccessibleContext().getAccessibleRole());
            assertEquals(1, link.getAccessibleContext().getAccessibleAction().getAccessibleActionCount());
            assertEquals(Boolean.TRUE, link.getClientProperty("html.disable"));
            assertNull(link.getClientProperty(BasicHTML.propertyKey), "feed titles must not load HTML resources");
            assertEquals(LauncherTheme.ACCENT, link.getForeground());
            assertEquals(Cursor.HAND_CURSOR, link.getCursor().getType());
            assertFalse(link.isOpaque());
            assertFalse(link.isContentAreaFilled());
            link.setSize(180, 30);
            link.setSize(180, link.getPreferredSize().height);
            BufferedImage image = render(link);
            assertEquals(0, image.getRGB(0, 0), "no normal button background or outline");
            assertTrue(hasColor(image, LauncherTheme.ACCENT));
            return null;
        });
    }

    @Test
    void shortDestinationLabelsFitOnOneLineAtTheirNaturalWidth() throws Exception {
        SwingTestSupport.onEdt(() -> {
            for (String text : new String[]{"Join Discord", "All news"}) {
                LauncherLink link = new LauncherLink(text, "shortLink", GuiScale.DEFAULT);
                link.setSize(link.getPreferredSize());
                int singleLineHeight = link.getFontMetrics(link.getFont()).getHeight()
                        + link.getInsets().top + link.getInsets().bottom + 2;
                assertTrue(link.getPreferredSize().height <= singleLineHeight,
                        "short labels must not wrap because of rounded text measurements");
            }
            return null;
        });
    }

    @Test
    void fullTitlesWrapAndReflowWithoutExpandingTheMinimumWidth() throws Exception {
        SwingTestSupport.onEdt(() -> {
            LauncherLink link = new LauncherLink(
                    "A detailed MegaMek announcement with a long title that must remain fully readable",
                    "wrappedLink", GuiScale.DEFAULT);
            link.setSize(160, 30);
            int narrowHeight = link.getPreferredSize().height;
            assertTrue(narrowHeight > link.getFontMetrics(link.getFont()).getHeight() * 2);
            link.setSize(800, 30);
            int wideHeight = link.getPreferredSize().height;
            assertTrue(narrowHeight > wideHeight);
            assertEquals(0, link.getMinimumSize().width);
            assertEquals(wideHeight, link.getMaximumSize().height);
            LauncherLink zoomed = new LauncherLink("All news", "zoomedLink", new GuiScale(2f));
            LauncherLink normal = new LauncherLink("All news", "normalLink", GuiScale.DEFAULT);
            assertTrue(zoomed.getPreferredSize().height >= normal.getPreferredSize().height * 1.8);
            return null;
        });
    }

    @Test
    void mouseEnterSpaceAndDisabledStateKeepTheButtonActionSemantics() throws Exception {
        SwingTestSupport.onEdt(() -> {
            LauncherLink link = new LauncherLink("All news", "testLink", GuiScale.DEFAULT);
            AtomicInteger clicks = new AtomicInteger();
            link.addActionListener(event -> clicks.incrementAndGet());
            link.doClick(0);
            activate(link, KeyEvent.VK_ENTER);
            activate(link, KeyEvent.VK_SPACE);
            assertEquals(3, clicks.get());
            link.setEnabled(false);
            assertEquals(Cursor.DEFAULT_CURSOR, link.getCursor().getType());
            link.doClick(0);
            activate(link, KeyEvent.VK_ENTER);
            assertEquals(3, clicks.get());
            return null;
        });
    }

    @Test
    void highContrastUsesSystemLinkTextInsteadOfForcingCyan() throws Exception {
        SwingTestSupport.onEdt(() -> {
            Object contrast = UIManager.get("Theme.highContrast");
            Object foreground = UIManager.get("Button.foreground");
            try {
                UIManager.put("Theme.highContrast", true);
                UIManager.put("Button.foreground", Color.WHITE);
                LauncherLink link = new LauncherLink("All news", "contrastLink", GuiScale.DEFAULT);
                link.setSize(link.getPreferredSize());
                assertEquals(Color.WHITE, link.getForeground());
                assertTrue(hasColor(render(link), Color.WHITE));
            } finally {
                UIManager.put("Theme.highContrast", contrast);
                UIManager.put("Button.foreground", foreground);
            }
            return null;
        });
    }

    private static void activate(LauncherLink link, int key) {
        for (boolean released : new boolean[]{false, true}) {
            Object binding = link.getInputMap(JComponent.WHEN_FOCUSED).get(
                    KeyStroke.getKeyStroke(key, 0, released));
            link.getActionMap().get(binding).actionPerformed(new ActionEvent(link, 0, "activate"));
        }
    }

    private static BufferedImage render(LauncherLink link) {
        BufferedImage image = new BufferedImage(link.getWidth(), link.getHeight(), BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            link.paint(graphics);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private static boolean hasColor(BufferedImage image, Color color) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if (image.getRGB(x, y) == color.getRGB()) return true;
            }
        }
        return false;
    }
}
