package org.megamek.launcher.gui;

import org.junit.jupiter.api.Test;
import org.megamek.launcher.channel.FollowChannel;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
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
