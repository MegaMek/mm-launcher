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

final class VersionDisplay {
    private VersionDisplay() {
    }

    static String programChannelVersion(String program, String channel, String version) {
        StringBuilder label = new StringBuilder(program);
        if (channel != null && !channel.isBlank()) {
            label.append(' ').append(channel);
        }
        if (version != null && !version.isBlank()) {
            label.append(" (").append(version).append(')');
        }
        return label.toString();
    }

    static String installLatest(String program, String channel, String version) {
        return "Install latest " + programChannelVersion(program, channel, version);
    }

    static String launch(String program, String channel, String version) {
        return "Launch " + programChannelVersion(program, channel, version);
    }
}
