package org.megamek.launcher.channel;

import org.megamek.launcher.release.OfficialRepository;
import org.megamek.launcher.release.ReleaseCatalog;

import java.io.IOException;

/** Replaceable boundary for the official channel labels and exact release metadata. */
public interface ChannelCatalog {
    Target target(FollowChannel channel, OfficialRepository repository)
            throws IOException, InterruptedException;

    record Target(FollowChannel channel, String version, OfficialRepository repository,
                  ReleaseCatalog.Release release, ReleaseCatalog.Asset asset, String source) {
    }
}
