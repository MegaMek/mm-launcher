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
                text(200, release("v0.51.0", true)));
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
                text(200, release("v0.51.0", false)));
        IOException error = assertThrows(IOException.class,
                () -> new OfficialYamlChannelCatalog(transport)
                        .target(FollowChannel.MILESTONE, OfficialRepository.MEKHQ));
        assertTrue(error.getMessage().contains("SHA-256"));
    }

    private static String release(String tag, boolean digest) {
        String digestField = digest ? "\"digest\":\"sha256:" + "a".repeat(64) + "\"," : "";
        return """
                {"tag_name":"%s","name":"Development","draft":false,"prerelease":false,
                 "html_url":"https://github.com/MegaMek/mekhq/releases/tag/%s",
                 "assets":[{"name":"MekHQ-%s.tar.gz","size":123,%s
                 "browser_download_url":"https://github.com/MegaMek/mekhq/releases/download/%s/MekHQ-%s.tar.gz"}]}
                """.formatted(tag, tag, tag, digestField, tag, tag);
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
