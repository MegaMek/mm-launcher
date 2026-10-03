/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.megamek.launcher.gui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.megamek.launcher.operation.OperationContext;
import org.megamek.launcher.operation.OperationPhase;
import org.megamek.launcher.operation.OperationType;
import org.megamek.launcher.operation.ProgressUnit;
import org.megamek.launcher.update.LauncherSelfUpdate;

import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@Tag("gui-smoke")
@Tag("native-gui")
class LauncherUpdateDialogTest {
    private JFrame owner() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "actual dialog test requires a display");
        return SwingTestSupport.onEdt(() -> {
            JFrame frame = new JFrame("Launcher update test");
            frame.setSize(600, 400);
            frame.setVisible(true);
            return frame;
        });
    }

    private void confirmation(boolean accept, String notice) throws Exception {
        JFrame owner = owner();
        CompletableFuture<Boolean> answer = new CompletableFuture<>();
        LauncherSelfUpdate.Candidate candidate = new LauncherSelfUpdate.Candidate("v0.1.1", "0.1.1",
                "MegaMek-Launcher-0.1.1-windows-x64.msi",
                URI.create("https://github.com/MegaMek/mm-launcher/releases/download/v0.1.1/"
                        + "MegaMek-Launcher-0.1.1-windows-x64.msi"), 42, "a".repeat(64));
        SwingUtilities.invokeLater(() -> {
            try {
                answer.complete(LauncherFrame.confirmLauncherUpdate(owner, GuiScale.DEFAULT, candidate, notice));
            } catch (Throwable error) {
                answer.completeExceptionally(error);
            }
        });
        try {
            LauncherAlertDialog dialog = SwingTestSupport.await("themed launcher update confirmation",
                    () -> SwingTestSupport.showingWindow(owner, LauncherAlertDialog.class));
            assertEquals(1, SwingTestSupport.countButtonText(dialog, "Update now"));
            assertEquals(1, SwingTestSupport.countButtonText(dialog, "Cancel"));
            assertEquals(0, SwingTestSupport.countButtonText(dialog, "Yes"));
            SwingTestSupport.onEdt(() -> {
                assertEquals(FirstLaunchPanel.BACKGROUND, dialog.getContentPane().getBackground());
                JLabel body = SwingTestSupport.find(dialog, "launcherAlertMessage", JLabel.class);
                String expected = "Version 0.1.1 is available.\n\nClose any running games before updating.";
                if (notice != null) expected = notice + "\n\n" + expected;
                assertEquals(expected, dialog.getAccessibleContext().getAccessibleDescription());
                assertEquals(expected.replaceAll("\\s+", " "),
                        body.getText().replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim());
                return null;
            });
            SwingTestSupport.click(dialog, accept ? "launcherAlertButton1" : "launcherAlertButton0");
            assertEquals(accept, answer.get(8, TimeUnit.SECONDS));
            SwingTestSupport.awaitCondition("confirmation dismissed",
                    () -> SwingTestSupport.showingWindow(owner, LauncherAlertDialog.class) == null);
        } finally {
            SwingTestSupport.dispose(owner);
        }
    }

    @Test void oneThemedConfirmationAuthorizesTheWholeUpdate() throws Exception { confirmation(true, null); }
    @Test void cancellingDoesNotAuthorizeTheUpdate() throws Exception { confirmation(false, null); }
    @Test void actionableWarningPrecedesTheConciseUpdatePrompt() throws Exception {
        confirmation(true, "Windows requires a computer restart.");
    }

    @Test void progressShowsDownloadedBytesAndDisablesCancellationAtInstallerHandoff(@TempDir Path temp)
            throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "actual progress test requires a display");
        LauncherFrame owner = SwingTestSupport.onEdt(() -> {
            LauncherFrame frame = new LauncherFrame(new LauncherServices(temp.resolve("progress-registry.json")));
            frame.setVisible(true);
            return frame;
        });
        try {
            OperationProgressDialog progress = SwingTestSupport.onEdt(() -> {
                OperationProgressDialog dialog = new OperationProgressDialog(owner,
                        "Updating launcher", "launcherUpdateTestLog", () -> {});
                dialog.setVisible(true);
                return dialog;
            });
            OperationContext context = new OperationContext(OperationType.LAUNCHER_UPDATE, progress::onProgress);
            SwingTestSupport.onEdt(() -> { progress.bind(context); return null; });
            context.progress(OperationPhase.DOWNLOAD, 10_000_000, 20_000_000, ProgressUnit.BYTES, "downloading MSI");
            SwingTestSupport.awaitCondition("launcher download byte progress", () ->
                    SwingTestSupport.onEdt(() -> "50%".equals(SwingTestSupport.find(progress,
                            "operationProgressBar", JProgressBar.class).getString())));
            SwingTestSupport.onEdt(() -> {
                assertTrue(SwingTestSupport.find(progress, "operationCancelButton", JButton.class).isEnabled());
                assertEquals("Downloaded 10 MB of 20 MB",
                        SwingTestSupport.find(progress, "operationProgressDetail", JLabel.class).getText());
                return null;
            });
            context.phase(OperationPhase.APPLY, "Preparing Windows Installer");
            context.enterFinalization("Windows Installer handoff has started.");
            SwingTestSupport.awaitCondition("noncancellable launcher handoff", () -> SwingTestSupport.onEdt(() ->
                    !SwingTestSupport.find(progress, "operationCancelButton", JButton.class).isEnabled()));
            SwingTestSupport.onEdt(() -> {
                assertTrue(SwingTestSupport.find(progress, "operationProgressDetail", JLabel.class)
                        .getText().contains("Finishing launcher update"));
                return null;
            });
            assertFalse(context.requestCancellation().accepted());
        } finally {
            SwingTestSupport.dispose(owner);
        }
    }
}
