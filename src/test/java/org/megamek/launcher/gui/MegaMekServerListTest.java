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
import org.megamek.launcher.release.ReleaseTransport;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MegaMekServerListTest {
    private static MegaMekServerList serving(int status, String body) {
        return new MegaMekServerList((uri, accept) -> {
            assertEquals(MegaMekServerList.LIST, uri);
            return new ReleaseTransport.Response(status, Map.of(),
                    new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        });
    }

    @Test
    void keepsValidCurrentServersAndSkipsDeprecatedOrMalformedEntries() throws Exception {
        String json = """
                {"schemaVersion":1,"servers":[
                  {"host":"mm.example.org","port":2346,"version":"0.50.07","channel":"milestone",
                   "size":"Large","provider":"Example Host"},
                  {"host":"dev.example.org","port":2347,"version":"0.50.08","channel":"development"},
                  {"host":"old.example.org","port":2348,"version":"0.49.19","channel":"deprecated"},
                  {"host":"bad host","port":2349,"version":"1","channel":"milestone"},
                  {"host":"ok.example.org","port":70000,"version":"1","channel":"milestone"},
                  {"host":"ok.example.org","port":2350,"version":"<b>1</b>","channel":"milestone"},
                  {"host":"ok.example.org","port":2351,"version":"1","channel":"nightly"},
                  {"host":"ok.example.org","port":"2352","version":"1","channel":"milestone"},
                  "not an object"
                ],"futureField":true}
                """;
        List<MegaMekServerList.Server> servers = serving(200, json).current();
        assertEquals(List.of(
                new MegaMekServerList.Server("mm.example.org", 2346, "0.50.07",
                        MegaMekServerList.Channel.MILESTONE, "Large", "Example Host"),
                new MegaMekServerList.Server("dev.example.org", 2347, "0.50.08",
                        MegaMekServerList.Channel.DEVELOPMENT, "", "")), servers);
        assertEquals("mm.example.org:2346", servers.get(0).address());
    }

    @Test
    void unsupportedOrUnavailableListsFail() {
        assertThrows(IOException.class, () -> serving(404, "").current());
        assertThrows(IOException.class, () -> serving(200, "{\"schemaVersion\":2,\"servers\":[]}").current());
        assertThrows(IOException.class, () -> serving(200, "{\"schemaVersion\":1}").current());
        assertThrows(IOException.class, () -> serving(200, "not json").current());
        assertThrows(IOException.class, () -> serving(200, "{\"schemaVersion\":1,\"servers\":[\""
                + "x".repeat(300_000) + "\"]}").current());
    }

    @Test
    void launcherHelpersNormalizeVersionsAndQuoteCommands() {
        assertEquals("0.50.07", LauncherFrame.normalizedVersion("v0.50.07"));
        assertEquals("0.50.07", LauncherFrame.normalizedVersion(" 0.50.07-SNAPSHOT "));
        assertEquals("-Da=1", LauncherFrame.quoteArgument("-Da=1"));
        assertEquals("\"C:\\Program Files\\Java\\bin\\java.exe\"",
                LauncherFrame.quoteArgument("C:\\Program Files\\Java\\bin\\java.exe"));
        assertEquals("\"\"", LauncherFrame.quoteArgument(""));
    }
}
