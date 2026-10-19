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

package org.megamek.launcher.launch;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.megamek.launcher.update.StrictPathSafety;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * Launcher-wide launch options and per-installation overrides, stored beside the registry in their own
 * file so older launcher builds that strictly validate the settings and registry files keep working.
 */
public final class LaunchOptionsStore {
    public static final int SCHEMA = 1;
    private static final int MAX_BYTES = 262_144;
    private static final int MAX_INSTALLATIONS = 256;
    private static final Pattern INSTALLATION_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Set<String> FIELDS = Set.of("megamekHeapMb", "mekhqHeapMb", "labHeapMb",
            "renderer", "extraJvmArguments", "userDirectory");
    private final Path registry;
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public LaunchOptionsStore(Path registry) {
        this.registry = registry.toAbsolutePath().normalize();
    }

    public record Document(LaunchOptions global, Map<String, LaunchOptions> installations) {
        public static final Document EMPTY = new Document(LaunchOptions.INHERIT, Map.of());

        public Document {
            installations = Map.copyOf(installations);
        }

        public LaunchOptions installation(String id) {
            return installations.getOrDefault(id, LaunchOptions.INHERIT);
        }

        /** Built-in defaults, then launcher-wide options, then this installation's overrides. */
        public LaunchOptions effective(String installationId) {
            return installation(installationId).over(global.over(LaunchOptions.builtIn()));
        }
    }

    public Path path() {
        return registry.resolveSibling(registry.getFileName() + ".launch-options.json");
    }

