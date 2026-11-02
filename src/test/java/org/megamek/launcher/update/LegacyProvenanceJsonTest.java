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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.release.OfficialRepository;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.UnaryOperator;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyProvenanceJsonTest {
    @TempDir Path temp;
    private final ObjectMapper json = new ObjectMapper();
    private final RegistryStore store = new RegistryStore();
    private final ReceiptStore receipts = new ReceiptStore();
    private final CurrentStateStore states = new CurrentStateStore(receipts);

    @Test
    void receiptAndCurrentStateWithoutProtectedPackageFilesStillLoad() throws Exception {
        Path registry = temp.resolve("registry.json");
        InstallationRecord record = install(registry);
        OwnershipReceipt written = receipts.read(registry, store.read(registry), record);
        states.write(registry, record, written, CurrentUpdateState.initial(written));
        Path receiptFile = receipts.receiptPath(registry, record.id());
        Path stateFile = states.path(registry, record.id());
        edit(receiptFile, node -> remove(node, "protectedPackageFiles"));
        edit(stateFile, node -> remove(node, "protectedPackageFiles"));

        OwnershipReceipt receipt = receipts.read(registry, store.read(registry), record);
        CurrentUpdateState state = states.read(registry, store.read(registry), record, receipt);

        assertTrue(receipt.protectedPackageFiles().isEmpty());
        assertTrue(state.protectedPackageFiles().isEmpty());
        assertEquals(written.officialManifest(), receipt.officialManifest());
    }

    @Test
    void legacyDefaultingKeepsOtherStrictnessChecks() throws Exception {
        Path registry = temp.resolve("strict-registry.json");
        InstallationRecord record = install(registry);
        OwnershipReceipt written = receipts.read(registry, store.read(registry), record);
        states.write(registry, record, written, CurrentUpdateState.initial(written));

        for (Path file : new Path[] {receipts.receiptPath(registry, record.id()),
                states.path(registry, record.id())}) {
            edit(file, node -> remove(node, "protectedPackageFiles"));
            byte[] legacy = Files.readAllBytes(file);
            String text = Files.readString(file);

            edit(file, node -> remove(node, "installationId"));
            assertUnreadable(registry, record);
            Files.write(file, legacy);

            edit(file, node -> node.put("unexpectedField", true));
            assertUnreadable(registry, record);
            Files.write(file, legacy);

            Files.writeString(file, text.replaceFirst("\\{",
                    "{\"schemaVersion\": 1, "));
            assertUnreadable(registry, record);

            Files.writeString(file, text + " {}");
            assertUnreadable(registry, record);
            Files.write(file, legacy);
        }
        OwnershipReceipt receipt = receipts.read(registry, store.read(registry), record);
        states.read(registry, store.read(registry), record, receipt);
    }

    private void assertUnreadable(Path registry, InstallationRecord record) {
        assertThrows(IOException.class, () -> {
            OwnershipReceipt receipt = receipts.read(registry, store.read(registry), record);
            states.read(registry, store.read(registry), record, receipt);
        });
    }

    private InstallationRecord install(Path registry) throws Exception {
        Path root = Files.createDirectories(temp.resolve(registry.getFileName() + "-root"));
        for (String directory : new String[] {"data", "mmconf", "lib", "docs"}) {
            Files.createDirectories(root.resolve(directory));
        }
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "megamek.MegaMek");
        try (JarOutputStream output = new JarOutputStream(
                Files.newOutputStream(root.resolve("MegaMek.jar")), manifest)) {
            output.putNextEntry(new JarEntry("megamek/Version.properties"));
            output.write("major=1\nminor=0\npatch=0\n".getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        Files.writeString(root.resolve("lib/runtime.txt"), "runtime");
        Files.writeString(root.resolve("README.md"), "readme");
        InstallationRecord record = store.register(registry, "Legacy", root, null);
        OwnershipPolicy.Build build =
                new OwnershipPolicy().build(root, OfficialRepository.MEGAMEK, "v1");
        receipts.write(registry, store.read(registry), record, OfficialRepository.MEGAMEK, "v1",
                "MegaMek-v1.tar.gz", 10, "sha256:" + "a".repeat(64), build);
        return record;
    }

    private void edit(Path file, UnaryOperator<ObjectNode> change) throws IOException {
        ObjectNode node = (ObjectNode) json.readTree(file.toFile());
        json.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), change.apply(node));
    }

    private static ObjectNode remove(ObjectNode node, String field) {
        assertTrue(node.remove(field) != null, field);
        return node;
    }
}
