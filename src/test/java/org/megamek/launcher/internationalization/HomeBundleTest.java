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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The home-screen bundle has every key its sources read, and its formatted values parse. */
class HomeBundleTest {
    private static final Path BUNDLE =
            Path.of("src/main/resources/org/megamek/launcher/gui/home.properties");
    private static final Path SOURCES = Path.of("src/main/java/org/megamek/launcher/gui");
    private static final List<String> FILES = List.of(
            "AutomaticInstallationChecks", "BinarySizeFormat", "BusyGate", "ButtonDetail",
            "FirstLaunchButton", "FirstLaunchPanel", "FirstLaunchSplitButton", "GuiScale",
            "HomeLaunchSplitButton", "LaunchCurtain", "LauncherAppearance", "LauncherIcons",
            "LauncherLink", "LauncherNewsFeed", "LauncherTheme", "MapCheckBox", "StyledComboBox",
            "VersionDisplay");
    private static final Pattern PLAIN = Pattern.compile("getTextAt\\(BUNDLE,\\s*\"([^\"]+)\"");
    private static final Pattern FORMATTED =
            Pattern.compile("getFormattedTextAt\\(BUNDLE,\\s*\"([^\"]+)\"");

    @Test
    void everyKeyReadBySourcesExistsAndFormattedValuesParse() throws IOException {
        Properties bundle = new Properties();
        try (Reader reader = Files.newBufferedReader(BUNDLE, StandardCharsets.UTF_8)) {
            bundle.load(reader);
        }
        assertFalse(bundle.isEmpty(), "the bundle has no keys");

        Set<String> formatted = new TreeSet<>();
        Set<String> used = new TreeSet<>();
        for (String file : FILES) {
            String source = Files.readString(SOURCES.resolve(file + ".java"), StandardCharsets.UTF_8);
            collect(PLAIN, source, used);
            collect(FORMATTED, source, formatted);
        }
        used.addAll(formatted);
        assertFalse(used.isEmpty(), "no keys found in the sources");
        for (String key : used) {
            assertTrue(bundle.containsKey(key), "missing key " + key);
        }
        for (String key : formatted) {
            String value = bundle.getProperty(key);
            assertDoesNotThrow(() -> new MessageFormat(value), "bad MessageFormat in " + key);
        }
    }

    private static void collect(Pattern pattern, String source, Set<String> keys) {
        Matcher matcher = pattern.matcher(source);
        while (matcher.find()) keys.add(matcher.group(1));
    }
}
