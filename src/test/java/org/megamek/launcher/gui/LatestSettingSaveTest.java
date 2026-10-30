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

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LatestSettingSaveTest {
    @Test
    void supersededSaveReachingTheLockLastCannotOverwriteTheNewerValue() throws Exception {
        AtomicReference<Boolean> stored = new AtomicReference<>();
        LatestSettingSave<Boolean> save = new LatestSettingSave<>(stored::set);
        long on = save.request();
        long off = save.request();

        assertTrue(save.saveIfLatest(off, false));
        assertFalse(save.saveIfLatest(on, true), "the older request must be discarded");
        assertEquals(Boolean.FALSE, stored.get());
    }

    @Test
    void requestMadeWhileAnOlderSaveIsWritingStillWins() throws Exception {
        AtomicReference<Boolean> stored = new AtomicReference<>();
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        LatestSettingSave<Boolean> save = new LatestSettingSave<>(value -> {
            if (value) {
                writing.countDown();
                try {
                    assertTrue(release.await(30, TimeUnit.SECONDS));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException(interrupted);
                }
            }
            stored.set(value);
        });
        long on = save.request();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread older = new Thread(() -> {
            try {
                save.saveIfLatest(on, true);
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        older.start();
        assertTrue(writing.await(30, TimeUnit.SECONDS));
        long off = save.request();
        Thread newer = new Thread(() -> {
            try {
                save.saveIfLatest(off, false);
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        newer.start();
        release.countDown();
        older.join(30_000);
        newer.join(30_000);

        assertEquals(null, failure.get());
        assertEquals(Boolean.FALSE, stored.get());
        assertFalse(save.saveIfLatest(on, true));
        assertEquals(Boolean.FALSE, stored.get());
    }
}
