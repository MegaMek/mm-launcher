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

import org.junit.jupiter.api.Test;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformOpenerTest {
    private static final URI LINK = URI.create("https://github.com/MegaMek/mm-launcher/releases");

    @Test
    void usesJavaDesktopWhenSupported() throws Exception {
        FakeDesktop awt = new FakeDesktop(true, null);
        RecordingRunner runner = new RecordingRunner(OptionalInt.of(0), null);

        new PlatformOpener(awt, runner, true).browse(LINK);

        assertEquals(List.of(LINK.toString()), awt.calls);
        assertTrue(runner.commands.isEmpty());
    }

    @Test
    void fallsBackToXdgOpenWhenJavaDesktopCannotBrowse() throws Exception {
        FakeDesktop awt = new FakeDesktop(false, null);
        RecordingRunner runner = new RecordingRunner(OptionalInt.of(0), null);

        new PlatformOpener(awt, runner, true).browse(LINK);

        assertTrue(awt.calls.isEmpty());
        assertEquals(List.of(List.of("xdg-open", LINK.toString())), runner.commands);
    }

    @Test
    void fallsBackToXdgOpenForFoldersAndWhenJavaDesktopFails() throws Exception {
        Path folder = Path.of("install", "MegaMek");
        FakeDesktop awt = new FakeDesktop(true, new IOException("no handler"));
        RecordingRunner runner = new RecordingRunner(OptionalInt.empty(), null);

        new PlatformOpener(awt, runner, true).open(folder);

        assertEquals(List.of(folder.toString()), awt.calls);
        assertEquals(List.of(List.of("xdg-open", folder.toString())), runner.commands);
    }

    @Test
    void reportsMissingOrFailingXdgOpenWithTheTarget() {
        FakeDesktop awt = new FakeDesktop(false, null);

        IOException missing = assertThrows(IOException.class, () -> new PlatformOpener(awt,
                new RecordingRunner(null, new IOException("No such file")), true).browse(LINK));
        assertTrue(missing.getMessage().contains("xdg-utils"));
        assertTrue(missing.getMessage().contains(LINK.toString()));

        IOException failed = assertThrows(IOException.class, () -> new PlatformOpener(awt,
                new RecordingRunner(OptionalInt.of(3), null), true).browse(LINK));
        assertTrue(failed.getMessage().contains("code 3"));
        assertTrue(failed.getMessage().contains(LINK.toString()));
    }

    @Test
    void doesNotUseXdgOpenOnWindowsOrMac() {
        RecordingRunner runner = new RecordingRunner(OptionalInt.of(0), null);

        IOException unsupported = assertThrows(IOException.class, () -> new PlatformOpener(
                new FakeDesktop(false, null), runner, false).browse(LINK));
        assertTrue(unsupported.getMessage().contains("not supported"));
        IOException failed = new IOException("denied");
        assertEquals(failed, assertThrows(IOException.class, () -> new PlatformOpener(
                new FakeDesktop(true, failed), runner, false).browse(LINK)));
        assertTrue(runner.commands.isEmpty());
    }

    @Test
    void recognizesXdgPlatforms() {
        assertTrue(PlatformOpener.isXdgPlatform("Linux"));
        assertTrue(PlatformOpener.isXdgPlatform("FreeBSD"));
        assertFalse(PlatformOpener.isXdgPlatform("Windows 11"));
        assertFalse(PlatformOpener.isXdgPlatform("Mac OS X"));
        assertFalse(PlatformOpener.isXdgPlatform(""));
    }

    private static final class FakeDesktop implements PlatformOpener.AwtDesktop {
        private final boolean supported;
        private final IOException failure;
        private final List<String> calls = new ArrayList<>();

        FakeDesktop(boolean supported, IOException failure) {
            this.supported = supported;
            this.failure = failure;
        }

        @Override public boolean supports(Desktop.Action action) {
            return supported;
        }

        @Override public void browse(URI address) throws IOException {
            calls.add(address.toString());
            if (failure != null) throw failure;
        }

        @Override public void open(Path directory) throws IOException {
            calls.add(directory.toString());
            if (failure != null) throw failure;
        }
    }

    private static final class RecordingRunner implements PlatformOpener.CommandRunner {
        private final OptionalInt exit;
        private final IOException failure;
        private final List<List<String>> commands = new ArrayList<>();

        RecordingRunner(OptionalInt exit, IOException failure) {
            this.exit = exit;
            this.failure = failure;
        }

        @Override public OptionalInt run(List<String> command, long waitMillis) throws IOException {
            commands.add(command);
            if (failure != null) throw failure;
            return exit;
        }
    }
}
