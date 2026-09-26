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

package org.megamek.launcher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DesktopLauncherTest {
    @TempDir
    Path temp;

    @Test
    void desktopDefaultDelegatesToGuiWithoutChangingMainCli() {
        AtomicInteger starts = new AtomicInteger();
        AtomicReference<String[]> delegated = new AtomicReference<>();

        int result = DesktopLauncher.run(new String[0], output(), output(), (args, error) -> {
            starts.incrementAndGet();
            delegated.set(args);
            return 0;
        });

        assertEquals(0, result);
        assertEquals(1, starts.get());
        assertArrayEquals(new String[]{"gui"}, delegated.get());
    }

    @Test
    void registryOverrideIsForwardedAsOneUnmodifiedArgument() {
        Path registry = temp.resolve("state Ω & spaces").resolve("registry.json").toAbsolutePath();
        AtomicReference<String[]> delegated = new AtomicReference<>();

        int result = DesktopLauncher.run(
                new String[]{"--registry", registry.toString()},
                output(), output(), (args, error) -> {
                    delegated.set(args);
                    return 0;
                });

        assertEquals(0, result);
        assertArrayEquals(new String[]{"gui", "--registry", registry.toString()}, delegated.get());
        assertFalse(Files.exists(registry));
    }

    @Test
    void finderProcessSerialArgumentStillOpensGuiByDefault() {
        AtomicReference<String[]> delegated = new AtomicReference<>();

        int result = DesktopLauncher.run(new String[]{"-psn_0_12345"},
                output(), output(), (args, error) -> {
                    delegated.set(args);
                    return 0;
                });

        assertEquals(0, result);
        assertArrayEquals(new String[]{"gui"}, delegated.get());
    }

    @Test
    void startupCheckIsSideEffectFreeAndDoesNotOpenGui() {
        Path registry = temp.resolve("registry.json").toAbsolutePath();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        AtomicInteger starts = new AtomicInteger();

        int result = DesktopLauncher.run(
                new String[]{"--startup-check", "--registry", registry.toString()},
                new PrintStream(bytes), output(), (args, error) -> {
                    starts.incrementAndGet();
                    return 0;
                });

        assertEquals(0, result);
        assertEquals(0, starts.get());
        assertFalse(Files.exists(registry));
        String report = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(report.contains("MM-LAUNCHER-STARTUP-OK"));
        assertTrue(report.contains("registryOverride=validated"));
        assertFalse(report.contains(temp.toString()), "diagnostics must not disclose private paths");
    }

    @Test
    void versionCanWriteANewExplicitReportForGuiHeaderAutomation() throws Exception {
        Path report = temp.resolve("version result.txt").toAbsolutePath();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        int result = DesktopLauncher.run(
                new String[]{"--version", "--report", report.toString()},
                new PrintStream(bytes), output(), (args, error) -> 99);

        assertEquals(0, result);
        assertTrue(Files.readString(report).startsWith("MegaMek Launcher "));
        assertEquals(Files.readString(report).strip(), bytes.toString(StandardCharsets.UTF_8).strip());
    }

    @Test
    void diagnosticsRejectRelativePathsAndNeverCreateThem() {
        ByteArrayOutputStream errors = new ByteArrayOutputStream();

        int result = DesktopLauncher.run(
                new String[]{"--startup-check", "--registry", "relative-registry.json"},
                output(), new PrintStream(errors), (args, error) -> 99);

        assertEquals(2, result);
        assertTrue(errors.toString(StandardCharsets.UTF_8).contains("must be absolute"));
        assertFalse(Files.exists(Path.of("relative-registry.json")));
    }

    private static PrintStream output() {
        return new PrintStream(new ByteArrayOutputStream());
    }
}
