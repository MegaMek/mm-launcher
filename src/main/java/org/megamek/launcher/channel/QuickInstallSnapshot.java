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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable quick-install catalog with explicit availability for each offered product/channel
 * pair. Core choices are always present; Experimental choices are present only when the player
 * has opted in to see them.
 */
public final class QuickInstallSnapshot {
    public static final QuickInstallOption.Key DEFAULT_KEY =
            new QuickInstallOption.Key(OfficialRepository.MEKHQ, FollowChannel.CORE);
    public static final List<QuickInstallOption.Key> CORE_KEYS = List.of(
            DEFAULT_KEY,
            new QuickInstallOption.Key(OfficialRepository.MEGAMEK, FollowChannel.CORE),
            new QuickInstallOption.Key(OfficialRepository.LAB, FollowChannel.CORE));
    public static final List<QuickInstallOption.Key> EXPERIMENTAL_KEYS = List.of(
            new QuickInstallOption.Key(OfficialRepository.MEKHQ, FollowChannel.EXPERIMENTAL),
            new QuickInstallOption.Key(OfficialRepository.MEGAMEK, FollowChannel.EXPERIMENTAL),
            new QuickInstallOption.Key(OfficialRepository.LAB, FollowChannel.EXPERIMENTAL));

    private final List<QuickInstallOption> options;
    private final Map<QuickInstallOption.Key, QuickInstallOption> byKey;
    private final boolean experimentalShown;

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
        List<QuickInstallOption.Key> keys = List.copyOf(indexed.keySet());
        if (keys.equals(keys(false))) {
            experimentalShown = false;
        } else if (keys.equals(keys(true))) {
            experimentalShown = true;
        } else {
            throw new IllegalArgumentException("quick-install snapshot must contain the Core "
                    + "choices, optionally followed by the Experimental choices, in fixed order");
        }
        this.options = List.copyOf(indexed.values());
        this.byKey = Map.copyOf(indexed);
    }

    /** Every offered choice in fixed order; the first is the primary install. */
    public static List<QuickInstallOption.Key> keys(boolean showExperimental) {
        if (!showExperimental) return CORE_KEYS;
        List<QuickInstallOption.Key> keys = new ArrayList<>(CORE_KEYS);
        keys.addAll(EXPERIMENTAL_KEYS);
        return List.copyOf(keys);
    }

    /** The offered choices other than the primary install, in menu order. */
    public static List<QuickInstallOption.Key> menuKeys(boolean showExperimental) {
        List<QuickInstallOption.Key> keys = keys(showExperimental);
        return keys.subList(1, keys.size());
    }

    public boolean experimentalShown() {
        return experimentalShown;
    }

    public List<QuickInstallOption.Key> menuKeys() {
        return menuKeys(experimentalShown);
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
