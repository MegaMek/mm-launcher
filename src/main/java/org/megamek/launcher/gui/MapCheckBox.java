/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek Launcher.
 *
 * MegaMek Launcher is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 */

package org.megamek.launcher.gui;

import javax.swing.JCheckBox;
import java.awt.Font;

/** A design-token check box; the MegaMek look and feel paints the box, check and focus ring. */
final class MapCheckBox extends JCheckBox {
    MapCheckBox(String text, boolean selected, GuiScale scale) {
        super(text, selected);
        setOpaque(false);
        setFont(getFont().deriveFont(Font.PLAIN));
        setIconTextGap(scale.scaleForGUI(8));
    }
}