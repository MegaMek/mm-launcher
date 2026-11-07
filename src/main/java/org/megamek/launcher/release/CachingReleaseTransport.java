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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shares GitHub API metadata between the launcher's checks instead of asking GitHub again for
 * every one. Without a signed-in account GitHub allows only 60 API requests an hour per network,
 * and a single update check reads several pages, so uncached checks quickly exhaust it.
 *
 * <p>Successful JSON responses from api.github.com are reused for {@link #FRESHNESS}, in memory
 * and in a cache folder so a relaunch (for example after a launcher update) reuses them too.
 * Conditional requests would not help: GitHub counts unauthenticated 304 responses against the
 * allowance. Once GitHub refuses a request for exceeding the allowance, further API requests
 * fail immediately until its reset time instead of spending more of the allowance.
 * Package downloads and other hosts always pass straight through.
 */
public final class CachingReleaseTransport implements ReleaseTransport {
    public static final Duration FRESHNESS = Duration.ofMinutes(10);
    static final String CACHED_ACCEPT = "application/vnd.github+json";
    private static final int MAX_BODY = 8 * 1024 * 1024;
    private static final int SCHEMA = 1;
    private static final List<String> KEPT_HEADERS = List.of("link");

    private final ReleaseTransport delegate;
    private final Path directory;
    private final Clock clock;
    private final ObjectMapper json = JsonMapper.builder().build();
    private final Map<String, Entry> memory = new ConcurrentHashMap<>();
    private final Map<String, Object> locks = new ConcurrentHashMap<>();
    private volatile GitHubRateLimitException limited;

    /** @param directory where responses survive relaunches; null keeps them in memory only */
    public CachingReleaseTransport(ReleaseTransport delegate, Path directory) {
        this(delegate, directory, Clock.systemUTC());
    }

    CachingReleaseTransport(ReleaseTransport delegate, Path directory, Clock clock) {
        this.delegate = java.util.Objects.requireNonNull(delegate, "delegate");
        this.directory = directory == null ? null : directory.toAbsolutePath().normalize();
        this.clock = java.util.Objects.requireNonNull(clock, "clock");
    }

    /** The cache folder kept next to a launcher registry, like its operation logs. */
    public static Path directoryFor(Path registry) {
        Path normalized = registry.toAbsolutePath().normalize();
        return normalized.resolveSibling(normalized.getFileName() + ".github-cache");
    }

    @Override
    public Response get(URI uri, String accept) throws IOException, InterruptedException {
        return get(uri, accept, true);
    }

    /**
     * Skips reading the cache but still refreshes it and honours a rate-limit refusal, so release
     * revalidation sees current GitHub metadata without bypassing the cooldown.
     */
    @Override
    public ReleaseTransport fresh() {
        return (uri, accept) -> get(uri, accept, false);
    }

    private Response get(URI uri, String accept, boolean reuse)
            throws IOException, InterruptedException {
        if (!isApi(uri)) return delegate.get(uri, accept);
        if (!CACHED_ACCEPT.equals(accept)) {
            throwIfLimited();
            return checked(delegate.get(uri, accept));
        }
        String key = key(uri, accept);
        // One fetch per URL at a time, so checks that start together share one request.
        synchronized (locks.computeIfAbsent(key, ignored -> new Object())) {
            // A response cached before a refusal is still served during the cooldown.
            Entry cached = reuse ? fresh(key, uri, accept) : null;
            if (cached != null) return cached.response();
            throwIfLimited();
            Response response = checked(delegate.get(uri, accept));
            if (response.status() != 200) return response;
            byte[] body;
            try (response) {
                body = response.body().readNBytes(MAX_BODY + 1);
            }
            Map<String, List<String>> headers = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (String name : KEPT_HEADERS) {
                String value = response.firstHeader(name);
                if (value != null) headers.put(name, List.of(value));
            }
            Entry entry = new Entry(uri.toString(), accept, clock.instant(), Map.copyOf(headers), body);
            if (body.length <= MAX_BODY) {
                memory.put(key, entry);
                store(key, entry);
            }
            return entry.response();
        }
    }

    private static boolean isApi(URI uri) {
        return uri != null && "https".equalsIgnoreCase(uri.getScheme())
                && "api.github.com".equalsIgnoreCase(uri.getHost());
    }

    private void throwIfLimited() throws GitHubRateLimitException {
        GitHubRateLimitException current = limited;
        if (current == null) return;
        if (current.reset().isPresent() && !clock.instant().isBefore(current.reset().get())) {
            limited = null;
            return;
        }
        throw current;
    }

    private Response checked(Response response) throws IOException {
        GitHubRateLimitException refused = GitHubRateLimitException.from(response, clock);
        if (refused == null) return response;
        response.close();
        // A refusal without a reset time only blocks requests briefly.
        limited = refused.reset().isPresent() ? refused
                : new GitHubRateLimitException(clock.instant().plus(Duration.ofMinutes(1)));
        throw refused;
    }

    private Entry fresh(String key, URI uri, String accept) {
        Entry entry = memory.get(key);
        if (entry == null) {
            entry = load(key, uri, accept);
            if (entry != null) memory.put(key, entry);
        }
        if (entry == null) return null;
        Duration age = Duration.between(entry.fetched(), clock.instant());
        if (age.isNegative() || age.compareTo(FRESHNESS) >= 0) {
            memory.remove(key, entry);
            return null;
        }
        return entry;
    }

    private Entry load(String key, URI uri, String accept) {
        if (directory == null) return null;
        Path file = directory.resolve(key + ".json");
        try {
            if (!Files.isRegularFile(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    || Files.size(file) > MAX_BODY * 2L) return null;
            JsonNode root = json.readTree(Files.readAllBytes(file));
            if (root == null || root.path("schema").asInt() != SCHEMA
                    || !uri.toString().equals(root.path("uri").asText(null))
                    || !accept.equals(root.path("accept").asText(null))) return null;
            Instant fetched = Instant.ofEpochMilli(root.path("fetched").asLong());
            byte[] body = root.path("body").binaryValue();
            if (body == null || body.length > MAX_BODY) return null;
            Map<String, List<String>> headers = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (String name : KEPT_HEADERS) {
                JsonNode value = root.path("headers").get(name);
                if (value != null && value.isTextual()) headers.put(name, List.of(value.textValue()));
            }
            return new Entry(uri.toString(), accept, fetched, Map.copyOf(headers), body);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
    }

    private void store(String key, Entry entry) {
        if (directory == null) return;
        Path temporary = null;
        try {
            Files.createDirectories(directory);
            ObjectNode root = json.createObjectNode();
            root.put("schema", SCHEMA);
            root.put("uri", entry.uri());
            root.put("accept", entry.accept());
            root.put("fetched", entry.fetched().toEpochMilli());
            ObjectNode headers = root.putObject("headers");
            entry.headers().forEach((name, values) -> headers.put(name.toLowerCase(Locale.ROOT), values.getFirst()));
            root.put("body", entry.body());
            temporary = Files.createTempFile(directory, key, ".tmp");
            Files.write(temporary, json.writeValueAsBytes(root));
            Path target = directory.resolve(key + ".json");
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;
        } catch (IOException | RuntimeException unwritable) {
            // The disk copy only saves requests on the next launch; memory still serves this one.
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // A leftover temporary file is harmless and never read as a cache entry.
                }
            }
        }
    }

    private static String key(URI uri, String accept) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    (accept + "\n" + uri).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Entry(String uri, String accept, Instant fetched,
                         Map<String, List<String>> headers, byte[] body) {
        Response response() {
            return new Response(200, headers, new ByteArrayInputStream(body));
        }
    }
}
