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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class I18nTest {
    private static final Path RESOURCES = Path.of("src", "main", "resources");
    private static final Pattern TRANSLATION = Pattern.compile("(.+)_([a-z]{2})\\.properties");

    @AfterEach
    void restoreLanguage() {
        I18n.setLanguage("en");
    }

    @Test
    void readsTheDefaultBundleAndMarksMissingKeys() {
        I18n.setLanguage("en");
        assertEquals("Language", I18n.getText("Settings.language"));
        assertEquals("!No.such.key!", I18n.getText("No.such.key"));
        assertFalse(I18n.isResourceKeyValid(I18n.getText("No.such.key")));
    }

    @Test
    void anUntranslatedKeyFallsBackToEnglishNotTheSystemLanguage() {
        Locale system = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMAN);
            I18n.setLanguage("ru");
            assertEquals(Locale.forLanguageTag("ru"), I18n.getLocale());
            assertEquals("Language", I18n.getText("Settings.language"));
        } finally {
            Locale.setDefault(system);
        }
    }

    @Test
    void blankLanguageFollowsTheSystemLanguage() {
        I18n.setLanguage(null);
        assertEquals(Locale.getDefault(), I18n.getLocale());
    }

    @Test
    void everyBundleHasAFileForEveryOfferedLanguage() throws IOException {
        List<Path> bases = baseBundles();
        assertFalse(bases.isEmpty());
        List<String> missing = new ArrayList<>();
        for (Path base : bases) {
            String name = base.getFileName().toString().replace(".properties", "");
            for (String language : I18n.LOCALE_CHOICES) {
                if (language.equals("en")) {
                    continue;
                }
                if (!Files.isRegularFile(base.resolveSibling(name + "_" + language + ".properties"))) {
                    missing.add(base + " has no " + language + " file");
                }
            }
        }
        assertTrue(missing.isEmpty(), String.join("\n", missing));
    }

    @Test
    void translationsOnlyUseEnglishKeysAndKeepFormattedValuesValid() throws IOException {
        List<String> problems = new ArrayList<>();
        try (Stream<Path> files = Files.walk(RESOURCES)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Matcher matcher = TRANSLATION.matcher(file.getFileName().toString());
                if (!matcher.matches()) {
                    continue;
                }
                Path base = file.resolveSibling(matcher.group(1) + ".properties");
                if (!Files.isRegularFile(base)) {
                    problems.add(file + " has no English bundle " + base.getFileName());
                    continue;
                }
                Properties english = load(base);
                Properties translated = load(file);
                for (String key : translated.stringPropertyNames()) {
                    if (!english.containsKey(key)) {
                        problems.add(file + ": " + key + " is not in " + base.getFileName());
                        continue;
                    }
                    try {
                        new MessageFormat(translated.getProperty(key));
                    } catch (IllegalArgumentException invalid) {
                        problems.add(file + ": " + key + " is not a valid MessageFormat pattern");
                    }
                }
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    private static List<Path> baseBundles() throws IOException {
        try (Stream<Path> files = Files.walk(RESOURCES)) {
            return files.filter(Files::isRegularFile)
                    .filter(file -> file.getFileName().toString().endsWith(".properties"))
                    .filter(file -> !TRANSLATION.matcher(file.getFileName().toString()).matches())
                    .toList();
        }
    }

    private static Properties load(Path file) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }
}
