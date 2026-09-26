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

import org.megamek.launcher.onboarding.Product;
import org.megamek.launcher.plan.Decision;
import org.megamek.launcher.registry.InstallationRecord;

import java.util.List;

record RealUpdateJournal(
        int schemaVersion,
        String transactionId,
        String canonicalRoot,
        String canonicalRegistry,
        String installationId,
        String registeredAt,
        String phase,
        boolean previousStatePublished,
        CurrentUpdateState previousState,
        CurrentUpdateState nextState,
        InstallationRecord expectedRecord,
        String targetObservedBuild,
        List<Product> targetProducts,
        List<Decision> decisions,
        List<Operation> operations,
        List<String> createdDirectories) {

    RealUpdateJournal {
        targetProducts = targetProducts == null ? null : List.copyOf(targetProducts);
        decisions = decisions == null ? null : List.copyOf(decisions);
        operations = operations == null ? null : List.copyOf(operations);
        createdDirectories = createdDirectories == null ? null : List.copyOf(createdDirectories);
    }

    record Operation(String action, String path, String beforeHash, String afterHash,
                     String backupPath, String stagedPath) {
    }
}
