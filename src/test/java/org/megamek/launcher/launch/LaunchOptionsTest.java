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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaunchOptionsTest {
    @TempDir
    Path temp;

    @Test
    void layersResolveInstallationThenLauncherThenBuiltIn() {
        LaunchOptions global = new LaunchOptions(6144, null, null, LaunchOptions.Renderer.NO_DIRECT3D,
                "-Dexample=1", null);
        LaunchOptions installation = new LaunchOptions(null, 8192, null, null, "", null);
        LaunchOptions effective = installation.over(global.over(LaunchOptions.builtIn()));
        assertEquals(6144, effective.megamekHeapMb());
        assertEquals(8192, effective.mekhqHeapMb());
        assertEquals(1024, effective.labHeapMb());
        assertEquals(LaunchOptions.Renderer.NO_DIRECT3D, effective.renderer());
        assertEquals("", effective.extraJvmArguments(), "an empty override clears inherited arguments");
        assertEquals("", effective.userDirectory());
        assertEquals(List.of("-Dsun.java2d.d3d=false"), effective.jvmArguments());
        assertTrue(LaunchOptions.INHERIT.inheritsEverything());
        assertFalse(installation.inheritsEverything());
    }

    @Test
    void userDirectoryBecomesTheMegaMekProperty() {
        String directory = temp.resolve("user data").toAbsolutePath().toString();
        LaunchOptions options = new LaunchOptions(null, null, null, LaunchOptions.Renderer.OPENGL,
                "-Da=1", directory).over(LaunchOptions.builtIn());
        assertEquals(List.of("-Dsun.java2d.opengl=true", "-Dmegamek.userDir=" + directory, "-Da=1"),
                options.jvmArguments());
    }

    @Test
    void argumentsSplitOnWhitespaceAndHonourQuotes() {
        assertEquals(List.of("-Da=1", "-Dpath=C:\\My Folder", "-XX:+UseG1GC"),
                LaunchOptions.parseArguments("  -Da=1 \"-Dpath=C:\\My Folder\"\t-XX:+UseG1GC "));
        assertEquals(List.of(), LaunchOptions.parseArguments("   "));
        assertEquals(List.of("--add-opens=java.base/java.lang=ALL-UNNAMED"),
                LaunchOptions.parseArguments("--add-opens=java.base/java.lang=ALL-UNNAMED"));
    }

    @Test
    void argumentsThatCouldChangeWhatStartsAreRejected() {
        for (String text : List.of("-jar x.jar", "-cp other", "--class-path=x", "-classpath x",
                "megamek.Other", "@argfile", "-Xmx2g", "--add-opens java.base/java.lang=ALL-UNNAMED",
                "\"-Dunclosed", "-Dbad=\u0007", "-")) {
            assertThrows(IllegalArgumentException.class, () -> LaunchOptions.parseArguments(text), text);
        }
        assertThrows(IllegalArgumentException.class,
                () -> LaunchOptions.parseArguments("-Da=1 ".repeat(LaunchOptions.MAX_ARGUMENTS + 1)));
        assertThrows(IllegalArgumentException.class,
                () -> LaunchOptions.parseArguments("-D" + "x".repeat(LaunchOptions.MAX_ARGUMENTS_LENGTH)));
    }

    @Test
    void validationChecksMemoryAndUserDirectory() {
        new LaunchOptions(512, 65_536, null, null, null, "").validate();
        assertThrows(IllegalArgumentException.class,
                () -> new LaunchOptions(511, null, null, null, null, null).validate());
        assertThrows(IllegalArgumentException.class,
                () -> new LaunchOptions(null, null, 65_537, null, null, null).validate());
        assertThrows(IllegalArgumentException.class,
                () -> new LaunchOptions(null, null, null, null, null, "relative/folder").validate());
        assertThrows(IllegalArgumentException.class,
                () -> new LaunchOptions(null, null, null, null, null,
                        temp.toAbsolutePath() + "\nother").validate());
    }

    @Test
    void storeRoundTripsLayersAndRemovesEmptyOverrides() throws Exception {
        Path registry = temp.resolve("registry.json");
        LaunchOptionsStore store = new LaunchOptionsStore(registry);
        assertEquals(LaunchOptionsStore.Document.EMPTY, store.read());
        LaunchOptions global = new LaunchOptions(6144, null, 2048, LaunchOptions.Renderer.OPENGL, "-Da=1",
                temp.resolve("shared").toAbsolutePath().toString());
        store.writeGlobal(global);
        LaunchOptions override = new LaunchOptions(null, 8192, null, LaunchOptions.Renderer.DEFAULT, null, "");
        store.writeInstallation("install-1", override);

        LaunchOptionsStore.Document document = new LaunchOptionsStore(registry).read();
        assertEquals(global, document.global());
        assertEquals(override, document.installation("install-1"));
        assertEquals(LaunchOptions.INHERIT, document.installation("other"));
        LaunchOptions effective = document.effective("install-1");
        assertEquals(6144, effective.megamekHeapMb());
        assertEquals(8192, effective.mekhqHeapMb());
        assertEquals(LaunchOptions.Renderer.DEFAULT, effective.renderer());
        assertEquals("", effective.userDirectory());
        assertEquals(temp.resolve("shared").toAbsolutePath().toString(),
                document.effective("other").userDirectory());

        store.writeInstallation("install-1", LaunchOptions.INHERIT);
        assertTrue(store.read().installations().isEmpty());
        assertTrue(Files.isRegularFile(store.path()));
        assertEquals(registry.getFileName() + ".launch-options.json", store.path().getFileName().toString());
    }

    @Test
    void storeRejectsUnknownOrInvalidContentWithoutResetting() throws Exception {
        LaunchOptionsStore store = new LaunchOptionsStore(temp.resolve("registry.json"));
        for (String json : List.of(
                "{\"schemaVersion\":2,\"global\":{}}",
                "{\"schemaVersion\":1,\"global\":{\"surprise\":1}}",
                "{\"schemaVersion\":1,\"global\":{\"megamekHeapMb\":100}}",
                "{\"schemaVersion\":1,\"global\":{\"renderer\":\"VULKAN\"}}",
                "{\"schemaVersion\":1,\"global\":{\"extraJvmArguments\":\"-jar x\"}}",
                "{\"schemaVersion\":1,\"installations\":{\"../escape\":{}}}",
                "{\"schemaVersion\":1,\"schemaVersion\":1}",
                "[]")) {
            Files.writeString(store.path(), json, StandardCharsets.UTF_8);
            assertThrows(IOException.class, store::read, json);
            assertThrows(IOException.class, () -> store.writeGlobal(LaunchOptions.INHERIT), json);
            assertEquals(json, Files.readString(store.path(), StandardCharsets.UTF_8),
                    "unreadable launch options are never overwritten");
        }
        Files.delete(store.path());
        assertThrows(IOException.class, () -> store.writeInstallation("bad id", LaunchOptions.INHERIT));
        assertThrows(IOException.class, () -> store.writeGlobal(
                new LaunchOptions(100, null, null, null, null, null)));
        assertFalse(Files.exists(store.path()));
    }
}
