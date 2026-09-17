package org.megamek.launcher.channel;

/** Strictly bound external metadata; it is not update ownership or provenance. */
public record ChannelPreference(
        int schemaVersion,
        String installationId,
        String canonicalRoot,
        String registeredAt,
        FollowChannel channel,
        boolean checkOnOpen) {
}
