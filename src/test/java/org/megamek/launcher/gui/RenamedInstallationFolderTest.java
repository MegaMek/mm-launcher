/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.megamek.launcher.gui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.megamek.launcher.channel.ChannelPreferenceStore;
import org.megamek.launcher.channel.FollowChannel;
import org.megamek.launcher.launch.ApplicationLauncher;
import org.megamek.launcher.launch.JavaRuntime;
import org.megamek.launcher.launch.LaunchOptions;
import org.megamek.launcher.launch.LaunchOptionsStore;
import org.megamek.launcher.launch.RootCoordinator;
import org.megamek.launcher.onboarding.InstallationInspector;
import org.megamek.launcher.operation.OperationContext;
import org.megamek.launcher.operation.OperationType;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryData;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.release.OfficialRepository;
import org.megamek.launcher.update.ImportedCopyAdoptionService.Availability;
import org.megamek.launcher.update.InstallationRelocationService;
import org.megamek.launcher.update.OwnershipPolicy;
import org.megamek.launcher.update.ReceiptStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MegaMek/mm-launcher#61 follow-up: a player renames a launcher-installed folder in the file
 * manager and the launcher reports it missing. Locating the renamed folder must keep the copy,
 * including its update history, release type, selections and launch options.
 */
class RenamedInstallationFolderTest {
    private static final OperationContext CONTEXT =
            OperationContext.none(OperationType.IMPORT_EXISTING);
    private static final LaunchOptions OPTIONS =
            new LaunchOptions(2048, null, null, null, null, null);

    @TempDir Path temp;

    @Test
    void locatingARenamedManagedCopyKeepsReceivingUpdates() throws Exception {
        LauncherServices services = services();
        Path original = suite(temp.resolve("games").resolve("MegaMek Core"), "1.0.0");
        InstallationRecord installed = managed(services, original);
        services.saveInstallationLaunchOptions(installed, OPTIONS);
        assertEquals(Availability.MANAGED, status(services, installed).adoption());

        Path renamed = original.resolveSibling("MegaMek-C");
        Files.move(original, renamed);
        assertTrue(status(services, installed).missing());

        InstallationRecord located = services.relocate(installed, renamed, CONTEXT);

        assertNotEquals(installed.id(), located.id());
        assertEquals(renamed.toRealPath().toString(), located.canonicalRoot());
        assertEquals(installed.name(), located.name());
        assertEquals(installed.registeredAt(), located.registeredAt());
        LauncherServices.InstallationStatus after = status(services, located);
        assertFalse(after.missing());
        assertEquals(Availability.MANAGED, after.adoption());
        assertEquals(FollowChannel.CORE, after.channelPreference().preference().channel());
        RegistryData registry = services.readRegistry();
        assertEquals(1, registry.installations().size());
        assertEquals(located.id(), registry.defaultInstallationId());
        assertEquals(located.id(), registry.preferredInstallationIds().get("megamek"));
        assertEquals(OPTIONS, services.launchOptions().installation(located.id()));
        assertTrue(services.launchOptions().installation(installed.id()).inheritsEverything());
        try (Stream<Path> files = Files.list(metadata(services))) {
            assertTrue(files.noneMatch(file -> file.getFileName().toString()
                    .startsWith(installed.id())), "the old id's metadata is cleaned up");
        }
    }

    @Test
    void locatingARenamedLaunchOnlyCopyKeepsIt() throws Exception {
        LauncherServices services = services();
        Path original = suite(temp.resolve("games").resolve("MegaMek"), "1.0.0");
        InstallationRecord installed = services.register("MegaMek", original);
        Path renamed = original.resolveSibling("MegaMek renamed");
        Files.move(original, renamed);

        InstallationRecord located = services.relocate(installed, renamed, CONTEXT);

        assertEquals(renamed.toRealPath().toString(), located.canonicalRoot());
        assertEquals(java.util.List.of(located), services.readRegistry().installations());
        assertFalse(status(services, located).missing());
    }

    @Test
    void refusesAFolderWithADifferentBuild() throws Exception {
        LauncherServices services = services();
        Path original = suite(temp.resolve("games").resolve("MegaMek"), "1.0.0");
        InstallationRecord installed = managed(services, original);
        deleteTree(original);
        Path other = suite(temp.resolve("games").resolve("Other"), "2.0.0");

        IOException error = assertThrows(IOException.class,
                () -> services.relocate(installed, other, CONTEXT));
        assertTrue(error.getMessage().contains("2.0.0"), error.getMessage());
        assertEquals(java.util.List.of(installed), services.readRegistry().installations());
    }

    @Test
    void refusesAFolderWhoseOfficialJarDiffers() throws Exception {
        LauncherServices services = services();
        Path original = suite(temp.resolve("games").resolve("MegaMek"), "1.0.0");
        InstallationRecord installed = managed(services, original);
        Path renamed = original.resolveSibling("Renamed");
        Files.move(original, renamed);
        jar(renamed.resolve("MegaMek.jar"), "1.0.0", "different.txt");

        IOException error = assertThrows(IOException.class,
                () -> services.relocate(installed, renamed, CONTEXT));
        assertTrue(error.getMessage().contains("MegaMek.jar"), error.getMessage());
        assertUnchanged(services, installed);
    }

