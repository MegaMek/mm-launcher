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

package org.megamek.launcher.sandbox;

import java.util.List;

public record Journal(
        int schemaVersion,
        String transactionId,
        Phase phase,
        SandboxState previousState,
        SandboxState nextState,
        List<Operation> operations,
        List<String> createdDirectories) {
    public enum Phase {
        PREPARED,
        MUTATING,
        STATE_COMMITTED,
        CLEANUP
    }

    public record Operation(
            String action,
            String path,
            String beforeHash,
            String afterHash,
            String backupPath,
            boolean started,
            boolean completed) {
        public Operation markStarted() {
            return new Operation(action, path, beforeHash, afterHash, backupPath, true, completed);
        }

        public Operation markCompleted() {
            return new Operation(action, path, beforeHash, afterHash, backupPath, true, true);
        }
    }
}
