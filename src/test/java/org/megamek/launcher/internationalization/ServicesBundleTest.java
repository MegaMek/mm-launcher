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

package org.megamek.launcher.internationalization;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

class ServicesBundleTest {
    private static final Path SOURCE_ROOT = Path.of("src", "main", "java", "org", "megamek", "launcher");
    private static final Path BUNDLE_FILE =
            Path.of("src", "main", "resources", "org", "megamek", "launcher", "services.properties");
    private static final Set<String> EXCLUDED_PACKAGES = Set.of("gui", "design", "internationalization");
    private static final Set<String> EXCLUDED_FILES =
            Set.of("Main.java", "WindowsBootstrap.java", "DesktopLauncher.java");
    private static final Pattern TEXT_KEY = Pattern.compile("getTextAt\\(\\s*BUNDLE,\\s*\"([^\"]+)\"");
    private static final Pattern FORMATTED_KEY =
            Pattern.compile("getFormattedTextAt\\(\\s*BUNDLE,\\s*\"([^\"]+)\"");

    @Test
    void everyServiceKeyExistsAndFormattedValuesParse() throws IOException {
        Properties bundle = new Properties();
        try (Reader reader = Files.newBufferedReader(BUNDLE_FILE, StandardCharsets.UTF_8)) {
            bundle.load(reader);
        }
        Set<String> keys = new TreeSet<>();
        Set<String> formattedKeys = new TreeSet<>();
        try (Stream<Path> files = Files.walk(SOURCE_ROOT)) {
            for (Path file : files.filter(ServicesBundleTest::isServiceSource).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                collect(TEXT_KEY, source, keys);
                collect(FORMATTED_KEY, source, formattedKeys);
            }
        }
        keys.addAll(formattedKeys);
        assertFalse(keys.isEmpty(), "no services bundle keys found");
        for (String key : keys) {
            assertTrue(bundle.containsKey(key), "missing services bundle key: " + key);
        }
        for (String key : formattedKeys) {
            String value = bundle.getProperty(key);
            assertDoesNotThrow(() -> new MessageFormat(value), "invalid MessageFormat for " + key);
        }
    }

    private static boolean isServiceSource(Path file) {
        if (!file.toString().endsWith(".java") || EXCLUDED_FILES.contains(file.getFileName().toString())) {
            return false;
        }
        Path relative = SOURCE_ROOT.relativize(file);
        return relative.getNameCount() == 1 || !EXCLUDED_PACKAGES.contains(relative.getName(0).toString());
    }

    private static void collect(Pattern pattern, String source, Set<String> keys) {
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) {
            keys.add(matcher.group(1));
        }
    }
}
