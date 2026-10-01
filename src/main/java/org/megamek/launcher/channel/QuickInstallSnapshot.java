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

package org.megamek.launcher.channel;

import org.megamek.launcher.release.OfficialRepository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable nine-choice catalog, with explicit availability for each product/channel pair. */
public final class QuickInstallSnapshot {
    public static final QuickInstallOption.Key DEFAULT_KEY =
            new QuickInstallOption.Key(OfficialRepository.MEKHQ, FollowChannel.MILESTONE);
    public static final List<QuickInstallOption.Key> MENU_KEYS = List.of(
            new QuickInstallOption.Key(OfficialRepository.MEGAMEK, FollowChannel.MILESTONE),
            new QuickInstallOption.Key(OfficialRepository.LAB, FollowChannel.MILESTONE),
            new QuickInstallOption.Key(OfficialRepository.MEKHQ, FollowChannel.DEVELOPMENT),
            new QuickInstallOption.Key(OfficialRepository.MEGAMEK, FollowChannel.DEVELOPMENT),
            new QuickInstallOption.Key(OfficialRepository.LAB, FollowChannel.DEVELOPMENT),
            new QuickInstallOption.Key(OfficialRepository.MEKHQ, FollowChannel.WEEKLY),
            new QuickInstallOption.Key(OfficialRepository.MEGAMEK, FollowChannel.WEEKLY),
            new QuickInstallOption.Key(OfficialRepository.LAB, FollowChannel.WEEKLY));
    public static final List<QuickInstallOption.Key> ALL_KEYS = List.of(
            DEFAULT_KEY,
            MENU_KEYS.get(0), MENU_KEYS.get(1), MENU_KEYS.get(2), MENU_KEYS.get(3),
            MENU_KEYS.get(4), MENU_KEYS.get(5), MENU_KEYS.get(6), MENU_KEYS.get(7));

    private final List<QuickInstallOption> options;
    private final Map<QuickInstallOption.Key, QuickInstallOption> byKey;

    public QuickInstallSnapshot(List<QuickInstallOption> options) {
        Objects.requireNonNull(options, "options");
        LinkedHashMap<QuickInstallOption.Key, QuickInstallOption> indexed =
                new LinkedHashMap<>();
        for (QuickInstallOption option : options) {
            Objects.requireNonNull(option, "quick-install option");
            if (indexed.putIfAbsent(option.key(), option) != null) {
                throw new IllegalArgumentException(
                        "duplicate quick-install option: " + option.key());
            }
        }
        if (!List.copyOf(indexed.keySet()).equals(ALL_KEYS)) {
            throw new IllegalArgumentException(
                    "quick-install snapshot must contain all nine choices in fixed order");
        }
        this.options = List.copyOf(indexed.values());
        this.byKey = Map.copyOf(indexed);
    }

    public List<QuickInstallOption> options() {
        return options;
    }

    public Map<QuickInstallOption.Key, QuickInstallOption> byKey() {
        return byKey;
    }

    public QuickInstallOption option(QuickInstallOption.Key key) {
        QuickInstallOption option = byKey.get(key);
        if (option == null) {
            throw new IllegalArgumentException("unknown quick-install choice: " + key);
        }
        return option;
    }
}
