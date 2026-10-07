/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.megamek.launcher.gui;

import javax.swing.JComponent;
import javax.swing.RepaintManager;
import java.awt.Rectangle;

final class FocusRepaintManager extends RepaintManager {
    private final JComponent owner;
    Rectangle dirty;

    FocusRepaintManager(JComponent owner) {
        this.owner = owner;
    }

    @Override
    public void addDirtyRegion(JComponent component, int x, int y, int width, int height) {
        if (component == owner) {
            Rectangle region = new Rectangle(x, y, width, height);
            dirty = dirty == null ? region : dirty.union(region);
        }
    }
}
