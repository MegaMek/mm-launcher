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

import com.formdev.flatlaf.ui.FlatTableCellBorder;
import com.formdev.flatlaf.ui.FlatTableHeaderBorder;

/**
 * Cell borders of the MegaMek tables (design system, Tables): FlatLaf's, with the wider margins of
 * {@link TokenTables#withEdges} at the table's outer edges. The lead cell has no focus rectangle of its own, since the
 * selected row is the cursor; FlatLaf still marks the cell being edited in a table with editable cells.
 */
public final class TokenTableCellBorder {
    private TokenTableCellBorder() {
    }

    /** Every unselected cell, the lead one included. */
    public static class Plain extends FlatTableCellBorder.Default {
        @Override
        public Insets getBorderInsets(Component c, Insets insets) {
            return TokenTables.withEdges(c, super.getBorderInsets(c, insets));
        }
    }

    /** The lead cell of a selected row. */
    public static class Selected extends FlatTableCellBorder.Selected {
        @Override
        public Insets getBorderInsets(Component c, Insets insets) {
            return TokenTables.withEdges(c, super.getBorderInsets(c, insets));
        }
    }

    /** Header cells, so that labels line up with their column's text. */
    public static class Header extends FlatTableHeaderBorder {
        @Override
        public Insets getBorderInsets(Component c, Insets insets) {
            return TokenTables.withEdges(c, super.getBorderInsets(c, insets));
        }
    }
}
