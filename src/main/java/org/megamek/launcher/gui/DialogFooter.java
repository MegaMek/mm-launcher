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
import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FocusTraversalPolicy;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * The one button row every launcher dialog uses, so all of them look and answer keys alike.
 *
 * <ul>
 *   <li>The actions are centred, equally wide (at least 88 scaled pixels) and ordered main action
 *       first, then secondary actions, then Cancel; a right-to-left window mirrors the row.</li>
 *   <li>Small helper commands (Copy, View logs, a link) sit at the footer's leading end as tools.</li>
 *   <li>Enter presses the focused button, otherwise the main action; Escape cancels.</li>
 *   <li>A destructive main action is never the default button: focus starts on Cancel and Enter
 *       presses the destructive button only when it has focus.</li>
 * </ul>
 */
final class DialogFooter {
    static final int MINIMUM_BUTTON_WIDTH = 88;
    /** Root-pane action key of the Escape binding {@link #install} adds. */
    static final String ESCAPE_ACTION = "dialogFooter.escape";

    private final GuiScale scale;
    private final List<JComponent> tools = new ArrayList<>();
    private final List<AbstractButton> secondaries = new ArrayList<>();
    private AbstractButton main;
    private boolean danger;
    private AbstractButton cancel;
    private JPanel panel;

    DialogFooter(GuiScale scale) {
        this.scale = scale;
    }

    /** The main, safe action: first in the row and the default button. */
    DialogFooter primary(AbstractButton button) {
        requireNoMain();
        main = button;
        return this;
    }

    /** A destructive main action: first in the row, but never the default button. */
    DialogFooter danger(AbstractButton button) {
        requireNoMain();
        main = button;
        danger = true;
        return this;
    }

    /** Another choice, placed after the main action in the order added. */
    DialogFooter secondary(AbstractButton button) {
        secondaries.add(button);
        return this;
    }

    /** The action that leaves without changing anything; always last, and what Escape presses. */
    DialogFooter cancel(AbstractButton button) {
        if (cancel != null) throw new IllegalStateException("A dialog footer has one cancel action");
        cancel = button;
        return this;
    }

    /** A small helper command at the footer's leading end, outside the action row. */
    DialogFooter tool(JComponent component) {
        tools.add(component);
        return this;
    }

    AbstractButton main() {
        return main;
    }

    AbstractButton cancelButton() {
        return cancel;
    }

    List<AbstractButton> actions() {
        List<AbstractButton> actions = new ArrayList<>();
        if (main != null) actions.add(main);
        actions.addAll(secondaries);
        if (cancel != null) actions.add(cancel);
        return actions;
    }

    /** The footer component; build it once all its buttons are added. */
    JPanel panel() {
        if (panel == null) {
            List<AbstractButton> actions = actions();
            panel = new JPanel(new FooterLayout(tools, actions, scale));
            panel.setOpaque(false);
            tools.forEach(panel::add);
            actions.forEach(panel::add);
        }
        return panel;
    }

