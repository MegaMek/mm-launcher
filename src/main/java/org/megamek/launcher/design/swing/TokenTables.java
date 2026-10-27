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

import java.awt.Component;
import java.awt.Insets;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.table.JTableHeader;

import com.formdev.flatlaf.util.UIScale;

/**
 * The design system's tables for Swing (design system, Tables). The MegaMek look and feels draw every
 * {@link JTable} with the list's rows stretched across its columns: zebra stripes, a surface wash under the pointer
 * and, for the selection, the selection wash plus a selection marker at the row's leading edge. Headers are upper-case
 * {@code overline} labels on the table's own surface, aligned like their column. This adds an empty-state text.
 */
public final class TokenTables {
    /** The translucent surface wash painted over the row under the pointer, cells and all. */
    static final String HOVER_WASH = "MegaMek.table.hoverWash";
    /**
     * The cell margins at the table's two outer edges: the leading one leaves room for the selection marker, the
     * trailing one for the row shape's inset.
     */
    static final String EDGE_MARGINS = "MegaMek.table.edgeMargins";
    /** Client property freezing the header's hover look on a view column, for the design gallery. */
    static final String FROZEN_HEADER_HOVER = "MegaMek.tableHeader.frozenHover";
    /** The gap between a header's label and its sort chevron. */
    static final String SORT_ICON_GAP = "MegaMek.tableHeader.sortIconGap";

    private TokenTables() {
    }

    /**
     * Shows {@code text} in the muted color while {@code table} has no rows. The table then fills its scroll pane, so
     * the text has somewhere to go.
     */
    public static <T extends JTable> T emptyText(T table, String text) {
        table.putClientProperty(TokenLists.EMPTY_TEXT, text);
        table.setFillsViewportHeight(true);
        table.repaint();
        return table;
    }

    /**
     * Widens {@code insets} at the table's outer edges when {@code cell}, a renderer being painted, sits in the first
     * or last column. Renderers learn their cell's bounds before they paint, so the bounds tell the column apart.
     */
    static Insets withEdges(Component cell, Insets insets) {
        Insets edges = UIManager.getInsets(EDGE_MARGINS);
        JTable table = tableOf(cell);
        if (edges == null || table == null || table.getColumnCount() == 0) {
            return insets;
        }
        int total = table.getColumnModel().getTotalColumnWidth();
        int left = cell.getX();
        int right = left + cell.getWidth();
        boolean ltr = table.getComponentOrientation().isLeftToRight();
        int width = cell.getParent() == null || cell.getParent().getParent() == null
              ? total : cell.getParent().getParent().getWidth();
        boolean leading = ltr ? left <= 0 : right >= width;
        boolean trailing = ltr ? right >= total : left <= width - total;
        if (leading) {
            setSide(insets, ltr, UIScale.scale(edges.left));
        }
        if (trailing) {
            setSide(insets, !ltr, UIScale.scale(edges.right));
        }
        return insets;
    }

    private static void setSide(Insets insets, boolean left, int value) {
        if (left) {
            insets.left = value;
        } else {
            insets.right = value;
        }
    }

    private static JTable tableOf(Component cell) {
        JTable table = (JTable) SwingUtilities.getAncestorOfClass(JTable.class, cell);
        if (table != null) {
            return table;
        }
        JTableHeader header = (JTableHeader) SwingUtilities.getAncestorOfClass(JTableHeader.class, cell);
        return header == null ? null : header.getTable();
    }
}
