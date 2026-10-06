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

import java.io.IOException;
import java.util.Objects;

/** Replaceable boundary for the official channel labels and exact release metadata. */
public interface ChannelCatalog {
    Target target(FollowChannel channel, OfficialRepository repository)
            throws IOException, InterruptedException;

    default Target targetBySource(FollowChannel channel, OfficialRepository repository, String source)
            throws IOException, InterruptedException {
        Target target = target(channel, repository);
        if (!target.source().equals(source)) throw new IOException("captured suite record source changed");
        return target;
    }

    default Target targetByTag(FollowChannel channel, OfficialRepository repository, String tag)
            throws IOException, InterruptedException {
        Target target = target(channel, repository);
        if (!target.release().tag().equals(tag)) throw new IOException("exact suite product target unavailable");
        return target;
    }

    record Target(FollowChannel channel, String version, OfficialRepository repository,
                  ReleaseCatalog.Release release, ReleaseCatalog.Asset asset, String source) {
    }

    final class UnpublishedChannelException extends IOException {
        private final FollowChannel channel;

        public UnpublishedChannelException(FollowChannel channel) {
            super("No complete " + Objects.requireNonNull(channel, "channel")
                    + " suite record has been published.");
            this.channel = channel;
        }

        public FollowChannel channel() {
            return channel;
        }
    }
}
