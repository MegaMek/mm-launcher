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

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;

/**
 * Opens web links and folders with the user's desktop applications. Java's own desktop support
 * relies on GNOME libraries on Linux and is often unavailable under KDE, Wayland or lighter
 * desktops, so on Linux and other Unix desktops this falls back to {@code xdg-open}.
 * Must be called off the UI thread because the fallback briefly waits for {@code xdg-open}.
 */
final class PlatformOpener {
    private static final long XDG_OPEN_WAIT_MILLIS = 2_000;

    private final AwtDesktop awt;
    private final CommandRunner commands;
    private final boolean xdgPlatform;

    PlatformOpener() {
        this(new SystemAwtDesktop(), new ProcessCommandRunner(),
                isXdgPlatform(System.getProperty("os.name", "")));
    }

    PlatformOpener(AwtDesktop awt, CommandRunner commands, boolean xdgPlatform) {
        this.awt = Objects.requireNonNull(awt);
        this.commands = Objects.requireNonNull(commands);
        this.xdgPlatform = xdgPlatform;
    }

    static boolean isXdgPlatform(String osName) {
        String name = osName.toLowerCase(Locale.ROOT);
        return !name.isBlank() && !name.contains("win") && !name.contains("mac")
                && !name.contains("darwin");
    }

    void browse(URI address) throws IOException {
        Objects.requireNonNull(address);
        open(Desktop.Action.BROWSE, address.toString(), "this link",
                () -> awt.browse(address));
    }

    void open(Path directory) throws IOException {
        Objects.requireNonNull(directory);
        open(Desktop.Action.OPEN, directory.toString(), "this folder",
                () -> awt.open(directory));
    }

    private void open(Desktop.Action action, String target, String what, AwtCall call)
            throws IOException {
        IOException awtFailure = null;
        if (awt.supports(action)) {
            try {
                call.run();
                return;
            } catch (IOException | UnsupportedOperationException | SecurityException e) {
                awtFailure = e instanceof IOException io ? io : new IOException(e.getMessage(), e);
                if (!xdgPlatform) throw awtFailure;
            }
        }
        if (!xdgPlatform) {
            throw new IOException("Opening " + what + " is not supported on this system. "
                    + "Open it manually: " + target);
        }
        IOException failure;
        try {
            OptionalInt exit = commands.run(List.of("xdg-open", target), XDG_OPEN_WAIT_MILLIS);
            // A still-running xdg-open has handed off to an application that stayed attached.
            if (exit.isEmpty() || exit.getAsInt() == 0) return;
            failure = new IOException("Could not open " + what + " (xdg-open exited with code "
                    + exit.getAsInt() + "). Open it manually: " + target);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failure = new IOException("Interrupted while opening " + what, e);
        } catch (IOException e) {
            failure = new IOException("Could not open " + what
                    + " (xdg-open is not available; installing xdg-utils usually fixes this). "
                    + "Open it manually: " + target, e);
        }
        if (awtFailure != null) failure.addSuppressed(awtFailure);
        throw failure;
    }

    @FunctionalInterface
    private interface AwtCall {
        void run() throws IOException;
    }

    interface AwtDesktop {
        boolean supports(Desktop.Action action);

        void browse(URI address) throws IOException;

        void open(Path directory) throws IOException;
    }

    interface CommandRunner {
        /** Exit code if the command finished within the wait, or empty if it is still running. */
        OptionalInt run(List<String> command, long waitMillis)
                throws IOException, InterruptedException;
    }

    private static final class SystemAwtDesktop implements AwtDesktop {
        @Override
        public boolean supports(Desktop.Action action) {
            return Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(action);
        }

        @Override
        public void browse(URI address) throws IOException {
            Desktop.getDesktop().browse(address);
        }

        @Override
        public void open(Path directory) throws IOException {
            Desktop.getDesktop().open(directory.toFile());
        }
    }

    private static final class ProcessCommandRunner implements CommandRunner {
        @Override
        public OptionalInt run(List<String> command, long waitMillis)
                throws IOException, InterruptedException {
            Process process = new ProcessBuilder(command)
                    .redirectInput(ProcessBuilder.Redirect.from(new File("/dev/null")))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (process.waitFor(waitMillis, TimeUnit.MILLISECONDS)) {
                return OptionalInt.of(process.exitValue());
            }
            return OptionalInt.empty();
        }
    }
}
