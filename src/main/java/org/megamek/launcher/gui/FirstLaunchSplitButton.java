package org.megamek.launcher.gui;

import org.megamek.launcher.channel.FollowChannel;
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
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
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
    static final Color POPUP_BACKGROUND = new Color(22, 36, 40);
    static final Color POPUP_FOREGROUND = new Color(237, 243, 237);
    static final Color POPUP_MUTED = new Color(166, 186, 181);
    static final Color POPUP_SELECTION = new Color(226, 196, 125);
    static final Color POPUP_SELECTION_FOREGROUND = new Color(25, 34, 30);
    static final Color POPUP_BORDER = new Color(192, 159, 88);
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
                "Install the latest MekHQ Milestone, or open the adjacent five-choice menu.");

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
                "Choose another official application and Milestone or Development channel.");
        optionsButton.getAccessibleContext().setAccessibleName(
                "Choose another application and channel");
        optionsButton.getAccessibleContext().setAccessibleDescription(
                "Open the five-choice quick-install menu. Opening it performs no network request.");
        optionsButton.addActionListener(event -> openPopup());

        popupMenu = new StyledPopupMenu(palette(), scale);
        popupMenu.setName("downloadOptionsPopup");
        popupMenu.getAccessibleContext().setAccessibleName("Latest application choices");
        popupMenu.getAccessibleContext().setAccessibleDescription(
                "Five validated official Milestone or Development choices. "
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
                "Retry loading the six official current install choices.", palette());
        retryItem.addActionListener(event -> {
            closePopup();
            if (!disposed && isEnabled() && optionState == OptionState.FAILED) {
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
        setPrimaryLabel(menuLabel(QuickInstallSnapshot.DEFAULT_KEY,
                snapshot.option(QuickInstallSnapshot.DEFAULT_KEY).version()));
        optionState = OptionState.READY;
        popupMenu.remove(retryItem);
        primaryButton.getAccessibleContext().setAccessibleDescription(
                "Review and install this captured official Milestone MekHQ bundle containing "
                        + "MegaMek, MekHQ, and MegaMekLab.");
        getAccessibleContext().setAccessibleDescription(
                "Install one of the six ready, validated official application choices.");
        setNormalOptionsDescription();
        Map<QuickInstallOption.Key, String> labels = new LinkedHashMap<>();
        for (QuickInstallOption.Key key : QuickInstallSnapshot.MENU_KEYS) {
            labels.put(key, menuLabel(key, snapshot.option(key).version()));
        }
        availableOptions = Set.copyOf(labels.keySet());
        for (Map.Entry<QuickInstallOption.Key, String> entry : labels.entrySet()) {
            JMenuItem item = optionItems.get(entry.getKey());
            item.setText(entry.getValue());
            item.setToolTipText(null);
            item.getAccessibleContext().setAccessibleName(entry.getValue());
            item.getAccessibleContext().setAccessibleDescription(
                    entry.getValue() + ". Review this exact normal-install choice.");
        }
        popupMenu.getAccessibleContext().setAccessibleDescription(
                "Five validated official choices with versions. "
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
                "Five official choices are loading. Opening this menu performs no "
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
                "Five official choices are unavailable. Retry version check is the only "
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
                "Choose another official application and Milestone or Development channel.");
        optionsButton.getAccessibleContext().setAccessibleDescription(
                "Open the five-choice quick-install menu. Opening it performs no "
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
        primaryButton.setEnabled(ready);
        primaryButton.setFocusable(ready);
        optionsButton.setEnabled(ready || retry);
        optionsButton.setFocusable(ready || retry);
        optionItems.forEach((key, item) ->
                item.setEnabled(ready && availableOptions.contains(key)));
        if (retryItem != null) retryItem.setEnabled(retry);
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
        return switch (key.repository()) {
            case MEKHQ -> "MekHQ";
            case MEGAMEK -> "MegaMek";
            case LAB -> "MegaMekLab";
        };
    }

    private static String menuItemName(QuickInstallOption.Key key) {
        if (key.repository() == OfficialRepository.MEGAMEK
                && key.channel() == FollowChannel.MILESTONE) {
            return "latestMegaMekMilestoneMenuItem";
        }
        if (key.repository() == OfficialRepository.LAB
                && key.channel() == FollowChannel.MILESTONE) {
            return "latestMegaMekLabMilestoneMenuItem";
        }
        if (key.repository() == OfficialRepository.MEKHQ) {
            return "latestMekHQDevelopmentMenuItem";
        }
        if (key.repository() == OfficialRepository.MEGAMEK) {
            return "latestMegaMekDevelopmentMenuItem";
        }
        return "latestMegaMekLabDevelopmentMenuItem";
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
                    BorderFactory.createLineBorder(palette.border(), scale.scaleForGUI(2)),
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
            int inset = scale.scaleForGUI(3);
            int cut = scale.scaleForGUI(8);
            int right = getWidth() - inset - 1;
            int bottom = getHeight() - inset - 1;
            Polygon plate = new Polygon(
                    new int[]{inset + cut, right, right, right - cut, inset, inset},
                    new int[]{inset, inset, bottom - cut, bottom, bottom, inset + cut}, 6);
            boolean pressed = primaryButton.getModel().isPressed()
                    && primaryButton.getModel().isArmed()
                    || optionsButton.getModel().isPressed()
                    && optionsButton.getModel().isArmed();
            boolean hover = primaryButton.getModel().isRollover()
                    || optionsButton.getModel().isRollover();
            Color top = new Color(226, 196, 125);
            Color lower = new Color(192, 159, 88);
            if (!isEnabled() || !primaryButton.isEnabled() || !optionsButton.isEnabled()) {
                top = new Color(54, 68, 69);
                lower = new Color(43, 55, 57);
            } else if (pressed) {
                top = lower;
            } else if (hover) {
                top = top.brighter();
                lower = lower.brighter();
            }
            canvas.setPaint(new GradientPaint(0, inset, top, 0, bottom, lower));
            canvas.fillPolygon(plate);
            canvas.setColor(isEnabled()
                    ? new Color(239, 211, 147) : new Color(90, 105, 104));
            canvas.setStroke(new BasicStroke(scale.scaleForGUI(1f)));
            canvas.drawPolygon(plate);

            int separator = optionsButton.getX();
            canvas.setColor(isEnabled()
                    ? new Color(143, 115, 58) : new Color(74, 88, 88));
            canvas.drawLine(separator, inset + scale.scaleForGUI(4),
                    separator, bottom - scale.scaleForGUI(4));
            if (primaryButton.isFocusOwner() || optionsButton.isFocusOwner()) {
                canvas.setColor(new Color(246, 238, 207));
                canvas.setStroke(new BasicStroke(scale.scaleForGUI(2f)));
                canvas.drawPolygon(plate);
            }
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
            setRolloverEnabled(true);
            setUI(new BasicButtonUI() {
                @Override
                protected void paintText(Graphics graphics, JComponent component,
                                         Rectangle textRectangle, String value) {
                    Graphics2D textGraphics = (Graphics2D) graphics.create();
                    try {
                        textGraphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                        textGraphics.setColor(isEnabled()
                                ? new Color(25, 34, 30) : new Color(159, 174, 171));
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
                canvas.setColor(component.isEnabled()
                        ? new Color(25, 34, 30) : new Color(159, 174, 171));
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
