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
                frame.setVisible(true);
            });
            HomeLaunchSplitButton split = holder[0];
            assertEquals("Launch MegaMek Milestone (1.0.0)",
                    split.primaryButton().getText());
            assertFalse(split.primaryButton().isContentAreaFilled());
            assertFalse(split.optionsButton().isContentAreaFilled());
            assertEquals(List.of("Campaign copy · MegaMek Milestone (0.49.20)",
                            "Development · MegaMek Development (0.51.0)"),
                    java.util.Arrays.stream(split.popupMenu().getComponents())
                            .map(JMenuItem.class::cast).map(JMenuItem::getText).toList());
            assertEquals(HomeLaunchSplitButton.POPUP_BACKGROUND,
                    split.popupMenu().getBackground());
            assertNotEquals(Color.WHITE, split.popupMenu().getBackground());

            invokeKeyBinding(split.primaryButton(), KeyStroke.getKeyStroke(
                    KeyEvent.VK_DOWN, InputEvent.ALT_DOWN_MASK));
            assertTrue(split.popupMenu().isVisible());
            JMenuItem selected = (JMenuItem) split.popupMenu().getComponent(1);
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
            assertFalse(((JMenuItem) split.popupMenu().getComponent(0)).isEnabled());
        } finally {
            SwingUtilities.invokeAndWait(frame::dispose);
        }
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
        assertEquals(0, split.popupMenu().getComponentCount());
    }

    @Test
    void popupUsesSystemMenuContrastWhenHighContrastIsRequested() throws Exception {
        Object prior = UIManager.get("Theme.highContrast");
        Color priorBackground = UIManager.getColor("MenuItem.background");
        Color priorForeground = UIManager.getColor("MenuItem.foreground");
        try {
            UIManager.put("Theme.highContrast", true);
            UIManager.put("MenuItem.background", Color.BLACK);
            UIManager.put("MenuItem.foreground", Color.WHITE);
            HomeLaunchSplitButton split = new HomeLaunchSplitButton(GuiScale.DEFAULT,
                    "mekhq", "MekHQ", () -> {
                    }, List.of(new HomeLaunchSplitButton.Option(
                    "Other · 1.0.0", () -> {
                    })));
            assertEquals(Color.BLACK, split.popupMenu().getBackground());
            assertEquals(Color.WHITE,
                    ((JMenuItem) split.popupMenu().getComponent(0)).getForeground());
        } finally {
            restoreUiValue("Theme.highContrast", prior);
            restoreUiValue("MenuItem.background", priorBackground);
            restoreUiValue("MenuItem.foreground", priorForeground);
        }
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

    private static void restoreUiValue(String key, Object value) {
        if (value == null) UIManager.getDefaults().remove(key);
        else UIManager.put(key, value);
    }
}
