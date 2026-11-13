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

import java.util.Locale;

/** Small shared formatter for user-facing binary download sizes. */
final class BinarySizeFormat {
    private static final String BUNDLE = "org.megamek.launcher.gui.home";
    private static final long KIB = 1L << 10;
    private static final long MIB = 1L << 20;
    private static final long GIB = 1L << 30;

    private BinarySizeFormat() {
    }

    static String humanReadable(long bytes) {
        requireNonNegative(bytes);
        if (bytes >= GIB) return I18n.getFormattedTextAt(BUNDLE, "BinarySizeFormat.gigabytes",
                oneDecimal((double) bytes / GIB));
        if (bytes >= MIB) return I18n.getFormattedTextAt(BUNDLE, "BinarySizeFormat.megabytes",
                oneDecimal((double) bytes / MIB));
        if (bytes >= KIB) return I18n.getFormattedTextAt(BUNDLE, "BinarySizeFormat.kilobytes",
                oneDecimal((double) bytes / KIB));
        return bytes == 1 ? I18n.getFormattedTextAt(BUNDLE, "BinarySizeFormat.byte", String.valueOf(bytes))
                : I18n.getFormattedTextAt(BUNDLE, "BinarySizeFormat.bytes", String.valueOf(bytes));
    }

    static String mebibytes(long bytes) {
        requireNonNegative(bytes);
        double mebibytes = (double) bytes / MIB;
        if (bytes > 0 && mebibytes < 0.05d) return I18n.getTextAt(BUNDLE, "BinarySizeFormat.underTenthMegabyte");
        return I18n.getFormattedTextAt(BUNDLE, "BinarySizeFormat.megabytes", oneDecimal(mebibytes));
    }

    private static String oneDecimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static void requireNonNegative(long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("byte size must not be negative");
    }
}
