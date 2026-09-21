package org.megamek.launcher.update;

import org.junit.jupiter.api.Test;
import org.megamek.launcher.onboarding.Product;
import org.megamek.launcher.operation.OperationContext;
import org.megamek.launcher.operation.OperationType;
import org.megamek.launcher.release.OfficialRepository;
import org.megamek.launcher.release.ReleaseTransport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
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

class AutomaticAdoptionResolverTest {
    @Test
    void numericLeadingZerosMatchWithoutGuessingAnExactEndpoint() throws Exception {
        QueueTransport transport = new QueueTransport(200,
                "[" + release("v0.50.07", "Unrelated title", false, true) + "]");
        AutomaticAdoptionResolver.Resolution result = resolve(transport, "0.50.7");

        assertEquals(AutomaticAdoptionResolver.Status.MATCHED, result.status());
        assertEquals("v0.50.07", result.release().tag());
        assertEquals(1, transport.uris.size());
        assertTrue(transport.uris.getFirst().toString().contains("/releases?per_page=50&page=1"));
        assertFalse(transport.uris.getFirst().toString().contains("/releases/tags/"));
        assertFalse(transport.accepts.contains("application/octet-stream"));

        AutomaticAdoptionResolver.Resolution displayMatch = resolve(new QueueTransport(200,
                "[" + release("release_0_50_07", "MegaMek 0.50.07",
                        false, true) + "]"), "0.50.7");
        assertEquals(AutomaticAdoptionResolver.Status.MATCHED, displayMatch.status());
        assertEquals("release_0_50_07", displayMatch.release().tag());
    }

    @Test
    void draftIneligibleAndSuffixMismatchAreIgnored() throws Exception {
        String page = "[" + String.join(",",
                release("v0.50.07", "0.50.07", true, true),
                release("v0.50.007", "0.50.7", false, false),
                release("v0.50.7-beta", "0.50.7 beta", false, true)) + "]";
        AutomaticAdoptionResolver.Resolution finalBuild =
                resolve(new QueueTransport(200, page), "0.50.7");
        AutomaticAdoptionResolver.Resolution suffixed =
                resolve(new QueueTransport(200, page), "0.50.7-rc1");

        assertEquals(AutomaticAdoptionResolver.Status.NO_UNIQUE_MATCH, finalBuild.status());
        assertEquals(AutomaticAdoptionResolver.Status.NO_UNIQUE_MATCH, suffixed.status());
    }

    @Test
    void multipleMatchingTagsNeverGuess() throws Exception {
        String page = "[" + release("v0.50.07", "First", false, true) + ","
                + release("v00.050.007", "Second", false, true) + "]";
        assertEquals(AutomaticAdoptionResolver.Status.NO_UNIQUE_MATCH,
                resolve(new QueueTransport(200, page), "0.50.7").status());
    }

    @Test
    void requestFailureIsTypedAndTechnicalDetailIsOnlyInDiagnostics() throws Exception {
        QueueTransport transport = new QueueTransport(404, "{\"message\":\"Not Found\"}");
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        AutomaticAdoptionResolver.Resolution result = new AutomaticAdoptionResolver(transport)
                .resolve(OfficialRepository.MEGAMEK, "0.50.7",
                        new PrintStream(diagnostics),
                        OperationContext.none(OperationType.ADOPT_EXISTING));

        assertEquals(AutomaticAdoptionResolver.Status.REQUEST_FAILED, result.status());
        assertTrue(result.failure() instanceof IOException);
        assertTrue(diagnostics.toString(StandardCharsets.UTF_8).contains("HTTP 404"));
        assertFalse(ImportedCopyAdoptionService.AUTOMATIC_MATCH_UNAVAILABLE.contains("404"));
    }

    @Test
    void fullPaginationBoundNeverTreatsPartialHistoryAsUnique() throws Exception {
        List<String> pages = new ArrayList<>();
        for (int page = 0; page < AutomaticAdoptionResolver.MAX_PAGES; page++) {
            List<String> releases = new ArrayList<>();
            for (int row = 0; row < AutomaticAdoptionResolver.PAGE_SIZE; row++) {
                releases.add(release("v9." + page + "." + row,
                        "Other", false, true));
            }
            pages.add("[" + String.join(",", releases) + "]");
        }
        QueueTransport transport = new QueueTransport(200, pages.toArray(String[]::new));

        assertEquals(AutomaticAdoptionResolver.Status.SEARCH_INCOMPLETE,
                resolve(transport, "0.50.7").status());
        assertEquals(AutomaticAdoptionResolver.MAX_PAGES, transport.uris.size());
    }

    @Test
    void productSetsMapDeterministicallyAndAmbiguousSetsFail() throws Exception {
        Product mega = product("megamek");
        Product lab = product("lab");
        Product hq = product("mekhq");
        assertEquals(OfficialRepository.MEKHQ,
                ImportedCopyAdoptionService.repositoryFor(List.of(mega, lab, hq)));
        assertEquals(OfficialRepository.LAB,
                ImportedCopyAdoptionService.repositoryFor(List.of(lab)));
        assertEquals(OfficialRepository.MEGAMEK,
                ImportedCopyAdoptionService.repositoryFor(List.of(mega)));
        assertThrows(IOException.class,
                () -> ImportedCopyAdoptionService.repositoryFor(List.of(mega, lab)));
    }

    private static AutomaticAdoptionResolver.Resolution resolve(
            QueueTransport transport, String observed) throws Exception {
        return new AutomaticAdoptionResolver(transport).resolve(
                OfficialRepository.MEGAMEK, observed,
                new PrintStream(new ByteArrayOutputStream()),
                OperationContext.none(OperationType.ADOPT_EXISTING));
    }

    private static Product product(String key) {
        return new Product(key, key + ".jar", key + ".Main", "0.50.7", List.of());
    }

    private static String release(String tag, String title, boolean draft,
                                  boolean eligible) {
        String asset = eligible ? """
                [{"name":"MegaMek-%s.tar.gz","size":1234,
                "digest":"sha256:%s",
                "browser_download_url":"https://github.com/MegaMek/megamek/releases/download/%s/MegaMek-%s.tar.gz"}]
                """.formatted(tag, "a".repeat(64), tag, tag) : "[]";
        return """
                {"tag_name":"%s","name":"%s","draft":%s,"prerelease":false,
                "html_url":"https://github.com/MegaMek/megamek/releases/tag/%s",
                "assets":%s}
                """.formatted(tag, title, draft, tag, asset);
    }

    private static final class QueueTransport implements ReleaseTransport {
        private final int status;
        private final ArrayDeque<String> responses;
        private final List<URI> uris = new ArrayList<>();
        private final List<String> accepts = new ArrayList<>();

        private QueueTransport(int status, String... responses) {
            this.status = status;
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public Response get(URI uri, String accept) throws IOException {
            uris.add(uri);
            accepts.add(accept);
            String response = responses.poll();
            if (response == null) throw new IOException("unexpected request");
            return new Response(status, Map.of(), new ByteArrayInputStream(
                    response.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
