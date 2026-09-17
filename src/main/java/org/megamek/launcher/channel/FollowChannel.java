package org.megamek.launcher.channel;

import java.util.Locale;

/** The two release labels intentionally published by the official website. */
public enum FollowChannel {
    MILESTONE("milestone", "stable", "Milestone"),
    DEVELOPMENT("development", "dev", "Development");

    private final String cliName;
    private final String feedKey;
    private final String displayName;

    FollowChannel(String cliName, String feedKey, String displayName) {
        this.cliName = cliName;
        this.feedKey = feedKey;
        this.displayName = displayName;
    }

    public String cliName() {
        return cliName;
    }

    public String feedKey() {
        return feedKey;
    }

    @Override
    public String toString() {
        return displayName;
    }

    public static FollowChannel parse(String value) {
        if (value != null) {
            String normalized = value.toLowerCase(Locale.ROOT);
            for (FollowChannel channel : values()) {
                if (channel.cliName.equals(normalized)) return channel;
            }
        }
        throw new IllegalArgumentException(
                "channel must be milestone or development (nightly is not supported)");
    }
}
