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

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicGraphicsUtils;
import javax.swing.plaf.basic.BasicMenuItemUI;
import javax.swing.plaf.basic.BasicPopupMenuUI;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.HeadlessException;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.List;

/**
 * Joined managed-Home launch control. The primary segment launches the application's preferred
 * exact record while the optional arrow exposes already-registered copies without introducing a
 * selected-copy state.
 */
final class HomeLaunchSplitButton extends JPanel {
    static final Color POPUP_BACKGROUND = LauncherTheme.POPUP_BACKGROUND;
    static final Color POPUP_FOREGROUND = LauncherTheme.TEXT;
    static final Color POPUP_SELECTION = LauncherTheme.ACTIVE_BACKGROUND;
    static final Color POPUP_SELECTION_FOREGROUND = LauncherTheme.ACCENT;
    static final Color POPUP_BORDER = LauncherTheme.BORDER;
    private static final Color POPUP_DISABLED = LauncherTheme.MUTED;
    private static final String OPEN_POPUP_ACTION = "openAlternateInstallations";
    private static final String CLOSE_POPUP_ACTION = "closeAlternateInstallations";

    private final GuiScale scale;
    private final SegmentButton primaryButton;
    private final SegmentButton optionsButton;
    private final JPopupMenu popupMenu;
    private final List<JMenuItem> optionItems;
    private final List<Option> alternatives;
    private final boolean hasOptions;
    private boolean primaryAvailable = true;
    private boolean disposed;

    HomeLaunchSplitButton(GuiScale scale, String productKey, String productName,
                          Runnable primaryAction, List<Option> alternatives) {
        this(scale, productKey, productName, null, null, primaryAction, alternatives);
    }

    HomeLaunchSplitButton(GuiScale scale, String productKey, String productName,
                          String preferredVersion, Runnable primaryAction,
                          List<Option> alternatives) {
        this(scale, productKey, productName, preferredVersion, null,
                primaryAction, alternatives);
    }

