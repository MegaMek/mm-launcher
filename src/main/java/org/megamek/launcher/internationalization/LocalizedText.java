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

import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

/**
 * Text from a bundle that hasn't been put into words yet, so the same message can be shown to the player in their
 * language and written to logs and diagnostics in English. Arguments that are themselves {@code LocalizedText} are
 * resolved in the same language. {@link #toString()} is the English form, so text that ends up in a log by accident
 * stays English.
 */
public final class LocalizedText {
    private final String bundle;
    private final String key;
    private final Object[] args;
    private final String literal;
    private final LocalizedText[] parts;

    private LocalizedText(String bundle, String key, Object[] args, String literal, LocalizedText[] parts) {
        this.bundle = bundle;
        this.key = key;
        this.args = args;
        this.literal = literal;
        this.parts = parts;
    }

    /** Text read from {@code bundle}; with arguments, the value is a {@link java.text.MessageFormat} pattern. */
    public static LocalizedText of(String bundle, String key, Object... args) {
        return new LocalizedText(Objects.requireNonNull(bundle), Objects.requireNonNull(key),
              args == null ? new Object[0] : args.clone(), null, null);
    }

    /** Text that reads the same in every language, such as a version number or text that isn't translated. */
    public static LocalizedText literal(String text) {
        return new LocalizedText(null, null, new Object[0], text, null);
    }

    /** Sentences shown one after another; {@code null} parts are skipped. */
    public static LocalizedText concat(LocalizedText... parts) {
        return new LocalizedText(null, null, new Object[0], null,
              Arrays.stream(parts).filter(Objects::nonNull).toArray(LocalizedText[]::new));
    }

    /** The text in the launcher's language. */
    public String text() {
        return in(I18n.getLocale());
    }

    /** The text in English, for logs and diagnostics. */
    public String english() {
        return in(Locale.ENGLISH);
    }

    /** The text in {@code locale}, falling back to English for anything not translated. */
    public String in(Locale locale) {
        if (parts != null) {
            StringBuilder joined = new StringBuilder();
            for (LocalizedText part : parts) {
                String text = part.in(locale);
                if (text != null) {
                    joined.append(text);
                }
            }
            return joined.toString();
        }
        if (literal != null || key == null) {
            return literal;
        }
        if (args.length == 0) {
            return I18n.getTextAt(locale, bundle, key);
        }
        Object[] resolved = Arrays.stream(args)
              .map(arg -> arg instanceof LocalizedText text ? text.in(locale) : arg)
              .toArray();
        return I18n.getFormattedTextAt(locale, bundle, key, resolved);
    }

    @Override
    public String toString() {
        return english();
    }
}