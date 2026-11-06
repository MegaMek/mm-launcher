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

package org.megamek.launcher.gui;

import org.junit.jupiter.api.Test;

import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class GuiLauncherTest {
    @Test
    void direct3DIsTurnedOffOnWindows() {
        Properties properties = new Properties();
        GuiLauncher.avoidDirect3D("Windows 10", properties);
        assertEquals("false", properties.getProperty(GuiLauncher.DIRECT3D));
    }

    @Test
    void anExplicitDirect3DChoiceIsKept() {
        Properties properties = new Properties();
        properties.setProperty(GuiLauncher.DIRECT3D, "true");
        GuiLauncher.avoidDirect3D("Windows 11", properties);
        assertEquals("true", properties.getProperty(GuiLauncher.DIRECT3D));
    }

    @Test
    void otherSystemsAreLeftAlone() {
        Properties properties = new Properties();
        GuiLauncher.avoidDirect3D("Mac OS X", properties);
        GuiLauncher.avoidDirect3D("Linux", properties);
        assertNull(properties.getProperty(GuiLauncher.DIRECT3D));
    }
}