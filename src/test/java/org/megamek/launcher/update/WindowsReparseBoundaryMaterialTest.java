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

package org.megamek.launcher.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.release.FreshInstaller;
import org.megamek.launcher.release.OfficialRepository;
import org.megamek.launcher.release.ReleaseTransport;
import org.megamek.launcher.release.VerifiedPackageFetcher;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledOnOs(OS.WINDOWS)
class WindowsReparseBoundaryMaterialTest {
    @TempDir Path temp;

    @Test
    void destinationParentJunctionIsRefusedBeforeNetworkOrWrites() throws Exception {
        Path realParent = Files.createDirectory(temp.resolve("redirect-target"));
        Files.writeString(realParent.resolve("marker"), "untouched");
        Path junction = junction(temp.resolve("redirect-parent"), realParent);
        Path registry = temp.resolve("registry.json");
        CountingTransport network = new CountingTransport();

        try {
            IOException failure = assertThrows(IOException.class, () -> new FreshInstaller(network)
                    .install(OfficialRepository.MEGAMEK, "v1", junction.resolve("installed"),
                            registry, "Official", quiet()));

            assertTrue(failure.getMessage().contains("reparse points"), failure.getMessage());
            assertTrue(network.requests.isEmpty());
            assertFalse(Files.exists(registry));
            assertEquals(List.of("marker"), childNames(realParent));
            assertEquals("untouched", Files.readString(realParent.resolve("marker")));
        } finally {
            Files.deleteIfExists(junction);
        }
    }

    @Test
    void redirectedReceiptMetadataIsRefusedBeforeNetworkWithoutMutation() throws Exception {
        Path root = createInstallation(temp.resolve("installed"));
        Path registry = temp.resolve("registry.json");
        var record = new RegistryStore().register(registry, "Official", root, null);
        byte[] registryBefore = Files.readAllBytes(registry);
        Path redirected = Files.createDirectory(temp.resolve("metadata-target"));
        Files.writeString(redirected.resolve(record.id() + ".json"), "do not follow");
        Path metadata = junction(registry.resolveSibling("registry.json.metadata"), redirected);
        CountingTransport network = new CountingTransport();

        try {
            IOException failure = assertThrows(IOException.class, () ->
                    new UpdatePreviewService(network).preview(
                            registry, record.id(), "v-new", quiet()));

            assertTrue(failure.getMessage().contains("linked, reparse, or special ancestor"),
                    failure.getMessage());
            assertTrue(network.requests.isEmpty());
            assertArrayEquals(registryBefore, Files.readAllBytes(registry));
            assertEquals("do not follow",
                    Files.readString(redirected.resolve(record.id() + ".json")));
            assertEquals(List.of(record.id() + ".json"), childNames(redirected));
            assertTrue(Files.isDirectory(metadata));
        } finally {
            Files.deleteIfExists(metadata);
        }
    }

    @Test
    void packageWorkspaceParentJunctionIsRefusedAfterMetadataButBeforeTargetDownload()
            throws Exception {
        Path redirected = Files.createDirectory(temp.resolve("workspace-target"));
        Files.writeString(redirected.resolve("marker"), "untouched");
        Path junction = junction(temp.resolve("workspace-parent"), redirected);
        MetadataTransport network = new MetadataTransport();

        try {
            IOException failure = assertThrows(IOException.class, () ->
                    new VerifiedPackageFetcher(network).fetch(OfficialRepository.MEGAMEK, "v1",
                            junction, ".preview-", quiet()));

            assertTrue(failure.getMessage().contains("linked, reparse, or special ancestor"),
                    failure.getMessage());
            assertEquals(1, network.requests.size(),
                    "public fetch resolves release metadata before constructing its workspace");
            assertEquals(List.of("marker"), childNames(redirected));
            assertEquals("untouched", Files.readString(redirected.resolve("marker")));
            assertThrows(IOException.class, () ->
                    StrictPathSafety.requireDirectory(junction, "direct workspace boundary"));
        } finally {
            Files.deleteIfExists(junction);
        }
    }

    private static Path junction(Path link, Path target) throws Exception {
        Process process = new ProcessBuilder(System.getenv("ComSpec"), "/d", "/c", "mklink",
                "/J", link.toString(), target.toString()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exit = process.waitFor();
        assertEquals(0, exit, output);
        return link;
    }

    private static Path createInstallation(Path root) throws Exception {
        Files.createDirectory(root);
        Files.createDirectories(root.resolve("data"));
        Files.createDirectories(root.resolve("lib"));
        Files.createDirectories(root.resolve("mmconf"));
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "megamek.MegaMek");
        try (JarOutputStream jar = new JarOutputStream(
                Files.newOutputStream(root.resolve("MegaMek.jar")), manifest)) {
            jar.putNextEntry(new JarEntry("megamek/Version.properties"));
            jar.write("major=1\nminor=2\npatch=3\n".getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return root;
    }

    private static List<String> childNames(Path directory) throws IOException {
        try (var children = Files.list(directory)) {
            return children.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private static PrintStream quiet() {
        return new PrintStream(new ByteArrayOutputStream());
    }

    private static class CountingTransport implements ReleaseTransport {
        final List<URI> requests = new ArrayList<>();
        @Override public Response get(URI uri, String accept) throws IOException {
            requests.add(uri);
            throw new IOException("unexpected network request");
        }
    }

    private static final class MetadataTransport extends CountingTransport {
        @Override public Response get(URI uri, String accept) throws IOException {
            requests.add(uri);
            String json = """
                    {"tag_name":"v1","name":"v1","draft":false,"prerelease":false,
                    "html_url":"https://github.com/MegaMek/megamek/releases/tag/v1",
                    "assets":[{"name":"MegaMek-v1.tar.gz","size":1,
                    "digest":"sha256:%s",
                    "browser_download_url":"https://github.com/MegaMek/megamek/releases/download/v1/MegaMek-v1.tar.gz"}]}
                    """.formatted("a".repeat(64));
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            return new Response(200, Map.of(), new ByteArrayInputStream(bytes));
        }
    }
}
