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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * Reads provenance JSON written before ownership policy 2. Those receipts, states and journals
 * lack {@code protectedPackageFiles}; the strict mappers reject any missing record component, so
 * exactly that one absent field is supplied as an empty list before binding.
 */
final class LegacyProvenanceJson {
    static final String PROTECTED_PACKAGE_FILES = "protectedPackageFiles";
    private static final Map<Class<?>, List<String>> STATE_LOCATIONS = Map.of(
            OwnershipReceipt.class, List.of(""),
            CurrentUpdateState.class, List.of(""),
            RealUpdateJournal.class, List.of("previousState", "nextState"),
            UninstallJournal.class, List.of("current"));

    private LegacyProvenanceJson() {
    }

    static <T> T read(ObjectMapper mapper, byte[] bytes, Class<T> type) throws IOException {
        List<String> locations = STATE_LOCATIONS.get(type);
        if (locations == null) return mapper.readValue(bytes, type);
        return bind(mapper, mapper.readTree(bytes), type, locations);
    }

    static <T> T read(ObjectMapper mapper, InputStream input, Class<T> type) throws IOException {
        List<String> locations = STATE_LOCATIONS.get(type);
        if (locations == null) return mapper.readValue(input, type);
        return bind(mapper, mapper.readTree(input), type, locations);
    }

    private static <T> T bind(ObjectMapper mapper, JsonNode tree, Class<T> type,
                              List<String> locations) throws IOException {
        if (tree instanceof ObjectNode root) {
            for (String location : locations) {
                JsonNode target = location.isEmpty() ? root : root.get(location);
                if (target instanceof ObjectNode state && !state.has(PROTECTED_PACKAGE_FILES)) {
                    state.putArray(PROTECTED_PACKAGE_FILES);
                }
            }
        }
        return mapper.treeToValue(tree, type);
    }
}
