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
import java.awt.Container;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.util.List;
import javax.swing.CellRendererPane;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.RowSorter;
import javax.swing.SortOrder;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import javax.swing.plaf.ComponentUI;
import javax.swing.plaf.FontUIResource;
import javax.swing.plaf.UIResource;
import javax.swing.plaf.basic.BasicHTML;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumn;

import com.formdev.flatlaf.ui.FlatTableHeaderUI;
import com.formdev.flatlaf.util.UIScale;
import org.megamek.launcher.design.DesignTokens;

/**
 * Table headers of the MegaMek look and feels (design system, Tables): upper-case {@code overline}
 * labels in the secondary text color on the table's own surface, condensed on a game screen, aligned like their
 * column's cells. The label under the pointer, the one being dragged and the sorted one turn primary; the sorted one
 * also shows its chevron.
 */
public class TokenTableHeaderUI extends FlatTableHeaderUI {
    private Font condensedBase;
    private Font condensedFont;

    public static ComponentUI createUI(JComponent c) {
        return new TokenTableHeaderUI();
    }

    @Override
    public void installUI(JComponent c) {
        super.installUI(c);
        // FlatLaf's own pane paints hover backgrounds and moves sort icons; these headers only change the label
        header.remove(rendererPane);
        rendererPane = new LabelPane();
        header.add(rendererPane);
    }

    @Override
    protected void installDefaults() {
        super.installDefaults();
        // FlatLaf's font syntax takes whole sizes only, and overline is 10.5
        Font font = header.getFont();
        if (font == null || font instanceof UIResource) {
            header.setFont(new FontUIResource(TokenFonts.font("overline")));
        }
    }

    /** The view column whose header cell is painted at {@code x} with {@code width}, or -1. */
    private int columnAt(int x, int width) {
        TableColumn dragged = header.getDraggedColumn();
        for (int i = 0; i < header.getColumnModel().getColumnCount(); i++) {
            Rectangle cell = header.getHeaderRect(i);
            if (cell.width != width) {
                continue;
            }
            boolean isDragged = header.getColumnModel().getColumn(i) == dragged;
            if (cell.x == x || (isDragged && cell.x + header.getDraggedDistance() == x)) {
                return i;
            }
        }
        return -1;
    }

    private boolean isActive(int column) {
        Object frozen = header.getClientProperty(TokenTables.FROZEN_HEADER_HOVER);
        int hover = frozen instanceof Integer frozenColumn ? frozenColumn : getRolloverColumn();
        TableColumn dragged = header.getDraggedColumn();
        return column == hover || (dragged != null && header.getColumnModel().getColumn(column) == dragged)
              || isSorted(column);
    }

    private boolean isSorted(int column) {
        JTable table = header.getTable();
        RowSorter<?> sorter = table == null ? null : table.getRowSorter();
        if (sorter == null) {
            return false;
        }
        List<? extends RowSorter.SortKey> keys = sorter.getSortKeys();
        return !keys.isEmpty() && keys.get(0).getSortOrder() != SortOrder.UNSORTED
              && table.convertColumnIndexToView(keys.get(0).getColumn()) == column;
    }

    /**
     * The font a header label is painted in: the theme's overline is condensed on a game screen, a font a view set
     * stays.
     */
    private Font shownFont(Font font) {
        if (font == null || !(header.getFont() instanceof UIResource) || !font.equals(header.getFont())
              || !DesignTokens.shipped().condensed("overline", TokenPresentation.mode(header))) {
            return font;
        }
        if (!font.equals(condensedBase)) {
            condensedBase = font;
            condensedFont = TokenFonts.condensed(font, DesignTokens.shipped().roleStyle("overline"), true);
        }
        return condensedFont;
    }

    /** The alignment of the column's cells when its renderer is a label, such as right for numbers. */
    private int alignmentOf(int column, int fallback) {
        JTable table = header.getTable();
        if (table == null) {
            return fallback;
        }
        TableCellRenderer renderer = header.getColumnModel().getColumn(column).getCellRenderer();
        if (renderer == null) {
            renderer = table.getDefaultRenderer(table.getColumnClass(column));
        }
        return renderer instanceof JLabel label ? label.getHorizontalAlignment() : SwingConstants.LEADING;
    }

    private final class LabelPane extends CellRendererPane {
        @Override
        public void paintComponent(Graphics g, Component c, Container p, int x, int y, int w, int h,
              boolean shouldValidate) {
            int column = c instanceof JLabel ? columnAt(x, w) : -1;
            if (column < 0) {
                super.paintComponent(g, c, p, x, y, w, h, shouldValidate);
                return;
            }
            JLabel label = (JLabel) c;
            String text = label.getText();
            int alignment = label.getHorizontalAlignment();
            Color foreground = label.getForeground();
            boolean enabled = label.isEnabled();
            int gap = label.getIconTextGap();
            if (text != null && !BasicHTML.isHTMLString(text)) {
                label.setText(text.toUpperCase(header.getLocale()));
            }
            Font font = label.getFont();
            label.setFont(shownFont(font));
            label.setHorizontalAlignment(alignmentOf(column, alignment));
            label.setEnabled(enabled && header.isEnabled());
            label.setIconTextGap(UIScale.scale(UIManager.getInt(TokenTables.SORT_ICON_GAP)));
            if (hoverForeground != null && header.isEnabled() && isActive(column)) {
                label.setForeground(hoverForeground);
            }
            if (pressedBackground != null && header.getDraggedColumn() != null
                  && header.getColumnModel().getColumn(column) == header.getDraggedColumn()) {
                boolean opaque = label.isOpaque();
                Color background = label.getBackground();
                label.setOpaque(true);
                label.setBackground(pressedBackground);
                super.paintComponent(g, c, p, x, y, w, h, shouldValidate);
                label.setOpaque(opaque);
                label.setBackground(background);
            } else {
                super.paintComponent(g, c, p, x, y, w, h, shouldValidate);
            }
            label.setText(text);
            label.setFont(font);
            label.setHorizontalAlignment(alignment);
            label.setForeground(foreground);
            label.setEnabled(enabled);
            label.setIconTextGap(gap);
        }
    }
}
