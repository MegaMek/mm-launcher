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
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.release.OfficialRepository;
import org.megamek.launcher.update.OwnershipPolicy;
import org.megamek.launcher.update.ReceiptStore;
import org.megamek.launcher.update.UninstallService;

import javax.swing.JDialog;
import javax.swing.JLabel;
import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

@Tag("native-gui")
class MissingInstallationSwingTest {
    @TempDir Path temp;

    @Test
    void missingCardOffersStyledConfirmationAndCancelKeepsRegistration() throws Exception {
        missingCard(false);
    }

    @Test
    void missingCardWithInterruptedUninstallCanBeRemovedInsteadOfRecoveringDeletedFiles() throws Exception {
        missingCard(true);
    }

    private void missingCard(boolean interrupted) throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "actual dialogs require a display");
        Fixture fixture = fixture();
        if (interrupted) {
            var build = new OwnershipPolicy().build(
                    fixture.root, OfficialRepository.MEGAMEK, "v1.0.0");
            var receipt = new ReceiptStore().write(fixture.services.registry(),
                    fixture.services.readRegistry(), fixture.removed, OfficialRepository.MEGAMEK,
                    "v1.0.0", "MegaMek-v1.0.0.tar.gz", 10, "sha256:" + "a".repeat(64), build);
            new ChannelPreferenceStore().initializeManaged(fixture.services.registry(),
                    fixture.removed, receipt, FollowChannel.MILESTONE, false);
            var service = new UninstallService(fixture.services.registry(), fixture.coordinator);
            var plan = service.plan(fixture.removed, OperationContext.none(OperationType.UNINSTALL));
            OperationContext interruptedContext = new OperationContext(OperationType.UNINSTALL, event -> {
                if (event.phase() == OperationPhase.UNINSTALL) throw new SimulatedCrash();
            });
            assertThrows(SimulatedCrash.class, () -> service.uninstall(
                    plan, UninstallService.CONFIRMATION, interruptedContext));
        }
        deleteRoot(fixture.root);
        LauncherFrame frame = SwingTestSupport.onEdt(() -> new LauncherFrame(fixture.services));
        try {
            SwingTestSupport.onEdt(() -> { frame.showWindow(); return null; });
            SwingTestSupport.click(frame, "manageInstallationsButton");
            SwingTestSupport.await("missing installation card", () ->
                    SwingTestSupport.find(frame, "installationStatus-" + fixture.removed.id(), JLabel.class));
            SwingTestSupport.startClick(frame, "installationMenuButton-" + fixture.removed.id());
            JDialog notice = SwingTestSupport.dialog(frame, "Installation not found");
            SwingTestSupport.onEdt(() -> {
                assertEquals(FirstLaunchPanel.BACKGROUND, notice.getContentPane().getBackground());
                assertEquals("The folder for \"Old installation\" no longer exists on disk.\n\n"
                                + "Remove this installation from the launcher?",
                        notice.getAccessibleContext().getAccessibleDescription());
                assertEquals(1, SwingTestSupport.countButtonText(notice, "Cancel"));
                assertEquals(1, SwingTestSupport.countButtonText(notice, "Remove from launcher"));
                return null;
            });
            SwingTestSupport.click(notice, "launcherAlertButton0");
            assertEquals(2, fixture.services.readRegistry().installations().size());
            SwingTestSupport.startClick(frame, "installationMenuButton-" + fixture.removed.id());
            JDialog retryNotice = SwingTestSupport.dialog(frame, "Installation not found");
            SwingTestSupport.click(retryNotice, "launcherAlertButton1");
            SwingTestSupport.awaitCondition("registration removal and refreshed Home", () ->
                    fixture.services.readRegistry().installations().equals(List.of(fixture.retained))
                            && SwingTestSupport.find(frame, "launch-megamek-button") != null);
            assertFalse(Files.exists(fixture.root));
            assertTrue(Files.exists(Path.of(fixture.retained.canonicalRoot()).resolve("MegaMek.jar")));
            assertFalse(Files.exists(UninstallService.pendingJournalPath(
                    fixture.services.registry(), fixture.removed.id())));
        } finally {
            SwingTestSupport.dispose(frame);
        }
    }

    @Test
    void folderDeletedAfterHomeWasRenderedOffersRemovalOnLaunchWithoutStartingJava() throws Exception {
        staleAction(true);
    }

    @Test
    void folderDeletedAfterInstallationsWereRenderedIsCheckedAgainBeforeOpeningMore() throws Exception {
        staleAction(false);
    }

    private void staleAction(boolean launch) throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "actual dialogs require a display");
        Fixture fixture = fixture();
        LauncherFrame frame = SwingTestSupport.onEdt(() -> new LauncherFrame(fixture.services));
        try {
            SwingTestSupport.onEdt(() -> { frame.showWindow(); return null; });
            SwingTestSupport.await("initial Home", () ->
                    SwingTestSupport.find(frame, "launch-megamek-button", javax.swing.JButton.class));
            if (!launch) {
                SwingTestSupport.click(frame, "manageInstallationsButton");
                SwingTestSupport.await("initial installation card", () -> SwingTestSupport.find(
                        frame, "installationMenuButton-" + fixture.removed.id(), javax.swing.JButton.class));
            }
            deleteRoot(fixture.root);
            SwingTestSupport.startClick(frame, launch
                    ? "launch-megamek-button" : "installationMenuButton-" + fixture.removed.id());
            JDialog notice = SwingTestSupport.dialog(frame, "Installation not found");
            SwingTestSupport.click(notice, "launcherAlertButton1");
            SwingTestSupport.awaitCondition("deleted launch target removed", () ->
                    fixture.services.readRegistry().installations().equals(List.of(fixture.retained)));
            assertFalse(Files.exists(fixture.root));
        } finally {
            SwingTestSupport.dispose(frame);
        }
    }

    @Test
    void menuUsesTheCurrentCardWhenTheListIsRebuiltDuringFolderValidation() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "actual menus require a display");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        Fixture fixture = fixture(started, release, finished);
        LauncherFrame frame = SwingTestSupport.onEdt(() -> new LauncherFrame(fixture.services));
        try {
            SwingTestSupport.onEdt(() -> { frame.showWindow(); return null; });
            SwingTestSupport.click(frame, "manageInstallationsButton");
            var old = SwingTestSupport.await("initial More button", () ->
                    SwingTestSupport.find(frame, "installationMenuButton-" + fixture.removed.id(),
                            javax.swing.JButton.class));
            SwingTestSupport.startClick(frame, "installationMenuButton-" + fixture.removed.id());
            assertTrue(started.await(5, TimeUnit.SECONDS));
            SwingTestSupport.onEdt(() -> {
                var render = LauncherFrame.class.getDeclaredMethod("renderInstallationsPage");
                render.setAccessible(true);
                render.invoke(frame);
                assertNotSame(old, SwingTestSupport.find(
                        frame, "installationMenuButton-" + fixture.removed.id()));
                return null;
            });
            release.countDown();
            var menu = SwingTestSupport.await("popup on the replacement card", () ->
                    SwingTestSupport.showingInstallationMenu(frame, fixture.removed.id()));
            SwingTestSupport.onEdt(() -> {
                assertSame(SwingTestSupport.find(frame, "installationMenuButton-" + fixture.removed.id()),
                        menu.getInvoker());
                assertFalse(old.isShowing());
                return null;
            });
        } finally {
            release.countDown();
            try {
                assertTrue(finished.await(5, TimeUnit.SECONDS));
            } finally {
                SwingTestSupport.dispose(frame);
            }
        }
    }

    private Fixture fixture() throws Exception {
        return fixture(null, null, null);
    }

    private Fixture fixture(CountDownLatch started, CountDownLatch release, CountDownLatch finished)
            throws Exception {
        RegistryStore store = new RegistryStore();
        RootCoordinator coordinator = new RootCoordinator(temp.resolve("coordination"));
        LauncherServices services = new LauncherServices(temp.resolve("registry.json"), store,
                new InstallationInspector(), (uri, accept) -> {
                    throw new AssertionError("missing-folder handling must not use the network");
                }, new JavaRuntime((command, directory, timeout, inherit) -> {
                    throw new AssertionError("missing-folder handling must not execute Java");
                }), new ApplicationLauncher(), coordinator) {
            @Override public void requireInstallationPresent(InstallationRecord record) throws IOException {
                super.requireInstallationPresent(record);
                if (started == null) return;
                started.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("test did not release folder validation");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IOException("folder validation interrupted", error);
                } finally {
                    finished.countDown();
                }
            }
        };
        Path root = suite("old");
        InstallationRecord removed = services.register("Old installation", root);
        InstallationRecord retained = services.register("Retained installation", suite("retained"));
        return new Fixture(services, coordinator, root, removed, retained);
    }

    private Path suite(String name) throws Exception {
        Path root = Files.createDirectory(temp.resolve(name));
        Files.createDirectory(root.resolve("data"));
        Files.createDirectory(root.resolve("mmconf"));
        Files.createDirectory(root.resolve("lib"));
        Files.createDirectory(root.resolve("docs"));
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "megamek.MegaMek");
        manifest.getMainAttributes().put(Attributes.Name.IMPLEMENTATION_VERSION, "1.0.0");
        try (var output = new JarOutputStream(Files.newOutputStream(root.resolve("MegaMek.jar")), manifest)) {
            output.finish();
        }
        return root;
    }

    private void deleteRoot(Path root) throws Exception {
        assertTrue(root.startsWith(temp) && !root.equals(temp));
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }

    private record Fixture(LauncherServices services, RootCoordinator coordinator,
                           Path root, InstallationRecord removed, InstallationRecord retained) {}

    private static final class SimulatedCrash extends Error {}
}
