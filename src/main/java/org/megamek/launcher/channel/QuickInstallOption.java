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
