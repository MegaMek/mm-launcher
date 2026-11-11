/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.megamek.launcher.internationalization;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

import org.junit.jupiter.api.Test;

class FrameBundleTest {
    private static final Path SOURCE = Path.of("src", "main", "java", "org", "megamek", "launcher", "gui",
            "LauncherFrame.java");
    private static final Path BUNDLE = Path.of("src", "main", "resources", "org", "megamek", "launcher", "gui",
            "frame.properties");
    /** Every key literal, including keys chosen by a ternary inside a getTextAt call. */
    private static final Pattern KEY = Pattern.compile("\"(LauncherFrame\\.\\w+)\"");
    /** A formatted lookup, with either a literal key or a ternary choosing between two literal keys. */
    private static final Pattern FORMATTED = Pattern.compile("getFormattedTextAt\\(\\s*BUNDLE\\s*,\\s*"
            + "(?:[^\"]*\\?\\s*)?\"(LauncherFrame\\.\\w+)\"(?:\\s*:\\s*\"(LauncherFrame\\.\\w+)\")?");

    private static Properties bundle() throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(BUNDLE, StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    private static String source() throws IOException {
        return Files.readString(SOURCE, StandardCharsets.UTF_8);
    }

    private static Set<String> formattedKeys(String source) {
        Matcher matcher = FORMATTED.matcher(source);
        Set<String> keys = new TreeSet<>();
        while (matcher.find()) {
            keys.add(matcher.group(1));
            if (matcher.group(2) != null) keys.add(matcher.group(2));
        }
        return keys;
    }

    @Test
    void everyKeyUsedByLauncherFrameExists() throws IOException {
        Properties properties = bundle();
        Matcher matcher = KEY.matcher(source());
        Set<String> missing = new TreeSet<>();
        int found = 0;
        while (matcher.find()) {
            found++;
            if (!properties.containsKey(matcher.group(1))) missing.add(matcher.group(1));
        }
        assertTrue(found > 0, "no keys found in LauncherFrame");
        assertTrue(missing.isEmpty(), "missing from frame.properties: " + missing);
    }

    @Test
    void everyBundleKeyIsUsed() throws IOException {
        String source = source();
        Set<String> unused = new TreeSet<>();
        for (String key : bundle().stringPropertyNames()) {
            if (!source.contains('"' + key + '"')) unused.add(key);
        }
        assertTrue(unused.isEmpty(), "unused in LauncherFrame: " + unused);
    }

    @Test
    void formattedValuesParseAndEscapeApostrophes() throws IOException {
        Properties properties = bundle();
        Set<String> keys = formattedKeys(source());
        assertFalse(keys.isEmpty(), "no formatted keys found in LauncherFrame");
        for (String key : keys) {
            String value = properties.getProperty(key);
            assertNotNull(value, "missing formatted key " + key);
            assertDoesNotThrow(() -> new MessageFormat(value), key);
            assertFalse(value.replace("''", "").contains("'"), key + " needs '' for an apostrophe");
        }
    }

    @Test
    void plainValuesKeepSingleApostrophes() throws IOException {
        Properties properties = bundle();
        Set<String> formatted = formattedKeys(source());
        for (String key : properties.stringPropertyNames()) {
            if (formatted.contains(key)) continue;
            assertFalse(properties.getProperty(key).contains("''"), key + " is not formatted; use a single '");
        }
    }
}
