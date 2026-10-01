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

import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.megamek.launcher.release.OfficialRepository;
import org.megamek.launcher.release.ReleaseTransport;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Real schema-shaped metadata with no package transfers unless explicitly provided. */
public final class SuiteTestData implements ReleaseTransport {
    private static final JsonMapper JSON = new JsonMapper();
    private final List<ObjectNode> inventory = new ArrayList<>();
    private final Map<URI, ObjectNode> metadata = new HashMap<>();
    private final Map<URI, byte[]> bodies = new HashMap<>();
    public final List<URI> requests = new ArrayList<>();

    public static String canonical(String version) {
        String[] parts = version.split("\\.");
        return Integer.parseInt(parts[0]) + "." + "%02d".formatted(Integer.parseInt(parts[1]))
                + "." + "%02d".formatted(Integer.parseInt(parts[2]));
    }

    public static long releaseId(OfficialRepository repository, String version) {
        return 10000L + repository.ordinal() * 1000L + Integer.toUnsignedLong(version.hashCode());
    }

    public static long assetId(OfficialRepository repository, String version) {
        return releaseId(repository, version) + 100;
    }

    public static URI releaseUri(OfficialRepository repository, long id) {
        return URI.create("https://api.github.com/repos/" + repository.slug() + "/releases/" + id);
    }

    public static URI recordUri(String version) {
        return URI.create(OfficialSuiteChannelCatalog.SOURCE + "/assets/"
                + (10000000000L + Integer.toUnsignedLong(version.hashCode())));
    }

    public static URI packageUri(OfficialRepository repository, String version) {
        return URI.create("https://github.com/" + repository.slug() + "/releases/download/v"
                + version + "/" + repository.productName() + "-" + version + ".tar.gz");
    }

    public static Response channels(String milestone, String development) {
        SuiteTestData data = new SuiteTestData();
        String stable = canonical(milestone);
        String dev = canonical(development);
        data.suite(stable, FollowChannel.MILESTONE, stable);
        String devSuite = dev;
        if (dev.equals(stable)) {
            String[] components = dev.split("\\.");
            devSuite = components[0] + "." + components[1] + "."
                    + "%02d".formatted(Integer.parseInt(components[2]) + 1);
        }
        data.suite(devSuite, FollowChannel.DEVELOPMENT, dev);
        return new Response(200, Map.of(), new InventoryBody(data));
    }

    public static final class MetadataRouter {
        private SuiteTestData active;

        public Response response(URI uri, String accept, java.util.function.Supplier<Response> queued)
                throws IOException {
            return response(uri, accept, queued != null
                    && uri.toString().startsWith(OfficialSuiteChannelCatalog.SOURCE + "?")
                    ? queued.get() : null);
        }

        public Response response(URI uri, String accept, Response queued) throws IOException {
            if (uri.toString().startsWith(OfficialSuiteChannelCatalog.SOURCE + "?")) {
                if (queued != null && queued.body() instanceof InventoryBody inventoryBody) {
                    active = inventoryBody.data;
                }
                return null;
            }
            return active != null && (active.metadata.containsKey(uri) || active.bodies.containsKey(uri))
                    ? active.get(uri, accept) : null;
        }
    }

    private static final class InventoryBody extends ByteArrayInputStream {
        private final SuiteTestData data;

        private InventoryBody(SuiteTestData data) {
            super(bytes(JSON.valueToTree(data.inventory)));
            this.data = data;
        }
    }

    public ObjectNode suite(String version, FollowChannel channel, String productVersion) {
        Map<OfficialRepository, String> versions = new EnumMap<>(OfficialRepository.class);
        for (OfficialRepository repository : OfficialRepository.values()) versions.put(repository, productVersion);
        return suite(version, channel, versions, Map.of(), Map.of());
    }

