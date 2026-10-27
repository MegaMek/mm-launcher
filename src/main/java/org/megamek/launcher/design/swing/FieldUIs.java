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

package org.megamek.launcher.design.swing;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JSpinner;
import javax.swing.ListCellRenderer;
import javax.swing.UIManager;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.basic.ComboPopup;

import com.formdev.flatlaf.ui.FlatComboBoxUI;
import com.formdev.flatlaf.ui.FlatFormattedTextFieldUI;
import com.formdev.flatlaf.ui.FlatPasswordFieldUI;
import com.formdev.flatlaf.ui.FlatSpinnerUI;
import com.formdev.flatlaf.ui.FlatTextFieldUI;
import com.formdev.flatlaf.ui.FlatUIUtils;
import org.megamek.launcher.design.DesignTokens;
import org.megamek.launcher.design.host.UIUtil;

/**
 * FlatLaf's single-line input delegates, grown to the design system's control height so fields line up with buttons:
 * control.sm unless the component sets {@code JComponent.minimumHeight}, as {@link TokenFields} does for regular
 * inputs. FlatLaf has a minimum height for buttons only. Editors inside a spinner or combo box and cell editors keep
 * their natural height. The MegaMek look and feels install these by class name, so they stay public.
 */
public final class FieldUIs {
    private FieldUIs() {
    }

    /** Grows {@code size} to the input height of {@code component}. */
    static Dimension tall(JComponent component, Dimension size) {
        if (size == null || FlatUIUtils.isCellEditor(component) || isInnerEditor(component)) {
            return size;
        }
        int height = FlatUIUtils.minimumHeight(component, UIManager.getInt(FieldEdge.HEIGHT));
        if (height > 0) {
            size.height = Math.max(size.height, UIUtil.scaleForGUI(height));
        }
        return size;
    }

