package org.megamek.launcher.update;

import org.megamek.launcher.manifest.Manifest;

import java.util.List;

public record OwnershipReceipt(
        int schemaVersion,
        int ownershipPolicyVersion,
        String installationId,
        String canonicalRoot,
        String registeredAt,
        String repository,
        String tag,
        String assetName,
        long assetSize,
        String assetSha256,
        Manifest officialManifest,
        List<String> excludedOfficialPaths) {
}
