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

package org.megamek.launcher.release;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CachingReleaseTransportTest {
    private static final URI LIST = URI.create("https://api.github.com/repos/MegaMek/megamek/releases?per_page=50&page=1");
    private static final URI ASSET = URI.create("https://github.com/MegaMek/megamek/releases/download/v1/a.tar.gz");
    private static final String JSON = "application/vnd.github+json";

    @TempDir Path temp;

    @Test
    void freshResponsesAreSharedUntilTheyExpire() throws Exception {
        MutableClock clock = new MutableClock();
        FakeTransport network = new FakeTransport();
        CachingReleaseTransport cache = new CachingReleaseTransport(network, null, clock);

        assertEquals("[1]", body(cache.get(LIST, JSON)));
        assertEquals("[1]", body(cache.get(LIST, JSON)));
        assertEquals(1, network.requests.size());

        clock.advance(CachingReleaseTransport.FRESHNESS.minusSeconds(1));
        body(cache.get(LIST, JSON));
        assertEquals(1, network.requests.size());

        clock.advance(Duration.ofSeconds(1));
        assertEquals("[2]", body(cache.get(LIST, JSON)));
        assertEquals(2, network.requests.size());
    }

    @Test
    void aRelaunchReusesFreshResponsesFromDisk() throws Exception {
        MutableClock clock = new MutableClock();
        FakeTransport network = new FakeTransport();
        network.link = "<https://api.github.com/x?page=2>; rel=\"next\"";
        body(new CachingReleaseTransport(network, temp, clock).get(LIST, JSON));

        CachingReleaseTransport relaunched = new CachingReleaseTransport(network, temp, clock);
        ReleaseTransport.Response cached = relaunched.get(LIST, JSON);
        assertEquals(network.link, cached.firstHeader("Link"));
        assertEquals("[1]", body(cached));
        assertEquals(1, network.requests.size());

        clock.advance(CachingReleaseTransport.FRESHNESS);
        assertEquals("[2]", body(new CachingReleaseTransport(network, temp, clock).get(LIST, JSON)));
    }

    @Test
    void unreadableDiskEntriesAreRefetched() throws Exception {
        MutableClock clock = new MutableClock();
        FakeTransport network = new FakeTransport();
        body(new CachingReleaseTransport(network, temp, clock).get(LIST, JSON));
        try (var files = Files.list(temp)) {
            for (Path file : files.toList()) Files.writeString(file, "{not json");
        }
        assertEquals("[2]", body(new CachingReleaseTransport(network, temp, clock).get(LIST, JSON)));
    }

    @Test
    void failuresDownloadsAndOtherHostsAreNeverCached() throws Exception {
        FakeTransport network = new FakeTransport();
        CachingReleaseTransport cache = new CachingReleaseTransport(network, temp, new MutableClock());

        network.status = 500;
        assertEquals(500, cache.get(LIST, JSON).status());
        assertEquals(500, cache.get(LIST, JSON).status());
        network.status = 200;
        body(cache.get(ASSET, "application/octet-stream"));
        body(cache.get(ASSET, "application/octet-stream"));
        body(cache.get(LIST, "application/octet-stream"));
        body(cache.get(LIST, "application/octet-stream"));
        assertEquals(6, network.requests.size());
        try (var files = Files.list(temp)) {
            assertEquals(0, files.count());
        }
    }

    @Test
    void aRateLimitStopsFurtherApiRequestsUntilItsReset() throws Exception {
        MutableClock clock = new MutableClock();
        FakeTransport network = new FakeTransport();
        CachingReleaseTransport cache = new CachingReleaseTransport(network, null, clock);
        Instant reset = clock.instant().plus(Duration.ofMinutes(30));
        network.status = 403;
        network.headers = Map.of("X-RateLimit-Remaining", List.of("0"),
                "X-RateLimit-Reset", List.of(Long.toString(reset.getEpochSecond())));

        GitHubRateLimitException first = assertThrows(GitHubRateLimitException.class,
                () -> cache.get(LIST, JSON));
        assertEquals(reset, first.reset().orElseThrow());
        assertThrows(GitHubRateLimitException.class, () -> cache.get(
                URI.create("https://api.github.com/repos/MegaMek/mm-launcher/releases/latest"), JSON));
        assertEquals(1, network.requests.size());

        body(cache.get(ASSET, "application/octet-stream"));
        assertEquals(2, network.requests.size());

        network.status = 200;
        network.headers = Map.of();
        clock.advance(Duration.ofMinutes(30));
        assertEquals("[3]", body(cache.get(LIST, JSON)));
    }

    @Test
    void freshResponsesAreStillServedDuringARateLimit() throws Exception {
        MutableClock clock = new MutableClock();
        FakeTransport network = new FakeTransport();
        CachingReleaseTransport cache = new CachingReleaseTransport(network, null, clock);
        assertEquals("[1]", body(cache.get(LIST, JSON)));
        network.status = 429;
        URI other = URI.create("https://api.github.com/repos/MegaMek/mm-launcher/releases/latest");
        assertThrows(GitHubRateLimitException.class, () -> cache.get(other, JSON));

        assertEquals("[1]", body(cache.get(LIST, JSON)), "the cached copy needs no request");
        assertThrows(GitHubRateLimitException.class, () -> cache.get(other, JSON));
        assertThrows(GitHubRateLimitException.class, () -> cache.get(other, "application/octet-stream"));
        assertThrows(GitHubRateLimitException.class, () -> cache.fresh().get(LIST, JSON));
        assertEquals(2, network.requests.size());
    }

    @Test
    void theFreshViewAsksGitHubAndRefreshesTheSharedCopy() throws Exception {
        FakeTransport network = new FakeTransport();
        CachingReleaseTransport cache = new CachingReleaseTransport(network, null, new MutableClock());
        assertEquals("[1]", body(cache.get(LIST, JSON)));
        assertEquals("[2]", body(cache.fresh().get(LIST, JSON)));
        assertEquals("[2]", body(cache.get(LIST, JSON)), "later checks reuse the newer copy");
        assertEquals(2, network.requests.size());
        assertEquals("[3]", body(cache.fresh().get(ASSET, "application/octet-stream")));
    }

    @Test
    void rateLimitMessagesNameTheLocalResetTime() throws Exception {
        Instant reset = Instant.parse("2026-11-06T19:33:37Z");
        String message = new GitHubRateLimitException(reset, ZoneOffset.UTC).getMessage();
        assertTrue(message.startsWith("GitHub is limiting how often this network can check for releases."),
                message);
        assertTrue(message.contains("33"), message);
        assertTrue(new GitHubRateLimitException(null, ZoneId.of("UTC")).getMessage()
                .endsWith("Try again in a little while."));
    }

    @Test
    void onlyExhaustedOrThrottledResponsesAreRateLimits() {
        Clock clock = Clock.fixed(Instant.parse("2026-11-06T18:00:00Z"), ZoneOffset.UTC);
        assertNull(GitHubRateLimitException.from(response(403, Map.of(
                "x-ratelimit-remaining", List.of("12"))), clock));
        assertNull(GitHubRateLimitException.from(response(404, Map.of(
                "x-ratelimit-remaining", List.of("0"))), clock));
        assertEquals(Instant.parse("2026-11-06T18:01:00Z"), GitHubRateLimitException.from(
                response(403, Map.of("Retry-After", List.of("60"))), clock).reset().orElseThrow());
        assertTrue(GitHubRateLimitException.from(response(429, Map.of()), clock).reset().isEmpty());
        assertTrue(GitHubRateLimitException.from(response(403, Map.of(
                "x-ratelimit-remaining", List.of("0"))), clock).reset().isEmpty());
    }

    @Test
    void releasePagingFollowsGitHubsLinkHeader() {
        assertTrue(ReleaseCatalog.mayHaveNextPage(
                "<https://api.github.com/x?page=2>; rel=\"next\", <https://api.github.com/x?page=3>; rel=\"last\"",
                50, 50));
        assertFalse(ReleaseCatalog.mayHaveNextPage(
                "<https://api.github.com/x?page=1>; rel=\"prev\", <https://api.github.com/x?page=1>; rel=\"first\"",
                50, 50));
        assertTrue(ReleaseCatalog.mayHaveNextPage(null, 50, 50));
        assertFalse(ReleaseCatalog.mayHaveNextPage(null, 49, 50));
    }

    private static ReleaseTransport.Response response(int status, Map<String, List<String>> headers) {
        return new ReleaseTransport.Response(status, headers, new ByteArrayInputStream(new byte[0]));
    }

    private static String body(ReleaseTransport.Response response) throws IOException {
        try (response) {
            return new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static final class FakeTransport implements ReleaseTransport {
        final List<URI> requests = new ArrayList<>();
        int status = 200;
        Map<String, List<String>> headers = Map.of();
        String link;

        @Override
        public Response get(URI uri, String accept) {
            requests.add(uri);
            Map<String, List<String>> sent = new java.util.HashMap<>(headers);
            if (link != null) sent.put("Link", List.of(link));
            return new Response(status, sent, new ByteArrayInputStream(
                    ("[" + requests.size() + "]").getBytes(StandardCharsets.UTF_8)));
        }
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-06T18:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
