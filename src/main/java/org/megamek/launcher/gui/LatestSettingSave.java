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

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Serializes background saves of one setting so the latest request wins. Each request takes a
 * revision on the event-dispatch thread; a worker that reaches the lock after a newer request
 * skips its write instead of overwriting the newer value.
 */
final class LatestSettingSave<T> {
    @FunctionalInterface
    interface Writer<T> {
        void write(T value) throws IOException;
    }

    private final Object lock = new Object();
    private final AtomicLong latest = new AtomicLong();
    private final Writer<T> writer;

    LatestSettingSave(Writer<T> writer) {
        this.writer = writer;
    }

    /** Records a new request; older pending revisions become superseded. */
    long request() {
        return latest.incrementAndGet();
    }

    /** Writes {@code value} unless a newer request exists; returns whether it wrote. */
    boolean saveIfLatest(long revision, T value) throws IOException {
        synchronized (lock) {
            if (revision != latest.get()) return false;
            writer.write(value);
            return true;
        }
    }
}
