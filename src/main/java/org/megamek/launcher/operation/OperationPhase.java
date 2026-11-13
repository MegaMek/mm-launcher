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

package org.megamek.launcher.operation;

import org.megamek.launcher.internationalization.I18n;

public enum OperationPhase {
    METADATA,
    DOWNLOAD,
    VERIFY,
    EXTRACT,
    PLAN,
    AWAIT_CONSENT,
    PREPARE_INSTALL,
    APPLY,
    UNINSTALL,
    RECOVER,
    CLEANUP,
    FINAL;

    private static final String BUNDLE = "org.megamek.launcher.services";

    public String displayName() {
        return switch (this) {
            case METADATA -> I18n.getTextAt(BUNDLE, "OperationPhase.metadata");
            case DOWNLOAD -> I18n.getTextAt(BUNDLE, "OperationPhase.download");
            case VERIFY -> I18n.getTextAt(BUNDLE, "OperationPhase.verify");
            case EXTRACT -> I18n.getTextAt(BUNDLE, "OperationPhase.extract");
            case PLAN -> I18n.getTextAt(BUNDLE, "OperationPhase.plan");
            case AWAIT_CONSENT -> I18n.getTextAt(BUNDLE, "OperationPhase.awaitConsent");
            case PREPARE_INSTALL -> I18n.getTextAt(BUNDLE, "OperationPhase.prepareInstall");
            case APPLY -> I18n.getTextAt(BUNDLE, "OperationPhase.apply");
            case UNINSTALL -> I18n.getTextAt(BUNDLE, "OperationPhase.uninstall");
            case RECOVER -> I18n.getTextAt(BUNDLE, "OperationPhase.recover");
            case CLEANUP -> I18n.getTextAt(BUNDLE, "OperationPhase.cleanup");
            case FINAL -> I18n.getTextAt(BUNDLE, "OperationPhase.finalPhase");
        };
    }
}
