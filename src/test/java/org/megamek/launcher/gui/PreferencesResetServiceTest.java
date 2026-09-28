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
import org.megamek.launcher.launch.ProcessIdentity;
import org.megamek.launcher.launch.RootCoordinator;
import org.megamek.launcher.onboarding.InstallationInspector;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.release.OfficialRepository;
import org.megamek.launcher.update.OwnershipPolicy;
import org.megamek.launcher.update.ReceiptStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

class PreferencesResetServiceTest {
    @TempDir Path temp;
    private final RegistryStore store = new RegistryStore();
    private final InstallationInspector inspector = new InstallationInspector();

    private Path root(String name, String... products) throws IOException {
        Path root = Files.createDirectory(temp.resolve(name));
        for (String dir : List.of("data", "lib", "mmconf")) Files.createDirectory(root.resolve(dir));
        for (String product : products) {
            String jar = switch (product) {
                case "megamek" -> "MegaMek.jar";
                case "mekhq" -> "MekHQ.jar";
                case "lab" -> "MegaMekLab.jar";
                default -> throw new IllegalArgumentException(product);
            };
            String main = switch (product) {
                case "megamek" -> "megamek.MegaMek";
                case "mekhq" -> "mekhq.MekHQ";
                default -> "megameklab.MegaMekLab";
            };
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, main);
            try (JarOutputStream ignored = new JarOutputStream(
                    Files.newOutputStream(root.resolve(jar)), manifest)) {
            }
        }
        return root;
    }

    private PreferencesResetService service(Path registry, RootCoordinator gate) {
        return new PreferencesResetService(registry, store, inspector, gate);
    }

    @Test
    void importedProductsResetOnlyTheirScopeAndKeepCustomFiles() throws Exception {
        Path registry = temp.resolve("registry.json");
        RootCoordinator gate = new RootCoordinator(temp.resolve("coordination"));
        for (String kind : List.of("megamek", "lab", "mekhq")) {
            Path root = kind.equals("mekhq") ? root(kind, "megamek", "lab", "mekhq")
                    : root(kind, kind);
            InstallationRecord record = store.register(registry, kind, root, null);
            Path conf = root.resolve("mmconf");
            for (String name : List.of("clientsettings.xml", "mm.preferences",
                    "mhq.preferences", "mml.preferences", "megameklab.properties",
                    "megameklab.properties.bak", "custom.txt")) {
                Files.writeString(conf.resolve(name), name);
            }
            var plan = service(registry, gate).plan(record);
            assertEquals(kind, plan.product());
            List<String> expected = switch (kind) {
                case "megamek" -> List.of("clientsettings.xml", "mm.preferences");
                case "lab" -> List.of("mml.preferences", "megameklab.properties",
                        "megameklab.properties.bak");
                default -> List.of("clientsettings.xml", "mm.preferences", "mhq.preferences",
                        "mml.preferences", "megameklab.properties", "megameklab.properties.bak");
            };
            assertEquals(expected, plan.files());
            var result = service(registry, gate).reset(plan);
            for (String name : expected) {
                assertFalse(Files.exists(conf.resolve(name)));
                assertEquals(name, Files.readString(result.backup().resolve(name)));
            }
            assertEquals("custom.txt", Files.readString(conf.resolve("custom.txt")));
            assertTrue(service(registry, gate).plan(record).files().isEmpty());
            assertTrue(service(registry, gate).reset(service(registry, gate).plan(record))
                    .moved().isEmpty());
        }
    }

    @Test
    void ambiguousImportAndLinkedPreferenceFailClosed() throws Exception {
        Path registry = temp.resolve("registry.json");
        RootCoordinator gate = new RootCoordinator(temp.resolve("coordination"));
        Path ambiguous = root("ambiguous", "megamek", "lab");
        InstallationRecord record = store.register(registry, "ambiguous", ambiguous, null);
        assertThrows(IOException.class, () -> service(registry, gate).plan(record));

        Path onlyLab = root("only-lab", "lab");
        InstallationRecord lab = store.register(registry, "lab", onlyLab, null);
        Path outside = temp.resolve("outside");
        Files.writeString(outside, "keep");
        try {
            Files.createSymbolicLink(onlyLab.resolve("mmconf/mml.preferences"), outside);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException e) {
            org.junit.jupiter.api.Assumptions.abort("symlinks unavailable: " + e);
        }
        assertThrows(IOException.class, () -> service(registry, gate).plan(lab));
        assertEquals("keep", Files.readString(outside));
    }

    @Test
    void blocksLiveTrackedGameAndRollsBackPartialMove() throws Exception {
        Path registry = temp.resolve("registry.json");
        Path root = root("lab", "lab");
        InstallationRecord record = store.register(registry, "lab", root, null);
        Path conf = root.resolve("mmconf");
        Files.writeString(conf.resolve("mml.preferences"), "first");
        Files.writeString(conf.resolve("megameklab.properties"), "second");
        RootCoordinator gate = new RootCoordinator(temp.resolve("coordination"));
        var plan = service(registry, gate).plan(record);
        try (RootCoordinator.Lease lease = gate.acquireForLaunch(root)) {
            lease.markLaunchStarting();
            lease.markChild(ProcessIdentity.of(ProcessHandle.current()));
            assertThrows(IOException.class, () -> service(registry, gate).reset(plan));
            lease.clearLaunchMarker();
        }
        assertEquals("first", Files.readString(conf.resolve("mml.preferences")));
        var failing = new PreferencesResetService(registry, store, inspector, gate,
                (source, target) -> {
                    if (source.getFileName().toString().equals("megameklab.properties")) {
                        throw new IOException("simulated second move failure");
                    }
                    Files.move(source, target);
                });
        IOException failure = assertThrows(IOException.class, () -> failing.reset(plan));
        assertTrue(failure.getMessage().contains(plan.backup().toString()));
        assertEquals("first", Files.readString(conf.resolve("mml.preferences")));
        assertEquals("second", Files.readString(conf.resolve("megameklab.properties")));
    }

    @Test
    void staleConfirmationDoesNotMoveNewPreferences() throws Exception {
        Path registry = temp.resolve("registry.json");
        Path root = root("lab", "lab");
        InstallationRecord record = store.register(registry, "lab", root, null);
        Path conf = root.resolve("mmconf");
        Files.writeString(conf.resolve("mml.preferences"), "first");
        var service = service(registry, new RootCoordinator(temp.resolve("coordination")));
        var plan = service.plan(record);
        Files.writeString(conf.resolve("megameklab.properties.bak"), "later");
        assertThrows(IOException.class, () -> service.reset(plan));
        assertEquals("first", Files.readString(conf.resolve("mml.preferences")));
        assertEquals("later", Files.readString(conf.resolve("megameklab.properties.bak")));
        assertFalse(Files.exists(plan.backup()));
    }

    @Test
    void sameNameChangedContentsAndReplacedFileAreNotReset() throws Exception {
        Path registry = temp.resolve("registry.json");
        Path root = root("lab", "lab");
        InstallationRecord record = store.register(registry, "lab", root, null);
        Path file = root.resolve("mmconf/mml.preferences");
        Files.writeString(file, "first");
        var service = service(registry, new RootCoordinator(temp.resolve("coordination")));
        var plan = service.plan(record);
        Files.writeString(file, "newer");
        assertThrows(IOException.class, () -> service.reset(plan));
        assertEquals("newer", Files.readString(file));
        assertFalse(Files.exists(plan.backup()));

        var replacementPlan = service.plan(record);
        Files.move(file, root.resolve("original-preferences"));
        Files.writeString(file, "newer");
        // File keys are optional on some filesystems; where available, they distinguish
        // the original (still present elsewhere) from this same-content replacement.
        if (replacementPlan.fingerprints().get("mml.preferences").fileKey() != null) {
            assertThrows(IOException.class, () -> service.reset(replacementPlan));
            assertFalse(Files.exists(replacementPlan.backup()));
        }
    }

    @Test
    void driftBetweenMovesRollsBackAlreadyMovedFiles() throws Exception {
        Path registry = temp.resolve("registry.json");
        Path root = root("lab", "lab");
        InstallationRecord record = store.register(registry, "lab", root, null);
        Path first = root.resolve("mmconf/mml.preferences");
        Path second = root.resolve("mmconf/megameklab.properties");
        Files.writeString(first, "first");
        Files.writeString(second, "second");
        RootCoordinator gate = new RootCoordinator(temp.resolve("coordination"));
        var plan = service(registry, gate).plan(record);
        var changing = new PreferencesResetService(registry, store, inspector, gate,
                (source, target) -> {
                    Files.move(source, target);
                    if (source.equals(first)) Files.writeString(second, "changed");
                });
        assertThrows(IOException.class, () -> changing.reset(plan));
        assertEquals("first", Files.readString(first));
        assertEquals("changed", Files.readString(second));
    }

    @Test
    void managedReceiptSelectsProductAndInvalidReceiptNeverFallsBack() throws Exception {
        Path registry = temp.resolve("registry.json");
        Path root = root("suite", "megamek", "lab", "mekhq");
        InstallationRecord record = store.register(registry, "suite", root, null);
        var receiptStore = new ReceiptStore();
        var build = new OwnershipPolicy().build(root, OfficialRepository.MEGAMEK, "v1");
        receiptStore.write(registry, store.read(registry), record, OfficialRepository.MEGAMEK,
                "v1", "MegaMek-v1.tar.gz", 10, "sha256:" + "a".repeat(64), build);
        Path conf = root.resolve("mmconf");
        Files.writeString(conf.resolve("mm.preferences"), "mm");
        Files.writeString(conf.resolve("mhq.preferences"), "mhq");
        var service = service(registry, new RootCoordinator(temp.resolve("coordination")));
        var plan = service.plan(record);
        assertEquals("megamek", plan.product());
        assertEquals(List.of("mm.preferences"), plan.files());
        service.reset(plan);
        assertEquals("mhq", Files.readString(conf.resolve("mhq.preferences")));
        Files.writeString(receiptStore.receiptPath(registry, record.id()), "{}");
        assertThrows(IOException.class, () -> service.plan(record));
    }

    @Test
    void launcherServicesDelegatesPlanAndReset() throws Exception {
        Path registry = temp.resolve("registry.json");
        Path root = root("lab", "lab");
        InstallationRecord record = store.register(registry, "lab", root, null);
        Files.writeString(root.resolve("mmconf/mml.preferences"), "local");
        LauncherServices services = new LauncherServices(registry);
        var plan = services.planPreferencesReset(record);
        assertEquals("lab", plan.product());
        assertEquals(List.of("mml.preferences"), plan.files());
        var result = services.resetPreferences(plan);
        assertEquals("local", Files.readString(result.backup().resolve("mml.preferences")));
        assertFalse(Files.exists(root.resolve("mmconf/mml.preferences")));
    }

    @Test
    void resetRejectsUnverifiableOrRedirectedPlansEvenWhenNoFilesExist() throws Exception {
        Path registry = temp.resolve("registry.json");
        Path root = root("lab", "lab");
        InstallationRecord record = store.register(registry, "lab", root, null);
        var service = service(registry, new RootCoordinator(temp.resolve("coordination")));
        var empty = service.plan(record);
        var redirected = new PreferencesResetService.Plan(record, empty.product(),
                empty.files(), temp.resolve("elsewhere"), empty.fingerprints());
        assertThrows(IOException.class, () -> service.reset(redirected));
        Files.writeString(root.resolve("mmconf/mml.preferences"), "local");
        var plan = service.plan(record);
        var forged = new PreferencesResetService.Plan(record, plan.product(),
                plan.files(), plan.backup(), java.util.Map.of());
        assertThrows(IOException.class, () -> service.reset(forged));
        assertEquals("local", Files.readString(root.resolve("mmconf/mml.preferences")));
    }
}
