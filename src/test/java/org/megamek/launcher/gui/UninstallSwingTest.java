/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.megamek.launcher.gui;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.megamek.launcher.channel.ChannelPreferenceStore;
import org.megamek.launcher.channel.FollowChannel;
import org.megamek.launcher.launch.ApplicationLauncher;
import org.megamek.launcher.launch.JavaRuntime;
import org.megamek.launcher.launch.RootCoordinator;
import org.megamek.launcher.onboarding.InstallationInspector;
import org.megamek.launcher.operation.OperationContext;
import org.megamek.launcher.operation.OperationPhase;
import org.megamek.launcher.operation.OperationType;
import org.megamek.launcher.operation.ProgressUnit;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.release.OfficialRepository;
import org.megamek.launcher.update.OwnershipPolicy;
import org.megamek.launcher.update.ReceiptStore;
import org.megamek.launcher.update.UninstallService;

import javax.swing.JLabel;
import javax.swing.JProgressBar;
import javax.swing.MenuSelectionManager;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@Tag("native-gui")
class UninstallSwingTest {
    @TempDir Path temp;

    @Test
    void confirmationCallsSingleScanFacadeOnceAndCancellationDoesNotStartRemoval() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "actual dialogs require a display");
        AtomicInteger calls = new AtomicInteger();
        LauncherServices services = new LauncherServices(temp.resolve("registry.json"), new RegistryStore(),
                new InstallationInspector(), (uri, accept) -> {
                    throw new AssertionError("uninstall must not use the network");
                }, new JavaRuntime((command, directory, timeout, inherit) -> {
                    throw new AssertionError("uninstall must not execute Java");
                }), new ApplicationLauncher(), new RootCoordinator(temp.resolve("coordination"))) {
            @Override public UninstallService.Plan planUninstall(
                    InstallationRecord record, OperationContext context) {
                throw new AssertionError("the GUI must not prepare a separate uninstall plan");
            }

            @Override public UninstallService.Result uninstall(
                    UninstallService.Plan plan, OperationContext context) {
                throw new AssertionError("the GUI must not execute a separately prepared plan");
            }

            @Override public UninstallService.Result uninstall(
                    InstallationRecord record, OperationContext context)
                    throws IOException, InterruptedException,
                    org.megamek.launcher.manifest.ManifestException {
                calls.incrementAndGet();
                return super.uninstall(record, context);
            }
        };
        Path root = suite("managed");
        Files.createDirectory(root.resolve("custom"));
        Path custom = Files.writeString(root.resolve("custom/user.txt"), "keep");
        InstallationRecord record = services.register("Managed installation", root);
        InstallationRecord retained = services.register("Retained installation", suite("retained"));
        var build = new OwnershipPolicy().build(root, OfficialRepository.MEGAMEK, "v1.0.0");
        var receipt = new ReceiptStore().write(services.registry(), services.readRegistry(), record,
                OfficialRepository.MEGAMEK, "v1.0.0", "MegaMek-v1.0.0.tar.gz", 10,
                "sha256:" + "a".repeat(64), build);
        new ChannelPreferenceStore().initializeManaged(
                services.registry(), record, receipt, FollowChannel.MILESTONE, false);
        LauncherFrame frame = SwingTestSupport.onEdt(() -> new LauncherFrame(services));
        try {
            SwingTestSupport.onEdt(() -> { frame.showWindow(); return null; });
            SwingTestSupport.click(frame, "manageInstallationsButton");
            var menu = SwingTestSupport.installationMenu(frame, record.id());
            SwingTestSupport.startClickText(menu, "Uninstall…");
            var confirmation = SwingTestSupport.dialog(frame, "Uninstall");
            SwingTestSupport.click(confirmation, "launcherAlertButton0");
            assertEquals(0, calls.get());
            assertTrue(Files.exists(root.resolve("MegaMek.jar")));
            assertEquals(2, services.readRegistry().installations().size());
            SwingTestSupport.onEdt(() -> {
                MenuSelectionManager.defaultManager().clearSelectedPath();
                return null;
            });

            menu = SwingTestSupport.installationMenu(frame, record.id());
            SwingTestSupport.startClickText(menu, "Uninstall…");
            confirmation = SwingTestSupport.dialog(frame, "Uninstall");
            SwingTestSupport.click(confirmation, "launcherAlertButton1");
            SwingTestSupport.awaitCondition("uninstall and refreshed Home", () ->
                    services.readRegistry().installations().equals(List.of(retained))
                            && SwingTestSupport.find(frame, "launch-megamek-button") != null
                            && SwingTestSupport.showingWindow(frame, OperationProgressDialog.class) == null);
            assertEquals(1, calls.get());
            assertFalse(Files.exists(root.resolve("MegaMek.jar")));
            assertEquals("keep", Files.readString(custom));
            assertTrue(Files.exists(Path.of(retained.canonicalRoot()).resolve("MegaMek.jar")));
            assertEquals(1, SwingTestSupport.onEdt(() -> Arrays.stream(frame.getOwnedWindows())
                    .filter(OperationProgressDialog.class::isInstance).count()));
        } finally {
            SwingTestSupport.dispose(frame);
        }
    }

    @Test
    void progressShowsCheckingThenRemovingInTheSameDialog() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "actual progress controls require a display");
        LauncherFrame frame = SwingTestSupport.onEdt(() ->
                new LauncherFrame(new LauncherServices(temp.resolve("progress-registry.json"))));
        OperationProgressDialog dialog = SwingTestSupport.onEdt(() -> {
            frame.setVisible(true);
            var progress = new OperationProgressDialog(frame, "Uninstalling", "uninstallFixture", () -> {});
            progress.setVisible(true);
            return progress;
        });
        OperationContext context = new OperationContext(OperationType.UNINSTALL, dialog::onProgress);
        try {
            SwingTestSupport.onEdt(() -> { dialog.bind(context); return null; });
            context.progress(OperationPhase.PLAN, 12, 100, ProgressUnit.FILES,
                    "Compared official application files");
            SwingTestSupport.onEdt(() -> {
                dialog.flush();
                assertEquals("Checking installation files", SwingTestSupport.find(
                        dialog, "operationPhaseLabel", JLabel.class).getText());
                assertEquals("12 / 100 files", SwingTestSupport.find(
                        dialog, "operationProgressBar", JProgressBar.class).getString());
                assertTrue(SwingTestSupport.find(dialog, "operationCancelButton").isVisible());
                return null;
            });
            context.enterFinalization("File moves must finish or roll back.");
            context.progress(OperationPhase.UNINSTALL, 3, 80, ProgressUnit.FILES,
                    "Secured official application files");
            SwingTestSupport.onEdt(() -> {
                dialog.flush();
                assertEquals("Removing official files", SwingTestSupport.find(
                        dialog, "operationPhaseLabel", JLabel.class).getText());
                assertEquals("3 / 80 files", SwingTestSupport.find(
                        dialog, "operationProgressBar", JProgressBar.class).getString());
                assertFalse(SwingTestSupport.find(dialog, "operationCancelButton").isVisible());
                return null;
            });
        } finally {
            SwingTestSupport.dispose(frame);
        }
    }

    private Path suite(String name) throws Exception {
        Path root = Files.createDirectory(temp.resolve(name));
        for (String directory : List.of("data", "mmconf", "lib", "docs")) {
            Files.createDirectory(root.resolve(directory));
        }
        Files.writeString(root.resolve("docs/guide.txt"), "official");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "megamek.MegaMek");
        manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, "1.0.0");
        try (var output = new JarOutputStream(Files.newOutputStream(root.resolve("MegaMek.jar")), manifest)) {
            output.finish();
        }
        return root;
    }
}
