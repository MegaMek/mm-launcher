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

package org.megamek.launcher.gui;

import org.megamek.launcher.internationalization.I18n;

final class VersionDisplay {
    private static final String BUNDLE = "org.megamek.launcher.gui.home";

    private VersionDisplay() {
    }

    static String programChannelVersion(String program, String channel, String version) {
        StringBuilder label = new StringBuilder(program);
        if (channel != null && !channel.isBlank()) {
            label.append(' ').append(channel);
        }
        if (version != null && !version.isBlank()) {
            label.append(" (").append(version).append(')');
        }
        return label.toString();
    }

    /** Sentence-case value line under a short game-mode label, e.g. "Core · 0.51.02.7". */
    static String channelVersion(String channel, String version) {
        StringBuilder value = new StringBuilder();
        if (channel != null && !channel.isBlank()) value.append(channel);
        if (version != null && !version.isBlank()) {
            if (!value.isEmpty()) value.append(" · ");
            value.append(version);
        }
        return value.toString();
    }

    static String installLatest(String program, String channel, String version) {
        return I18n.getFormattedTextAt(BUNDLE, "VersionDisplay.installLatest", programChannelVersion(program,
                channel, version));
    }

    static String launch(String program, String channel, String version) {
        return I18n.getFormattedTextAt(BUNDLE, "VersionDisplay.launch", programChannelVersion(program, channel,
                version));
    }

    /** A release's publish day in the player's own calendar and locale, e.g. "Nov 3, 2026". */
    static String publishedDate(java.time.Instant published, java.time.ZoneId zone,
                                java.util.Locale locale) {
        return java.time.format.DateTimeFormatter
                .ofLocalizedDate(java.time.format.FormatStyle.MEDIUM)
                .withLocale(locale)
                .format(published.atZone(zone));
    }
}
