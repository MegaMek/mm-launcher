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

import org.megamek.launcher.design.swing.TokenPresentation;

import javax.swing.AbstractAction;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingConstants;
import javax.swing.plaf.basic.BasicButtonUI;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsConfiguration;
import java.awt.Insets;
import java.awt.Point;
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
    /** Preferred and shrunk minimum widths of the Home launch control; the design tokens have no width for them. */
    private static final int MINIMUM_WIDTH = 210;
    private static final int MINIMUM_SHRUNK_WIDTH = 175;
    private static final String OPEN_POPUP_ACTION = "openAlternateInstallations";
    private static final String CLOSE_POPUP_ACTION = "closeAlternateInstallations";
    /** The popup rows' horizontal padding, a design-token space name. */
    private static final String ROW_SIDE = "xl";

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

        primaryButton = new SegmentButton("Launch " + productName,
                "launch-" + productKey + "-button", false);
        ButtonDetail.setDetail(primaryButton,
                VersionDisplay.channelVersion(preferredChannel, preferredVersion));
        primaryButton.getAccessibleContext().setAccessibleName(
                VersionDisplay.launch(productName, preferredChannel, preferredVersion));
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

        popupMenu = new JPopupMenu();
        popupMenu.setName("launch-" + productKey + "-popup");
        // A native window keeps the community links and other home content from painting over the open list
        popupMenu.setLightWeightPopupEnabled(false);
        popupMenu.getAccessibleContext().setAccessibleName(
                "Other " + productName + " installations");
        popupMenu.getAccessibleContext().setAccessibleDescription(
                "Launch a captured registered copy without changing the preference.");
        if (hasOptions) popupMenu.add(popupHeader());
        optionItems = capturedOptions.stream().map(option -> {
            JMenuItem item = menuItem(option.label());
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

    /** A short caps frame over the rows; the rows themselves are values and stay sentence case. */
    private JLabel popupHeader() {
        JLabel header = new JLabel("Other installations");
        header.setName("launch-popup-header");
        header.setFont(scale.font(header.getFont(), "caption"));
        header.setForeground(LauncherTheme.MUTED);
        header.setBorder(scale.padding("sm", ROW_SIDE, "xs", ROW_SIDE));
        TokenPresentation.role(header, "overline");
        return header;
    }

    private JMenuItem menuItem(String label) {
        JMenuItem item = new AlternativeItem(label);
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
        // At least as wide as the control, with both right edges lined up under the arrow
        popupMenu.setPreferredSize(null);
        Dimension natural = popupMenu.getPreferredSize();
        int width = Math.max(natural.width, getWidth());
        popupMenu.setPreferredSize(new Dimension(width, natural.height));
        popupMenu.show(this, getWidth() - width, popupY(natural.height));
        JMenuItem selected = optionItems.stream().filter(JMenuItem::isEnabled)
                .findFirst().orElse(null);
        MenuSelectionManager.defaultManager().setSelectedPath(selected == null
                ? new MenuElement[]{popupMenu}
                : new MenuElement[]{popupMenu, selected});
    }

    /** Opens below the control, or above it when the screen has no room below, as near the screen bottom. */
    private int popupY(int height) {
        int gap = scale.space("xs");
        int below = getHeight() + gap;
        GraphicsConfiguration configuration = getGraphicsConfiguration();
        if (configuration == null) return below;
        Rectangle screen = configuration.getBounds();
        Insets reserved = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        int top = screen.y + reserved.top;
        int bottom = screen.y + screen.height - reserved.bottom;
        Point origin = getLocationOnScreen();
        int roomBelow = bottom - (origin.y + below);
        int roomAbove = origin.y - gap - top;
        return height > roomBelow && roomAbove > roomBelow ? -gap - height : below;
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

    /** The alternative rows, in order, without the popup's header. */
    List<JMenuItem> menuItems() {
        return optionItems;
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
                scale.scaleForGUI(MINIMUM_WIDTH)),
                Math.max(Math.max(primary.height, options.height), scale.size("control.lg")));
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(scale.scaleForGUI(MINIMUM_SHRUNK_WIDTH), scale.size("control.lg"));
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
            LauncherTheme.paintButton(canvas, getWidth(), getHeight(), scale, true, enabled, pressed, hover,
                    primaryButton.isFocusOwner() || hasOptions && optionsButton.isFocusOwner());
            if (hasOptions) {
                int separator = optionsButton.getX();
                Color divider = LauncherTheme.buttonText(true, enabled);
                canvas.setColor(new Color(divider.getRed(), divider.getGreen(), divider.getBlue(), 64));
                canvas.drawLine(separator, scale.space("md"),
                        separator, getHeight() - scale.space("md"));
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

    /**
     * One alternative: its name, then the version, channel and state on a muted second line. The item text keeps
     * the full label for screen readers and keyboard search.
     */
    private final class AlternativeItem extends JMenuItem {
        private final String title;
        private final String detail;

        private AlternativeItem(String label) {
            super(label);
            int split = label.indexOf(" · ");
            title = split < 0 ? label : label.substring(0, split);
            detail = split < 0 ? null : label.substring(split + 3);
            setFont(scale.font(getFont(), "body"));
            setOpaque(false);
            setBorder(scale.padding("md", ROW_SIDE));
        }

        private Font detailFont() {
            return scale.font(getFont(), "small");
        }

        @Override
        public Dimension getPreferredSize() {
            Insets insets = getInsets();
            FontMetrics titleMetrics = getFontMetrics(getFont());
            int width = titleMetrics.stringWidth(title);
            int height = titleMetrics.getHeight();
            if (detail != null) {
                FontMetrics detailMetrics = getFontMetrics(detailFont());
                width = Math.max(width, detailMetrics.stringWidth(detail));
                height += scale.space("xs") + detailMetrics.getHeight();
            }
            return new Dimension(width + insets.left + insets.right,
                    height + insets.top + insets.bottom);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D canvas = (Graphics2D) graphics.create();
            try {
                canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                canvas.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                boolean enabled = isEnabled();
                if (enabled && getModel().isArmed()) {
                    Color wash = javax.swing.UIManager.getColor("MenuItem.selectionBackground");
                    canvas.setColor(wash == null ? LauncherTheme.ACTIVE_BACKGROUND : wash);
                    int inset = scale.space("xs");
                    int arc = 2 * scale.radius("sm");
                    canvas.fillRoundRect(inset, 0, getWidth() - 2 * inset, getHeight(), arc, arc);
                }
                Insets insets = getInsets();
                FontMetrics titleMetrics = canvas.getFontMetrics(getFont());
                canvas.setFont(getFont());
                canvas.setColor(enabled ? LauncherTheme.TEXT : LauncherTheme.DISABLED_TEXT);
                int y = insets.top + titleMetrics.getAscent();
                canvas.drawString(title, insets.left, y);
                if (detail != null) {
                    Font font = detailFont();
                    FontMetrics detailMetrics = canvas.getFontMetrics(font);
                    canvas.setFont(font);
                    canvas.setColor(enabled ? LauncherTheme.MUTED : LauncherTheme.DISABLED_TEXT);
                    canvas.drawString(detail, insets.left, y + titleMetrics.getDescent()
                            + scale.space("xs") + detailMetrics.getAscent());
                }
            } finally {
                canvas.dispose();
            }
        }
    }

    private final class SegmentButton extends JButton {
        private SegmentButton(String text, String name, boolean arrow) {
            super(text);
            setName(name);
            getAccessibleContext().setAccessibleName(text);
            setFont(scale.font(getFont(), "title"));
            setBorder(scale.padding("md", arrow ? "md" : "xl"));
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            LauncherTheme.repaintOwnerOnFocusChange(this, HomeLaunchSplitButton.this);
            setRolloverEnabled(true);
            TokenPresentation.role(this, "button");
        }

        @Override
        public void updateUI() {
            setUI(new BasicButtonUI() {
                @Override
                protected void paintText(Graphics graphics, JComponent component,
                                         Rectangle textRectangle, String value) {
                    Graphics2D text = (Graphics2D) graphics.create();
                    try {
                        ButtonDetail.paint(text, SegmentButton.this, scale, textRectangle, value,
                                LauncherTheme.buttonText(true, isEnabled()));
                    } finally {
                        text.dispose();
                    }
                }
            });
        }

        @Override
        public Dimension getPreferredSize() {
            return ButtonDetail.grow(this, scale, super.getPreferredSize());
        }
    }

    private static final class DownArrowIcon implements Icon {
        private final int width;
        private final int height;

        private DownArrowIcon(GuiScale scale) {
            width = scale.size("icon.sm");
            height = width / 2;
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
                canvas.setColor(LauncherTheme.buttonText(true, component.isEnabled()));
                canvas.fillPolygon(new int[]{x, x + width, x + width / 2},
                        new int[]{y, y, y + height}, 3);
            } finally {
                canvas.dispose();
            }
        }
    }
}
