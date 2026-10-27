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
import java.awt.Graphics;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.CellRendererPane;
import javax.swing.JComponent;
import javax.swing.UIManager;
import javax.swing.plaf.ComponentUI;

import com.formdev.flatlaf.ui.FlatTableUI;

/**
 * Tables of the MegaMek look and feels (design system, Tables). FlatLaf rounds and insets the zebra
 * stripes and the selection across the whole row; this adds the surface wash under the pointer, the selection marker on
 * selected rows and the empty-state text of {@link TokenTables#emptyText}. The wash goes over the cells, so rows whose
 * renderers paint their own background still show it.
 */
public class TokenTableUI extends FlatTableUI {
    private final MouseAdapter hoverTracker = new MouseAdapter() {
        @Override
        public void mouseMoved(MouseEvent e) {
            setHoverRow(rowAt(e.getPoint()));
        }

        @Override
        public void mouseDragged(MouseEvent e) {
            setHoverRow(rowAt(e.getPoint()));
        }

        @Override
        public void mouseExited(MouseEvent e) {
            setHoverRow(-1);
        }
    };
    private int hoverRow = -1;
    private RowWash wash;

    public static ComponentUI createUI(JComponent c) {
        return new TokenTableUI();
    }

    @Override
    public void installUI(JComponent c) {
        super.installUI(c);
        table.remove(rendererPane);
        rendererPane = new EnablingPane();
        table.add(rendererPane);
    }

    @Override
    protected void installListeners() {
        super.installListeners();
        wash = new RowWash(table);
        table.addMouseListener(hoverTracker);
        table.addMouseMotionListener(hoverTracker);
    }

    @Override
    protected void uninstallListeners() {
        table.removeMouseListener(hoverTracker);
        table.removeMouseMotionListener(hoverTracker);
        hoverRow = -1;
        wash.clear();
        super.uninstallListeners();
    }

    @Override
    public void paint(Graphics g, JComponent c) {
        super.paint(g, c);
        if (table.getRowCount() == 0) {
            if (c.getClientProperty(TokenLists.EMPTY_TEXT) instanceof String text && !text.isEmpty()) {
                TokenLists.paintEmptyText(g, c, text, table.getRowHeight());
            }
            return;
        }
        if (!table.isEnabled() || table.getColumnCount() == 0) {
            return;
        }
        Rectangle clip = g.getClipBounds();
        if (clip == null) {
            clip = new Rectangle(0, 0, table.getWidth(), table.getHeight());
        }
        int first = table.rowAtPoint(new Point(clip.x, clip.y));
        int last = table.rowAtPoint(new Point(clip.x, clip.y + clip.height - 1));
        first = first < 0 ? 0 : first;
        last = last < 0 ? table.getRowCount() - 1 : last;
        boolean marked = table.getRowSelectionAllowed() && !table.getColumnSelectionAllowed();
        int frozen = TokenLists.frozenHover(table);
        Color hover = UIManager.getColor(TokenTables.HOVER_WASH);
        for (int row = first; row <= last; row++) {
            boolean selected = table.isRowSelected(row);
            Color color = selected ? null : wash.color(row, frozen, hover);
            if (color != null) {
                Rectangle bounds = rowBounds(row);
                TokenLists.paintWash(g, color, bounds.x, bounds.y, bounds.width, bounds.height, selectionInsets,
                      selectionArc);
            }
            if (selected && marked) {
                Rectangle bounds = rowBounds(row);
                TokenLists.paintMarker(g, table, bounds.x, bounds.y, bounds.width, bounds.height, selectionInsets);
            }
        }
    }

    /** The row's full width across every column. */
    private Rectangle rowBounds(int row) {
        return table.getCellRect(row, 0, true).union(table.getCellRect(row, table.getColumnCount() - 1, true));
    }

    private int rowAt(Point point) {
        return table.columnAtPoint(point) < 0 ? -1 : table.rowAtPoint(point);
    }

    private void setHoverRow(int row) {
        if (row == hoverRow) {
            return;
        }
        wash.moveTo(row);
        repaintRow(hoverRow);
        hoverRow = row;
        repaintRow(hoverRow);
    }

    private void repaintRow(int row) {
        if (row >= 0 && row < table.getRowCount() && table.getColumnCount() > 0) {
            table.repaint(rowBounds(row));
        }
    }

    /**
     * Paints each cell enabled like its table, so a disabled table's text dims as a disabled list's does. Swing's
     * table renderers leave that to whoever calls them.
     */
    private final class EnablingPane extends CellRendererPane {
        @Override
        public void paintComponent(Graphics g, Component cell, Container parent, int x, int y, int w, int h,
              boolean shouldValidate) {
            boolean enabled = cell.isEnabled();
            cell.setEnabled(enabled && table.isEnabled());
            super.paintComponent(g, cell, parent, x, y, w, h, shouldValidate);
            cell.setEnabled(enabled);
        }
    }
}
