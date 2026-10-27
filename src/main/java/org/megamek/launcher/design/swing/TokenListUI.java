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

import java.awt.Graphics;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.ListCellRenderer;
import javax.swing.ListModel;
import javax.swing.ListSelectionModel;
import javax.swing.UIManager;
import javax.swing.plaf.ComponentUI;

import com.formdev.flatlaf.ui.FlatListUI;

/**
 * Lists of the MegaMek look and feels (design system, Lists and trees): rows at least
 * {@code control.sm} high, a surface wash under the pointer and a selection marker on selected rows, and the empty-state
 * text of {@link TokenLists#emptyText}. A combo box's popup list keeps the select's own rows.
 */
public class TokenListUI extends FlatListUI {
    static final String CELL_MARGINS = "MegaMek.list.cellMargins";

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
    private Insets defaultMargins;
    private Insets tokenMargins;

    public static ComponentUI createUI(JComponent c) {
        return new TokenListUI();
    }

    @Override
    protected void installDefaults() {
        super.installDefaults();
        defaultMargins = cellMargins;
        tokenMargins = UIManager.getInsets(CELL_MARGINS);
    }

    @Override
    protected void installListeners() {
        super.installListeners();
        wash = new RowWash(list);
        list.addMouseListener(hoverTracker);
        list.addMouseMotionListener(hoverTracker);
    }

    @Override
    protected void uninstallListeners() {
        list.removeMouseListener(hoverTracker);
        list.removeMouseMotionListener(hoverTracker);
        hoverRow = -1;
        wash.clear();
        super.uninstallListeners();
    }

    @Override
    protected void updateLayoutState() {
        syncMargins();
        super.updateLayoutState();
        if (isPopup() || list.getFixedCellHeight() != -1 || list.getLayoutOrientation() != JList.VERTICAL) {
            return;
        }
        int minimum = TokenLists.rowHeight();
        if (cellHeights != null) {
            for (int i = 0; i < cellHeights.length; i++) {
                cellHeights[i] = Math.max(cellHeights[i], minimum);
            }
        } else if (cellHeight > 0) {
            cellHeight = Math.max(cellHeight, minimum);
        }
    }

    @Override
    public void paint(Graphics g, JComponent c) {
        syncMargins();
        super.paint(g, c);
        if (!isPopup() && list.getModel().getSize() == 0
              && c.getClientProperty(TokenLists.EMPTY_TEXT) instanceof String text && !text.isEmpty()) {
            TokenLists.paintEmptyText(g, c, text);
        }
    }

    @SuppressWarnings("rawtypes")
    @Override
    protected void paintCell(Graphics g, int row, Rectangle rowBounds, ListCellRenderer cellRenderer,
          ListModel dataModel, ListSelectionModel selModel, int leadIndex) {
        boolean decorated = !isPopup() && list.isEnabled();
        boolean selected = selModel.isSelectedIndex(row);
        if (decorated && !selected) {
            TokenLists.paintWash(g,
                  wash.color(row, TokenLists.frozenHover(list), UIManager.getColor(TokenLists.HOVER_BACKGROUND)),
                  rowBounds.x, rowBounds.y, rowBounds.width, rowBounds.height, selectionInsets, selectionArc);
        }
        super.paintCell(g, row, rowBounds, cellRenderer, dataModel, selModel, leadIndex);
        if (decorated && selected) {
            TokenLists.paintMarker(g, list, rowBounds.x, rowBounds.y, rowBounds.width, rowBounds.height,
                  selectionInsets);
        }
    }

    private boolean isPopup() {
        return TokenLists.isPopupList(list);
    }

    /**
     * Ordinary lists leave room for the selection marker before the text; the popup list keeps FlatLaf's margins,
     * which the select's own padding builds on. A margin set through a FlatLaf style is left alone.
     */
    private void syncMargins() {
        if (tokenMargins == null || (cellMargins != defaultMargins && cellMargins != tokenMargins)) {
            return;
        }
        cellMargins = isPopup() ? defaultMargins : tokenMargins;
    }

    private int rowAt(Point point) {
        int row = list.locationToIndex(point);
        Rectangle bounds = row < 0 ? null : list.getCellBounds(row, row);
        return bounds != null && bounds.contains(point) ? row : -1;
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
        if (row >= 0 && row < list.getModel().getSize()) {
            Rectangle bounds = list.getCellBounds(row, row);
            if (bounds != null) {
                list.repaint(bounds);
            }
        }
    }
}
