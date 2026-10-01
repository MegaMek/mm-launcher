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

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.megamek.launcher.release.OfficialRepository;

import java.io.IOException;
import java.math.BigInteger;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** The schema-1 complete suite record; product versions are independent of the suite version. */
public record SuiteReleaseRecord(String version, FollowChannel membership, String tag,
                                 String minimumLauncherVersion,
                                 Map<OfficialRepository, Product> products, String dataCommit) {
    public static final int MAX_BYTES = 1024 * 1024;
    private static final Pattern VERSION = Pattern.compile(
            "(?:0|[1-9][0-9]*)\\.(?:[0-9]{2}|[1-9][0-9]{2,})\\."
                    + "(?:[0-9]{2}|[1-9][0-9]{2,})");
    private static final Pattern LAUNCHER_VERSION = Pattern.compile(
            "(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)\\.(?:0|[1-9][0-9]*)");
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder()
                    .maxNestingDepth(16).maxStringLength(4096).maxDocumentLength(MAX_BYTES).build())
            .build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public SuiteReleaseRecord {
        products = Map.copyOf(products);
    }

    public static SuiteReleaseRecord parse(byte[] bytes) throws IOException {
        if (bytes.length > MAX_BYTES) throw new IOException("suite record exceeds size limit");
        final JsonNode root;
        try {
            root = JSON.readTree(bytes);
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IOException("malformed suite record: " + error.getOriginalMessage(), error);
        }
        fields(root, "record", Set.of("schemaVersion", "version", "membership", "tag",
                "products", "mmData"), Set.of("minimumLauncherVersion"));
        JsonNode schema = root.get("schemaVersion");
        if (!schema.isIntegralNumber() || !schema.canConvertToInt() || schema.intValue() != 1) {
            throw new IOException("unsupported suite record schemaVersion");
        }
        String version = canonicalVersion(text(root, "version"), "version");
        String tag = text(root, "tag");
        if (!tag.equals("v" + version)) throw new IOException("suite record tag mismatch");
        final FollowChannel membership;
        try {
            String value = text(root, "membership");
            membership = FollowChannel.parse(value);
            if (!membership.cliName().equals(value)) {
                throw new IOException("suite membership must be lowercase");
            }
        } catch (IllegalArgumentException error) {
            throw new IOException("unknown suite membership", error);
        }
        String minimum = root.has("minimumLauncherVersion")
                ? text(root, "minimumLauncherVersion") : null;
        if (minimum != null && !LAUNCHER_VERSION.matcher(minimum).matches()) {
            throw new IOException("invalid minimumLauncherVersion");
        }
        JsonNode productNodes = root.get("products");
        fields(productNodes, "products", Set.of("MegaMek", "MegaMekLab", "MekHQ"), Set.of());
        Map<OfficialRepository, Product> products = new EnumMap<>(OfficialRepository.class);
        for (OfficialRepository repository : OfficialRepository.values()) {
            String name = repository.productName();
            JsonNode node = productNodes.get(name);
            fields(node, name, Set.of("repository", "commit", "version", "tag", "releaseId",
                    "asset"), Set.of());
            if (!text(node, "repository").equals("https://github.com/" + repository.slug())) {
                throw new IOException(name + " repository mismatch");
            }
            String commit = hex(node, "commit", 40);
            String productVersion = canonicalVersion(text(node, "version"), name + ".version");
            String productTag = text(node, "tag");
            if (!productTag.equals("v" + productVersion)
                    || compareVersions(productVersion, version) > 0) {
                throw new IOException(name + " version/tag mismatch");
            }
            long releaseId = positive(node, "releaseId");
            JsonNode asset = node.get("asset");
            fields(asset, name + ".asset", Set.of("assetId", "name", "sha256", "size"), Set.of());
            String filename = text(asset, "name");
            if (!filename.equals(name + "-" + productVersion + ".tar.gz")) {
                throw new IOException(name + " asset name mismatch");
            }
            products.put(repository, new Product(commit, productVersion, productTag, releaseId,
                    positive(asset, "assetId"), filename, hex(asset, "sha256", 64),
                    positive(asset, "size")));
        }
        if (compareVersions(products.get(OfficialRepository.MEGAMEK).version(),
                products.get(OfficialRepository.LAB).version()) > 0
                || compareVersions(products.get(OfficialRepository.LAB).version(),
                products.get(OfficialRepository.MEKHQ).version()) > 0) {
            throw new IOException("bundled product version predates its dependency");
        }
        JsonNode data = root.get("mmData");
        fields(data, "mmData", Set.of("repository", "commit"), Set.of());
        if (!text(data, "repository").equals("https://github.com/MegaMek/mm-data")) {
            throw new IOException("mmData repository mismatch");
        }
        return new SuiteReleaseRecord(version, membership, tag, minimum, products,
                hex(data, "commit", 40));
    }

    static String canonicalVersion(String value, String field) throws IOException {
        if (!VERSION.matcher(value).matches()) throw new IOException("invalid canonical " + field);
        for (String component : value.split("\\.")) {
            try {
                Integer.parseInt(component);
            } catch (NumberFormatException error) {
                throw new IOException(field + " component exceeds Java integer range", error);
            }
        }
        return value;
    }

    public static int compareVersions(String first, String second) {
        String[] left = first.split("\\.");
        String[] right = second.split("\\.");
        for (int i = 0; i < 3; i++) {
            int comparison = new BigInteger(left[i]).compareTo(new BigInteger(right[i]));
            if (comparison != 0) return comparison;
        }
        return 0;
    }

    void requireSupportedLauncher(String launcherVersion) throws IOException {
        if (!LAUNCHER_VERSION.matcher(launcherVersion).matches()) {
            throw new IOException("running launcher version is unavailable or invalid");
        }
        if (minimumLauncherVersion != null
                && compareVersions(launcherVersion, minimumLauncherVersion) < 0) {
            throw new IOException("suite " + version + " requires launcher "
                    + minimumLauncherVersion + " or newer");
        }
    }

    private static String text(JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.textValue().isEmpty()) {
            throw new IOException("missing or invalid suite field: " + field);
        }
        return value.textValue();
    }

    private static String hex(JsonNode node, String field, int length) throws IOException {
        String value = text(node, field);
        if (!value.matches("[0-9a-f]{" + length + "}")) {
            throw new IOException("invalid suite " + field);
        }
        return value;
    }

    private static long positive(JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
                || value.longValue() <= 0) {
            throw new IOException(field + " must be a positive signed 64-bit integer");
        }
        return value.longValue();
    }

    private static void fields(JsonNode node, String label, Set<String> required,
                               Set<String> optional) throws IOException {
        if (node == null || !node.isObject()) throw new IOException(label + " must be an object");
        for (String field : required) {
            if (!node.has(field)) throw new IOException("missing " + label + "." + field);
        }
        var names = node.fieldNames();
        while (names.hasNext()) {
            String field = names.next();
            if (!required.contains(field) && !optional.contains(field)) {
                throw new IOException("unknown " + label + "." + field);
            }
        }
    }

    public record Product(String commit, String version, String tag, long releaseId,
                          long assetId, String name, String sha256, long size) {
    }
}
