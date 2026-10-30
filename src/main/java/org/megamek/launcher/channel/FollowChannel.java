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

import java.util.List;
import java.util.Locale;

/**
 * The channel named by a published complete suite record. Core is the default for players;
 * Experimental is opt-in. The record's channel is authoritative, never GitHub's prerelease flag
 * or the version number.
 */
public enum FollowChannel {
    CORE("core", "Core", "For playing"),
    EXPERIMENTAL("experimental", "Experimental", "Help us test, may break things");

    private final String cliName;
    private final String displayName;
    private final String description;

    FollowChannel(String cliName, String displayName, String description) {
        this.cliName = cliName;
        this.displayName = displayName;
        this.description = description;
    }

    public String cliName() {
        return cliName;
    }

    /** Short player-facing explanation of who the channel is for. */
    public String description() {
        return description;
    }

    @Override
    public String toString() {
        return displayName;
    }

    /** The channels a player may choose; Experimental only after opting in. */
    public static List<FollowChannel> offered(boolean showExperimental) {
        return showExperimental ? List.of(CORE, EXPERIMENTAL) : List.of(CORE);
    }

    public static FollowChannel parse(String value) {
        if (value != null) {
            String normalized = value.toLowerCase(Locale.ROOT);
            for (FollowChannel channel : values()) {
                if (channel.cliName.equals(normalized)) return channel;
            }
        }
        throw new IllegalArgumentException("channel must be core or experimental");
    }
}