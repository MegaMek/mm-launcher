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

import org.megamek.launcher.channel.QuickInstallOption;
import org.megamek.launcher.channel.QuickInstallSnapshot;
import org.megamek.launcher.release.OfficialRepository;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.KeyStroke;
import javax.swing.MenuElement;
import javax.swing.MenuSelectionManager;
import javax.swing.JPanel;
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
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * A real two-segment first-run command: the large segment performs the safe default while the
 * narrow segment opens alternate download choices. Both segments remain ordinary accessible
 * push buttons, while this parent paints one joined plate and one separator.
 */
final class FirstLaunchSplitButton extends JPanel {
    static final String PRIMARY_NAME = "downloadAndInstallButton";
    static final String OPTIONS_NAME = "downloadOptionsButton";
    static final String PRIMARY_LABEL =
            VersionDisplay.installLatest("MekHQ", "Milestone", null);
    static final Color POPUP_BACKGROUND = LauncherTheme.POPUP_BACKGROUND;
    static final Color POPUP_FOREGROUND = LauncherTheme.TEXT;
    static final Color POPUP_MUTED = LauncherTheme.MUTED;
    static final Color POPUP_SELECTION = LauncherTheme.ACTIVE_BACKGROUND;
    static final Color POPUP_SELECTION_FOREGROUND = LauncherTheme.ACCENT;
    static final Color POPUP_BORDER = LauncherTheme.BORDER;
    private static final String OPEN_POPUP_ACTION = "openFirstLaunchDownloadOptions";
    private static final String CLOSE_POPUP_ACTION = "closeFirstLaunchDownloadOptions";

    private final GuiScale scale;
    private final SegmentButton primaryButton;
    private final SegmentButton optionsButton;
    private final JPopupMenu popupMenu;
    private final Map<QuickInstallOption.Key, JMenuItem> optionItems = new LinkedHashMap<>();
    private final JMenuItem retryItem;
    private final Runnable unavailableRetryAction;
    private Set<QuickInstallOption.Key> availableOptions = Set.of();
    private OptionState optionState = OptionState.LOADING;
    private boolean disposed;

