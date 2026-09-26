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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfficialYamlChannelCatalogTest {
    private static final String FIXTURE = """
            # Production-shaped fixture; unrelated official website values are scalar-only.
            stable: "0.51.0"
            stable_date: June 6, 2026
            dev: 0.51.0 # the same release may be in both channels
            dev_date: June 6, 2026
            corerules: 0.51.00.1
            megamek_extras: 1.2
            """;

    @Test
    void mapsStableAndDevLabelsToExactSameRepositoryTagWithoutPackageDownload()
            throws Exception {
        Map<FollowChannel, String> labels = new OfficialYamlChannelCatalog(
                new QueueTransport(text(200, FIXTURE))).versions();
        assertEquals("0.51.0", labels.get(FollowChannel.MILESTONE));
        assertEquals(labels.get(FollowChannel.MILESTONE),
                labels.get(FollowChannel.DEVELOPMENT));

        QueueTransport transport = new QueueTransport(text(200, FIXTURE),
                text(200, release(OfficialRepository.MEKHQ, "v0.51.0", true)));
        ChannelCatalog.Target target = new OfficialYamlChannelCatalog(transport)
                .target(FollowChannel.DEVELOPMENT, OfficialRepository.MEKHQ);

        assertEquals("v0.51.0", target.release().tag());
        assertEquals(OfficialRepository.MEKHQ, target.repository());
        assertEquals("MekHQ-v0.51.0.tar.gz", target.asset().name());
        assertEquals(List.of(OfficialYamlChannelCatalog.SOURCE,
                URI.create("https://api.github.com/repos/MegaMek/mekhq/releases/tags/v0.51.0")),
                transport.uris);
        assertTrue(transport.accepts.stream().noneMatch("application/octet-stream"::equals),
                "a channel check must never request package bytes");
    }

    @Test
    void quickSnapshotFetchesYamlOnceAndDeduplicatesExactMetadataForAllRepositories()
            throws Exception {
        QueueTransport transport = new QueueTransport(
                text(200, FIXTURE),
                text(200, release(OfficialRepository.MEKHQ, "v0.51.0", true)),
                text(200, release(OfficialRepository.MEGAMEK, "v0.51.0", true)),
                text(200, release(OfficialRepository.LAB, "v0.51.0", true)));

        QuickInstallSnapshot snapshot =
                new OfficialYamlChannelCatalog(transport).quickInstallSnapshot();

        assertEquals(QuickInstallSnapshot.ALL_KEYS,
                snapshot.options().stream().map(QuickInstallOption::key).toList());
        assertEquals(6, snapshot.byKey().size());
        assertEquals("MekHQ-v0.51.0.tar.gz",
                snapshot.option(QuickInstallSnapshot.DEFAULT_KEY).asset().name());
        assertEquals("MegaMek-v0.51.0.tar.gz",
                snapshot.option(QuickInstallSnapshot.MENU_KEYS.get(0)).asset().name());
        assertEquals("MegaMekLab-v0.51.0.tar.gz",
                snapshot.option(QuickInstallSnapshot.MENU_KEYS.get(1)).asset().name());
        assertEquals(1, transport.uris.stream()
                .filter(OfficialYamlChannelCatalog.SOURCE::equals).count());
        assertEquals(3, transport.uris.stream()
                .filter(uri -> "api.github.com".equals(uri.getHost())).count(),
                "stable==dev must share exact release metadata per repository/tag");
        assertTrue(transport.accepts.stream().noneMatch("application/octet-stream"::equals));
    }

    @Test
    void quickSnapshotKeepsDifferentStableAndDevelopmentVersionsForEveryRepository()
            throws Exception {
        String different = "stable: 0.51.0\ndev: 0.52.0\n";
        QueueTransport transport = new QueueTransport(
                text(200, different),
                text(200, release(OfficialRepository.MEKHQ, "v0.51.0", true)),
                text(200, release(OfficialRepository.MEGAMEK, "v0.51.0", true)),
                text(200, release(OfficialRepository.LAB, "v0.51.0", true)),
                text(200, release(OfficialRepository.MEKHQ, "v0.52.0", true)),
                text(200, release(OfficialRepository.MEGAMEK, "v0.52.0", true)),
                text(200, release(OfficialRepository.LAB, "v0.52.0", true)));

        QuickInstallSnapshot snapshot =
                new OfficialYamlChannelCatalog(transport).quickInstallSnapshot();

        assertEquals(List.of("0.51.0", "0.51.0", "0.51.0",
                        "0.52.0", "0.52.0", "0.52.0"),
                snapshot.options().stream().map(QuickInstallOption::version).toList());
        assertEquals(6, transport.uris.stream()
                .filter(uri -> "api.github.com".equals(uri.getHost())).count());
    }

    @Test
    void rejectsDuplicateMissingStructuredAliasedOversizedAndBadHttpYaml() {
        for (String yaml : List.of(
                "stable: 0.51.0\nstable: 0.50.0\ndev: 0.51.0\n",
                "stable: 0.51.0\n",
                "stable:\n  nested: 0.51.0\ndev: 0.51.0\n",
                "stable: &same 0.51.0\ndev: *same\n",
                "stable: v0.51.0\ndev: 0.51.0\n")) {
            OfficialYamlChannelCatalog catalog =
                    new OfficialYamlChannelCatalog(new QueueTransport(text(200, yaml)));
            assertThrows(IOException.class, catalog::versions, yaml);
        }
        String oversized = "stable: 0.51.0\ndev: 0.51.0\nx: "
                + "a".repeat(OfficialYamlChannelCatalog.MAX_BYTES);
        assertThrows(IOException.class, new OfficialYamlChannelCatalog(
                new QueueTransport(text(200, oversized)))::versions);
        IOException redirect = assertThrows(IOException.class, new OfficialYamlChannelCatalog(
                new QueueTransport(text(302, "")))::versions);
        assertTrue(redirect.getMessage().contains("redirect"));
        assertThrows(IOException.class, new OfficialYamlChannelCatalog(
                new QueueTransport(text(503, "")))::versions);
    }

    @Test
    void releaseMetadataMustContainAnEligibleDigestBoundAsset() {
        QueueTransport transport = new QueueTransport(text(200, FIXTURE),
                text(200, release(OfficialRepository.MEKHQ, "v0.51.0", false)));
        IOException error = assertThrows(IOException.class,
                () -> new OfficialYamlChannelCatalog(transport)
                        .target(FollowChannel.MILESTONE, OfficialRepository.MEKHQ));
        assertTrue(error.getMessage().contains("SHA-256"));
    }

    private static String release(OfficialRepository repository, String tag, boolean digest) {
        String digestField = digest ? "\"digest\":\"sha256:" + "a".repeat(64) + "\"," : "";
        return """
                {"tag_name":"%s","name":"Development","draft":false,"prerelease":false,
                 "html_url":"https://github.com/%s/releases/tag/%s",
                 "assets":[{"name":"%s%s.tar.gz","size":123,%s
                 "browser_download_url":"https://github.com/%s/releases/download/%s/%s%s.tar.gz"}]}
                """.formatted(tag, repository.slug(), tag, repository.assetPrefix(), tag,
                digestField, repository.slug(), tag, repository.assetPrefix(), tag);
    }

    private static ReleaseTransport.Response text(int status, String body) {
        return new ReleaseTransport.Response(status, Map.of(),
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static final class QueueTransport implements ReleaseTransport {
        private final ArrayDeque<Response> responses = new ArrayDeque<>();
        private final List<URI> uris = new ArrayList<>();
        private final List<String> accepts = new ArrayList<>();

        QueueTransport(Response... responses) {
            this.responses.addAll(List.of(responses));
        }

        @Override
        public Response get(URI uri, String accept) throws IOException {
            uris.add(uri);
            accepts.add(accept);
            if (responses.isEmpty()) throw new IOException("unexpected request " + uri);
            return responses.removeFirst();
        }
    }
}
