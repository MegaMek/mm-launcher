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
import java.net.URI;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.megamek.launcher.release.ReleaseTransport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {
    @TempDir Path temp;

    @Test
    void invalidInvocationIsExplicitAndNonzero() {
        ByteArrayOutputStream errors = new ByteArrayOutputStream();

        int result = Main.run(new String[]{"--baseline", "only-one.json"},
                new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(errors));

        assertEquals(2, result);
        String message = errors.toString(StandardCharsets.UTF_8);
        assertTrue(message.contains("ERROR:"));
        assertTrue(message.contains("Usage:"));
    }

    @Test
    void invalidRealUpdateConsentFailsBeforeRegistryNetworkOrWrites() {
        AtomicInteger requests = new AtomicInteger();
        ReleaseTransport transport = (URI uri, String accept) -> {
            requests.incrementAndGet();
            throw new IOException("network must not be reached");
        };
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int result = Main.run(new String[]{
                        "apply-update", "--registry", "absent.json", "--from-tag", "v1",
                        "--tag", "v2", "--size", "123", "--digest", "sha256:" + "a".repeat(64),
                        "--confirm", "NO"
                }, new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors), transport);
        assertEquals(2, result);
        assertEquals(0, requests.get());
        assertTrue(errors.toString(StandardCharsets.UTF_8)
                .contains("CLOSE-ALL-SUITE-APPS-AND-APPLY"));
    }

    @Test
    void legacyChannelMutationIsRejectedWithoutReadingNetworkOrWritingState() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        ReleaseTransport transport = (URI uri, String accept) -> {
            requests.incrementAndGet();
            throw new IOException("network must not be reached");
        };
        Path registry = temp.resolve("registry.json");
        byte[] original = "not parsed because mutation is disabled".getBytes(StandardCharsets.UTF_8);
        Files.write(registry, original);
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int result = Main.run(new String[]{"channel-set", "--registry", registry.toString(),
                        "--channel", "nightly"},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(errors), transport);

        assertEquals(2, result);
        assertEquals(0, requests.get());
        assertArrayEquals(original, Files.readAllBytes(registry));
        assertFalse(Files.exists(registry.resolveSibling(
                registry.getFileName() + ".metadata")));
        assertTrue(errors.toString(StandardCharsets.UTF_8)
                .contains("Channel is fixed; install another managed copy"));
        assertFalse(errors.toString(StandardCharsets.UTF_8)
                .contains("mm-launcher channel-set"));
    }
}
