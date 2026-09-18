package org.megamek.launcher.gui;

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
import javax.swing.plaf.basic.BasicButtonUI;
import javax.swing.plaf.basic.BasicGraphicsUtils;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/**
 * A real two-segment first-run command: the large segment performs the safe default while the
 * narrow segment opens alternate download choices. Both segments remain ordinary accessible
 * push buttons, while this parent paints one joined plate and one separator.
 */
final class FirstLaunchSplitButton extends JPanel {
    static final String PRIMARY_NAME = "downloadAndInstallButton";
    static final String OPTIONS_NAME = "downloadOptionsButton";
    static final String DEVELOPMENT_ITEM_NAME = "latestDevelopmentMenuItem";
    static final String EXACT_ITEM_NAME = "chooseAnotherVersionMenuItem";
    private static final String OPEN_POPUP_ACTION = "openFirstLaunchDownloadOptions";
    private static final String CLOSE_POPUP_ACTION = "closeFirstLaunchDownloadOptions";

    private final GuiScale scale;
    private final SegmentButton primaryButton;
    private final SegmentButton optionsButton;
    private final JPopupMenu popupMenu = new JPopupMenu();
    private boolean disposed;

    FirstLaunchSplitButton(GuiScale scale, Runnable primaryAction,
                           Runnable developmentAction, Runnable exactReleaseAction) {
        this.scale = scale;
        setName("firstLaunchSplitButton");
        setLayout(new BorderLayout());
        setOpaque(false);
        setAlignmentX(Component.LEFT_ALIGNMENT);
        getAccessibleContext().setAccessibleName("Download and install");
        getAccessibleContext().setAccessibleDescription(
                "Install the latest Milestone, or open the adjacent menu for another choice.");

        primaryButton = new SegmentButton("Download & install", PRIMARY_NAME, false);
        primaryButton.setMnemonic(KeyEvent.VK_D);
        primaryButton.setToolTipText("Install the latest official Milestone MekHQ bundle.");
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
                "Choose Latest Development or another application and exact release.");
        optionsButton.getAccessibleContext().setAccessibleName(
                "Choose another version or application");
        optionsButton.getAccessibleContext().setAccessibleDescription(
                "Open a menu for Latest Development or the full version and application picker.");
        optionsButton.addActionListener(event -> openPopup());

        JMenuItem development = menuItem("Latest Development", DEVELOPMENT_ITEM_NAME,
                "Review and install the exact official Development MekHQ bundle.");
        development.addActionListener(event -> invokeMenuAction(developmentAction));
        JMenuItem exact = menuItem("Choose another version or application…", EXACT_ITEM_NAME,
                "Open the full product, exact release, and channel picker.");
        exact.addActionListener(event -> invokeMenuAction(exactReleaseAction));
        popupMenu.setName("downloadOptionsPopup");
        popupMenu.getAccessibleContext().setAccessibleName("Download and install options");
        popupMenu.getAccessibleContext().setAccessibleDescription(
                "Contains Latest Development and the full release picker.");
        popupMenu.add(development);
        popupMenu.add(exact);

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
    }

    private JMenuItem menuItem(String text, String name, String description) {
        JMenuItem item = new JMenuItem(text);
        item.setName(name);
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

    private void invokeMenuAction(Runnable action) {
        closePopup();
        if (!disposed && isEnabled()) action.run();
    }

    void openPopup() {
        if (disposed || !isEnabled() || !optionsButton.isEnabled() || popupMenu.isVisible()
                || !isShowing()) {
            return;
        }
        int x = Math.max(0, getWidth() - popupMenu.getPreferredSize().width);
        popupMenu.show(this, x, Math.max(0, getHeight() - scale.scaleForGUI(3)));
        MenuElement[] items = popupMenu.getSubElements();
        if (items.length > 0) {
            MenuSelectionManager.defaultManager().setSelectedPath(
                    new MenuElement[]{popupMenu, items[0]});
        }
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
        if (primaryButton != null) primaryButton.setEnabled(enabled);
        if (optionsButton != null) optionsButton.setEnabled(enabled);
        if (!enabled && popupMenu != null) closePopup();
        repaint();
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

        @Override
        protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (!isFocusOwner()) return;
            Graphics2D canvas = (Graphics2D) graphics.create();
            try {
                canvas.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                canvas.setColor(new Color(246, 238, 207));
                canvas.setStroke(new BasicStroke(scale.scaleForGUI(2f)));
                int inset = scale.scaleForGUI(3);
                canvas.drawRect(inset, inset, Math.max(0, getWidth() - inset * 2 - 1),
                        Math.max(0, getHeight() - inset * 2 - 1));
            } finally {
                canvas.dispose();
            }
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
