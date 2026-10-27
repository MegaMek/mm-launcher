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

import javax.swing.UIDefaults;

import com.formdev.flatlaf.FlatDarkLaf;
import org.megamek.launcher.design.DesignTokens.Mode;

/** The dark MegaMek look and feel: FlatLaf dark with every value taken from the design tokens. */
public class MegaMekDarkLaf extends FlatDarkLaf {
    public static final String NAME = "MegaMek Dark";

    public MegaMekDarkLaf() {
        setExtraDefaults(TokenLafDefaults.of(Mode.DARK));
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public UIDefaults getDefaults() {
        UIDefaults defaults = super.getDefaults();
        TokenLafDefaults.putFonts(defaults);
        TokenLafDefaults.putIcons(defaults);
        return defaults;
    }

    @Override
    public void initialize() {
        super.initialize();
        FocusVisibility.install();
        FieldEdge.install();
        TokenPopupFactory.install();
    }

    @Override
    public void uninitialize() {
        TokenPopupFactory.uninstall();
        FieldEdge.uninstall();
        FocusVisibility.uninstall();
        super.uninitialize();
    }
}