    @Test
    void refusesAFolderMissingAnOfficialFile() throws Exception {
        LauncherServices services = services();
        Path original = suite(temp.resolve("games").resolve("MegaMek"), "1.0.0");
        Files.writeString(original.resolve("docs").resolve("history.txt"), "history");
        InstallationRecord installed = managed(services, original);
        Path renamed = original.resolveSibling("Renamed");
        Files.move(original, renamed);
        Files.delete(renamed.resolve("docs").resolve("history.txt"));

        IOException error = assertThrows(IOException.class,
                () -> services.relocate(installed, renamed, CONTEXT));
        assertTrue(error.getMessage().contains("docs/history.txt"), error.getMessage());
        assertUnchanged(services, installed);
    }

    @Test
    void refusesAFolderThatIsAlreadyRegistered() throws Exception {
        LauncherServices services = services();
        Path original = suite(temp.resolve("games").resolve("MegaMek"), "1.0.0");
        InstallationRecord installed = services.register("MegaMek", original);
        Path other = suite(temp.resolve("games").resolve("Other"), "1.0.0");
        InstallationRecord registered = services.register("Other", other);
        deleteTree(original);

        IOException error = assertThrows(IOException.class,
                () -> services.relocate(installed, other, CONTEXT));
        assertTrue(error.getMessage().contains("already in the launcher"), error.getMessage());
        assertEquals(java.util.List.of(installed, registered),
                services.readRegistry().installations());
    }

    @Test
    void refusesWhenTheOriginalFolderIsBack() throws Exception {
        LauncherServices services = services();
        Path original = suite(temp.resolve("games").resolve("MegaMek"), "1.0.0");
        InstallationRecord installed = services.register("MegaMek", original);
        Path copy = suite(temp.resolve("games").resolve("Copy"), "1.0.0");

        assertThrows(IOException.class, () -> services.relocate(installed, copy, CONTEXT));
        assertEquals(java.util.List.of(installed), services.readRegistry().installations());
    }

    @Test
    void refusesAFolderWithAnInterruptedUpdate() throws Exception {
        LauncherServices services = services();
        Path original = suite(temp.resolve("games").resolve("MegaMek"), "1.0.0");
        InstallationRecord installed = managed(services, original);
        Path renamed = original.resolveSibling("Renamed");
        Files.move(original, renamed);
        Files.createDirectory(renamed.resolve(RootCoordinator.UPDATE_NAMESPACE));

        IOException error = assertThrows(IOException.class,
                () -> services.relocate(installed, renamed, CONTEXT));
        assertTrue(error.getMessage().contains("interrupted update"), error.getMessage());
        assertUnchanged(services, installed);
    }

    @Test
    void refusesAFolderInsideTheLauncherMetadata() throws Exception {
        LauncherServices services = services();
        Path original = suite(temp.resolve("games").resolve("MegaMek"), "1.0.0");
        InstallationRecord installed = managed(services, original);
        Path hidden = metadata(services).resolve("relocated-game");
        Files.move(original, hidden);

        IOException error = assertThrows(IOException.class,
                () -> services.relocate(installed, hidden, CONTEXT));
        assertTrue(error.getMessage().contains("launcher's own data"), error.getMessage());
        assertEquals(java.util.List.of(installed), services.readRegistry().installations());
        try (Stream<Path> files = Files.list(metadata(services))) {
            assertTrue(files.map(file -> file.getFileName().toString())
                    .filter(name -> !name.equals("relocated-game"))
                    .allMatch(name -> name.startsWith(installed.id())));
        }
    }

    @Test
    void launchOptionsMoveEvenWhenEveryOverrideSlotIsTaken() throws Exception {
        LauncherServices services = services();
        Path original = suite(temp.resolve("games").resolve("MegaMek"), "1.0.0");
        InstallationRecord installed = managed(services, original);
        LaunchOptionsStore store = new LaunchOptionsStore(services.registry());
        for (int i = 1; i < 256; i++) {
            store.writeInstallation(java.util.UUID.randomUUID().toString(), OPTIONS);
        }
        services.saveInstallationLaunchOptions(installed, OPTIONS);
        assertEquals(256, store.read().installations().size());
        Path renamed = original.resolveSibling("Renamed");
        Files.move(original, renamed);

        InstallationRecord located = services.relocate(installed, renamed, CONTEXT);

        assertEquals(OPTIONS, services.launchOptions().installation(located.id()));
        assertTrue(services.launchOptions().installation(installed.id()).inheritsEverything());
        assertEquals(256, store.read().installations().size());
    }

