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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/** Checks that the design system's bundle has every key its controls look up. */
class DesignBundleTest {
    private static final String BUNDLE = "org/megamek/launcher/design/host/messages.properties";
    private static final Path SOURCES = Path.of("src/main/java/org/megamek/launcher/design");
    private static final Pattern PLAIN = Pattern.compile("getTextAt\\(\\s*BUNDLE\\s*,\\s*\"([^\"]+)\"");
    private static final Pattern FORMATTED = Pattern.compile("getFormattedTextAt\\(\\s*BUNDLE\\s*,\\s*\"([^\"]+)\"");
    /** TokenChips.Tone keeps its spoken-name keys in the enum and looks them up through a variable. */
    private static final Pattern TONE = Pattern.compile("\"(TokenChips\\.tone\\.\\w+)\"");

    @Test
    void everyKeyTheDesignSourcesUseIsInTheBundle() throws IOException {
        Properties bundle = bundle();
        Set<String> missing = new TreeSet<>();
        for (String key : keys(List.of(PLAIN, FORMATTED, TONE))) {
            if (!bundle.containsKey(key)) {
                missing.add(key);
            }
        }
        assertTrue(missing.isEmpty(), "Missing keys in " + BUNDLE + ": " + missing);
    }

    @Test
    void formattedValuesParseAsMessageFormat() throws IOException {
        Properties bundle = bundle();
        Set<String> formatted = keys(List.of(FORMATTED));
        assertFalse(formatted.isEmpty());
        for (String key : formatted) {
            String value = bundle.getProperty(key);
            assertNotNull(value, key);
            assertDoesNotThrow(() -> new MessageFormat(value), key);
        }
    }

    private static Properties bundle() throws IOException {
        Properties properties = new Properties();
        try (InputStream in = DesignBundleTest.class.getClassLoader().getResourceAsStream(BUNDLE)) {
            assertNotNull(in, BUNDLE);
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return properties;
    }

    private static Set<String> keys(List<Pattern> patterns) throws IOException {
        Set<String> keys = new TreeSet<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                for (Pattern pattern : patterns) {
                    Matcher matcher = pattern.matcher(source);
                    while (matcher.find()) {
                        keys.add(matcher.group(1));
                    }
                }
            }
        }
        return keys;
    }
}