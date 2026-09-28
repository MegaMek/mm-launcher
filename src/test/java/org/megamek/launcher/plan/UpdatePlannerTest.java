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

package org.megamek.launcher.plan;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.megamek.launcher.manifest.FileEntry;
import org.megamek.launcher.manifest.Manifest;
import org.megamek.launcher.manifest.ManifestException;
import org.megamek.launcher.manifest.ManifestReader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdatePlannerTest {
    private static final List<String> PROTECTED = List.of("saves", "campaigns", "userdata");

    @TempDir
    Path installation;

    @Test
    void plansEveryOwnershipOutcomeWithoutChangingInstallation() throws Exception {
        write("keep.txt", "target");
        write("replace.txt", "old");
        write("modified.txt", "mine");
        write("remove.txt", "old-remove");
        write("obsolete-modified.txt", "mine");
        write("collision.txt", "unknown");
        write("saves/campaign.sav", "personal");

        Manifest baseline = manifest("1", List.of(
                file("keep.txt", "old"),
                file("replace.txt", "old"),
                file("modified.txt", "old"),
                file("remove.txt", "old-remove"),
                file("obsolete-modified.txt", "old-remove"),
                file("already-gone.txt", "old")));
        Manifest target = manifest("2", List.of(
                file("keep.txt", "target"),
                file("replace.txt", "target"),
                file("modified.txt", "target"),
                file("collision.txt", "target"),
                file("missing.txt", "target")));

        List<Decision> decisions = new UpdatePlanner().plan(baseline, target, installation);

        assertEquals(List.of(
                new Decision(Action.KEEP, "already-gone.txt", "obsolete managed file is already absent"),
                new Decision(Action.SKIP, "collision.txt", "target collides with an untracked local file"),
                new Decision(Action.KEEP, "keep.txt", "local file already matches target SHA-256"),
                new Decision(Action.ADD, "missing.txt", "target managed file is missing"),
                new Decision(Action.SKIP, "modified.txt", "local file differs from both baseline and target"),
                new Decision(Action.SKIP, "obsolete-modified.txt", "obsolete local file was modified"),
                new Decision(Action.REMOVE, "remove.txt", "obsolete local file matches baseline"),
                new Decision(Action.REPLACE, "replace.txt", "local file matches baseline; target differs")
        ), decisions);
        assertEquals("old", Files.readString(installation.resolve("replace.txt")));
        assertEquals("old-remove", Files.readString(installation.resolve("remove.txt")));
        assertEquals("personal", Files.readString(installation.resolve("saves/campaign.sav")));
        assertTrue(Files.notExists(installation.resolve("missing.txt")));
    }

    @Test
    void modifiedOfficialRuntimeIsReplacedOrRemovedWhileModifiedDataIsSkipped()
            throws Exception {
        write("MegaMek.jar", "damaged");
        write("lib/core.jar", "damaged");
        write("lib/obsolete.jar", "modified");
        write("data/personal.txt", "personal");
        Manifest baseline = manifest("1", List.of(
                file("MegaMek.jar", "old"), file("lib/core.jar", "old"),
                file("lib/obsolete.jar", "old"), file("lib/missing.txt", "old"),
                file("data/personal.txt", "old")));
        Manifest target = manifest("2", List.of(
                file("MegaMek.jar", "new"), file("lib/core.jar", "new"),
                file("lib/missing.txt", "new"), file("data/personal.txt", "new")));

        List<Decision> decisions = new UpdatePlanner().plan(baseline, target, installation);
        assertEquals(Map.of("MegaMek.jar", Action.REPLACE, "lib/core.jar", Action.REPLACE,
                        "lib/missing.txt", Action.ADD, "lib/obsolete.jar", Action.REMOVE,
                        "data/personal.txt", Action.SKIP),
                decisions.stream().collect(java.util.stream.Collectors.toMap(
                        Decision::path, Decision::action)));
        assertEquals("damaged", Files.readString(installation.resolve("MegaMek.jar")));
    }

    @Test
    void refusesLinkInManagedPath() throws Exception {
        Path outside = Files.createDirectory(installation.resolve("real-directory"));
        try {
            Files.createSymbolicLink(installation.resolve("linked"), outside);
        } catch (UnsupportedOperationException | IOException e) {
            return;
        }
        Manifest baseline = manifest("1", List.of());
        Manifest target = manifest("2", List.of(file("linked/payload.txt", "target")));

        ManifestException error = assertThrows(ManifestException.class,
                () -> new UpdatePlanner().plan(baseline, target, installation));
        assertTrue(error.getMessage().contains("link/reparse escape refused"));
    }

    @Test
    void previewSkipsWholeCaseChangedPrefixBeforeInspectingLocalAliases() throws Exception {
        Files.createDirectory(installation.resolve("DATA"));
        FileEntry oldIcon = file("data/Icons/old.png", "old");
        FileEntry obsolete = file("lib/obsolete.txt", "obsolete");
        FileEntry newIcon = file("data/icons/new.png", "new");
        FileEntry added = file("docs/added.txt", "added");
        Manifest baseline = manifest("1", List.of(oldIcon, obsolete));
        Manifest target = manifest("2", List.of(newIcon, added));
        ManifestReader.PreviewPairValidation validation =
                new ManifestReader().validatePreviewPair(baseline, target);

        List<Decision> decisions = new UpdatePlanner().planPreview(
                baseline, target, installation, validation);

        assertEquals(List.of(
                new Decision(Action.SKIP, "data/icons/new.png",
                        "case-only rename/capitalization; preserved; rename unsupported "
                                + "(baseline prefix: data/Icons; target prefix: data/icons)"),
                new Decision(Action.SKIP, "data/Icons/old.png",
                        "case-only rename/capitalization; preserved; rename unsupported "
                                + "(baseline prefix: data/Icons; target prefix: data/icons)"),
                new Decision(Action.ADD, "docs/added.txt", "target managed file is missing"),
                new Decision(Action.KEEP, "lib/obsolete.txt",
                        "obsolete managed file is already absent")
        ), decisions);

        Manifest reversedBaseline = manifest("1", List.of(obsolete, oldIcon));
        Manifest reversedTarget = manifest("2", List.of(added, newIcon));
        assertEquals(decisions, new UpdatePlanner().planPreview(
                reversedBaseline, reversedTarget, installation,
                new ManifestReader().validatePreviewPair(reversedBaseline, reversedTarget)));
    }

    @Test
    void previewKeepsUnrelatedLocalCaseAliasGuardStrict() throws Exception {
        Files.createDirectory(installation.resolve("LIB"));
        Files.writeString(installation.resolve("LIB/expected.txt"), "local");
        Manifest baseline = manifest("1", List.of(file("data/Icons/old.png", "old")));
        Manifest target = manifest("2", List.of(
                file("data/icons/new.png", "new"),
                file("lib/expected.txt", "target")));
        ManifestReader.PreviewPairValidation validation =
                new ManifestReader().validatePreviewPair(baseline, target);

        ManifestException error = assertThrows(ManifestException.class,
                () -> new UpdatePlanner().planPreview(
                        baseline, target, installation, validation));
        assertTrue(error.getMessage().contains("local case alias collision"));
        assertTrue(error.getMessage().contains("expected lib"));
    }

    private void write(String path, String contents) throws IOException {
        Path destination = installation.resolve(path);
        Files.createDirectories(destination.getParent());
        Files.writeString(destination, contents, StandardCharsets.UTF_8);
    }

    private static Manifest manifest(String release, List<FileEntry> files) {
        return new Manifest(ManifestReader.SCHEMA_VERSION, "MegaMek", "standalone", release,
                PROTECTED, files);
    }

    private static FileEntry file(String path, String contents) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return new FileEntry(path, HexFormat.of().formatHex(
                digest.digest(contents.getBytes(StandardCharsets.UTF_8))));
    }
}
