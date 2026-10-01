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

package org.megamek.launcher.release;

import java.io.IOException;

public enum OfficialRepository {
    MEGAMEK("megamek", "MegaMek/megamek", "MegaMek-", "megamek"),
    LAB("lab", "MegaMek/megameklab", "MegaMekLab-", "lab"),
    MEKHQ("mekhq", "MegaMek/mekhq", "MekHQ-", "mekhq");

    private final String key;
    private final String slug;
    private final String assetPrefix;
    private final String requiredProduct;

    OfficialRepository(String key, String slug, String assetPrefix, String requiredProduct) {
        this.key = key;
        this.slug = slug;
        this.assetPrefix = assetPrefix;
        this.requiredProduct = requiredProduct;
    }

    public String key() { return key; }
    public String slug() { return slug; }
    public String assetPrefix() { return assetPrefix; }
    public String requiredProduct() { return requiredProduct; }

    public String productName() {
        return switch (this) {
            case MEGAMEK -> "MegaMek";
            case LAB -> "MegaMekLab";
            case MEKHQ -> "MekHQ";
        };
    }

    public static OfficialRepository parse(String value) throws IOException {
        for (OfficialRepository repository : values()) {
            if (repository.key.equals(value)) return repository;
        }
        throw new IOException("application must be one of: megamek, mekhq, lab");
    }
}