    public ObjectNode suite(String version, FollowChannel channel,
                            Map<OfficialRepository, String> versions,
                            Map<OfficialRepository, Long> sizes, Map<OfficialRepository, String> hashes) {
        ObjectNode record = JSON.createObjectNode();
        record.put("schemaVersion", 1).put("version", version)
                .put("membership", channel.cliName()).put("tag", "v" + version);
        ObjectNode products = record.putObject("products");
        for (OfficialRepository repository : OfficialRepository.values()) {
            String productVersion = versions.get(repository);
            long size = sizes.getOrDefault(repository, 1234L);
            String hash = hashes.getOrDefault(repository, "a".repeat(64));
            ObjectNode product = products.putObject(repository.productName());
            product.put("repository", "https://github.com/" + repository.slug())
                    .put("commit", "b".repeat(40)).put("version", productVersion)
                    .put("tag", "v" + productVersion).put("releaseId", releaseId(repository, productVersion));
            product.putObject("asset").put("assetId", assetId(repository, productVersion))
                    .put("name", repository.productName() + "-" + productVersion + ".tar.gz")
                    .put("sha256", hash).put("size", size);
            URI uri = releaseUri(repository, releaseId(repository, productVersion));
            if (!metadata.containsKey(uri)) {
                ObjectNode release = release(repository, productVersion, releaseId(repository, productVersion));
                release.putArray("assets").addObject()
                        .put("id", assetId(repository, productVersion)).put("state", "uploaded")
                        .put("name", repository.productName() + "-" + productVersion + ".tar.gz")
                        .put("size", size).put("browser_download_url", packageUri(repository, productVersion).toString());
                metadata.put(uri, release);
            }
        }
        record.putObject("mmData").put("repository", "https://github.com/MegaMek/mm-data")
                .put("commit", "c".repeat(40));
        URI source = recordUri(version);
        URI download = URI.create("https://github.com/MegaMek/megamek/releases/download/v"
                + version + "/suite-record-" + version + ".json");
        long parentId = versions.get(OfficialRepository.MEGAMEK).equals(version)
                ? releaseId(OfficialRepository.MEGAMEK, version)
                : 20000000000L + Integer.toUnsignedLong(version.hashCode());
        ObjectNode parent = metadata.get(releaseUri(OfficialRepository.MEGAMEK, parentId));
        if (parent == null) parent = release(OfficialRepository.MEGAMEK, version, parentId);
        if (!parent.has("assets")) parent.putArray("assets");
        parent.withArray("assets").addObject().put("id", Long.parseLong(source.getPath().substring(
                        source.getPath().lastIndexOf('/') + 1)))
                .put("state", "uploaded").put("name", "suite-record-" + version + ".json")
                .put("size", bytes(record).length).put("browser_download_url", download.toString());
        inventory.add(parent);
        metadata.put(source, record);
        metadata.put(download, record);
        return record;
    }

    public ObjectNode product(OfficialRepository repository, String version) {
        return metadata.get(releaseUri(repository, releaseId(repository, version)));
    }

    public ObjectNode recordAsset(String version) {
        for (ObjectNode parent : inventory) {
            for (var asset : parent.withArray("assets")) {
                if (asset.path("name").asText().equals("suite-record-" + version + ".json")) {
                    return (ObjectNode) asset;
                }
            }
        }
        throw new IllegalArgumentException("fixture has no such record asset");
    }

    public void put(URI uri, byte[] body) {
        bodies.put(uri, body);
    }

    public void refreshSize(String version) {
        ObjectNode record = metadata.get(recordUri(version));
        for (ObjectNode parent : inventory) {
            if (parent.path("tag_name").asText().equals("v" + version)) {
                for (var asset : parent.withArray("assets")) {
                    if (asset.path("name").asText().equals("suite-record-" + version + ".json")) {
                        ((ObjectNode) asset).put("size", bytes(record).length);
                    }
                }
            }
        }
    }

    @Override
    public Response get(URI uri, String accept) throws IOException {
        requests.add(uri);
        byte[] body = bodies.get(uri);
        if (body == null && metadata.containsKey(uri)) body = bytes(metadata.get(uri));
        if (body == null && uri.toString().startsWith(OfficialSuiteChannelCatalog.SOURCE + "?")) {
            int page = Integer.parseInt(uri.getQuery().substring(uri.getQuery().lastIndexOf('=') + 1));
            int start = Math.min((page - 1) * 50, inventory.size());
            body = bytes(JSON.valueToTree(inventory.subList(start, Math.min(start + 50, inventory.size()))));
        }
        if (body == null) throw new IOException("unexpected fixture request: " + uri);
        return new Response(200, Map.of("content-length", List.of(Integer.toString(body.length))),
                new ByteArrayInputStream(body));
    }

    public static byte[] bytes(com.fasterxml.jackson.databind.JsonNode value) {
        try {
            return JSON.writeValueAsBytes(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalArgumentException(error);
        }
    }

    private static ObjectNode release(OfficialRepository repository, String version, long id) {
        return JSON.createObjectNode().put("id", id).put("tag_name", "v" + version)
                .put("name", "Do not infer a channel").put("draft", false).put("prerelease", true)
                .put("html_url", "https://github.com/" + repository.slug() + "/releases/tag/v" + version);
    }
}
