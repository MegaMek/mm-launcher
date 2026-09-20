package org.megamek.launcher.channel;

/**
 * Strictly bound launcher provenance for a managed installation's immutable channel and its
 * independently mutable check-on-open flag. Update ownership remains in the separate receipt.
 */
public record ChannelPreference(
        int schemaVersion,
        String installationId,
        String canonicalRoot,
        String registeredAt,
        FollowChannel channel,
        boolean checkOnOpen) {
}
