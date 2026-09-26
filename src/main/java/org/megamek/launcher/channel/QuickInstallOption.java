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
import org.megamek.launcher.release.ReleaseCatalog;

import java.util.Objects;

/**
 * One validated official repository/channel target used by the first-launch quick installer.
 * The key and target are deliberately inseparable so a UI label cannot be paired with metadata
 * from another product or channel.
 */
public record QuickInstallOption(Key key, ChannelCatalog.Target target) {
    public QuickInstallOption {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(target, "target");
        if (target.repository() != key.repository() || target.channel() != key.channel()
                || target.version() == null || target.version().isBlank()
                || target.release() == null || target.asset() == null
                || !target.release().tag().equals("v" + target.version())
                || !target.release().assets().contains(target.asset())
                || target.source() == null || target.source().isBlank()) {
            throw new IllegalArgumentException(
                    "quick-install key and official target must match completely");
        }
    }

    public String version() {
        return target.version();
    }

    public ReleaseCatalog.Release release() {
        return target.release();
    }

    public ReleaseCatalog.Asset asset() {
        return target.asset();
    }

    public record Key(OfficialRepository repository, FollowChannel channel) {
        public Key {
            Objects.requireNonNull(repository, "repository");
            Objects.requireNonNull(channel, "channel");
        }
    }
}
