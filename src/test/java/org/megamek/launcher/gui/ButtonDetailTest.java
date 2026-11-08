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

import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ButtonDetailTest {
    @Test
    void inkMassOfABoxIsItsCentre() {
        Rectangle2D box = new Rectangle2D.Double(10, 0, 20, 8);
        assertEquals(20, ButtonDetail.inkMassX(box, box), 1e-6);
    }

    @Test
    void inkMassLeansTowardTheHeavierSideAndHolesSubtract() {
        // A thin bar on the left and a solid block on the right, as in "1 update".
        Path2D ink = new Path2D.Double();
        ink.append(new Rectangle2D.Double(0, 0, 2, 10), false);
        ink.append(new Rectangle2D.Double(10, 0, 10, 10), false);
        Rectangle2D bounds = ink.getBounds2D();
        assertEquals(1.0 * 20 / 120 + 15.0 * 100 / 120, ButtonDetail.inkMassX(ink, bounds), 1e-6);

        Area ring = new Area(new Rectangle2D.Double(0, 0, 10, 10));
        ring.subtract(new Area(new Rectangle2D.Double(0, 0, 5, 10)));
        assertEquals(7.5, ButtonDetail.inkMassX(ring, ring.getBounds2D()), 1e-6);
    }

    @Test
    void emptyInkFallsBackToTheBoxCentre() {
        Rectangle2D bounds = new Rectangle2D.Double(4, 0, 6, 3);
        assertEquals(7, ButtonDetail.inkMassX(new Path2D.Double(), bounds), 1e-6);
    }
}
