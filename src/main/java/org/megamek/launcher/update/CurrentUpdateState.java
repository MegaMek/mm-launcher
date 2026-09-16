package org.megamek.launcher.update;

import org.megamek.launcher.manifest.Manifest;
import org.megamek.launcher.sandbox.OverrideEntry;

import java.util.List;

/**
 * Latest verified package provenance.  The create-new ownership receipt remains immutable
 * ancestry; this separately published state advances only as part of a real update transaction.
 */
public record CurrentUpdateState(
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
        List<String> excludedOfficialPaths,
        List<OverrideEntry> overrides,
        List<CaseOverride> caseOverrides,
        String lastTransactionId) {

    public CurrentUpdateState {
        excludedOfficialPaths = excludedOfficialPaths == null
                ? null : List.copyOf(excludedOfficialPaths);
        overrides = overrides == null ? null : List.copyOf(overrides);
        caseOverrides = caseOverrides == null ? null : List.copyOf(caseOverrides);
    }

    public OwnershipReceipt asReceipt() {
        return new OwnershipReceipt(schemaVersion, ownershipPolicyVersion, installationId,
                canonicalRoot, registeredAt, repository, tag, assetName, assetSize, assetSha256,
                officialManifest, excludedOfficialPaths);
    }

    public static CurrentUpdateState initial(OwnershipReceipt receipt) {
        return new CurrentUpdateState(receipt.schemaVersion(), receipt.ownershipPolicyVersion(),
                receipt.installationId(), receipt.canonicalRoot(), receipt.registeredAt(),
                receipt.repository(), receipt.tag(), receipt.assetName(), receipt.assetSize(),
                receipt.assetSha256(), receipt.officialManifest(),
                receipt.excludedOfficialPaths(), List.of(), List.of(), null);
    }
}
