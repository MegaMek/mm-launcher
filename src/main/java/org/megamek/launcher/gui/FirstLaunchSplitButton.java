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

import org.megamek.launcher.channel.QuickInstallOption;
import org.megamek.launcher.channel.QuickInstallSnapshot;
import org.megamek.launcher.release.OfficialRepository;

import javax.swing.AbstractAction;
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
import javax.swing.plaf.basic.BasicButtonUI;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
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
    static final String PRIMARY_LABEL = "Install latest";
    static final String PRIMARY_SUBJECT = "MekHQ Core";
    static final String PRIMARY_ACCESSIBLE_NAME =
            VersionDisplay.installLatest("MekHQ", "Core", null);
    /** The first-run launch command's minimum width; the design tokens have no width for it. */
    private static final int MINIMUM_WIDTH = 248;
    private static final String OPEN_POPUP_ACTION = "openFirstLaunchDownloadOptions";
    private static final String CLOSE_POPUP_ACTION = "closeFirstLaunchDownloadOptions";

    private final GuiScale scale;
    private final SegmentButton primaryButton;
    private final SegmentButton optionsButton;
    private final JPopupMenu popupMenu;
    private final Map<QuickInstallOption.Key, JMenuItem> optionItems = new LinkedHashMap<>();
    private final JMenuItem retryItem;
    private final Runnable unavailableRetryAction;
    private final boolean showExperimental;
    private final int choiceCount;
    private Set<QuickInstallOption.Key> availableOptions = Set.of();
    private OptionState optionState = OptionState.LOADING;
    private boolean disposed;

    FirstLaunchSplitButton(GuiScale scale, Runnable primaryAction,
                           Consumer<QuickInstallOption.Key> optionAction,
                           Runnable unavailableRetryAction) {
        this(scale, primaryAction, optionAction, unavailableRetryAction, false);
    }

    /** Experimental choices appear in the menu only when the player has opted in to see them. */
    FirstLaunchSplitButton(GuiScale scale, Runnable primaryAction,
                           Consumer<QuickInstallOption.Key> optionAction,
                           Runnable unavailableRetryAction, boolean showExperimental) {
        this.scale = scale;
        this.showExperimental = showExperimental;
        this.choiceCount = QuickInstallSnapshot.keys(showExperimental).size();
        this.unavailableRetryAction = java.util.Objects.requireNonNull(
                unavailableRetryAction, "unavailableRetryAction");
        setName("firstLaunchSplitButton");
        setLayout(new BorderLayout());
        setOpaque(false);
        setAlignmentX(Component.LEFT_ALIGNMENT);
        getAccessibleContext().setAccessibleName("Install latest official applications");
        getAccessibleContext().setAccessibleDescription(
                "Install the latest MekHQ Core, or open the adjacent menu of other choices.");

        primaryButton = new SegmentButton(PRIMARY_LABEL, PRIMARY_NAME, false);
        primaryButton.setMnemonic(KeyEvent.VK_D);
        ButtonDetail.setDetail(primaryButton, PRIMARY_SUBJECT);
        primaryButton.getAccessibleContext().setAccessibleName(PRIMARY_ACCESSIBLE_NAME);
        primaryButton.getAccessibleContext().setAccessibleDescription(
                "Review and install the latest official Core MekHQ bundle containing "
                        + "MegaMek, MekHQ, and MegaMekLab.");
        primaryButton.addActionListener(event -> {
            if (!disposed) primaryAction.run();
        });

        optionsButton = new SegmentButton("", OPTIONS_NAME, true);
        optionsButton.setIcon(new DownArrowIcon(scale));
        optionsButton.setHorizontalAlignment(SwingConstants.CENTER);
        optionsButton.setToolTipText(optionsTooltip());
        optionsButton.getAccessibleContext().setAccessibleName(optionsAccessibleName());
        optionsButton.getAccessibleContext().setAccessibleDescription(
                "Open the quick-install menu. Opening it performs no network request.");
        optionsButton.addActionListener(event -> openPopup());

        popupMenu = new JPopupMenu();
        popupMenu.setName("downloadOptionsPopup");
        popupMenu.getAccessibleContext().setAccessibleName("Latest application choices");
        popupMenu.getAccessibleContext().setAccessibleDescription(
                menuChoices() + ". Opening this menu performs no network request.");
        for (QuickInstallOption.Key key : QuickInstallSnapshot.menuKeys(showExperimental)) {
            JMenuItem item = menuItem(menuLabel(key, "Loading…"), menuItemName(key),
                    menuLabel(key) + " version metadata is loading.");
            item.setEnabled(false);
            item.addActionListener(event -> invokeMenuAction(key, optionAction));
            optionItems.put(key, item);
            popupMenu.add(item);
        }
        retryItem = menuItem("Retry version check", "retryQuickInstallMetadataMenuItem",
                "Retry loading the official current install choices.");
        retryItem.addActionListener(event -> {
            closePopup();
            if (!disposed && isEnabled() && (optionState == OptionState.FAILED
                    || availableOptions.size() < choiceCount)) {
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

    private JMenuItem menuItem(String text, String name, String description) {
        JMenuItem item = new JMenuItem(text);
        item.setName(name);
        item.setFont(scale.font(item.getFont(), "body"));
        item.setBorder(scale.padding("md", "lg"));
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
        if (snapshot.experimentalShown() != showExperimental) {
            throw new IllegalArgumentException("quick-install snapshot does not match the "
                    + "Experimental releases setting");
        }
        QuickInstallOption primary = snapshot.option(QuickInstallSnapshot.DEFAULT_KEY);
        setPrimaryValue(primary.available() ? primary.version() : "Unavailable");
        optionState = OptionState.READY;
        popupMenu.remove(retryItem);
        primaryButton.getAccessibleContext().setAccessibleDescription(
                primary.available()
                        ? "Review and install this captured official Core MekHQ bundle containing "
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
        if (availableOptions.size() < choiceCount) popupMenu.add(retryItem);
        popupMenu.getAccessibleContext().setAccessibleDescription(
                menuChoices() + " with versions or explicit unavailable reasons. "
                        + "Opening this menu performs no network request.");
        applyEnabledState();
        popupMenu.revalidate();
        popupMenu.repaint();
    }

    void setOptionsLoading() {
        if (disposed) return;
        setPrimaryValue("Loading…");
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
                menuChoices() + " are loading. Opening this menu performs no "
                        + "network request.");
        applyEnabledState();
        popupMenu.revalidate();
        popupMenu.repaint();
    }

    void setOptionsUnavailable(String detail) {
        if (disposed) return;
        closePopup();
        setPrimaryValue("Unavailable");
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
                menuChoices() + " are unavailable. Retry version check is the only "
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

    /** The caps label stays "Install latest"; the version or state shows as sentence-case detail. */
    private void setPrimaryValue(String value) {
        ButtonDetail.setDetail(primaryButton, PRIMARY_SUBJECT + " · " + value);
        primaryButton.getAccessibleContext().setAccessibleName(
                menuLabel(QuickInstallSnapshot.DEFAULT_KEY, value));
        revalidate();
        repaint();
    }

    void openPopup() {
        if (disposed || !isEnabled() || !optionsButton.isEnabled() || popupMenu.isVisible()
                || !isShowing()) {
            return;
        }
        int x = Math.max(0, getWidth() - popupMenu.getPreferredSize().width);
        popupMenu.show(this, x, Math.max(0, getHeight() - scale.space("xs")));
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
        optionsButton.getAccessibleContext().setAccessibleName(optionsAccessibleName());
        optionsButton.setToolTipText(optionsTooltip());
        optionsButton.getAccessibleContext().setAccessibleDescription(
                "Open the quick-install menu. Opening it performs no "
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
                || ready && availableOptions.size() < choiceCount);
        if (!ready && !retry && popupMenu != null) closePopup();
    }

    private enum OptionState {
        LOADING,
        READY,
        FAILED
    }

    private String optionsTooltip() {
        return showExperimental
                ? "Choose another official application, on Core (for playing) or Experimental "
                        + "(help us test, may break things)."
                : "Choose another official Core application.";
    }

    private String optionsAccessibleName() {
        return showExperimental ? "Choose another application and channel"
                : "Choose another application";
    }

    private String menuChoices() {
        return showExperimental ? "Official Core and Experimental choices"
                : "Official Core choices";
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

    @Override
    public Dimension getPreferredSize() {
        Dimension primary = primaryButton.getPreferredSize();
        Dimension options = optionsButton.getPreferredSize();
        return new Dimension(Math.max(primary.width + options.width, scale.scaleForGUI(MINIMUM_WIDTH)),
                Math.max(Math.max(primary.height, options.height), scale.size("control.lg")));
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
            LauncherTheme.paintButton(canvas, getWidth(), getHeight(), scale, true, enabled, pressed, hover,
                    primaryButton.isFocusOwner() || optionsButton.isFocusOwner());
            int separator = optionsButton.getX();
            Color divider = LauncherTheme.buttonText(true, enabled);
            canvas.setColor(new Color(divider.getRed(), divider.getGreen(), divider.getBlue(), 64));
            canvas.drawLine(separator, scale.space("md"),
                    separator, getHeight() - scale.space("md"));
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
            setFont(scale.font(getFont(), "title"));
            setBorder(scale.padding("md", arrow ? "lg" : "xl"));
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            LauncherTheme.repaintOwnerOnFocusChange(this, FirstLaunchSplitButton.this);
            setRolloverEnabled(true);
            TokenPresentation.role(this, "button");
        }

        @Override
        public void updateUI() {
            setUI(new BasicButtonUI() {
                @Override
                protected void paintText(Graphics graphics, JComponent component,
                                         Rectangle textRectangle, String value) {
                    Graphics2D textGraphics = (Graphics2D) graphics.create();
                    try {
                        ButtonDetail.paint(textGraphics, SegmentButton.this, scale, textRectangle,
                                value, LauncherTheme.buttonText(true, isEnabled()));
                    } finally {
                        textGraphics.dispose();
                    }
                }
            });
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension preferred = ButtonDetail.grow(this, scale, super.getPreferredSize());
            int height = scale.size("control.lg");
            if (arrow) {
                return new Dimension(Math.max(preferred.width, height), Math.max(preferred.height, height));
            }
            // The primary segment fills the split button's minimum width beside the square arrow segment.
            return new Dimension(Math.max(preferred.width, scale.scaleForGUI(MINIMUM_WIDTH) - height),
                    Math.max(preferred.height, height));
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
