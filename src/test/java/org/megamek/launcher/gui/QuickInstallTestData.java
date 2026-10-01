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

import org.megamek.launcher.channel.ChannelCatalog;
import org.megamek.launcher.channel.FollowChannel;
import org.megamek.launcher.channel.SuiteTestData;
import org.megamek.launcher.channel.QuickInstallOption;
import org.megamek.launcher.channel.QuickInstallSnapshot;
import org.megamek.launcher.release.OfficialRepository;
import org.megamek.launcher.release.ReleaseCatalog;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

final class QuickInstallTestData {
    private QuickInstallTestData() {
    }

    static QuickInstallSnapshot snapshot(String milestone, String development) {
        List<QuickInstallOption> options = new ArrayList<>();
        for (QuickInstallOption.Key key : QuickInstallSnapshot.ALL_KEYS) {
            String version = key.channel() == FollowChannel.MILESTONE
                    ? milestone : development;
            String tag = "v" + version;
            OfficialRepository repository = key.repository();
            String assetName = repository.assetPrefix() + version + ".tar.gz";
            ReleaseCatalog.Asset asset = new ReleaseCatalog.Asset(assetName, 123,
                    "sha256:" + "a".repeat(64), URI.create("https://github.com/"
                    + repository.slug() + "/releases/download/" + tag + "/" + assetName));
            ReleaseCatalog.Release release = new ReleaseCatalog.Release(tag, tag,
                    false, key.channel() == FollowChannel.DEVELOPMENT,
                    URI.create("https://github.com/" + repository.slug()
                            + "/releases/tag/" + tag), List.of(asset));
            ChannelCatalog.Target target = new ChannelCatalog.Target(key.channel(), version,
                    repository, release, asset,
                    SuiteTestData.recordUri(SuiteTestData.canonical(version)).toString());
            options.add(new QuickInstallOption(key, target));
        }
        return new QuickInstallSnapshot(options);
    }

    static QuickInstallSnapshot weeklyOnly(String version) {
        return new QuickInstallSnapshot(snapshot(version, version).options().stream()
                .map(option -> option.key().channel() == FollowChannel.WEEKLY ? option
                        : new QuickInstallOption(option.key(), null,
                        "No complete " + option.key().channel() + " suite record has been published."))
                .toList());
    }
}