    /**
     * Wires the dialog's keys and first focus: the default button, Enter on a focused button, and
     * Escape, which runs {@code onEscape} or, without one, presses Cancel.
     */
    void install(JDialog dialog, Runnable onEscape) {
        JRootPane root = dialog.getRootPane();
        root.setDefaultButton(danger || !(main instanceof JButton button) ? null : button);
        Runnable escape = onEscape != null ? onEscape : cancel != null ? cancel::doClick : dialog::dispose;
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), ESCAPE_ACTION);
        root.getActionMap().put(ESCAPE_ACTION, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                escape.run();
            }
        });
        for (AbstractButton button : actions()) {
            pressOnEnter(button);
        }
        for (JComponent tool : tools) {
            if (tool instanceof AbstractButton button) pressOnEnter(button);
        }
        FocusTraversalPolicy base = dialog.getFocusTraversalPolicy();
        if (base != null) dialog.setFocusTraversalPolicy(new InitialFocus(base));
        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent event) {
                keepFooterVisible(dialog, panel());
            }
        });
    }

    /**
     * Stops {@code window} from being narrowed below {@code footer}'s full row, so no action is
     * clipped; the screen's usable width still wins. Call once the window is laid out.
     */
    static void keepFooterVisible(Window window, JComponent footer) {
        if (!footer.isShowing()) return;
        int needed = window.getWidth() - footer.getWidth() + footer.getPreferredSize().width;
        Rectangle available = GuiScale.usableBounds(window.getGraphicsConfiguration());
        needed = Math.min(needed, available.width);
        Dimension minimum = window.getMinimumSize();
        if (minimum.width < needed) window.setMinimumSize(new Dimension(needed, minimum.height));
        if (window.getWidth() < needed) window.setSize(needed, window.getHeight());
    }

    private void requireNoMain() {
        if (main != null) throw new IllegalStateException("A dialog footer has one main action");
    }

    /** Makes Enter press {@code button} while it has focus, like Space, ahead of the default button. */
    static void pressOnEnter(AbstractButton button) {
        button.getInputMap(JComponent.WHEN_FOCUSED)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "dialogFooter.press");
        button.getActionMap().put("dialogFooter.press", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                if (button.isEnabled()) button.doClick();
            }
        });
    }

    private static boolean focusable(Component component) {
        return component != null && component.isShowing() && component.isEnabled() && component.isFocusable();
    }

    /** Starts on Cancel for a destructive choice, and on the main action rather than a lesser button. */
    private final class InitialFocus extends FocusTraversalPolicy {
        private final FocusTraversalPolicy base;

        InitialFocus(FocusTraversalPolicy base) {
            this.base = base;
        }

        @Override
        public Component getInitialComponent(Window window) {
            Component initial = base.getInitialComponent(window);
            if (danger && focusable(cancel)) return cancel;
            boolean footerButton = initial != null && initial != main
                    && (tools.contains(initial) || actions().contains(initial));
            if ((initial == null || footerButton) && focusable(main)) return main;
            return initial;
        }

        @Override
        public Component getComponentAfter(Container root, Component component) {
            return base.getComponentAfter(root, component);
        }

        @Override
        public Component getComponentBefore(Container root, Component component) {
            return base.getComponentBefore(root, component);
        }

        @Override
        public Component getFirstComponent(Container root) {
            return base.getFirstComponent(root);
        }

        @Override
        public Component getLastComponent(Container root) {
            return base.getLastComponent(root);
        }

        @Override
        public Component getDefaultComponent(Container root) {
            return base.getDefaultComponent(root);
        }
    }

    /**
     * Tools packed at the leading edge; the actions in equal cells centred on the whole width, nudged
     * past the tools when they would overlap. Hidden components take no space.
     */
    static final class FooterLayout implements LayoutManager {
        private final List<? extends Component> tools;
        private final List<? extends Component> actions;
        private final GuiScale scale;

        /** Lays out the given lists as they are at each layout, so callers may add to them later. */
        FooterLayout(List<? extends Component> tools, List<? extends Component> actions, GuiScale scale) {
            this.tools = tools;
            this.actions = actions;
            this.scale = scale;
        }

        @Override
        public void addLayoutComponent(String name, Component component) {
        }

        @Override
        public void removeLayoutComponent(Component component) {
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            Insets insets = parent.getInsets();
            int toolsWidth = toolsWidth();
            int rowWidth = rowWidth();
            int width = toolsWidth + rowWidth + (toolsWidth > 0 && rowWidth > 0 ? gap() : 0);
            int height = 0;
            for (Component component : visible(tools)) {
                height = Math.max(height, component.getPreferredSize().height);
            }
            for (Component component : visible(actions)) {
                height = Math.max(height, component.getPreferredSize().height);
            }
            return new Dimension(width + insets.left + insets.right, height + insets.top + insets.bottom);
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return preferredLayoutSize(parent);
        }

        @Override
        public void layoutContainer(Container parent) {
            Insets insets = parent.getInsets();
            int width = parent.getWidth() - insets.left - insets.right;
            int height = parent.getHeight() - insets.top - insets.bottom;
            boolean leftToRight = parent.getComponentOrientation().isLeftToRight();
            int x = 0;
            for (Component tool : visible(tools)) {
                place(tool, x, tool.getPreferredSize().width, insets, width, height, leftToRight);
                x += tool.getPreferredSize().width + gap();
            }
            int toolsEnd = x;
            int rowWidth = rowWidth();
            int start = Math.max((width - rowWidth) / 2, toolsEnd);
            int cell = cellWidth();
            for (Component action : visible(actions)) {
                place(action, start, cell, insets, width, height, leftToRight);
                start += cell + gap();
            }
        }

        private void place(Component component, int x, int componentWidth, Insets insets, int width,
                           int height, boolean leftToRight) {
            int componentHeight = Math.min(height, component.getPreferredSize().height);
            int left = leftToRight ? x : width - x - componentWidth;
            component.setBounds(insets.left + left, insets.top + (height - componentHeight) / 2,
                    componentWidth, componentHeight);
        }

        int cellWidth() {
            int cell = scale.scaleForGUI(MINIMUM_BUTTON_WIDTH);
            for (Component action : visible(actions)) {
                cell = Math.max(cell, action.getPreferredSize().width);
            }
            return cell;
        }

        private int rowWidth() {
            int count = visible(actions).size();
            return count == 0 ? 0 : count * cellWidth() + (count - 1) * gap();
        }

        private int toolsWidth() {
            List<Component> shown = visible(tools);
            int width = 0;
            for (Component tool : shown) {
                width += tool.getPreferredSize().width;
            }
            return shown.isEmpty() ? 0 : width + (shown.size() - 1) * gap();
        }

        private int gap() {
            return scale.space("md");
        }

        private static List<Component> visible(List<? extends Component> components) {
            List<Component> shown = new ArrayList<>();
            for (Component component : components) {
                if (component.isVisible()) shown.add(component);
            }
            return shown;
        }
    }
}
