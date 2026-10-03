/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.megamek.launcher.update;

import org.junit.jupiter.api.Test;
import org.megamek.launcher.operation.OperationCancelledException;
import org.megamek.launcher.operation.OperationContext;
import org.megamek.launcher.operation.OperationPhase;
import org.megamek.launcher.operation.OperationProgress;
import org.megamek.launcher.operation.OperationType;
import org.megamek.launcher.operation.ProgressUnit;
import org.megamek.launcher.release.ReleaseTransport;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class WindowsMsiDownloadTest {
    private final byte[] content = "synthetic MSI fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);

    private LauncherSelfUpdate.Candidate candidate() throws Exception {
        String name = "MegaMek-Launcher-0.1.1-windows-x64.msi";
        return new LauncherSelfUpdate.Candidate("v0.1.1", "0.1.1", name,
                URI.create("https://github.com/MegaMek/mm-launcher/releases/download/v0.1.1/" + name),
                content.length, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)));
    }

    private LauncherSelfUpdate updater(byte[] bytes, AtomicBoolean closed) {
        return new LauncherSelfUpdate((uri, accept) -> new ReleaseTransport.Response(200, Map.of(),
                new ByteArrayInputStream(bytes) {
                    @Override public void close() throws IOException {
                        closed.set(true);
                        super.close();
                    }
                }));
    }

    @Test void reportsByteProgressAndChecksMsiIdentityBeforeReturningOwnedFile() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        List<OperationProgress> events = new ArrayList<>();
        OperationContext context = new OperationContext(OperationType.LAUNCHER_UPDATE, events::add);
        LauncherSelfUpdate updater = updater(content, closed);
        AtomicBoolean identityChecked = new AtomicBoolean();
        Path msi = updater.stage(candidate(), "0.1.0", context, (file, version) -> {
            assertEquals("0.1.1", version);
            assertArrayEquals(content, Files.readAllBytes(file));
            assertEquals(OperationPhase.VERIFY, context.latest().phase());
            identityChecked.set(true);
        });
        try {
            assertTrue(identityChecked.get());
            assertTrue(closed.get());
            assertTrue(events.stream().anyMatch(event -> event.phase() == OperationPhase.DOWNLOAD
                    && event.unit() == ProgressUnit.BYTES && event.completed() == content.length
                    && event.total() == content.length));
        } finally {
            updater.discard(msi);
        }
        assertFalse(Files.exists(msi.getParent()));
    }

    @Test void cancellationDuringDownloadStopsBeforeIdentityOrInstallerHandoff() throws Exception {
        AtomicBoolean closed = new AtomicBoolean();
        AtomicReference<OperationContext> holder = new AtomicReference<>();
        OperationContext context = new OperationContext(OperationType.LAUNCHER_UPDATE, event -> {
            if (event.phase() == OperationPhase.DOWNLOAD && event.completed() > 0)
                holder.get().requestCancellation();
        });
        holder.set(context);
        assertThrows(OperationCancelledException.class, () -> updater(content, closed)
                .stage(candidate(), "0.1.0", context,
                        (file, version) -> fail("cancelled download cannot verify or hand off MSI")));
        assertTrue(closed.get());
    }

    @Test void failedIdentityAndCancellationAfterVerificationDiscardOnlyStagedFiles() throws Exception {
        AtomicReference<Path> staged = new AtomicReference<>();
        OperationContext context = new OperationContext(OperationType.LAUNCHER_UPDATE);
        assertThrows(IOException.class, () -> updater(content, new AtomicBoolean())
                .stage(candidate(), "0.1.0", context, (file, version) -> {
                    staged.set(file);
                    throw new IOException("wrong MSI identity");
                }));
        assertFalse(Files.exists(staged.get().getParent()));
        OperationContext cancelled = new OperationContext(OperationType.LAUNCHER_UPDATE);
        assertThrows(OperationCancelledException.class, () -> updater(content, new AtomicBoolean())
                .stage(candidate(), "0.1.0", cancelled, (file, version) -> {
                    staged.set(file);
                    cancelled.requestCancellation();
                }));
        assertFalse(Files.exists(staged.get().getParent()));
    }

    @Test void checksumAndPublishedSizeRemainMandatory() throws Exception {
        for (byte[] invalid : new byte[][]{new byte[content.length],
                new byte[content.length + 1]}) {
            assertThrows(IOException.class, () -> updater(invalid, new AtomicBoolean()).stage(candidate(),
                    "0.1.0", new OperationContext(OperationType.LAUNCHER_UPDATE),
                    (file, version) -> fail("bad bytes cannot reach MSI identity verification")));
        }
    }
}