    public Document read() throws IOException {
        Path file = path();
        try {
            StrictPathSafety.requireFile(file, "launch options");
        } catch (NoSuchFileException missing) {
            return Document.EMPTY;
        }
        long size = Files.size(file);
        if (size <= 0 || size > MAX_BYTES) throw new IOException("launch options size is invalid");
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length != size) throw new IOException("launch options changed while being read");
        try {
            JsonNode root = mapper.readTree(bytes);
            if (root == null || !root.isObject()) throw new IOException("launch options are not an object");
            requireOnly(root, Set.of("schemaVersion", "global", "installations"));
            JsonNode version = root.get("schemaVersion");
            if (version == null || !version.isInt() || version.intValue() != SCHEMA) {
                throw new IOException("unsupported launch options schema");
            }
            LaunchOptions global = options(root.get("global"));
            JsonNode installationsNode = root.get("installations");
            Map<String, LaunchOptions> installations = new LinkedHashMap<>();
            if (installationsNode != null) {
                if (!installationsNode.isObject() || installationsNode.size() > MAX_INSTALLATIONS) {
                    throw new IOException("launch option overrides are invalid");
                }
                for (Iterator<Map.Entry<String, JsonNode>> it = installationsNode.fields(); it.hasNext(); ) {
                    Map.Entry<String, JsonNode> entry = it.next();
                    if (!INSTALLATION_ID.matcher(entry.getKey()).matches()) {
                        throw new IOException("launch option override has an invalid installation id");
                    }
                    installations.put(entry.getKey(), options(entry.getValue()));
                }
            }
            return new Document(global, installations);
        } catch (IOException error) {
            throw new IOException("launch options are invalid or unreadable (not reset): "
                    + error.getMessage(), error);
        }
    }

    public Document writeGlobal(LaunchOptions global) throws IOException {
        validated(global);
        return update(current -> new Document(global, current.installations()));
    }

    public Document writeInstallation(String installationId, LaunchOptions overrides) throws IOException {
        if (!INSTALLATION_ID.matcher(installationId).matches()) {
            throw new IOException("invalid installation id");
        }
        validated(overrides);
        return update(current -> {
            Map<String, LaunchOptions> next = new TreeMap<>(current.installations());
            if (overrides.inheritsEverything()) {
                next.remove(installationId);
            } else {
                next.put(installationId, overrides);
            }
            return new Document(current.global(), next);
        });
    }

    private static void validated(LaunchOptions options) throws IOException {
        try {
            options.validate();
        } catch (IllegalArgumentException error) {
            throw new IOException(error.getMessage(), error);
        }
    }

    private LaunchOptions options(JsonNode node) throws IOException {
        if (node == null || node.isNull()) return LaunchOptions.INHERIT;
        if (!node.isObject()) throw new IOException("launch options entry is not an object");
        requireOnly(node, FIELDS);
        LaunchOptions.Renderer renderer = null;
        JsonNode rendererNode = node.get("renderer");
        if (rendererNode != null && !rendererNode.isNull()) {
            if (!rendererNode.isTextual()) throw new IOException("renderer is invalid");
            try {
                renderer = LaunchOptions.Renderer.valueOf(rendererNode.textValue());
            } catch (IllegalArgumentException error) {
                throw new IOException("renderer is invalid", error);
            }
        }
        LaunchOptions options = new LaunchOptions(heap(node, "megamekHeapMb"), heap(node, "mekhqHeapMb"),
                heap(node, "labHeapMb"), renderer, text(node, "extraJvmArguments"),
                text(node, "userDirectory"));
        validated(options);
        return options;
    }

    private static Integer heap(JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isInt()) throw new IOException(field + " is invalid");
        return value.intValue();
    }

    private static String text(JsonNode node, String field) throws IOException {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw new IOException(field + " is invalid");
        return value.textValue();
    }

    private static void requireOnly(JsonNode node, Set<String> allowed) throws IOException {
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            String name = names.next();
            if (!allowed.contains(name)) throw new IOException("unknown launch options field: " + name);
        }
    }

    private ObjectNode toJson(Document document) {
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", SCHEMA);
        root.set("global", toJson(document.global()));
        ObjectNode installations = root.putObject("installations");
        new TreeMap<>(document.installations()).forEach((id, options) -> installations.set(id, toJson(options)));
        return root;
    }

    private ObjectNode toJson(LaunchOptions options) {
        ObjectNode node = mapper.createObjectNode();
        if (options.megamekHeapMb() != null) node.put("megamekHeapMb", options.megamekHeapMb());
        if (options.mekhqHeapMb() != null) node.put("mekhqHeapMb", options.mekhqHeapMb());
        if (options.labHeapMb() != null) node.put("labHeapMb", options.labHeapMb());
        if (options.renderer() != null) node.put("renderer", options.renderer().name());
        if (options.extraJvmArguments() != null) node.put("extraJvmArguments", options.extraJvmArguments());
        if (options.userDirectory() != null) node.put("userDirectory", options.userDirectory());
        return node;
    }

    private Document update(UnaryOperator<Document> change) throws IOException {
        Path file = path();
        StrictPathSafety.requireDirectory(file.getParent(), "launch options parent");
        Path lockPath = file.resolveSibling(file.getFileName() + ".lock");
        if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)
                && (!Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(lockPath))) {
            throw new IOException("launch options lock is not an ordinary file: " + lockPath);
        }
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE); FileLock ignored = lock(channel, lockPath)) {
            Document next = change.apply(read());
            // Never publish a file that read() would reject.
            if (next.installations().size() > MAX_INSTALLATIONS) {
                throw new IOException("Too many installations have their own launch options (limit "
                        + MAX_INSTALLATIONS + ").");
            }
            byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(toJson(next));
            if (bytes.length > MAX_BYTES) throw new IOException("launch options exceed size limit");
            Path staging = file.resolveSibling(file.getFileName() + ".new");
            Files.deleteIfExists(staging);
            try (FileChannel output = FileChannel.open(staging, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            try {
                Files.move(staging, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.deleteIfExists(staging);
                throw new IOException("atomic launch options publication is unsupported", error);
            } catch (IOException error) {
                Files.deleteIfExists(staging);
                throw error;
            }
            return next;
        }
    }

    private static FileLock lock(FileChannel channel, Path file) throws IOException {
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException("launch options are locked: " + file);
            return lock;
        } catch (OverlappingFileLockException error) {
            throw new IOException("launch options are locked by this process: " + file, error);
        }
    }
}
