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

package org.megamek.launcher.onboarding;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.megamek.launcher.launch.ProcessRunner;
import org.megamek.launcher.operation.OperationContext;
import org.megamek.launcher.operation.OperationType;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryData;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.update.ReceiptStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExistingImportServiceTest {
    @TempDir Path temp;

    @Test
    void importsMegaMekOnlyAndFullBundleWithoutRunningJavaOrCreatingSidecars() throws Exception {
        Path registry = temp.resolve("registry.json");
        RecordingRunner runner = new RecordingRunner(
                new ProcessRunner.Result(0, "openjdk version \"21.0.8\"", false));
        ExistingImportService service = service(registry, runner);
        Path megaMek = suite("MegaMek-only", List.of("megamek"), true);
        Path maliciousMarker = megaMek.resolve("malicious-main-ran");

        ExistingImportService.Plan firstPlan = service.prepare(megaMek, context());
        assertFalse(Files.exists(registry), "read-only quote must not create a registry");
        ExistingImportService.Result first = service.register(firstPlan, "Existing MegaMek",
                context());

        assertTrue(first.becameMain());
        assertEquals(List.of("megamek"),
                first.record().products().stream().map(Product::key).toList());
        assertFalse(first.record().updateEligible());
        assertFalse(Files.exists(maliciousMarker), "static JAR metadata must not load classes");

        Path full = suite("MekHQ-bundle", List.of("megamek", "mekhq", "lab"), true);
        ExistingImportService.Result second = service.register(
                service.prepare(full, context()), "Existing MekHQ", context());
        RegistryData data = new RegistryStore().read(registry);

        assertFalse(second.becameMain());
        assertEquals(first.record().id(), data.defaultInstallationId());
        assertEquals(List.of("lab", "megamek", "mekhq"),
                second.record().products().stream().map(Product::key).sorted().toList());
        assertEquals(2, data.installations().size());
        assertTrue(runner.commands.isEmpty(), "import must never execute Java");
        assertFalse(Files.exists(new ReceiptStore().metadataDirectory(registry)),
                "import must not create receipt or channel metadata");
    }

    @Test
    void importsIndependentProgramVersionsWithoutGrantingManagedOwnership() throws Exception {
        Path registry = temp.resolve("independent-registry.json");
        RecordingRunner runner = goodRunner();
        ExistingImportService service = service(registry, runner);
        Path root = suite("independent-bundle", List.of("megamek", "lab", "mekhq"), true);
        Map<String, String> builds = Map.of(
                "megamek", "0.51.01", "lab", "0.51.02", "mekhq", "0.51.03");
        jar(root.resolve("MegaMek.jar"), "megamek.MegaMek", true, builds.get("megamek"));
        jar(root.resolve("MegaMekLab.jar"), "megameklab.MegaMekLab", true, builds.get("lab"));
        jar(root.resolve("MekHQ.jar"), "mekhq.MekHQ", true, builds.get("mekhq"));

        service.register(service.prepare(root, context()), "Independent MekHQ", context());
        InstallationRecord persisted = new RegistryStore().read(registry).installations().getFirst();
        assertEquals("0.51.03", persisted.observedBuild());
        assertEquals(3, persisted.products().size());
        for (Product product : persisted.products()) {
            assertEquals(builds.get(product.key()), product.build());
        }
        assertFalse(persisted.updateEligible());
        assertFalse(Files.exists(new ReceiptStore().metadataDirectory(registry)));
        assertTrue(runner.commands.isEmpty(), "import must never execute Java");
    }

    @Test
    void preservesExistingRecordAndConcurrentMainWins() throws Exception {
        Path registry = temp.resolve("preserve.json");
        RegistryStore store = new RegistryStore();
        Path originalRoot = suite("original", List.of("megamek"), false);
        InstallationRecord original = store.register(registry, "Pinned original", originalRoot,
                "keep-build");
        InstallationRecord configured = store.read(registry).installations().getFirst();

        ExistingImportService service = service(registry, goodRunner());
        Path importedRoot = suite("imported", List.of("megamek"), false);
        ExistingImportService.Result imported = service.register(
                service.prepare(importedRoot, context()), "Imported", context());
        RegistryData after = store.read(registry);

        assertFalse(imported.becameMain());
        assertEquals(original.id(), after.defaultInstallationId());
        assertEquals(configured, after.installations().getFirst(),
                "name, pin, registration time and products must be retained");

        Path racingRegistry = temp.resolve("racing.json");
        ExistingImportService racing = service(racingRegistry, goodRunner());
        Path quotedRoot = suite("quoted-first", List.of("megamek"), false);
        ExistingImportService.Plan staleUiQuote =
                racing.prepare(quotedRoot, context());
        Path winnerRoot = suite("concurrent-winner", List.of("megamek"), false);
        InstallationRecord winner = store.register(racingRegistry, "Concurrent Main", winnerRoot,
                null);

        ExistingImportService.Result late =
                racing.register(staleUiQuote, "Quoted import", context());
        RegistryData raced = store.read(racingRegistry);
        assertFalse(late.becameMain());
        assertEquals(winner.id(), raced.defaultInstallationId());
        assertEquals(2, raced.installations().size());
    }

    @Test
    void duplicateOverlapAndChangedOrMovedLayoutsFailWithoutAddingARecord() throws Exception {
        Path registry = temp.resolve("failures.json");
        RegistryStore store = new RegistryStore();
        ExistingImportService service = service(registry, goodRunner());
        Path firstRoot = suite("first", List.of("megamek"), false);
        ExistingImportService.Plan firstPlan = service.prepare(firstRoot, context());
        service.register(firstPlan, "First", context());

        assertThrows(IOException.class,
                () -> service.register(firstPlan, "Duplicate", context()));
        assertEquals(1, store.read(registry).installations().size());

        Path parent = suite("parent", List.of("megamek"), false);
        Path nested = suite("parent/nested", List.of("megamek"), false);
        ExistingImportService.Plan nestedPlan = service.prepare(nested, context());
        store.register(registry, "Parent", parent, null);
        assertThrows(IOException.class,
                () -> service.register(nestedPlan, "Overlapping", context()));
        assertEquals(2, store.read(registry).installations().size());

        Path changed = suite("changed", List.of("megamek"), false);
        ExistingImportService.Plan changedPlan =
                service.prepare(changed, context());
        jar(changed.resolve("MekHQ.jar"), "mekhq.MekHQ", false);
        assertThrows(IOException.class,
                () -> service.register(changedPlan, "Changed", context()));
        assertEquals(2, store.read(registry).installations().size());

        Path moved = suite("moved", List.of("megamek"), false);
        ExistingImportService.Plan movedPlan = service.prepare(moved, context());
        Files.move(moved, temp.resolve("moved-away"));
        suite("moved", List.of("megamek"), false);
        assertThrows(IOException.class, () -> service.register(movedPlan, "Moved", context()));
        assertEquals(2, store.read(registry).installations().size());
    }

    @Test
    void corruptOrUnusableJavaRunnerDoesNotAffectImport() throws Exception {
        List<ProcessRunner.Result> failures = List.of(
                new ProcessRunner.Result(0, "openjdk version \"17.0.12\"", false),
                new ProcessRunner.Result(7, "failed", false),
                new ProcessRunner.Result(0, "unexpected output", false),
                new ProcessRunner.Result(-1, "", true));
        for (int index = 0; index < failures.size(); index++) {
            Path registry = temp.resolve("java-failure-" + index + ".json");
            RecordingRunner runner = new RecordingRunner(failures.get(index));
            ExistingImportService service = service(registry, runner);
            Path root = suite("java-failure-root-" + index, List.of("megamek"), false);

            ExistingImportService.Result result = service.register(
                    service.prepare(root, context()), "Imported", context());
            assertEquals(root.toRealPath().toString(), result.record().canonicalRoot());
            assertTrue(runner.commands.isEmpty());
            assertFalse(Files.exists(new ReceiptStore().metadataDirectory(registry)));
        }
    }

    private ExistingImportService service(Path registry, ProcessRunner runner) {
        return new ExistingImportService(registry, new RegistryStore(),
                new InstallationInspector());
    }

    private static OperationContext context() {
        return OperationContext.none(OperationType.IMPORT_EXISTING);
    }

    private static RecordingRunner goodRunner() {
        return new RecordingRunner(
                new ProcessRunner.Result(0, "openjdk version \"21.0.8\"", false));
    }

    private Path suite(String name, List<String> products, boolean maliciousClass)
            throws Exception {
        Path root = temp.resolve(name);
        Files.createDirectories(root);
        Files.createDirectories(root.resolve("data"));
        Files.createDirectories(root.resolve("mmconf"));
        Files.createDirectories(root.resolve("lib"));
        Map<String, String> jars = Map.of(
                "megamek", "MegaMek.jar",
                "mekhq", "MekHQ.jar",
                "lab", "MegaMekLab.jar");
        Map<String, String> mains = Map.of(
                "megamek", "megamek.MegaMek",
                "mekhq", "mekhq.MekHQ",
                "lab", "megameklab.MegaMekLab");
        for (String product : products) {
            jar(root.resolve(jars.get(product)), mains.get(product), maliciousClass);
        }
        return root;
    }

    private static void jar(Path path, String mainClass, boolean maliciousClass) throws Exception {
        jar(path, mainClass, maliciousClass, null);
    }

    private static void jar(Path path, String mainClass, boolean maliciousClass, String build)
            throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mainClass);
        if (build != null) {
            manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, build);
        }
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            if (maliciousClass) {
                output.putNextEntry(new JarEntry(mainClass.replace('.', '/') + ".class"));
                output.write(new byte[]{0, 1, 2, 3});
                output.closeEntry();
            }
        }
    }

    private static final class RecordingRunner implements ProcessRunner {
        private final Result result;
        private final List<List<String>> commands = new ArrayList<>();

        private RecordingRunner(Result result) {
            this.result = result;
        }

        @Override
        public Result run(List<String> command, Path workingDirectory, Duration timeout,
                          boolean inheritIo) {
            commands.add(List.copyOf(command));
            assertNotNull(workingDirectory);
            assertEquals(Duration.ofSeconds(10), timeout);
            assertFalse(inheritIo);
            return result;
        }
    }
}
