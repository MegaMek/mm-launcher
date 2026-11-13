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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LauncherSettingsStoreTest {
    @TempDir Path temp;

    @Test
    void absentSettingsContainOnlyTheUnsetDefaultJavaAndDoNotWrite() throws Exception {
        Path registry = temp.resolve("registry.json");
        LauncherSettingsStore store = new LauncherSettingsStore(registry);

        LauncherSettingsStore.Settings initial = store.read();

        assertEquals(LauncherSettingsStore.SCHEMA, initial.schemaVersion());
        assertNull(initial.defaultJavaExecutable());
        assertNull(initial.defaultJavaFeature());
        assertEquals(LauncherAppearance.DEFAULT, initial.appearance().launcherAppearance());
        assertFalse(Files.exists(store.path()), "reading the default is side-effect free");
        assertFalse(Files.exists(registry));
    }

    @Test
    void defaultJavaRoundTripsInExactCurrentSchemaWithoutUpdateFields() throws Exception {
        Path registry = temp.resolve("registry.json");
        LauncherSettingsStore store = new LauncherSettingsStore(registry);
        Path java = temp.resolve("jdk").resolve("bin").resolve("java.exe")
                .toAbsolutePath().normalize();

        LauncherSettingsStore.Settings saved = store.writeDefaultJava(java, 21);
        String json = Files.readString(store.path());

        assertEquals(java.toString(), saved.defaultJavaExecutable());
        assertEquals(21, saved.defaultJavaFeature());
        assertEquals(saved, store.read());
        assertTrue(json.contains("\"schemaVersion\" : 8"));
        assertTrue(json.contains("\"showExperimental\" : false"));
        assertFalse(json.contains("checkInstalledVersionsOnOpen"));
        assertFalse(json.contains("checkNewInstallsOnOpen"));
    }

    @Test
    void oldGlobalUpdateSchemaIsRejectedWithoutMigrationOrRewrite() throws Exception {
        Path registry = temp.resolve("registry.json");
        LauncherSettingsStore store = new LauncherSettingsStore(registry);
        Files.writeString(store.path(), """
                {
                  "schemaVersion" : 2,
                  "checkInstalledVersionsOnOpen" : true,
                  "checkNewInstallsOnOpen" : false
                }
                """);
        String before = Files.readString(store.path());

        IOException error = assertThrows(IOException.class, store::read);

        assertTrue(error.getMessage().contains("unsupported launcher settings schema"));
        assertEquals(before, Files.readString(store.path()));
    }

    @Test
    void removedGlobalFieldIsRejectedEvenWhenClaimingCurrentSchema() throws Exception {
        LauncherSettingsStore store =
                new LauncherSettingsStore(temp.resolve("registry.json"));
        String malformed = """
                {
                  "schemaVersion" : 3,
                  "defaultJavaExecutable" : null,
                  "defaultJavaFeature" : null,
                  "checkInstalledVersionsOnOpen" : false
                }
                """;
        Files.writeString(store.path(), malformed);

        assertThrows(IOException.class, store::read);
        assertEquals(malformed, Files.readString(store.path()));
    }

    @Test
    void invalidDefaultJavaDoesNotReplacePreviousSettings() throws Exception {
        Path registry = temp.resolve("registry.json");
        LauncherSettingsStore store = new LauncherSettingsStore(registry);
        Path java = temp.resolve("jdk").resolve("bin").resolve("java.exe")
                .toAbsolutePath().normalize();
        store.writeDefaultJava(java, 21);
        byte[] before = Files.readAllBytes(store.path());

        assertThrows(IOException.class,
                () -> store.writeDefaultJava(Path.of("relative-java"), 21));
        assertArrayEquals(before, Files.readAllBytes(store.path()));
    }

    @Test
    void corruptExistingSettingIsNotReset() throws Exception {
        Path registry = temp.resolve("registry.json");
        LauncherSettingsStore store = new LauncherSettingsStore(registry);
        Path settings = Files.writeString(store.path(), "{broken");

        IOException read = assertThrows(IOException.class, store::read);
        assertTrue(read.getMessage().contains("not reset"));
        assertThrows(IOException.class, () -> store.writeDefaultJava(
                temp.resolve("java.exe").toAbsolutePath().normalize(), 21));
        assertEquals("{broken", Files.readString(settings));
    }

    @Test
    void wrongTypedDefaultJavaValueIsRejectedWithoutCoercionOrReset() throws Exception {
        LauncherSettingsStore store =
                new LauncherSettingsStore(temp.resolve("registry.json"));
        String malformed = """
                {
                  "schemaVersion" : 3,
                  "defaultJavaExecutable" : false,
                  "defaultJavaFeature" : 21
                }
                """;
        Files.writeString(store.path(), malformed);

        assertThrows(IOException.class, store::read);
        assertEquals(malformed, Files.readString(store.path()));
    }

    @Test
    void appearanceFreeSchemaReadsWithTheDefaultAppearanceAndUpgradesOnWrite() throws Exception {
        LauncherSettingsStore store =
                new LauncherSettingsStore(temp.resolve("registry.json"));
        Path java = temp.resolve("jdk").resolve("bin").resolve("java.exe")
                .toAbsolutePath().normalize();
        String legacy = """
                {
                  "schemaVersion" : 3,
                  "defaultJavaExecutable" : %s,
                  "defaultJavaFeature" : 21
                }
                """.formatted(new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(java.toString()));
        Files.writeString(store.path(), legacy);

        LauncherSettingsStore.Settings read = store.read();

        assertEquals(java.toString(), read.defaultJavaExecutable());
        assertEquals(LauncherAppearance.DEFAULT, read.appearance().launcherAppearance());
        assertEquals(legacy, Files.readString(store.path()), "reading never rewrites");

        LauncherAppearance light = LauncherAppearance.DEFAULT
                .withTheme(LauncherAppearance.Theme.LIGHT);
        LauncherSettingsStore.Settings upgraded = store.writeAppearance(light);

        assertEquals(LauncherSettingsStore.SCHEMA, upgraded.schemaVersion());
        assertEquals(java.toString(), upgraded.defaultJavaExecutable(), "the Java choice is preserved");
        assertEquals(light, store.read().appearance().launcherAppearance());
        String json = Files.readString(store.path());
        assertTrue(json.contains("\"theme\" : \"light\""), json);
        assertFalse(json.contains("palette"), json);
        assertFalse(json.contains("reduceMotion"), json);
    }

    @Test
    void developmentPaletteAndMotionFieldsAreIgnored() throws Exception {
        LauncherSettingsStore store =
                new LauncherSettingsStore(temp.resolve("registry.json"));
        Files.writeString(store.path(), """
                {
                  "schemaVersion" : 4,
                  "defaultJavaExecutable" : null,
                  "defaultJavaFeature" : null,
                  "appearance" : { "theme" : "light", "palette" : "colorblind", "reduceMotion" : true }
                }
                """);

        assertEquals(LauncherAppearance.DEFAULT.withTheme(LauncherAppearance.Theme.LIGHT),
                store.read().appearance().launcherAppearance());
    }

    @Test
    void changingDefaultJavaKeepsTheChosenAppearance() throws Exception {
        LauncherSettingsStore store =
                new LauncherSettingsStore(temp.resolve("registry.json"));
        LauncherAppearance light = LauncherAppearance.DEFAULT.withTheme(LauncherAppearance.Theme.LIGHT);
        store.writeAppearance(light);

        store.writeDefaultJava(temp.resolve("java.exe").toAbsolutePath().normalize(), 21);

        assertEquals(light, store.read().appearance().launcherAppearance());
    }

    @Test
    void unknownAppearanceValuesAreRejectedWithoutReset() throws Exception {
        LauncherSettingsStore store =
                new LauncherSettingsStore(temp.resolve("registry.json"));
        String malformed = """
                {
                  "schemaVersion" : 4,
                  "defaultJavaExecutable" : null,
                  "defaultJavaFeature" : null,
                  "appearance" : { "theme" : "sepia" }
                }
                """;
        Files.writeString(store.path(), malformed);

        assertThrows(IOException.class, store::read);
        assertEquals(malformed, Files.readString(store.path()));
    }

    @Test
    void currentSchemaRequiresTheAppearanceObject() throws Exception {
        LauncherSettingsStore store =
                new LauncherSettingsStore(temp.resolve("registry.json"));
        String malformed = """
                {
                  "schemaVersion" : 4,
                  "defaultJavaExecutable" : null,
                  "defaultJavaFeature" : null
                }
                """;
        Files.writeString(store.path(), malformed);

        assertThrows(IOException.class, store::read);
        assertEquals(malformed, Files.readString(store.path()));
    }

    @Test
    void showExperimentalRoundTripsAndKeepsOtherSettings() throws Exception {
        LauncherSettingsStore store =
                new LauncherSettingsStore(temp.resolve("registry.json"));
        LauncherAppearance light = LauncherAppearance.DEFAULT.withTheme(LauncherAppearance.Theme.LIGHT);
        store.writeAppearance(light);

        LauncherSettingsStore.Settings saved = store.writeShowExperimental(true);

        assertTrue(saved.showExperimental());
        assertEquals(saved, store.read());
        assertEquals(light, store.read().appearance().launcherAppearance());
        assertTrue(Files.readString(store.path()).contains("\"showExperimental\" : true"));
        assertFalse(store.writeShowExperimental(false).showExperimental());
        assertFalse(store.read().showExperimental());
    }

    @Test
    void schemaFourReadsAsCoreOnlyAndIsRewrittenOnTheNextChange() throws Exception {
        LauncherSettingsStore store =
                new LauncherSettingsStore(temp.resolve("registry.json"));
        Files.writeString(store.path(), """
                {
                  "schemaVersion" : 4,
                  "defaultJavaExecutable" : null,
                  "defaultJavaFeature" : null,
                  "appearance" : { "theme" : "light" }
                }
                """);

        LauncherSettingsStore.Settings read = store.read();

        assertEquals(LauncherSettingsStore.SCHEMA, read.schemaVersion());
        assertFalse(read.showExperimental());
        store.writeShowExperimental(true);
        String json = Files.readString(store.path());
        assertTrue(json.contains("\"schemaVersion\" : 8"));
        assertTrue(json.contains("\"showExperimental\" : true"));
        assertEquals(LauncherAppearance.DEFAULT.withTheme(LauncherAppearance.Theme.LIGHT),
                store.read().appearance().launcherAppearance());
    }

    @Test
    void nonBooleanShowExperimentalIsRejectedWithoutRewrite() throws Exception {
        LauncherSettingsStore store =
                new LauncherSettingsStore(temp.resolve("registry.json"));
        String malformed = """
                {
                  "schemaVersion" : 5,
                  "defaultJavaExecutable" : null,
                  "defaultJavaFeature" : null,
                  "appearance" : { "theme" : "light" },
                  "showExperimental" : "yes"
                }
                """;
        Files.writeString(store.path(), malformed);

        assertThrows(IOException.class, store::read);
        assertEquals(malformed, Files.readString(store.path()));
    }

    @Test
    void installParentRoundTripsAndKeepsOtherSettings() throws Exception {
        LauncherSettingsStore store =
                new LauncherSettingsStore(temp.resolve("registry.json"));
        store.writeShowExperimental(true);
        Path games = temp.resolve("games").toAbsolutePath().normalize();

        LauncherSettingsStore.Settings saved = store.writeInstallParent(games);

        assertEquals(games.toString(), saved.installParent());
        assertEquals(saved, store.read());
        assertTrue(store.read().showExperimental());
        assertNull(store.writeInstallParent(null).installParent());
        assertNull(store.read().installParent());
    }

    @Test
    void schemaFiveReadsWithoutAnInstallParentAndIsRewrittenOnTheNextChange() throws Exception {
        LauncherSettingsStore store =
                new LauncherSettingsStore(temp.resolve("registry.json"));
        String previous = """
                {
                  "schemaVersion" : 5,
                  "defaultJavaExecutable" : null,
                  "defaultJavaFeature" : null,
                  "appearance" : { "theme" : "light" },
                  "showExperimental" : true
                }
                """;
        Files.writeString(store.path(), previous);

        LauncherSettingsStore.Settings read = store.read();

        assertEquals(LauncherSettingsStore.SCHEMA, read.schemaVersion());
        assertNull(read.installParent());
        assertTrue(read.showExperimental());
        assertEquals(previous, Files.readString(store.path()), "reading never rewrites");
        store.writeInstallParent(temp.toAbsolutePath().normalize());
        String json = Files.readString(store.path());
        assertTrue(json.contains("\"schemaVersion\" : 8"), json);
        assertTrue(json.contains("\"installParent\""), json);
        assertTrue(store.read().showExperimental());
    }

    @Test
    void relativeOrMistypedInstallParentIsRejectedWithoutRewrite() throws Exception {
        LauncherSettingsStore store =
                new LauncherSettingsStore(temp.resolve("registry.json"));
        for (String value : List.of("\"games\"", "42")) {
            String malformed = """
                    {
                      "schemaVersion" : 6,
                      "defaultJavaExecutable" : null,
                      "defaultJavaFeature" : null,
                      "appearance" : { "theme" : "light" },
                      "showExperimental" : false,
                      "installParent" : %s
                    }
                    """.formatted(value);
            Files.writeString(store.path(), malformed);

            assertThrows(IOException.class, store::read, value);
            assertEquals(malformed, Files.readString(store.path()));
        }
        Files.delete(store.path());
        assertThrows(IOException.class, () -> store.writeInstallParent(Path.of("games")));
        assertFalse(Files.exists(store.path()));
    }

    @Test
    void playIntroDefaultsOnRoundTripsAndKeepsOtherSettings() throws Exception {
        LauncherSettingsStore store =
                new LauncherSettingsStore(temp.resolve("registry.json"));
        assertTrue(store.read().playIntro());
        store.writeShowExperimental(true);

        LauncherSettingsStore.Settings saved = store.writePlayIntro(false);

        assertFalse(saved.playIntro());
        assertEquals(saved, store.read());
        assertTrue(store.read().showExperimental());
        assertTrue(Files.readString(store.path()).contains("\"playIntro\" : false"));
        assertFalse(store.writeShowExperimental(false).playIntro(), "other writes keep it");
        assertTrue(store.writePlayIntro(true).playIntro());
    }

    @Test
    void concurrentSavesOfDifferentSettingsQueueInsteadOfRejectingEachOther() throws Exception {
        Path registry = temp.resolve("registry.json");
        LauncherSettingsStore introStore = new LauncherSettingsStore(registry);
        LauncherSettingsStore experimentalStore = new LauncherSettingsStore(registry);
        int rounds = 40;
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> intro = workers.submit(() -> {
                start.await();
                for (int i = 0; i < rounds; i++) introStore.writePlayIntro(i % 2 == 1);
                return null;
            });
            Future<?> experimental = workers.submit(() -> {
                start.await();
                for (int i = 0; i < rounds; i++) experimentalStore.writeShowExperimental(i % 2 == 0);
                return null;
            });
            intro.get(30, TimeUnit.SECONDS);
            experimental.get(30, TimeUnit.SECONDS);
        } finally {
            workers.shutdownNow();
        }

        LauncherSettingsStore.Settings saved = introStore.read();
        assertTrue(saved.playIntro(), "the last intro save survives");
        assertFalse(saved.showExperimental(), "the last Experimental save survives");
    }

    @Test
    void schemaSixReadsAsPlayingTheIntroAndIsRewrittenOnTheNextChange() throws Exception {
        LauncherSettingsStore store =
                new LauncherSettingsStore(temp.resolve("registry.json"));
        String previous = """
                {
                  "schemaVersion" : 6,
                  "defaultJavaExecutable" : null,
                  "defaultJavaFeature" : null,
                  "appearance" : { "theme" : "light" },
                  "showExperimental" : true,
                  "installParent" : null
                }
                """;
        Files.writeString(store.path(), previous);

        LauncherSettingsStore.Settings read = store.read();

        assertEquals(LauncherSettingsStore.SCHEMA, read.schemaVersion());
        assertTrue(read.playIntro());
        assertTrue(read.showExperimental());
        assertEquals(previous, Files.readString(store.path()), "reading never rewrites");
        store.writePlayIntro(false);
        String json = Files.readString(store.path());
        assertTrue(json.contains("\"schemaVersion\" : 8"), json);
        assertTrue(json.contains("\"playIntro\" : false"), json);
        assertTrue(store.read().showExperimental());
    }

    @Test
    void mistypedOrPrematurePlayIntroIsRejectedWithoutRewrite() throws Exception {
        LauncherSettingsStore store =
                new LauncherSettingsStore(temp.resolve("registry.json"));
        for (String header : List.of("\"schemaVersion\" : 7, \"playIntro\" : \"no\"",
                "\"schemaVersion\" : 7", "\"schemaVersion\" : 6, \"playIntro\" : false")) {
            String malformed = """
                    {
                      %s,
                      "defaultJavaExecutable" : null,
                      "defaultJavaFeature" : null,
                      "appearance" : { "theme" : "light" },
                      "showExperimental" : false,
                      "installParent" : null
                    }
                    """.formatted(header);
            Files.writeString(store.path(), malformed);

            assertThrows(IOException.class, store::read, header);
            assertEquals(malformed, Files.readString(store.path()));
        }
    }

    @Test
    void schemaSevenReadsAsTheSystemLanguageAndIsRewrittenOnTheNextChange() throws Exception {
        LauncherSettingsStore store =
                new LauncherSettingsStore(temp.resolve("registry.json"));
        String previous = """
                {
                  "schemaVersion" : 7,
                  "defaultJavaExecutable" : null,
                  "defaultJavaFeature" : null,
                  "appearance" : { "theme" : "light" },
                  "showExperimental" : true,
                  "installParent" : null,
                  "playIntro" : false
                }
                """;
        Files.writeString(store.path(), previous);

        LauncherSettingsStore.Settings read = store.read();

        assertEquals(LauncherSettingsStore.SCHEMA, read.schemaVersion());
        assertNull(read.language());
        assertFalse(read.playIntro());
        assertEquals(previous, Files.readString(store.path()), "reading never rewrites");
        store.writeLanguage("de");
        String json = Files.readString(store.path());
        assertTrue(json.contains("\"schemaVersion\" : 8"), json);
        assertTrue(json.contains("\"language\" : \"de\""), json);
        assertFalse(store.read().playIntro());
    }

    @Test
    void languageRoundTripsAndOtherWritesKeepIt() throws Exception {
        LauncherSettingsStore store =
                new LauncherSettingsStore(temp.resolve("registry.json"));
        assertNull(store.read().language());

        assertEquals("es", store.writeLanguage("es").language());
        assertEquals("es", store.writePlayIntro(false).language(), "other writes keep it");
        assertEquals("es", store.read().language());
        assertNull(store.writeLanguage(null).language());
        assertNull(store.read().language());
    }

    @Test
    void unknownOrMistypedLanguageIsRejectedWithoutRewrite() throws Exception {
        LauncherSettingsStore store =
                new LauncherSettingsStore(temp.resolve("registry.json"));
        assertThrows(IOException.class, () -> store.writeLanguage("fr"));
        for (String header : List.of("\"schemaVersion\" : 8, \"language\" : \"fr\"",
                "\"schemaVersion\" : 8, \"language\" : 3", "\"schemaVersion\" : 8",
                "\"schemaVersion\" : 7, \"language\" : \"de\"")) {
            String malformed = """
                    {
                      %s,
                      "defaultJavaExecutable" : null,
                      "defaultJavaFeature" : null,
                      "appearance" : { "theme" : "light" },
                      "showExperimental" : false,
                      "installParent" : null,
                      "playIntro" : true
                    }
                    """.formatted(header);
            Files.writeString(store.path(), malformed);

            assertThrows(IOException.class, store::read, header);
            assertEquals(malformed, Files.readString(store.path()));
        }
    }
}
