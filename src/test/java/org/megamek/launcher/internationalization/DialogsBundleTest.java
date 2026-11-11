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

/** Every key the dialog classes read from the dialogs bundle exists, and formatted values parse. */
class DialogsBundleTest {
    private static final Path BUNDLE = Path.of("src/main/resources/org/megamek/launcher/gui/dialogs.properties");
    private static final Path SOURCES = Path.of("src/main/java/org/megamek/launcher/gui");
    private static final List<String> FILES = List.of(
            "OperationProgressDialog.java", "NormalInstallConfirmationDialog.java", "BugReportDialog.java",
            "WhatsNewDialog.java", "LaunchOptionsForm.java", "LaunchOptionsDialog.java",
            "RecommendedUpdateConsentDialog.java", "LauncherErrorDialog.java", "LauncherAlertDialog.java",
            "GuiLauncher.java", "LauncherServices.java");
    private static final Pattern PLAIN = Pattern.compile("getTextAt\\(\\s*BUNDLE,\\s*\"([^\"]+)\"");
    private static final Pattern FORMATTED = Pattern.compile("getFormattedTextAt\\(\\s*BUNDLE,\\s*\"([^\"]+)\"");

    @Test
    void everyKeyUsedExistsAndFormattedValuesParse() throws IOException {
        Properties bundle = new Properties();
        try (Reader reader = Files.newBufferedReader(BUNDLE, StandardCharsets.UTF_8)) {
            bundle.load(reader);
        }
        assertFalse(bundle.isEmpty(), "dialogs bundle is empty");

        Set<String> missing = new TreeSet<>();
        Set<String> formatted = new TreeSet<>();
        int used = 0;
        for (String file : FILES) {
            String source = Files.readString(SOURCES.resolve(file), StandardCharsets.UTF_8);
            for (Pattern pattern : List.of(PLAIN, FORMATTED)) {
                Matcher matcher = pattern.matcher(source);
                while (matcher.find()) {
                    used++;
                    String key = matcher.group(1);
                    if (!bundle.containsKey(key)) missing.add(file + ": " + key);
                    if (pattern == FORMATTED) formatted.add(key);
                }
            }
        }
        assertTrue(used > 0, "no bundle keys found in the dialog sources");
        assertTrue(missing.isEmpty(), "keys missing from dialogs.properties: " + missing);
        for (String key : formatted) {
            String value = bundle.getProperty(key);
            if (value != null) {
                assertDoesNotThrow(() -> new MessageFormat(value), "invalid MessageFormat for " + key);
            }
        }
    }
}
