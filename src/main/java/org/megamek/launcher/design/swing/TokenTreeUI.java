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
import javax.swing.UIManager;
import javax.swing.plaf.ComponentUI;
import javax.swing.tree.TreePath;

import com.formdev.flatlaf.ui.FlatTreeUI;

/**
 * Trees of the MegaMek look and feels (design system, Lists and trees): the list's rows with chevrons
 * and indentation, a surface wash under the pointer and a selection marker at the leading edge of selected rows.
 */
public class TokenTreeUI extends FlatTreeUI {
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
        return new TokenTreeUI();
    }

    @Override
    protected void installListeners() {
        super.installListeners();
        wash = new RowWash(tree);
        tree.addMouseListener(hoverTracker);
        tree.addMouseMotionListener(hoverTracker);
    }

    @Override
    protected void uninstallListeners() {
        tree.removeMouseListener(hoverTracker);
        tree.removeMouseMotionListener(hoverTracker);
        hoverRow = -1;
        wash.clear();
        super.uninstallListeners();
    }

    @Override
    protected void paintRow(Graphics g, Rectangle clipBounds, Insets insets, Rectangle bounds, TreePath path, int row,
          boolean isExpanded, boolean hasBeenExpanded, boolean isLeaf) {
        boolean decorated = tree.isEnabled() && !(editingComponent != null && editingRow == row);
        boolean selected = tree.isRowSelected(row);
        int x = isWideSelection() ? 0 : bounds.x;
        int width = isWideSelection() ? tree.getWidth() : bounds.width;
        if (decorated && !selected) {
            TokenLists.paintWash(g,
                  wash.color(row, TokenLists.frozenHover(tree), UIManager.getColor(TokenLists.HOVER_BACKGROUND)),
                  x, bounds.y, width, bounds.height, selectionInsets, selectionArc);
        }
        super.paintRow(g, clipBounds, insets, bounds, path, row, isExpanded, hasBeenExpanded, isLeaf);
        if (decorated && selected && isPaintSelection()) {
            TokenLists.paintMarker(g, tree, x, bounds.y, width, bounds.height, selectionInsets);
        }
    }

    private int rowAt(Point point) {
        int row = tree.getClosestRowForLocation(point.x, point.y);
        Rectangle bounds = row < 0 ? null : tree.getRowBounds(row);
        if (bounds == null || point.y < bounds.y || point.y >= bounds.y + bounds.height) {
            return -1;
        }
        return isWideSelection() || bounds.contains(point) ? row : -1;
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
        Rectangle bounds = row < 0 ? null : tree.getRowBounds(row);
        if (bounds != null) {
            tree.repaint(0, bounds.y, tree.getWidth(), bounds.height);
        }
    }
}
