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
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.KeyEvent;
import java.util.Objects;
import java.util.function.Function;

/**
 * A design-token select: the MegaMek look and feel paints the field, arrow, focus ring and popup,
 * while this keeps Swing's combo model, keyboard actions and accessibility and adds a display-text
 * mapping and the launcher control height.
 */
final class StyledComboBox<E> extends JComboBox<E> {
    private final GuiScale scale;
    private Function<? super E, String> displayText = value ->
            value == null ? "" : value.toString();

    StyledComboBox(GuiScale scale) {
        this.scale = scale;
        applyStyle();
    }

    StyledComboBox(E[] values, GuiScale scale) {
        super(values);
        this.scale = scale;
        applyStyle();
    }

    @Override
    public void updateUI() {
        // JComboBox invokes updateUI from its constructor before this class has initialized scale.
        super.updateUI();
        if (scale != null) applyStyle();
    }

    private void applyStyle() {
        setFont(scale.font(getFont(), Font.PLAIN, 13f));
        ListCellRenderer<? super E> lookAndFeel = getRenderer();
        if (!(lookAndFeel instanceof StyledComboBox<?>.DisplayTextRenderer)) {
            setRenderer(new DisplayTextRenderer(lookAndFeel));
        }
        setFocusable(true);
        installMissingArrowBindings();
    }

    private void installMissingArrowBindings() {
        for (int direction : new int[]{1, -1}) {
            KeyStroke key = KeyStroke.getKeyStroke(
                    direction == 1 ? KeyEvent.VK_DOWN : KeyEvent.VK_UP, 0);
            var inputMap = getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
            Object actionKey = inputMap.get(key);
            if (actionKey != null && getActionMap().get(actionKey) != null) continue;
            String fallbackKey = direction == 1 ? "launcherSelectNext" : "launcherSelectPrevious";
            inputMap.put(key, fallbackKey);
            getActionMap().put(fallbackKey, new AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent event) {
                    if (isEnabled() && getItemCount() > 0) {
                        int selected = getSelectedIndex();
                        setSelectedIndex(Math.max(0, Math.min(getItemCount() - 1,
                                selected + direction)));
                    }
                }
            });
        }
    }

    void setDisplayText(Function<? super E, String> displayText) {
        this.displayText = Objects.requireNonNull(displayText, "displayText");
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension preferred = super.getPreferredSize();
        if (scale == null) return preferred;
        return new Dimension(preferred.width,
                Math.max(preferred.height, scale.scaleForGUI(LauncherTheme.CONTROL_HEIGHT)));
    }

    /**
     * Shows each value's display text through the look and feel's renderer, so popup rows keep
     * their check mark, wash and row height. A look-and-feel change replaces the delegate.
     */
    private final class DisplayTextRenderer implements ListCellRenderer<E>, javax.swing.plaf.UIResource {
        private final ListCellRenderer<? super E> delegate;

        private DisplayTextRenderer(ListCellRenderer<? super E> delegate) {
            this.delegate = delegate;
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends E> list, E value, int index,
                                                      boolean selected, boolean focused) {
            Component component = delegate.getListCellRendererComponent(
                    list, value, index, selected, focused);
            if (component instanceof JLabel label && value != null) {
                label.setText(displayText.apply(value));
            }
            return component;
        }
    }
}
