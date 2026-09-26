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