    @Test
    void launchOptionsMoveOnTheNextStartAfterAnInterruptedLocate() throws Exception {
        LauncherServices services = services();
        Path original = suite(temp.resolve("games").resolve("MegaMek"), "1.0.0");
        InstallationRecord installed = managed(services, original);
        services.saveInstallationLaunchOptions(installed, OPTIONS);
        Path renamed = original.resolveSibling("Renamed");
        Files.move(original, renamed);

        // The launcher stops right after the registry commit, before anything else runs.
        InstallationRecord located = new InstallationRelocationService(services.registry(),
                new RegistryStore(), new RootCoordinator(temp.resolve("coordination")),
                new InstallationInspector()).relocate(installed, renamed, CONTEXT);
        assertEquals(OPTIONS, services.launchOptions().installation(installed.id()));
        Path marker = metadata(services).resolve(installed.id() + ".relocated-to."
                + located.id());
        assertTrue(Files.isRegularFile(marker));

        services.loadHome();

        assertEquals(OPTIONS, services.launchOptions().installation(located.id()));
        assertTrue(services.launchOptions().installation(installed.id()).inheritsEverything());
        assertFalse(Files.exists(marker));
    }

    @Test
    void launchOptionsFollowAnInstallationMovedTwiceBeforeTheyCouldMove() throws Exception {
        LauncherServices services = services();
        Path first = suite(temp.resolve("games").resolve("MegaMek"), "1.0.0");
        InstallationRecord installed = managed(services, first);
        services.saveInstallationLaunchOptions(installed, OPTIONS);
        InstallationRelocationService raw = new InstallationRelocationService(
                services.registry(), new RegistryStore(),
                new RootCoordinator(temp.resolve("coordination")), new InstallationInspector());
        Path second = first.resolveSibling("Second");
        Files.move(first, second);
        InstallationRecord middle = raw.relocate(installed, second, CONTEXT);
        Path third = first.resolveSibling("Third");
        Files.move(second, third);
        InstallationRecord last = raw.relocate(middle, third, CONTEXT);

        services.loadHome();

        assertEquals(OPTIONS, services.launchOptions().installation(last.id()));
        assertEquals(1, services.launchOptions().installations().size());
        try (Stream<Path> files = Files.list(metadata(services))) {
            assertTrue(files.noneMatch(file -> file.getFileName().toString()
                    .contains(".relocated-to.")));
        }
    }

    @Test
    void launchOptionsSavedUnderTheNewIdAreNotOverwritten() throws Exception {
        LaunchOptionsStore store = new LaunchOptionsStore(
                temp.resolve("state").resolve("registry.json"));
        Files.createDirectories(temp.resolve("state"));
        LaunchOptions newer = new LaunchOptions(4096, null, null, null, null, null);
        store.writeInstallation("old", OPTIONS);
        store.writeInstallation("new", newer);

        store.rekeyInstallation("old", "new");

        assertEquals(newer, store.read().installation("new"));
        assertEquals(1, store.read().installations().size());
    }

    private void assertUnchanged(LauncherServices services, InstallationRecord installed)
            throws Exception {
        assertEquals(java.util.List.of(installed), services.readRegistry().installations());
        try (Stream<Path> files = Files.list(metadata(services))) {
            assertTrue(files.allMatch(file -> file.getFileName().toString()
                    .startsWith(installed.id())), "no metadata for another id was left behind");
        }
    }

    private static Path metadata(LauncherServices services) {
        return new ReceiptStore().metadataDirectory(services.registry());
    }

    private InstallationRecord managed(LauncherServices services, Path root) throws Exception {
        InstallationRecord record = services.register("MegaMek Core", root);
        var build = new OwnershipPolicy().build(root, OfficialRepository.MEGAMEK, "v1.0.0");
        var receipt = new ReceiptStore().write(services.registry(), services.readRegistry(), record,
                OfficialRepository.MEGAMEK, "v1.0.0", "MegaMek-v1.0.0.tar.gz", 10,
                "sha256:" + "a".repeat(64), build);
        new ChannelPreferenceStore().initializeManaged(services.registry(), record, receipt,
                FollowChannel.CORE, false);
        return record;
    }

    private static LauncherServices.InstallationStatus status(LauncherServices services,
                                                            InstallationRecord record)
            throws Exception {
        return services.loadHome().installationStatuses().get(record.id());
    }

    private LauncherServices services() {
        return new LauncherServices(temp.resolve("state").resolve("registry.json"),
                new RegistryStore(), new InstallationInspector(), (uri, accept) -> {
                    throw new AssertionError("locating a folder must not use the network");
                }, new JavaRuntime((command, directory, timeout, inherit) -> {
                    throw new AssertionError("locating a folder must not execute Java");
                }), new ApplicationLauncher(), new RootCoordinator(temp.resolve("coordination")));
    }

    private static Path suite(Path root, String version) throws Exception {
        Files.createDirectories(root);
        for (String folder : new String[] {"data", "mmconf", "lib", "docs"}) {
            Files.createDirectory(root.resolve(folder));
        }
        jar(root.resolve("MegaMek.jar"), version, null);
        return root;
    }

    private static void jar(Path file, String version, String extraEntry) throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "megamek.MegaMek");
        manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, version);
        try (var output = new JarOutputStream(Files.newOutputStream(file), manifest)) {
            if (extraEntry != null) {
                output.putNextEntry(new JarEntry(extraEntry));
                output.closeEntry();
            }
            output.finish();
        }
    }

    private static void deleteTree(Path root) throws Exception {
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
