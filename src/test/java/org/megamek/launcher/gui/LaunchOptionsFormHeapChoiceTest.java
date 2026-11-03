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

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LaunchOptionsFormHeapChoiceTest {
    private static List<Integer> sizes(long physicalMb, int... required) {
        return Arrays.stream(LaunchOptionsForm.HeapChoice.options(physicalMb, required))
                .map(LaunchOptionsForm.HeapChoice::megabytes).toList();
    }

    @Test
    void choicesStopAtThisComputersMemory() {
        // A 16 GB machine typically reports a little under 16384 MB.
        assertEquals(List.of(512, 1024, 1536, 2048, 3072, 4096, 6144, 8192, 12288, 16384),
                sizes(16_200, 4096));
        assertEquals(List.of(512, 1024, 1536, 2048, 3072, 4096, 6144, 8192), sizes(8_000, 4096));
    }

    @Test
    void savedOrInheritedSizesAboveThisComputerStaySelectable() {
        assertEquals(List.of(512, 1024, 1536, 2048, 3072, 4096, 6144, 8192, 32768),
                sizes(8_000, 32768, 4096));
    }

    @Test
    void unknownMemoryOffersEveryPreset() {
        assertEquals(14, sizes(0, 4096).size());
        assertEquals(65536, sizes(0, 4096).getLast());
    }
}