    private static boolean isInnerEditor(Component component) {
        for (Component parent = component.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof JSpinner || parent instanceof JComboBox) {
                return true;
            }
        }
        return false;
    }

    public static class TextField extends FlatTextFieldUI {
        public static ComponentUI createUI(JComponent c) {
            return new TextField();
        }

        @Override
        public Dimension getPreferredSize(JComponent c) {
            return tall(c, super.getPreferredSize(c));
        }

        @Override
        public Dimension getMinimumSize(JComponent c) {
            return tall(c, super.getMinimumSize(c));
        }
    }

    public static class PasswordField extends FlatPasswordFieldUI {
        public static ComponentUI createUI(JComponent c) {
            return new PasswordField();
        }

        @Override
        public Dimension getPreferredSize(JComponent c) {
            return tall(c, super.getPreferredSize(c));
        }

        @Override
        public Dimension getMinimumSize(JComponent c) {
            return tall(c, super.getMinimumSize(c));
        }
    }

    public static class FormattedTextField extends FlatFormattedTextFieldUI {
        public static ComponentUI createUI(JComponent c) {
            return new FormattedTextField();
        }

        @Override
        public Dimension getPreferredSize(JComponent c) {
            return tall(c, super.getPreferredSize(c));
        }

        @Override
        public Dimension getMinimumSize(JComponent c) {
            return tall(c, super.getMinimumSize(c));
        }
    }

    public static class Spinner extends FlatSpinnerUI {
        public static ComponentUI createUI(JComponent c) {
            return new Spinner();
        }

        // The spinner's size comes from its layout, which the delegate does not otherwise report
        @Override
        public Dimension getPreferredSize(JComponent c) {
            LayoutManager layout = c.getLayout();
            return layout == null ? super.getPreferredSize(c) : tall(c, layout.preferredLayoutSize(c));
        }

        @Override
        public Dimension getMinimumSize(JComponent c) {
            LayoutManager layout = c.getLayout();
            return layout == null ? super.getMinimumSize(c) : tall(c, layout.minimumLayoutSize(c));
        }
    }

    /**
     * Combo boxes are the design system's select ({@link TokenSelects}): the check renderer, the placeholder color, and
     * the inline variant's transparent face and secondary value that brightens under the pointer or with the keyboard.
     */
    public static class ComboBox extends FlatComboBoxUI {
        private static final Color CLEAR = new Color(0, 0, 0, 0);
        private boolean sizingPopup;

        public static ComponentUI createUI(JComponent c) {
            return new ComboBox();
        }

        @Override
        @SuppressWarnings({ "rawtypes", "unchecked" })
        protected ListCellRenderer createRenderer() {
            return new TokenSelects.CheckRenderer(comboBox);
        }

        // FlatLaf's borders are UIResources, so the inline border has to come back whenever the UI is installed
        @Override
        protected void installDefaults() {
            super.installDefaults();
            if (TokenSelects.isInline(comboBox)) {
                comboBox.setBorder(new FieldBorders.InlineSelect());
            }
        }

        @Override
        protected ComboPopup createPopup() {
            return new SelectPopup(comboBox);
        }

        /** The widest value, the placeholder included; while sizing the popup, plus the rows' check slot. */
        @Override
        @SuppressWarnings("unchecked")
        protected Dimension getDisplaySize() {
            Dimension size = super.getDisplaySize();
            String placeholder = TokenSelects.placeholderOf(comboBox);
            ListCellRenderer<Object> renderer = comboBox.getRenderer();
            if (placeholder != null && renderer != null && !comboBox.isEditable()) {
                Component shown = renderer.getListCellRendererComponent(listBox, null, -1, false, false);
                size.width = Math.max(size.width, getSizeForComponent(shown).width);
            }
            if (sizingPopup) {
                size.width += TokenSelects.CheckRenderer.slotWidth();
            }
            return size;
        }

        @Override
        public void update(Graphics g, JComponent c) {
            if (!TokenSelects.isInline(c)) {
                super.update(g, c);
                return;
            }
            Color button = buttonBackground;
            buttonBackground = null;
            try {
                super.update(g, c);
            } finally {
                buttonBackground = button;
            }
        }

        @Override
        protected Color getBackground(boolean enabled) {
            return TokenSelects.isInline(comboBox) ? CLEAR : super.getBackground(enabled);
        }

        @Override
        protected Color getForeground(boolean enabled) {
            if (enabled && comboBox.getSelectedItem() == null && TokenSelects.placeholderOf(comboBox) != null) {
                return UIManager.getColor(TokenSelects.PLACEHOLDER_FOREGROUND);
            }
            if (enabled && TokenSelects.isInline(comboBox)) {
                boolean bright = FieldEdge.isHovered(comboBox) || comboBox.isPopupVisible()
                      || FlatUIUtils.isPermanentFocusOwner(comboBox);
                Color color = UIManager.getColor(bright ? TokenSelects.INLINE_HOVER_FOREGROUND
                      : TokenSelects.INLINE_FOREGROUND);
                if (color != null) {
                    return color;
                }
            }
            return super.getForeground(enabled);
        }

        @Override
        public Dimension getPreferredSize(JComponent c) {
            return tall(c, super.getPreferredSize(c));
        }

        @Override
        public Dimension getMinimumSize(JComponent c) {
            return tall(c, super.getMinimumSize(c));
        }

        /** The shared popup list: wide enough for the check slot, a small gap away from the face. */
        private class SelectPopup extends FlatComboPopup {
            SelectPopup(JComboBox<?> combo) {
                super(combo);
            }

            @Override
            protected Rectangle computePopupBounds(int px, int py, int pw, int ph) {
                Rectangle bounds;
                sizingPopup = true;
                try {
                    bounds = super.computePopupBounds(px, py, pw, ph);
                } finally {
                    sizingPopup = false;
                }
                int gap = UIUtil.scaleForGUI(Math.round(DesignTokens.shipped().space().get("xs")));
                bounds.y += bounds.y < 0 ? -gap : gap;
                return bounds;
            }
        }
    }
}
