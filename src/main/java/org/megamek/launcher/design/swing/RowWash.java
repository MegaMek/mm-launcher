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
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import javax.swing.JComponent;

/**
 * The surface wash under the pointer in a list, tree or table, eased per row ({@link TokenMotion}): the row the pointer
 * enters fades in fast while the one it left fades out more slowly, so a sweep across rows leaves a short trail.
 */
final class RowWash {
    private static final String KEY = "row.wash.";

    private final JComponent owner;
    private final Set<Integer> fading = new HashSet<>();
    private int row = -1;

    RowWash(JComponent owner) {
        this.owner = owner;
    }

    /** Moves the wash to {@code next}, or off every row for -1. */
    void moveTo(int next) {
        if (next == row) {
            return;
        }
        settle();
        if (row >= 0) {
            fading.add(row);
            TokenMotion.color(owner, KEY + row, null, TokenMotion.hover(false));
        }
        row = next;
        if (row >= 0) {
            fading.remove(row);
            // Seeds a row the pointer has not been over yet as clear, so it fades in instead of appearing
            TokenMotion.color(owner, KEY + row, null, 0);
        }
    }

    /** Forgets every row, as when the UI is uninstalled. */
    void clear() {
        for (int faded : fading) {
            TokenMotion.forget(owner, KEY + faded);
        }
        fading.clear();
        if (row >= 0) {
            TokenMotion.forget(owner, KEY + row);
        }
        row = -1;
    }

    /**
     * The wash to paint on {@code index}, or null for none. The gallery's {@code frozen} row, or -1, shows the full
     * {@code color} at once.
     */
    Color color(int index, int frozen, Color color) {
        if (index == frozen) {
            return color;
        }
        if (index == row) {
            return TokenMotion.color(owner, KEY + index, color, TokenMotion.hover(true));
        }
        if (fading.contains(index)) {
            return TokenMotion.color(owner, KEY + index, null, TokenMotion.hover(false));
        }
        return null;
    }

    /** Drops the rows that have finished fading, including any scrolled out of sight before they finished. */
    private void settle() {
        Iterator<Integer> rows = fading.iterator();
        while (rows.hasNext()) {
            String key = KEY + rows.next();
            if (!TokenMotion.isMoving(owner, key)) {
                TokenMotion.forget(owner, key);
                rows.remove();
            }
        }
    }
}
