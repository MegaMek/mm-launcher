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

package org.megamek.launcher.distribution;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@Tag("archive")
class PortableDistributionTest {
    @TempDir Path temp;

    @Test
    void hostNativeRuntimeAndEntrypointWorkWithoutExternalJava() throws Exception {
        Path archive = Path.of(System.getProperty("portable.path"));
        assertEquals(System.getProperty("portable.name"), archive.getFileName().toString());
        String checksum = Files.readString(Path.of(System.getProperty("portable.checksum")),
                StandardCharsets.US_ASCII).strip();
        MessageDigest hasher = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(archive)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) hasher.update(buffer, 0, count);
        }
        byte[] digest = hasher.digest();
        String actual = java.util.HexFormat.of().formatHex(digest);
        assertEquals(actual + "  " + archive.getFileName(), checksum);

        String platform = System.getProperty("portable.platform");
        boolean mac = platform.startsWith("macos-");
        boolean windows = platform.startsWith("windows-");
        String runtime = "MegaMek Launcher/" +
                (mac ? "MegaMek Launcher.app/Contents/runtime/" : "runtime/");
        String javaPath = runtime + "bin/java" + (windows ? ".exe" : "");
        Path extraction = temp.resolve(windows ? "moved café & portable" : "moved Ω & portable");
        Files.createDirectories(extraction);
        Set<String> entries = new HashSet<>();
        try (InputStream in = Files.newInputStream(archive);
             GzipCompressorInputStream gzip = new GzipCompressorInputStream(in);
             TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                String name = entry.getName();
                assertTrue(name.startsWith("MegaMek Launcher/"));
                assertFalse(name.contains("\\") || Arrays.asList(name.split("/")).contains(".."));
                assertFalse(entry.isSymbolicLink() || entry.isLink(), name);
                assertTrue(entries.add(name.toLowerCase(Locale.ROOT)), name);
                Path file = extraction.resolve(name).normalize();
                assertTrue(file.startsWith(extraction));
                if (entry.isDirectory()) {
                    Files.createDirectories(file);
                } else {
                    Files.createDirectories(file.getParent());
                    try (var output = Files.newOutputStream(file)) {
                        tar.transferTo(output);
                    }
                    if (!windows && (entry.getMode() & 0111) != 0) {
                        Files.setPosixFilePermissions(file, Set.of(
                                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                                PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
                                PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
                                PosixFilePermission.OTHERS_EXECUTE));
                    }
                }
            }
        }
        assertTrue(entries.contains(javaPath.toLowerCase(Locale.ROOT)));
        assertEquals(windows, entries.contains("megamek launcher/megamek launcher.exe"));
        assertEquals(platform.equals("linux-x64"), entries.contains("megamek launcher/mm-launcher"));
        assertEquals(mac, entries.contains("megamek launcher/megamek launcher.app/contents/macos/megamek launcher"));
        assertEquals(mac, entries.contains("megamek launcher/megamek launcher.app/contents/info.plist"));
        assertEquals(mac, entries.contains("megamek launcher/megamek launcher.app/contents/resources/megameklauncher.icns"));
        assertTrue(entries.stream().anyMatch(n -> n.startsWith(runtime.toLowerCase(
                Locale.ROOT) + "legal/") && n.contains("java.base")));
        assertTrue(entries.contains((runtime + "lib/modules").toLowerCase(Locale.ROOT)));
        Path root = extraction.resolve("MegaMek Launcher");
        Path bundledJava = extraction.resolve(javaPath);
        assertTrue(Files.isExecutable(bundledJava), "bundled Java must be executable");
        Path entrypoint = root.resolve(windows ? "MegaMek Launcher.exe" :
                mac ? "MegaMek Launcher.app/Contents/MacOS/MegaMek Launcher" : "mm-launcher");
        Path report = temp.resolve("portable report.txt");
        Path cwd = temp.resolve("unrelated cwd");
        Files.createDirectories(cwd);
        var builder = new ProcessBuilder(entrypoint.toString(), "--startup-check",
                "--registry", temp.resolve("never-created.json").toString(),
                "--report", report.toString()).directory(cwd.toFile()).redirectErrorStream(true);
        Map<String, String> env = builder.environment();
        env.put("JAVA_HOME", temp.resolve("missing-java").toString());
        env.put("PATH", temp.resolve("empty-path").toString());
        env.put("HOME", temp.toString());
        env.put("USERPROFILE", temp.toString());
        env.put("LOCALAPPDATA", temp.resolve("local").toString());
        env.put("APPDATA", temp.resolve("roaming").toString());
        Process process = builder.start();
        assertTrue(process.waitFor(Duration.ofSeconds(60).toMillis(), TimeUnit.MILLISECONDS),
                "portable entrypoint timed out");
        assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8));
        String result = Files.readString(report);
        assertTrue(result.contains("MM-LAUNCHER-STARTUP-OK"), result);
        assertTrue(result.contains("packaged=true"), result);
        assertFalse(Files.exists(temp.resolve("never-created.json")));
    }
}
