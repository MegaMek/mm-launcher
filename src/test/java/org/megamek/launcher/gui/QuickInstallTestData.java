package org.megamek.launcher.gui;

import org.megamek.launcher.channel.ChannelCatalog;
import org.megamek.launcher.channel.FollowChannel;
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
            String assetName = repository.assetPrefix() + tag + ".tar.gz";
            ReleaseCatalog.Asset asset = new ReleaseCatalog.Asset(assetName, 123,
                    "sha256:" + "a".repeat(64), URI.create("https://github.com/"
                    + repository.slug() + "/releases/download/" + tag + "/" + assetName));
            ReleaseCatalog.Release release = new ReleaseCatalog.Release(tag, tag,
                    false, key.channel() == FollowChannel.DEVELOPMENT,
                    URI.create("https://github.com/" + repository.slug()
                            + "/releases/tag/" + tag), List.of(asset));
            ChannelCatalog.Target target = new ChannelCatalog.Target(key.channel(), version,
                    repository, release, asset, "fixture:official-channels");
            options.add(new QuickInstallOption(key, target));
        }
        return new QuickInstallSnapshot(options);
    }
}
