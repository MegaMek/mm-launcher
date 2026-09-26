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

package org.megamek.launcher.channel;

import org.junit.jupiter.api.Test;
import org.megamek.launcher.release.OfficialRepository;
import org.megamek.launcher.release.ReleaseTransport;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SelectedChannelReleaseCatalogTest {
    @Test
    void pageOneCombinesCurrentAndUnknownWithoutGuessingOrPackageBytes() throws Exception {
        OfficialRepository repository = OfficialRepository.MEKHQ;
        String current = release(repository, "v0.51.0", true);
        String unknown = release(repository, "v0.49.9", true);
        String opposite = release(repository, "v0.52.0", true);
        QueueTransport transport = new QueueTransport(
                text("stable: 0.51.0\ndev: 0.52.0\n"),
                text("[" + unknown + "," + opposite + "," + current + "," + current + "]"));

        SelectedChannelReleaseCatalog.Result result =
                new SelectedChannelReleaseCatalog(transport).page(
                        repository, FollowChannel.MILESTONE, 1, 10);

        assertEquals(List.of("v0.51.0", "v0.49.9"),
                result.entries().stream().map(entry -> entry.release().tag()).toList());
        assertEquals(List.of(SelectedChannelReleaseCatalog.Classification.SELECTED_CHANNEL,
                        SelectedChannelReleaseCatalog.Classification.UNKNOWN),
                result.entries().stream().map(
                        SelectedChannelReleaseCatalog.Entry::classification).toList());
        assertEquals(2, transport.uris.size(),
                "eligible current history metadata avoids a redundant exact lookup");
        assertTrue(transport.uris.get(1).toString()
                .contains("/repos/MegaMek/mekhq/releases?per_page=10&page=1"));
        assertFalse(transport.accepts.contains("application/octet-stream"));
    }

    @Test
    void missingCurrentIsResolvedExactlyAndSharedPointerBelongsToEitherSelection()
            throws Exception {
        OfficialRepository repository = OfficialRepository.MEGAMEK;
        QueueTransport missing = new QueueTransport(
                text("stable: 0.51.0\ndev: 0.52.0\n"),
                text("[" + release(repository, "v0.50.0", true) + "]"),
                text(release(repository, "v0.51.0", true)));

        SelectedChannelReleaseCatalog.Result injected =
                new SelectedChannelReleaseCatalog(missing).page(
                        repository, FollowChannel.MILESTONE, 1, 10);

        assertEquals(List.of("v0.51.0", "v0.50.0"),
                injected.entries().stream().map(entry -> entry.release().tag()).toList());
        assertTrue(missing.uris.get(2).toString().endsWith("/releases/tags/v0.51.0"));

        QueueTransport shared = new QueueTransport(
                text("stable: 0.51.0\ndev: 0.51.0\n"),
                text("[" + release(repository, "v0.51.0", true) + "]"));
        SelectedChannelReleaseCatalog.Result development =
                new SelectedChannelReleaseCatalog(shared).page(
                        repository, FollowChannel.DEVELOPMENT, 1, 10);
        assertEquals(1, development.entries().size());
        assertEquals(SelectedChannelReleaseCatalog.Classification.SELECTED_CHANNEL,
                development.entries().getFirst().classification());
        assertEquals(2, shared.uris.size());
    }

    @Test
    void laterPagesExcludeInjectedCurrentAndValidateBounds() throws Exception {
        OfficialRepository repository = OfficialRepository.LAB;
        QueueTransport transport = new QueueTransport(
                text("stable: 0.51.0\ndev: 0.52.0\n"),
                text("[" + release(repository, "v0.51.0", true) + ","
                        + release(repository, "v0.48.0", false) + "]"));

        SelectedChannelReleaseCatalog.Result result =
                new SelectedChannelReleaseCatalog(transport).page(
                        repository, FollowChannel.MILESTONE, 2, 10);

        assertEquals(List.of("v0.48.0"),
                result.entries().stream().map(entry -> entry.release().tag()).toList());
        assertFalse(result.entries().getFirst().assessment().eligible());
        assertTrue(result.entries().getFirst().assessment().reason().contains("SHA-256"));
        assertThrows(IOException.class, () -> new SelectedChannelReleaseCatalog(
                new QueueTransport()).page(repository, FollowChannel.MILESTONE, 0, 10));
    }

    private static String release(OfficialRepository repository, String tag, boolean digest) {
        String digestField = digest ? "\"digest\":\"sha256:" + "a".repeat(64) + "\"," : "";
        return """
                {"tag_name":"%s","name":"Do not infer a channel","draft":false,
                 "prerelease":true,
                 "html_url":"https://github.com/%s/releases/tag/%s",
                 "assets":[{"name":"%s%s.tar.gz","size":689648435,%s
                 "browser_download_url":"https://github.com/%s/releases/download/%s/%s%s.tar.gz"}]}
                """.formatted(tag, repository.slug(), tag, repository.assetPrefix(), tag,
                digestField, repository.slug(), tag, repository.assetPrefix(), tag);
    }

    private static ReleaseTransport.Response text(String body) {
        return new ReleaseTransport.Response(200, Map.of(),
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static final class QueueTransport implements ReleaseTransport {
        private final ArrayDeque<Response> responses = new ArrayDeque<>();
        private final List<URI> uris = new ArrayList<>();
        private final List<String> accepts = new ArrayList<>();

        private QueueTransport(Response... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public Response get(URI uri, String accept) throws IOException {
            uris.add(uri);
            accepts.add(accept);
            if (responses.isEmpty()) throw new IOException("unexpected request: " + uri);
            return responses.removeFirst();
        }
    }
}
