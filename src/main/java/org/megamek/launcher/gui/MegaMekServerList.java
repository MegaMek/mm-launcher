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

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.megamek.launcher.release.JavaReleaseTransport;
import org.megamek.launcher.release.ReleaseTransport;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Public community game servers published by megamek.games. */
final class MegaMekServerList {
    static final URI LIST = URI.create("https://megamek.games/servers.json");
    static final URI WEBSITE = URI.create("https://megamek.games/");
    private static final int MAX_BYTES = 262_144;
    private static final int MAX_SERVERS = 200;
    private static final int MAX_TEXT = 80;
    private static final Pattern HOST = Pattern.compile(
            "(?=.{1,253}$)[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*");
    private final ReleaseTransport transport;
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();

    enum Channel { MILESTONE, DEVELOPMENT, DEPRECATED }

    record Server(String host, int port, String version, Channel channel, String size, String provider) {
        String address() {
            return host + ":" + port;
        }
    }

    MegaMekServerList() {
        this(new JavaReleaseTransport());
    }

    MegaMekServerList(ReleaseTransport transport) {
        this.transport = transport;
    }

    List<Server> current() throws IOException, InterruptedException {
        try (ReleaseTransport.Response response = transport.get(LIST, "application/json")) {
            if (response.status() != 200) {
                throw new IOException("MegaMek server list returned HTTP " + response.status());
            }
            byte[] body = response.body().readNBytes(MAX_BYTES + 1);
            if (body.length > MAX_BYTES) throw new IOException("MegaMek server list exceeds the size limit");
            return parse(body);
        }
    }

    /** Parses the list, skipping malformed entries; deprecated servers are left out. */
    List<Server> parse(byte[] body) throws IOException {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (IOException error) {
            throw new IOException("MegaMek server list could not be read", error);
        }
        if (root == null || !root.isObject() || !root.path("schemaVersion").isInt()
                || root.path("schemaVersion").intValue() != 1 || !root.path("servers").isArray()) {
            throw new IOException("MegaMek server list has an unsupported format");
        }
        List<Server> servers = new ArrayList<>();
        for (JsonNode entry : root.get("servers")) {
            if (servers.size() >= MAX_SERVERS) break;
            Server server = server(entry);
            if (server != null && server.channel() != Channel.DEPRECATED) servers.add(server);
        }
        return List.copyOf(servers);
    }

    private static Server server(JsonNode entry) {
        if (!entry.isObject()) return null;
        String host = text(entry, "host");
        JsonNode port = entry.get("port");
        String version = text(entry, "version");
        String channelText = text(entry, "channel");
        if (host == null || !HOST.matcher(host).matches() || port == null || !port.isInt()
                || port.intValue() < 1 || port.intValue() > 65_535 || version == null
                || channelText == null) {
            return null;
        }
        Channel channel;
        try {
            channel = Channel.valueOf(channelText.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
        String provider = text(entry, "provider");
        String size = text(entry, "size");
        return new Server(host, port.intValue(), version, channel, size == null ? "" : size,
                provider == null ? "" : provider);
    }

    private static String text(JsonNode entry, String field) {
        JsonNode value = entry.get(field);
        if (value == null || !value.isTextual()) return null;
        String text = value.textValue().strip();
        if (text.isEmpty() || text.length() > MAX_TEXT
                || text.codePoints().anyMatch(c -> Character.isISOControl(c) || c == '<')) {
            return null;
        }
        return text;
    }
}
