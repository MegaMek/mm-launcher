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

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.List;
import java.util.Locale;
import java.util.PropertyResourceBundle;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The launcher's copy of MegaMek's {@code megamek.common.internationalization.I18n} (MekHQ keeps the same copy as
 * {@code MHQInternationalization}). It loads UTF-8 {@code .properties} bundles and marks a missing key as
 * {@code !key!}. The launcher runs without MegaMek's jar, so the language is its own: the Language setting, or the
 * operating system's language when that setting is empty.
 */
public class I18n {

    /** Languages offered in Settings, in the same order as MegaMek's {@code CommonSettingsDialog.LOCALE_CHOICES}. */
    public static final List<String> LOCALE_CHOICES = List.of("en", "de", "ru", "es");

    private static final String MISSING_RESOURCE_TAG = "!";

    private final String defaultBundle;
    private final ConcurrentHashMap<String, ResourceBundle> resourceBundles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ResourceBundle> fixedLocaleBundles = new ConcurrentHashMap<>();
    private volatile Locale locale = Locale.getDefault();
    protected static volatile I18n instance;

    static {
        instance = new I18n("org.megamek.launcher.messages");
    }

    protected I18n(String defaultBundle) {
        this.defaultBundle = defaultBundle;
    }

    public static I18n getInstance() {
        return instance;
    }

    private static class UTF8Control extends ResourceBundle.Control {
        @Override
        public ResourceBundle newBundle(String baseName, Locale locale, String format, ClassLoader loader,
              boolean reload)
              throws IOException {
            String resourceName = toResourceName(toBundleName(baseName, locale), "properties");
            InputStream is = loader.getResourceAsStream(resourceName);
            if (is == null) {
                return null; // Resource not found, let ResourceBundle try fallback
            }
            try (InputStreamReader isr = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                return new PropertyResourceBundle(isr);
            } finally {
                is.close();
            }
        }

        @Override
        public Locale getFallbackLocale(String baseName, Locale locale) {
            // Without this, a missing translation falls back to the operating system's language rather than English.
            return null;
        }
    }

    /**
     * Sets the language every bundle is read in. Call it at startup, before any text is shown; text already shown
     * keeps its language.
     *
     * @param language a tag such as {@code de}, or blank for the operating system's language
     */
    public static void setLanguage(String language) {
        Locale next = language == null || language.isBlank()
              ? Locale.getDefault()
              : Locale.forLanguageTag(language);
        getInstance().locale = next;
        getInstance().resourceBundles.clear();
        getInstance().fixedLocaleBundles.clear();
    }

    /** The language bundles are read in. */
    public static Locale getLocale() {
        return getInstance().locale;
    }

    ResourceBundle getResourceBundle(String bundleName) {
        return resourceBundles.computeIfAbsent(bundleName, k ->
              ResourceBundle.getBundle(bundleName, locale, I18n.class.getClassLoader(), new UTF8Control()));
    }

    ResourceBundle getResourceBundle(String bundleName, Locale bundleLocale) {
        if (bundleLocale.equals(locale)) {
            return getResourceBundle(bundleName);
        }
        return fixedLocaleBundles.computeIfAbsent(bundleLocale.toLanguageTag() + '\n' + bundleName, k ->
              ResourceBundle.getBundle(bundleName, bundleLocale, I18n.class.getClassLoader(), new UTF8Control()));
    }

    /**
     * Get a string from a specific bundle in a given language rather than the launcher's language. Logs and
     * diagnostics use this to stay in English.
     *
     * @param locale     the language to read
     * @param bundleName the name of the bundle
     * @param key        the key of the string
     *
     * @return the string in that language, falling back to English
     */
    public static String getTextAt(Locale locale, String bundleName, String key) {
        ResourceBundle bundle = I18n.getInstance().getResourceBundle(bundleName, locale);
        if (bundle.containsKey(key)) {
            return bundle.getString(key);
        }
        return MISSING_RESOURCE_TAG + key + MISSING_RESOURCE_TAG;
    }

    /**
     * Get a formatted string from a specific bundle in a given language rather than the launcher's language.
     *
     * @param locale     the language to read
     * @param bundleName the name of the bundle
     * @param key        the key of the string
     * @param args       the arguments to format the string
     *
     * @return the formatted string in that language, falling back to English
     */
    public static String getFormattedTextAt(Locale locale, String bundleName, String key, Object... args) {
        return new MessageFormat(getTextAt(locale, bundleName, key), locale).format(args);
    }

    /**
     * Get a localized string from a specific bundle
     *
     * @param bundleName the name of the bundle
     * @param key        the key of the string
     *
     * @return the localized string
     */
    public static String getTextAt(String bundleName, String key) {
        if (I18n.getInstance().getResourceBundle(bundleName).containsKey(key)) {
            return I18n.getInstance().getResourceBundle(bundleName).getString(key);
        }
        return MISSING_RESOURCE_TAG + key + MISSING_RESOURCE_TAG;
    }

    /**
     * Get the keys of all localized strings in a specific bundle
     *
     * @param bundleName the name of the bundle
     *
     * @return the set of keys with localization
     */
    public static Set<String> getKeys(String bundleName) {
        return I18n.getInstance().getResourceBundle(bundleName).keySet();
    }

    /**
     * Get a localized string from the default bundle
     *
     * @param key the key of the string
     *
     * @return the localized string
     */
    public static String getText(String key) {
        return getTextAt(getInstance().defaultBundle, key);
    }

    /**
     * Get a formatted localized string from the default bundle
     *
     * @param key  the key of the string
     * @param args the arguments to format the string
     *
     * @return the localized string
     */
    public static String getFormattedText(String key, Object... args) {
        return getFormattedTextAt(getInstance().defaultBundle, key, args);
    }

    /**
     * Get a formatted localized string from a specific bundle
     *
     * @param bundleName the name of the bundle
     * @param key        the key of the string
     * @param args       the arguments to format the string
     *
     * @return the localized string
     */
    public static String getFormattedTextAt(String bundleName, String key, Object... args) {
        return new MessageFormat(getTextAt(bundleName, key), getLocale()).format(args);
    }

    /**
     * Whether {@code text} is real text rather than the {@code !key!} marker for a missing key.
     *
     * @param text the text to check
     *
     * @return {@code true} unless {@code text} starts and ends with {@code !}
     */
    public static boolean isResourceKeyValid(String text) {
        return !(text.startsWith(MISSING_RESOURCE_TAG) && text.endsWith(MISSING_RESOURCE_TAG));
    }
}
