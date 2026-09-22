package org.megamek.launcher.gui;

import java.util.Locale;

/** Small shared formatter for user-facing binary download sizes. */
final class BinarySizeFormat {
    private static final long KIB = 1L << 10;
    private static final long MIB = 1L << 20;
    private static final long GIB = 1L << 30;

    private BinarySizeFormat() {
    }

    static String humanReadable(long bytes) {
        requireNonNegative(bytes);
        if (bytes >= GIB) return String.format(Locale.ROOT, "%.1f GiB", (double) bytes / GIB);
        if (bytes >= MIB) return String.format(Locale.ROOT, "%.1f MiB", (double) bytes / MIB);
        if (bytes >= KIB) return String.format(Locale.ROOT, "%.1f KiB", (double) bytes / KIB);
        return bytes + (bytes == 1 ? " byte" : " bytes");
    }

    static String mebibytes(long bytes) {
        requireNonNegative(bytes);
        double mebibytes = (double) bytes / MIB;
        if (bytes > 0 && mebibytes < 0.05d) return "< 0.1 MiB";
        return String.format(Locale.ROOT, "%.1f MiB", mebibytes);
    }

    private static void requireNonNegative(long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("byte size must not be negative");
    }
}
