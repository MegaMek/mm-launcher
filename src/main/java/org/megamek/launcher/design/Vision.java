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

package org.megamek.launcher.design;

/** The color vision types the token checks and the galleries simulate. */
public enum Vision {
    NORMAL(null),
    /** Green-blind, the most common deficiency. */
    DEUTERANOPIA(new double[][] {
          { 0.367322, 0.860646, -0.227968 },
          { 0.280085, 0.672501, 0.047413 },
          { -0.011820, 0.042940, 0.968881 } }),
    /** Red-blind. */
    PROTANOPIA(new double[][] {
          { 0.152286, 1.052583, -0.204868 },
          { 0.114503, 0.786281, 0.099216 },
          { -0.003882, -0.048116, 1.051998 } }),
    /** Blue-blind. */
    TRITANOPIA(new double[][] {
          { 1.255528, -0.076749, -0.178779 },
          { -0.078411, 0.930809, 0.147602 },
          { 0.004733, 0.691367, 0.303900 } });

    private final double[][] matrix;

    Vision(double[][] matrix) {
        this.matrix = matrix;
    }

    /** The linear-RGB simulation matrix, or null for normal vision. */
    double[][] matrix() {
        return matrix;
    }
}
