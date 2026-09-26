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

package org.megamek.launcher.diagnostics;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.megamek.launcher.operation.OperationContext;
import org.megamek.launcher.operation.OperationOutcome;
import org.megamek.launcher.operation.OperationPhase;
import org.megamek.launcher.operation.OperationType;
import org.megamek.launcher.operation.ProgressUnit;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationLogStoreTest {
    @TempDir Path temp;

    @Test
    void persistsSanitizedStructuredErrorAcrossReopenedStore() throws Exception {
        Path registry = temp.resolve("registry.json");
        Path logs = temp.resolve("logs");
        OperationLogStore store = new OperationLogStore(registry, logs);
        OperationLogStore.Collector collector = store.collector(List.of());
        OperationContext context = new OperationContext(OperationType.GUI_ERROR, collector);
        context.phase(OperationPhase.METADATA,
                "https://example.test/path?token=progress-secret#fragment");
        context.progress(OperationPhase.DOWNLOAD, 10, 20, ProgressUnit.BYTES,
                "Authorization: Bearer progress-bearer");

        JsonProcessingException parser = assertThrows(JsonProcessingException.class,
                () -> new ObjectMapper().readTree(
                        "{\"password\":\"config-secret\", broken"));
        IOException outer = new IOException(
                "invalid config {\"token\":\"outer-secret\"}", parser);
        outer.addSuppressed(new IOException(
                "password=hunter2 Bearer suppressed-secret "
                        + "https://downloads.test/pkg?signature=signed-secret#private"));
        context.finish(OperationOutcome.FAILED, "terminal error retained");
        Path written = collector.persist(outer);

        String bytes = Files.readString(written, StandardCharsets.UTF_8);
        for (String secret : List.of("progress-secret", "progress-bearer", "config-secret",
                "outer-secret", "hunter2", "suppressed-secret", "signed-secret", "private")) {
            assertFalse(bytes.contains(secret), secret);
        }
        assertTrue(bytes.contains("configuration source content omitted"));
        assertTrue(bytes.contains("java.io.IOException"));
        assertTrue(bytes.contains("terminal error retained"));
        assertTrue(bytes.contains("?[redacted]"));

        OperationLogStore reopened = new OperationLogStore(registry, logs);
        assertEquals(1, reopened.readAll().size());
        String viewer = reopened.renderForViewer();
        assertTrue(viewer.contains(context.id().toString()));
        assertTrue(viewer.contains("Review before sharing"));
    }

    @Test
    void retentionKeepsTerminalErrorUnknownFilesAndLinksWhileCappingOwnedLogs()
            throws Exception {
        Path registry = temp.resolve("registry.json");
        Path logs = temp.resolve("logs");
        OperationLogStore store = new OperationLogStore(registry, logs);
        Files.createDirectory(logs);
        Path unknown = Files.writeString(logs.resolve("user-notes.txt"), "keep me");
        Path linkTarget = Files.createDirectory(temp.resolve("link-target"));
        Path linkedName = logs.resolve("operation-" + UUID.randomUUID() + ".json");
        createDirectoryLink(linkedName, linkTarget);

        String finalId = null;
        for (int index = 0; index < OperationLogStore.MAX_LOGS + 3; index++) {
            OperationLogStore.Collector collector = store.collector(List.of());
            OperationContext context = new OperationContext(OperationType.UPDATE_PREVIEW,
                    collector);
            context.phase(OperationPhase.METADATA, "start");
            for (int item = 0; item < 600; item++) {
                context.progress(OperationPhase.DOWNLOAD, item, 600, ProgressUnit.BYTES,
                        "bounded progress " + item);
            }
            IOException terminal = new IOException("terminal-marker-" + index);
            context.finish(OperationOutcome.FAILED, "terminal-marker-" + index);
            collector.persist(terminal);
            finalId = context.id().toString();
        }

        List<OperationLogStore.StoredLog> retained = store.readAll();
        String expectedFinalId = finalId;
        assertEquals(OperationLogStore.MAX_LOGS, retained.size());
        assertTrue(retained.stream().anyMatch(log ->
                log.record().operationId().equals(expectedFinalId)
                        && log.record().errorDetails().contains("terminal-marker-22")
                        && log.record().progressTruncated()));
        assertTrue(Files.exists(unknown));
        assertTrue(Files.exists(linkedName, java.nio.file.LinkOption.NOFOLLOW_LINKS));
        Files.delete(linkedName);
    }

    @Test
    void unsafePlacementAndCorruptRegistryFailWithoutCreatingDestinationOrMaskingError()
            throws Exception {
        Path registry = temp.resolve("registry.json");
        Path destination = temp.resolve("fresh-destination");
        OperationLogStore overlapping = new OperationLogStore(
                registry, destination.resolve("logs"));
        OperationLogStore.Collector collector = overlapping.collector(List.of(destination));
        OperationContext context = new OperationContext(OperationType.FRESH_INSTALL, collector);
        context.phase(OperationPhase.METADATA, "start");
        IOException original = new IOException("original install failure");
        context.finish(OperationOutcome.FAILED, "failed");
        IOException placement = assertThrows(IOException.class,
                () -> collector.persist(original));
        assertTrue(placement.getMessage().contains("overlaps"));
        assertFalse(Files.exists(destination),
                "placement rejection must precede log-directory creation");
        assertEquals("original install failure", original.getMessage());

        Files.writeString(registry, "{broken");
        Path corruptLogs = temp.resolve("corrupt-logs");
        OperationLogStore corrupt = new OperationLogStore(registry, corruptLogs);
        OperationLogStore.Collector corruptCollector = corrupt.collector(List.of());
        OperationContext corruptContext = new OperationContext(OperationType.GUI_ERROR,
                corruptCollector);
        corruptContext.phase(OperationPhase.METADATA, "start");
        corruptContext.finish(OperationOutcome.FAILED, "failed");
        assertThrows(IOException.class, () -> corruptCollector.persist(original));
        assertFalse(Files.exists(corruptLogs));

        Path blockedDirectory = Files.writeString(temp.resolve("not-a-directory"), "owned");
        OperationLogStore blocked = new OperationLogStore(
                temp.resolve("missing-registry.json"), blockedDirectory);
        OperationLogStore.Collector blockedCollector = blocked.collector(List.of());
        OperationContext blockedContext = new OperationContext(OperationType.UPDATE_APPLY,
                blockedCollector);
        blockedContext.phase(OperationPhase.APPLY, "committed success");
        blockedContext.enterFinalization("committed");
        blockedContext.finish(OperationOutcome.SUCCEEDED, "committed success");
        assertThrows(IOException.class, () -> blockedCollector.persist(null));
        assertEquals("owned", Files.readString(blockedDirectory),
                "logging failure must not alter an unrelated result or object");
    }

    private static void createDirectoryLink(Path link, Path target) throws Exception {
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            Process process = new ProcessBuilder(System.getenv("ComSpec"), "/d", "/c", "mklink",
                    "/J", link.toString(), target.toString()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(),
                    StandardCharsets.UTF_8);
            if (process.waitFor() != 0) {
                throw new IOException("could not create disposable log-retention junction: "
                        + output);
            }
        } else {
            Files.createSymbolicLink(link, target);
        }
    }
}
