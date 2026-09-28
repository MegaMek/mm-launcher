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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LauncherNewsFeedTest {
    @Test
    void sortsAndLimitsDatedOfficialHeadlinesWithoutRenderingArticleHtml() throws Exception {
        String xml = "<feed xmlns=\"http://www.w3.org/2005/Atom\">"
                + entry("Old", "2025-01-02T00:00:00Z", "old")
                + entry("Latest &amp; news", "2026-09-26T11:25:10+00:00", "latest")
                + entry("Next", "2026-08-14T00:00:00Z", "next")
                + entry("Third", "2026-07-30T00:00:00Z", "third")
                + "</feed>";
        LauncherNewsFeed feed = new LauncherNewsFeed((uri, accept) -> {
            assertEquals(LauncherNewsFeed.FEED, uri);
            assertEquals("application/atom+xml", accept);
            return new ReleaseTransport.Response(200, Map.of(),
                    new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        });
        var articles = feed.latest();
        assertEquals(3, articles.size());
        assertEquals("Latest & news", articles.get(0).title());
        assertEquals("2026-09-26", articles.get(0).published().toString());
        assertEquals("https://megamek.org/latest", articles.get(0).link().toString());
        assertEquals("Next", articles.get(1).title());
        assertEquals("Third", articles.get(2).title());
    }

    @Test
    void rejectsUntrustedLinksAndXmlEntities() {
        String outside = "<feed xmlns=\"http://www.w3.org/2005/Atom\">"
                + entry("News", "2026-09-26T00:00:00Z", "https://not-megamek.org/post")
                + "</feed>";
        assertThrows(IOException.class, () -> LauncherNewsFeed.parse(
                outside.getBytes(StandardCharsets.UTF_8)));
        String entity = "<!DOCTYPE feed [<!ENTITY xxe SYSTEM \"file:///C:/secret.txt\">]>"
                + "<feed xmlns=\"http://www.w3.org/2005/Atom\">"
                + entry("&xxe;", "2026-09-26T00:00:00Z", "post") + "</feed>";
        assertThrows(IOException.class, () -> LauncherNewsFeed.parse(
                entity.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void rejectsFailedAndOversizedResponses() {
        LauncherNewsFeed unavailable = new LauncherNewsFeed((uri, accept) ->
                new ReleaseTransport.Response(503, Map.of(), new ByteArrayInputStream(new byte[0])));
        assertThrows(IOException.class, unavailable::latest);
        LauncherNewsFeed oversized = new LauncherNewsFeed((uri, accept) ->
                new ReleaseTransport.Response(200, Map.of(),
                        new ByteArrayInputStream(new byte[1_048_577])));
        IOException error = assertThrows(IOException.class, oversized::latest);
        assertTrue(error.getMessage().contains("size limit"));
    }

    private static String entry(String title, String published, String path) {
        String url = path.startsWith("https:") ? path : "https://megamek.org/" + path;
        return "<entry><title type=\"html\">" + title + "</title>"
                + "<published>" + published + "</published>"
                + "<link rel=\"alternate\" type=\"text/html\" href=\"" + url + "\"/>"
                + "<content type=\"html\"><![CDATA[<style>ignored</style>Article body]]></content>"
                + "</entry>";
    }
}
