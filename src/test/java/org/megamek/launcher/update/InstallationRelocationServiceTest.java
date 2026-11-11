/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.megamek.launcher.update;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.megamek.launcher.channel.ChannelPreferenceStore;
import org.megamek.launcher.channel.FollowChannel;
import org.megamek.launcher.launch.RootCoordinator;
import org.megamek.launcher.onboarding.InstallationInspector;
import org.megamek.launcher.operation.OperationContext;
import org.megamek.launcher.operation.OperationType;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryData;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.release.OfficialRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;

class InstallationRelocationServiceTest {
    private static final OperationContext CONTEXT =
            OperationContext.none(OperationType.IMPORT_EXISTING);

    @TempDir Path temp;

    @Test
    void localSpellingFollowsTheRecordedCaseOverride() {
        List<CaseOverride> cases = List.of(new CaseOverride("docs", "Docs",
                List.of("Docs", "docs")));
        assertEquals("Docs/Guide.txt",
                InstallationRelocationService.localSpelling("docs/Guide.txt", cases));
        assertEquals("Docs", InstallationRelocationService.localSpelling("docs", cases));
        assertEquals("data/docs/x.txt",
                InstallationRelocationService.localSpelling("data/docs/x.txt", cases));
        assertEquals("docsx/a", InstallationRelocationService.localSpelling("docsx/a", cases));
    }

    @Test
    void locatesACopyThatKeptAnEarlierFolderCapitalization() throws Exception {
        Path registry = temp.resolve("state").resolve("registry.json");
        Files.createDirectories(registry.getParent());
        RegistryStore registries = new RegistryStore();
        InstallationInspector inspector = new InstallationInspector();
        Path original = suite(temp.resolve("games").resolve("MegaMek"));
        InstallationRecord record = registries.register(registry, "MegaMek", original, null);
        ReceiptStore receipts = new ReceiptStore();
        var build = new OwnershipPolicy().build(original, OfficialRepository.MEGAMEK, "v1.0.0");
        RegistryData data = registries.read(registry);
        OwnershipReceipt receipt = receipts.write(registry, data, record,
                OfficialRepository.MEGAMEK, "v1.0.0", "MegaMek-v1.0.0.tar.gz", 10,
                "sha256:" + "a".repeat(64), build);
        new ChannelPreferenceStore().initializeManaged(registry, record, receipt,
                FollowChannel.CORE, false);
        CurrentUpdateState initial = CurrentUpdateState.initial(receipt);
        new CurrentStateStore(receipts).write(registry, record, receipt,
                new CurrentUpdateState(initial.schemaVersion(),
                        initial.ownershipPolicyVersion(), initial.installationId(),
                        initial.canonicalRoot(), initial.registeredAt(), initial.repository(),
                        initial.tag(), initial.assetName(), initial.assetSize(),
                        initial.assetSha256(), initial.officialManifest(),
                        initial.excludedOfficialPaths(), List.of(),
                        List.of(new CaseOverride("docs", "Docs", List.of("Docs", "docs"))),
                        null, initial.protectedPackageFiles()));
        assertTrue(receipt.officialManifest().files().stream()
                .anyMatch(entry -> entry.path().equals("docs/Guide.txt")));

        Path renamed = original.resolveSibling("Renamed");
        Files.move(original, renamed);
        // The copy still uses the capitalization recorded before the official rename.
        Files.move(renamed.resolve("docs"), renamed.resolve("docs-tmp"));
        Files.move(renamed.resolve("docs-tmp"), renamed.resolve("Docs"));

        InstallationRecord located = new InstallationRelocationService(registry, registries,
                new RootCoordinator(temp.resolve("coordination")), inspector)
                .relocate(record, renamed, CONTEXT);

        assertEquals(renamed.toRealPath().toString(), located.canonicalRoot());
        assertEquals(List.of(located), registries.read(registry).installations());
    }

    @Test
    void stillRefusesAnUnrecordedCaseAlias() throws Exception {
        Path registry = temp.resolve("state").resolve("registry.json");
        Files.createDirectories(registry.getParent());
        RegistryStore registries = new RegistryStore();
        InstallationInspector inspector = new InstallationInspector();
        Path original = suite(temp.resolve("games").resolve("MegaMek"));
        InstallationRecord record = registries.register(registry, "MegaMek", original, null);
        ReceiptStore receipts = new ReceiptStore();
        var build = new OwnershipPolicy().build(original, OfficialRepository.MEGAMEK, "v1.0.0");
        OwnershipReceipt receipt = receipts.write(registry, registries.read(registry), record,
                OfficialRepository.MEGAMEK, "v1.0.0", "MegaMek-v1.0.0.tar.gz", 10,
                "sha256:" + "a".repeat(64), build);
        new ChannelPreferenceStore().initializeManaged(registry, record, receipt,
                FollowChannel.CORE, false);

        Path renamed = original.resolveSibling("Renamed");
        Files.move(original, renamed);
        Files.move(renamed.resolve("docs"), renamed.resolve("docs-tmp"));
        Files.move(renamed.resolve("docs-tmp"), renamed.resolve("Docs"));

        assertThrows(IOException.class, () -> new InstallationRelocationService(registry,
                registries, new RootCoordinator(temp.resolve("coordination")), inspector)
                .relocate(record, renamed, CONTEXT));
        assertEquals(List.of(record), registries.read(registry).installations());
    }

    private static Path suite(Path root) throws Exception {
        Files.createDirectories(root);
        for (String folder : new String[] {"data", "mmconf", "lib", "docs"}) {
            Files.createDirectory(root.resolve(folder));
        }
        Files.writeString(root.resolve("docs").resolve("Guide.txt"), "guide");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "megamek.MegaMek");
        manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, "1.0.0");
        try (var output = new JarOutputStream(Files.newOutputStream(
                root.resolve("MegaMek.jar")), manifest)) {
            output.finish();
        }
        return root;
    }
}
