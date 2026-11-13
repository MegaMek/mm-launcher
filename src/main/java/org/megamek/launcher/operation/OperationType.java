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

public enum OperationType {
    FRESH_INSTALL,
    IMPORT_EXISTING,
    ADOPT_EXISTING,
    UPDATE_PREVIEW,
    UPDATE_APPLY,
    LAUNCHER_UPDATE,
    RECOVERY,
    UNINSTALL,
    UNINSTALL_RECOVERY,
    REPAIR,
    GUI_ERROR;

    private static final String BUNDLE = "org.megamek.launcher.services";

    public String displayName() {
        return switch (this) {
            case FRESH_INSTALL -> I18n.getTextAt(BUNDLE, "OperationType.freshInstall");
            case IMPORT_EXISTING -> I18n.getTextAt(BUNDLE, "OperationType.importExisting");
            case ADOPT_EXISTING -> I18n.getTextAt(BUNDLE, "OperationType.adoptExisting");
            case UPDATE_PREVIEW -> I18n.getTextAt(BUNDLE, "OperationType.updatePreview");
            case UPDATE_APPLY -> I18n.getTextAt(BUNDLE, "OperationType.updateApply");
            case LAUNCHER_UPDATE -> I18n.getTextAt(BUNDLE, "OperationType.launcherUpdate");
            case RECOVERY -> I18n.getTextAt(BUNDLE, "OperationType.recovery");
            case UNINSTALL -> I18n.getTextAt(BUNDLE, "OperationType.uninstall");
            case UNINSTALL_RECOVERY -> I18n.getTextAt(BUNDLE, "OperationType.uninstallRecovery");
            case REPAIR -> I18n.getTextAt(BUNDLE, "OperationType.repair");
            case GUI_ERROR -> I18n.getTextAt(BUNDLE, "OperationType.guiError");
        };
    }
}