    FirstLaunchSplitButton(GuiScale scale, Runnable primaryAction,
                           Consumer<QuickInstallOption.Key> optionAction,
                           Runnable unavailableRetryAction) {
        this.scale = scale;
        this.unavailableRetryAction = java.util.Objects.requireNonNull(
                unavailableRetryAction, "unavailableRetryAction");
        setName("firstLaunchSplitButton");
        setLayout(new BorderLayout());
        setOpaque(false);
        setAlignmentX(Component.LEFT_ALIGNMENT);
        getAccessibleContext().setAccessibleName("Install latest official applications");
        getAccessibleContext().setAccessibleDescription(
                "Install the latest MekHQ Milestone, or open the adjacent eight-choice menu.");

        primaryButton = new SegmentButton(PRIMARY_LABEL, PRIMARY_NAME, false);
        primaryButton.setMnemonic(KeyEvent.VK_D);
        primaryButton.getAccessibleContext().setAccessibleName(PRIMARY_LABEL);
        primaryButton.getAccessibleContext().setAccessibleDescription(
                "Review and install the latest official Milestone MekHQ bundle containing "
                        + "MegaMek, MekHQ, and MegaMekLab.");
        primaryButton.addActionListener(event -> {
            if (!disposed) primaryAction.run();
        });

        optionsButton = new SegmentButton("", OPTIONS_NAME, true);
        optionsButton.setIcon(new DownArrowIcon(scale));
        optionsButton.setHorizontalAlignment(SwingConstants.CENTER);
        optionsButton.setToolTipText(
                "Choose another official application and Milestone, Development, or Weekly channel.");
        optionsButton.getAccessibleContext().setAccessibleName(
                "Choose another application and channel");
        optionsButton.getAccessibleContext().setAccessibleDescription(
                "Open the eight-choice quick-install menu. Opening it performs no network request.");
        optionsButton.addActionListener(event -> openPopup());

        popupMenu = new StyledPopupMenu(palette(), scale);
        popupMenu.setName("downloadOptionsPopup");
        popupMenu.getAccessibleContext().setAccessibleName("Latest application choices");
        popupMenu.getAccessibleContext().setAccessibleDescription(
                "Eight official Milestone, Development, or Weekly choices. "
                        + "Opening this menu performs no network request.");
        for (QuickInstallOption.Key key : QuickInstallSnapshot.MENU_KEYS) {
            JMenuItem item = menuItem(menuLabel(key, "Loading…"), menuItemName(key),
                    menuLabel(key) + " version metadata is loading.", palette());
            item.setEnabled(false);
            item.addActionListener(event -> invokeMenuAction(key, optionAction));
            optionItems.put(key, item);
            popupMenu.add(item);
        }
        retryItem = menuItem("Retry version check", "retryQuickInstallMetadataMenuItem",
                "Retry loading the nine official current install choices.", palette());
        retryItem.addActionListener(event -> {
            closePopup();
            if (!disposed && isEnabled() && (optionState == OptionState.FAILED
                    || availableOptions.size() < QuickInstallSnapshot.ALL_KEYS.size())) {
                unavailableRetryAction.run();
            }
        });

        installOpenBindings(primaryButton);
        installOpenBindings(optionsButton);
        popupMenu.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), CLOSE_POPUP_ACTION);
        popupMenu.getActionMap().put(CLOSE_POPUP_ACTION, new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) {
                closePopup();
                optionsButton.requestFocusInWindow();
            }
        });

        primaryButton.getModel().addChangeListener(event -> repaint());
        optionsButton.getModel().addChangeListener(event -> repaint());
        add(primaryButton, BorderLayout.CENTER);
        add(optionsButton, BorderLayout.EAST);
        setOptionsLoading();
    }

    private JMenuItem menuItem(String text, String name, String description, Palette palette) {
        JMenuItem item = new JMenuItem(text);
        item.setUI(new StyledMenuItemUI(palette));
        item.setName(name);
        item.setOpaque(true);
        item.setBackground(palette.background());
        item.setForeground(palette.foreground());
        item.setFont(scale.font(item.getFont(), Font.BOLD, 13f));
        item.setBorder(BorderFactory.createEmptyBorder(scale.scaleForGUI(9),
                scale.scaleForGUI(12), scale.scaleForGUI(9), scale.scaleForGUI(12)));
        item.getAccessibleContext().setAccessibleName(text);
        item.getAccessibleContext().setAccessibleDescription(description);
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

    private void invokeMenuAction(QuickInstallOption.Key key,
                                  Consumer<QuickInstallOption.Key> action) {
        closePopup();
        if (!disposed && isEnabled() && availableOptions.contains(key)) action.accept(key);
    }

    void setOptions(QuickInstallSnapshot snapshot) {
        if (disposed) return;
        QuickInstallOption primary = snapshot.option(QuickInstallSnapshot.DEFAULT_KEY);
        setPrimaryLabel(menuLabel(QuickInstallSnapshot.DEFAULT_KEY,
                primary.available() ? primary.version() : "Unavailable"));
        optionState = OptionState.READY;
        popupMenu.remove(retryItem);
        primaryButton.getAccessibleContext().setAccessibleDescription(
                primary.available()
                        ? "Review and install this captured official Milestone MekHQ bundle containing "
                        + "MegaMek, MekHQ, and MegaMekLab."
                        : primary.unavailableReason());
        primaryButton.setToolTipText(primary.available() ? null : primary.unavailableReason());
        getAccessibleContext().setAccessibleDescription(
                "Install an available complete suite; unavailable channels are disabled.");
        setNormalOptionsDescription();
        availableOptions = snapshot.options().stream().filter(QuickInstallOption::available)
                .map(QuickInstallOption::key).collect(java.util.stream.Collectors.toUnmodifiableSet());
        for (Map.Entry<QuickInstallOption.Key, JMenuItem> entry : optionItems.entrySet()) {
            QuickInstallOption option = snapshot.option(entry.getKey());
            String label = menuLabel(entry.getKey(), option.available() ? option.version() : "Unavailable");
            JMenuItem item = entry.getValue();
            item.setText(label);
            item.setToolTipText(option.available() ? null : option.unavailableReason());
            item.getAccessibleContext().setAccessibleName(label);
            item.getAccessibleContext().setAccessibleDescription(
                    label + ". " + (option.available() ? "Review this exact normal-install choice."
                            : option.unavailableReason()));
        }
        if (availableOptions.size() < QuickInstallSnapshot.ALL_KEYS.size()) popupMenu.add(retryItem);
        popupMenu.getAccessibleContext().setAccessibleDescription(
                "Eight official choices with versions or explicit unavailable reasons. "
                        + "Opening this menu performs no network request.");
        applyEnabledState();
        popupMenu.revalidate();
        popupMenu.repaint();
    }

    void setOptionsLoading() {
        if (disposed) return;
        setPrimaryLabel(menuLabel(QuickInstallSnapshot.DEFAULT_KEY, "Loading…"));
        optionState = OptionState.LOADING;
        popupMenu.remove(retryItem);
        primaryButton.getAccessibleContext().setAccessibleDescription(
                "Install version metadata is loading. This action is unavailable.");
        optionsButton.setToolTipText("Official install choices are loading.");
        optionsButton.getAccessibleContext().setAccessibleName(
                "Install choices loading");
        optionsButton.getAccessibleContext().setAccessibleDescription(
                "Official install choices are loading. This action is unavailable.");
        getAccessibleContext().setAccessibleDescription(
                "Official install choices are loading; both install segments are unavailable.");
        availableOptions = Set.of();
        for (Map.Entry<QuickInstallOption.Key, JMenuItem> entry : optionItems.entrySet()) {
            String label = menuLabel(entry.getKey(), "Loading…");
            JMenuItem item = entry.getValue();
            item.setText(label);
            item.setToolTipText("Official channel and release metadata is loading.");
            item.getAccessibleContext().setAccessibleName(label);
            item.getAccessibleContext().setAccessibleDescription(
                    label + ". Wait for validated metadata.");
            item.setEnabled(false);
        }
        popupMenu.getAccessibleContext().setAccessibleDescription(
                "Eight official choices are loading. Opening this menu performs no "
                        + "network request.");
        applyEnabledState();
        popupMenu.revalidate();
        popupMenu.repaint();
    }

    void setOptionsUnavailable(String detail) {
        if (disposed) return;
        closePopup();
        setPrimaryLabel(menuLabel(QuickInstallSnapshot.DEFAULT_KEY, "Unavailable"));
        optionState = OptionState.FAILED;
        primaryButton.getAccessibleContext().setAccessibleDescription(
                "Install version metadata is unavailable. This action is unavailable.");
        getAccessibleContext().setAccessibleDescription(
                "Install choices are unavailable; open the Retry install version check "
                        + "segment to retry.");
        availableOptions = Set.of();
        String explanation = detail == null || detail.isBlank()
                ? "Install versions are unavailable. Choose Retry version check."
                : detail;
        for (Map.Entry<QuickInstallOption.Key, JMenuItem> entry : optionItems.entrySet()) {
            String label = menuLabel(entry.getKey(), "Unavailable");
            JMenuItem item = entry.getValue();
            item.setText(label);
            item.setToolTipText(explanation);
            item.getAccessibleContext().setAccessibleName(label);
            item.getAccessibleContext().setAccessibleDescription(label + ". " + explanation);
            item.setEnabled(false);
        }
        popupMenu.getAccessibleContext().setAccessibleDescription(
                "Eight official choices are unavailable. Retry version check is the only "
                        + "available menu command.");
        if (retryItem.getParent() != popupMenu) popupMenu.add(retryItem);
        retryItem.setToolTipText(explanation);
        retryItem.getAccessibleContext().setAccessibleDescription(
                "Explicitly retry loading the official current install choices. "
                        + explanation);
        optionsButton.setToolTipText(
                "Open the unavailable choices and select Retry version check.");
        optionsButton.getAccessibleContext().setAccessibleName("Retry install version check");
        optionsButton.getAccessibleContext().setAccessibleDescription(
                "Open the unavailable quick-install choices and explicit Retry command.");
        applyEnabledState();
        popupMenu.revalidate();
        popupMenu.repaint();
    }

    private void setPrimaryLabel(String label) {
        primaryButton.setText(label);
        primaryButton.getAccessibleContext().setAccessibleName(label);
        revalidate();
        repaint();
    }

    void openPopup() {
        if (disposed || !isEnabled() || !optionsButton.isEnabled() || popupMenu.isVisible()
                || !isShowing()) {
            return;
        }
        int x = Math.max(0, getWidth() - popupMenu.getPreferredSize().width);
        popupMenu.show(this, x, Math.max(0, getHeight() - scale.scaleForGUI(3)));
        MenuElement[] items = popupMenu.getSubElements();
        MenuElement firstEnabled = null;
        for (MenuElement item : items) {
            if (item.getComponent().isEnabled()) {
                firstEnabled = item;
                break;
            }
        }

        if (firstEnabled != null) {
            MenuSelectionManager.defaultManager().setSelectedPath(
                    new MenuElement[]{popupMenu, firstEnabled});
        } else {
            MenuSelectionManager.defaultManager().setSelectedPath(
                    new MenuElement[]{popupMenu});
        }
    }

    private void setNormalOptionsDescription() {
        optionsButton.getAccessibleContext().setAccessibleName(
                "Choose another application and channel");
        optionsButton.setToolTipText(
                "Choose another official application and Milestone, Development, or Weekly channel.");
        optionsButton.getAccessibleContext().setAccessibleDescription(
                "Open the eight-choice quick-install menu. Opening it performs no "
                        + "network request while metadata is loading or available.");
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

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        applyEnabledState();
        if (!enabled && popupMenu != null) closePopup();
        repaint();
    }

    private void applyEnabledState() {
        if (primaryButton == null || optionsButton == null || optionItems == null) return;
        boolean ready = isEnabled() && optionState == OptionState.READY;
        boolean retry = isEnabled() && optionState == OptionState.FAILED;
        boolean primaryReady = ready && availableOptions.contains(QuickInstallSnapshot.DEFAULT_KEY);
        primaryButton.setEnabled(primaryReady);
        primaryButton.setFocusable(primaryReady);
        optionsButton.setEnabled(ready || retry);
        optionsButton.setFocusable(ready || retry);
        optionItems.forEach((key, item) ->
                item.setEnabled(ready && availableOptions.contains(key)));
        if (retryItem != null) retryItem.setEnabled(retry
                || ready && availableOptions.size() < QuickInstallSnapshot.ALL_KEYS.size());
        if (!ready && !retry && popupMenu != null) closePopup();
    }

    private enum OptionState {
        LOADING,
        READY,
        FAILED
    }

    private static String menuLabel(QuickInstallOption.Key key) {
        return VersionDisplay.installLatest(productName(key), key.channel().toString(), null);
    }

    private static String menuLabel(QuickInstallOption.Key key, String version) {
        return VersionDisplay.installLatest(productName(key), key.channel().toString(), version);
    }

    private static String productName(QuickInstallOption.Key key) {
        return key.repository().productName();
    }

    private static String menuItemName(QuickInstallOption.Key key) {
        return "latest" + productName(key) + key.channel() + "MenuItem";
    }

    private static Palette palette() {
        if (!highContrast()) {
            return new Palette(POPUP_BACKGROUND, POPUP_FOREGROUND, POPUP_MUTED,
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

    @Override
    public Dimension getPreferredSize() {
        Dimension primary = primaryButton.getPreferredSize();
        Dimension options = optionsButton.getPreferredSize();
        return new Dimension(Math.max(primary.width + options.width, scale.scaleForGUI(248)),
                Math.max(Math.max(primary.height, options.height), scale.scaleForGUI(54)));
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D canvas = (Graphics2D) graphics.create();
        try {
            canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            boolean pressed = primaryButton.getModel().isPressed()
                    && primaryButton.getModel().isArmed()
                    || optionsButton.getModel().isPressed()
                    && optionsButton.getModel().isArmed();
            boolean hover = primaryButton.getModel().isRollover()
                    || optionsButton.getModel().isRollover();
            boolean enabled = isEnabled() && (primaryButton.isEnabled() || optionsButton.isEnabled());
            LauncherTheme.paintButton(canvas, getWidth(), getHeight(), scale, enabled, pressed, hover,
                    primaryButton.isFocusOwner() || optionsButton.isFocusOwner());
            int separator = optionsButton.getX();
            canvas.setColor(LauncherTheme.highContrast()
                    ? LauncherTheme.uiColor("Button.foreground", Color.WHITE) : LauncherTheme.BORDER);
            canvas.drawLine(separator, scale.scaleForGUI(4),
                    separator, getHeight() - scale.scaleForGUI(4));
        } finally {
            canvas.dispose();
        }
        super.paintComponent(graphics);
    }

    private final class SegmentButton extends JButton {
        private final boolean arrow;

        private SegmentButton(String text, String name, boolean arrow) {
            super(text);
            this.arrow = arrow;
            setName(name);
            getAccessibleContext().setAccessibleName(text);
            setFont(scale.font(getFont(), Font.BOLD, 16f));
            int vertical = scale.scaleForGUI(14);
            int horizontal = scale.scaleForGUI(arrow ? 10 : 18);
            setBorder(BorderFactory.createEmptyBorder(
                    vertical, horizontal, vertical, horizontal));
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            LauncherTheme.repaintOwnerOnFocusChange(this, FirstLaunchSplitButton.this);
            setRolloverEnabled(true);
            setUI(new BasicButtonUI() {
                @Override
                protected void paintText(Graphics graphics, JComponent component,
                                         Rectangle textRectangle, String value) {
                    Graphics2D textGraphics = (Graphics2D) graphics.create();
                    try {
                        textGraphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                        textGraphics.setColor(LauncherTheme.buttonText(true, isEnabled()));
                        BasicGraphicsUtils.drawStringUnderlineCharAt(textGraphics, value,
                                getDisplayedMnemonicIndex(), textRectangle.x,
                                textRectangle.y + textGraphics.getFontMetrics().getAscent());
                    } finally {
                        textGraphics.dispose();
                    }
                }
            });
        }

        @Override
        public javax.swing.JToolTip createToolTip() {
            return LauncherTheme.toolTip(this, scale);
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension preferred = super.getPreferredSize();
            if (arrow) {
                return new Dimension(Math.max(preferred.width, scale.scaleForGUI(46)),
                        Math.max(preferred.height, scale.scaleForGUI(54)));
            }
            return new Dimension(Math.max(preferred.width, scale.scaleForGUI(202)),
                    Math.max(preferred.height, scale.scaleForGUI(54)));
        }

    }

    private static final class DownArrowIcon implements Icon {
        private final int width;
        private final int height;

        private DownArrowIcon(GuiScale scale) {
            width = scale.scaleForGUI(13);
            height = scale.scaleForGUI(8);
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
                int middle = x + width / 2;
                Polygon arrow = new Polygon(
                        new int[]{x, x + width, middle},
                        new int[]{y, y, y + height}, 3);
                canvas.fillPolygon(arrow);
            } finally {
                canvas.dispose();
            }
        }
    }
}