    HomeLaunchSplitButton(GuiScale scale, String productKey, String productName,
                          String preferredVersion, String preferredChannel,
                          Runnable primaryAction, List<Option> alternatives) {
        this.scale = scale;
        List<Option> capturedOptions = List.copyOf(alternatives);
        this.alternatives = capturedOptions;
        hasOptions = !capturedOptions.isEmpty();
        setName("launch-" + productKey + "-split-button");
        setLayout(new BorderLayout());
        setOpaque(false);
        setAlignmentX(Component.CENTER_ALIGNMENT);
        getAccessibleContext().setAccessibleName("Launch " + productName);
        getAccessibleContext().setAccessibleDescription(hasOptions
                ? "Launch preferred " + productName
                + ", or choose another registered installation."
                : "Launch preferred " + productName + ".");

        String primaryLabel =
                VersionDisplay.launch(productName, preferredChannel, preferredVersion);
        primaryButton = new SegmentButton(primaryLabel,
                "launch-" + productKey + "-button", false);
        primaryButton.getAccessibleContext().setAccessibleDescription(
                "Launch " + productName + " from its preferred installation"
                        + (preferredVersion == null || preferredVersion.isBlank()
                        ? "" : ", version " + preferredVersion)
                        + (preferredChannel == null || preferredChannel.isBlank()
                        ? "." : ", " + preferredChannel + " channel."));
        primaryButton.addActionListener(event -> {
            if (!disposed && primaryButton.isEnabled()) primaryAction.run();
        });

        optionsButton = new SegmentButton("", "launch-" + productKey + "-options-button", true);
        optionsButton.setIcon(new DownArrowIcon(scale));
        optionsButton.setHorizontalAlignment(SwingConstants.CENTER);
        optionsButton.setToolTipText("Launch " + productName
                + " from another registered installation.");
        optionsButton.getAccessibleContext().setAccessibleName(
                "Other " + productName + " installations");
        optionsButton.getAccessibleContext().setAccessibleDescription(
                "Open registered alternatives for " + productName
                        + ". This does not change the preferred installation.");
        optionsButton.addActionListener(event -> openPopup());

        Palette palette = palette();
        popupMenu = new StyledPopupMenu(palette, scale);
        popupMenu.setName("launch-" + productKey + "-popup");
        popupMenu.getAccessibleContext().setAccessibleName(
                "Other " + productName + " installations");
        popupMenu.getAccessibleContext().setAccessibleDescription(
                "Launch a captured registered copy without changing the preference.");
        optionItems = capturedOptions.stream().map(option -> {
            JMenuItem item = menuItem(option.label(), palette);
            item.addActionListener(event -> invokeOption(option));
            popupMenu.add(item);
            return item;
        }).toList();
        optionsButton.addPropertyChangeListener("enabled", event -> {
            boolean enabled = optionsButton.isEnabled() && isEnabled();
            updateOptionAvailability();
            if (!enabled) closePopup();
            repaint();
        });

        installOpenBindings(primaryButton);
        if (hasOptions) {
            installOpenBindings(optionsButton);
            add(optionsButton, BorderLayout.EAST);
        } else {
            optionsButton.setEnabled(false);
            optionsButton.setFocusable(false);
            optionsButton.setVisible(false);
        }
        popupMenu.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), CLOSE_POPUP_ACTION);
        popupMenu.getActionMap().put(CLOSE_POPUP_ACTION, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) {
                closePopup();
                if (hasOptions) optionsButton.requestFocusInWindow();
                else primaryButton.requestFocusInWindow();
            }
        });

        primaryButton.getModel().addChangeListener(event -> repaint());
        optionsButton.getModel().addChangeListener(event -> repaint());
        add(primaryButton, BorderLayout.CENTER);
        updateOptionAvailability();
    }

    private JMenuItem menuItem(String label, Palette palette) {
        JMenuItem item = new JMenuItem(label);
        item.setUI(new StyledMenuItemUI(palette));
        item.setOpaque(true);
        item.setBackground(palette.background());
        item.setForeground(palette.foreground());
        item.setFont(scale.font(item.getFont(), Font.BOLD, 13f));
        item.setBorder(BorderFactory.createEmptyBorder(scale.scaleForGUI(9),
                scale.scaleForGUI(12), scale.scaleForGUI(9), scale.scaleForGUI(12)));
        item.getAccessibleContext().setAccessibleName(label);
        item.getAccessibleContext().setAccessibleDescription(
                label + ". Launch this registered copy without changing the preference.");
        return item;
    }

    private void installOpenBindings(JButton button) {
        button.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, InputEvent.ALT_DOWN_MASK),
                OPEN_POPUP_ACTION);
        button.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_F4, 0), OPEN_POPUP_ACTION);
        button.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_CONTEXT_MENU, 0), OPEN_POPUP_ACTION);
        button.getInputMap(JComponent.WHEN_FOCUSED).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_F10, InputEvent.SHIFT_DOWN_MASK),
                OPEN_POPUP_ACTION);
        button.getActionMap().put(OPEN_POPUP_ACTION, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) {
                openPopup();
            }
        });
    }

    private void invokeOption(Option option) {
        closePopup();
        if (!disposed && isEnabled() && optionsButton.isEnabled() && option.available()) {
            option.action().run();
        }
    }

    void openPopup() {
        if (disposed || !hasOptions || !isEnabled() || !optionsButton.isEnabled()
                || popupMenu.isVisible() || !isShowing()) {
            return;
        }
        int x = Math.max(0, getWidth() - popupMenu.getPreferredSize().width);
        popupMenu.show(this, x, Math.max(0, getHeight() - scale.scaleForGUI(3)));
        JMenuItem selected = optionItems.stream().filter(JMenuItem::isEnabled)
                .findFirst().orElse(null);
        MenuSelectionManager.defaultManager().setSelectedPath(selected == null
                ? new MenuElement[]{popupMenu}
                : new MenuElement[]{popupMenu, selected});
    }

    void closePopup() {
        MenuSelectionManager.defaultManager().clearSelectedPath();
        popupMenu.setVisible(false);
    }

    void disposePopup() {
        if (disposed) return;
        disposed = true;
        closePopup();
        popupMenu.removeAll();
    }

    JButton primaryButton() {
        return primaryButton;
    }

    JButton optionsButton() {
        return optionsButton;
    }

    JPopupMenu popupMenu() {
        return popupMenu;
    }

    boolean hasOptions() {
        return hasOptions;
    }

    void setPrimaryAvailable(boolean available) {
        primaryAvailable = available;
        primaryButton.setEnabled(isEnabled() && available);
    }

    private void updateOptionAvailability() {
        boolean enabled = isEnabled() && optionsButton.isEnabled();
        for (int index = 0; index < optionItems.size(); index++) {
            optionItems.get(index).setEnabled(enabled && alternatives.get(index).available());
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        if (primaryButton == null) return;
        primaryButton.setEnabled(enabled && primaryAvailable);
        optionsButton.setEnabled(enabled && hasOptions);
        updateOptionAvailability();
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension primary = primaryButton.getPreferredSize();
        Dimension options = optionsButton.getPreferredSize();
        return new Dimension(Math.max(primary.width + (hasOptions ? options.width : 0),
                scale.scaleForGUI(210)),
                Math.max(Math.max(primary.height, options.height), scale.scaleForGUI(48)));
    }

    @Override
    public Dimension getMinimumSize() {
        return scale.scaleForGUI(175, 48);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D canvas = (Graphics2D) graphics.create();
        try {
            canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            boolean pressed = primaryButton.getModel().isPressed()
                    && primaryButton.getModel().isArmed()
                    || hasOptions && optionsButton.getModel().isPressed()
                    && optionsButton.getModel().isArmed();
            boolean hover = primaryButton.getModel().isRollover()
                    || hasOptions && optionsButton.getModel().isRollover();
            boolean enabled = isEnabled() && (primaryButton.isEnabled()
                    || hasOptions && optionsButton.isEnabled());
            LauncherTheme.paintButton(canvas, getWidth(), getHeight(), scale, enabled, pressed, hover,
                    primaryButton.isFocusOwner() || hasOptions && optionsButton.isFocusOwner());
            if (hasOptions) {
                int separator = optionsButton.getX();
                canvas.setColor(LauncherTheme.highContrast()
                        ? LauncherTheme.uiColor("Button.foreground", Color.WHITE) : LauncherTheme.BORDER);
                canvas.drawLine(separator, scale.scaleForGUI(4),
                        separator, getHeight() - scale.scaleForGUI(4));
            }
        } finally {
            canvas.dispose();
        }
        super.paintComponent(graphics);
    }

    record Option(String label, Runnable action, boolean available) {
        Option(String label, Runnable action) {
            this(label, action, true);
        }

        Option {
            if (label == null || label.isBlank()) {
                throw new IllegalArgumentException("alternate label is required");
            }
            java.util.Objects.requireNonNull(action, "action");
        }
    }

    private final class SegmentButton extends JButton {
        private SegmentButton(String text, String name, boolean arrow) {
            super(text);
            setName(name);
            getAccessibleContext().setAccessibleName(text);
            setFont(scale.font(getFont(), Font.BOLD, 14f));
            int horizontal = scale.scaleForGUI(arrow ? 9 : 14);
            setBorder(BorderFactory.createEmptyBorder(scale.scaleForGUI(11),
                    horizontal, scale.scaleForGUI(11), horizontal));
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            LauncherTheme.repaintOwnerOnFocusChange(this, HomeLaunchSplitButton.this);
            setRolloverEnabled(true);
            setUI(new BasicButtonUI() {
                @Override
                protected void paintText(Graphics graphics, JComponent component,
                                         Rectangle textRectangle, String value) {
                    Graphics2D text = (Graphics2D) graphics.create();
                    try {
                        text.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                        text.setColor(LauncherTheme.buttonText(true, isEnabled()));
                        BasicGraphicsUtils.drawStringUnderlineCharAt(text, value,
                                getDisplayedMnemonicIndex(), textRectangle.x,
                                textRectangle.y + text.getFontMetrics().getAscent());
                    } finally {
                        text.dispose();
                    }
                }
            });
        }

        @Override
        public javax.swing.JToolTip createToolTip() {
            return LauncherTheme.toolTip(this, scale);
        }
    }

    private static Palette palette() {
        if (!highContrast()) {
            return new Palette(POPUP_BACKGROUND, POPUP_FOREGROUND, POPUP_DISABLED,
                    POPUP_SELECTION, POPUP_SELECTION_FOREGROUND, POPUP_BORDER);
        }
        return new Palette(uiColor("MenuItem.background", Color.BLACK),
                uiColor("MenuItem.foreground", Color.WHITE),
                uiColor("MenuItem.disabledForeground", Color.LIGHT_GRAY),
                uiColor("MenuItem.selectionBackground", Color.WHITE),
                uiColor("MenuItem.selectionForeground", Color.BLACK),
                uiColor("MenuItem.foreground", Color.WHITE));
    }

    private static boolean highContrast() {
        if (UIManager.getBoolean("Theme.highContrast")
                || UIManager.getBoolean("win.highContrast.on")) {
            return true;
        }
        try {
            return Boolean.TRUE.equals(
                    Toolkit.getDefaultToolkit().getDesktopProperty("win.highContrast.on"));
        } catch (HeadlessException ignored) {
            return false;
        }
    }

    private static Color uiColor(String key, Color fallback) {
        Color color = UIManager.getColor(key);
        return color == null ? fallback : color;
    }

    private record Palette(Color background, Color foreground, Color disabled,
                           Color selection, Color selectionForeground, Color border) {
    }

    private static final class StyledPopupMenu extends JPopupMenu {
        private StyledPopupMenu(Palette palette, GuiScale scale) {
            setUI(new BasicPopupMenuUI());
            setOpaque(true);
            setBackground(palette.background());
            setForeground(palette.foreground());
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(palette.border(), scale.scaleForGUI(1)),
                    BorderFactory.createEmptyBorder(scale.scaleForGUI(3),
                            scale.scaleForGUI(3), scale.scaleForGUI(3),
                            scale.scaleForGUI(3))));
        }
    }

    private static final class StyledMenuItemUI extends BasicMenuItemUI {
        private final Palette palette;

        private StyledMenuItemUI(Palette palette) {
            this.palette = palette;
        }

        @Override
        protected void installDefaults() {
            super.installDefaults();
            selectionBackground = palette.selection();
            selectionForeground = palette.selectionForeground();
            disabledForeground = palette.disabled();
            acceleratorForeground = palette.foreground();
            acceleratorSelectionForeground = palette.selectionForeground();
        }
    }

    private static final class DownArrowIcon implements Icon {
        private final int width;
        private final int height;

        private DownArrowIcon(GuiScale scale) {
            width = scale.scaleForGUI(10);
            height = scale.scaleForGUI(6);
        }

        @Override public int getIconWidth() {
            return width;
        }

        @Override public int getIconHeight() {
            return height;
        }

        @Override
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D canvas = (Graphics2D) graphics.create();
            try {
                canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                canvas.setColor(LauncherTheme.buttonText(false, component.isEnabled()));
                canvas.fillPolygon(new int[]{x, x + width, x + width / 2},
                        new int[]{y, y, y + height}, 3);
            } finally {
                canvas.dispose();
            }
        }
    }
}
