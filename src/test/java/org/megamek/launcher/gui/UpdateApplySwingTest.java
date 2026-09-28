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

package org.megamek.launcher.gui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.megamek.launcher.launch.ApplicationLauncher;
import org.megamek.launcher.launch.JavaRuntime;
import org.megamek.launcher.launch.RootCoordinator;
import org.megamek.launcher.channel.ChannelPreferenceStore;
import org.megamek.launcher.channel.ChannelUpdateChecker;
import org.megamek.launcher.channel.FollowChannel;
import org.megamek.launcher.onboarding.Inspection;
import org.megamek.launcher.onboarding.InstallationInspector;
import org.megamek.launcher.onboarding.Product;
import org.megamek.launcher.operation.OperationContext;
import org.megamek.launcher.plan.Action;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryData;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.release.OfficialRepository;
import org.megamek.launcher.release.ReleaseCatalog;
import org.megamek.launcher.release.ReleaseTransport;
import org.megamek.launcher.update.CurrentUpdateState;
import org.megamek.launcher.update.OwnershipPolicy;
import org.megamek.launcher.update.OwnershipReceipt;
import org.megamek.launcher.update.PreparedUpdate;
import org.megamek.launcher.update.RealUpdateService;
import org.megamek.launcher.update.ReceiptStore;
import org.megamek.launcher.update.UpdatePreviewService;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.KeyStroke;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateApplySwingTest {
    @TempDir Path temp;

    @Test
    void applyWorkflowUsesOneConsentBindsSnapshotAndSilentlyRefreshesInstallations()
            throws Exception {
        Fixture fixture = fixture(false);
        LauncherFrame frame = onEdt(() -> new LauncherFrame(fixture.services));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            navigateToInstallations(frame);
            JButton update = checkAndWaitForUpdate(frame);
            assertTrue(update.isEnabled());
            Path preservedDocument =
                    Path.of(fixture.first.canonicalRoot()).resolve("docs/change.txt");
            Files.writeString(preservedDocument, "local customization");

            SwingUtilities.invokeLater(update::doClick);
            JDialog firstConsent = waitForDialog("Update application");
            assertTrue(firstConsent instanceof RecommendedUpdateConsentDialog);
            assertEquals("recommendedUpdateConsentDialog", firstConsent.getName());
            assertEquals(FirstLaunchPanel.BACKGROUND,
                    firstConsent.getContentPane().getBackground());
            String firstText = componentText(firstConsent);
            assertTrue(firstText.contains(
                    "This will download, verify, plan, and apply the update."));
            assertTrue(firstText.contains(
                    "Close MegaMek, MekHQ, and MegaMekLab before continuing."));
            assertTrue(firstText.contains("Application: MegaMek"));
            assertTrue(firstText.contains("Current version: 1.0.0"));
            assertTrue(firstText.contains("New version: 2.0.0"));
            assertTrue(firstText.contains("Download size: "
                    + BinarySizeFormat.mebibytes(fixture.services.asset.size())));
            assertFalse(firstText.contains(fixture.services.asset.name()));
            assertFalse(firstText.contains(fixture.services.asset.digest()));
            assertFalse(firstText.contains(fixture.services.asset.url().toString()));
            assertFalse(firstText.contains(" bytes"));
            assertFalse(firstText.contains("v2.0.0"));
            assertFalse(firstText.contains("tag"));
            assertFalse(firstText.contains("digest"));
            assertFalse(firstText.contains("read-only"));
            assertFalse(firstText.contains("preview"));
            JButton cancelUpdate = find(
                    firstConsent, "cancelRecommendedUpdateButton");
            JButton confirmUpdate = find(firstConsent, "updateRecommendedUpdateButton");
            assertTrue(cancelUpdate instanceof FirstLaunchButton);
            assertTrue(confirmUpdate instanceof FirstLaunchButton);
            Container actions = find(firstConsent, "recommendedUpdateConsentActions");
            assertTrue(actions.getComponentZOrder(cancelUpdate)
                    < actions.getComponentZOrder(confirmUpdate));
            assertTrue(actions.getComponent(0) instanceof javax.swing.Box.Filler,
                    "horizontal glue must right-align the grouped actions");
            assertEquals(confirmUpdate, firstConsent.getRootPane().getDefaultButton());
            click(firstConsent, "Cancel");
            waitUntil(() -> !firstConsent.isDisplayable());
            assertEquals(0, fixture.services.previewCalls);
            assertEquals(0, fixture.services.applyCalls);
            assertEquals(0, fixture.services.network.binaryRequests);

            fixture.services.blockNextApply();
            SwingUtilities.invokeLater(update::doClick);
            JDialog acceptedConsent = waitForDialog("Update application");
            click(acceptedConsent, "Update");
            assertTrue(fixture.services.applyStarted.await(5, TimeUnit.SECONDS),
                    "acceptance must proceed automatically from preparation into Apply");
            assertNull(showingDialog("Authorize update Apply"));
            fixture.services.selectNewPreference();
            fixture.services.releaseApply.countDown();
            waitUntil(() -> fixture.services.loadHomeCalls >= 2);
            waitFor(() -> find(frame, "installationCards"));
            assertNull(showingDialog("Update complete"));
            assertNull(showingProgressDialog());

            assertEquals(1, fixture.services.previewCalls);
            assertEquals(1, fixture.services.applyCalls);
            assertEquals(fixture.first, fixture.services.appliedRecord);
            assertEquals(fixture.firstCurrent, fixture.services.appliedState);
            assertEquals("v2.0.0", fixture.services.appliedTag);
            assertEquals(fixture.services.asset.size(), fixture.services.appliedSize);
            assertEquals(fixture.services.asset.digest(), fixture.services.appliedDigest);
            assertEquals(RealUpdateService.CONFIRM, fixture.services.appliedConfirmation);
            assertFalse(fixture.services.applyOnEdt, "Apply backend must run off the EDT");
            assertEquals(1, fixture.services.network.binaryRequests,
                    "the accepted attempt downloads exactly one package");
            assertEquals(fixture.services.archive.length,
                    fixture.services.network.binaryBytes);
            assertEquals("v2.0.0", new RealUpdateService(fixture.services.network)
                    .snapshot(fixture.services.registry(), fixture.first.id()).current().tag());
            assertEquals("runtime-2", Files.readString(
                    Path.of(fixture.first.canonicalRoot()).resolve("lib/runtime.txt")));
            assertEquals("local customization", Files.readString(preservedDocument),
                    "a normal skipped decision remains preserved without a completion dialog");
            assertEquals(fixture.second.id(),
                    fixture.services.currentHome().defaultInstallationId());
            assertNotNull(find(frame, "installationCards"),
                    "clean success returns to the current Installations page");
        } finally {
            fixture.services.releaseApply.countDown();
            dispose(frame);
        }
    }

    @Test
    void recommendedGuiAttemptUsesOnePackageBodyAcrossPreviewAndApply() throws Exception {
        Fixture fixture = fixture(false);
        LauncherFrame frame = onEdt(() -> new LauncherFrame(fixture.services));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            navigateToInstallations(frame);
            JButton recommended = checkAndWaitForUpdate(frame);

            SwingUtilities.invokeLater(recommended::doClick);
            JDialog firstConsent = waitForDialog("Update application");
            cancelWithEscape(firstConsent);
            waitUntil(() -> !firstConsent.isDisplayable());
            assertEquals(0, fixture.services.network.binaryRequests,
                    "Escape before preparation must fetch no package body");

            SwingUtilities.invokeLater(recommended::doClick);
            JDialog closedConsent = waitForDialog("Update application");
            SwingUtilities.invokeAndWait(() -> closedConsent.dispatchEvent(
                    new WindowEvent(closedConsent, WindowEvent.WINDOW_CLOSING)));
            waitUntil(() -> !closedConsent.isDisplayable());
            assertEquals(0, fixture.services.network.binaryRequests,
                    "window close before preparation must fetch no package body");
            assertEquals(0, fixture.services.previewCalls);

            SwingUtilities.invokeLater(recommended::doClick);
            click(waitForDialog("Update application"), "Update");
            waitUntil(() -> fixture.services.applyCalls == 1);
            waitUntil(() -> fixture.services.loadHomeCalls >= 2);
            waitFor(() -> find(frame, "installationCards"));
            assertNull(showingDialog("Authorize update Apply"));
            assertNull(showingDialog("Update complete"));
            assertNull(showingProgressDialog());

            assertEquals(1, fixture.services.network.binaryRequests);
            assertEquals(fixture.services.archive.length,
                    fixture.services.network.binaryBytes);
            assertEquals(1, fixture.services.applyCalls);
            assertFalse(fixture.services.applyOnEdt);
            assertEquals("v2.0.0", fixture.services.appliedTag);
            RealUpdateService.Snapshot installed =
                    new RealUpdateService(fixture.services.network)
                            .snapshot(fixture.services.registry(), fixture.first.id());
            assertEquals("v2.0.0", installed.current().tag());
            assertEquals(fixture.first.canonicalRoot(), installed.current().canonicalRoot());
            assertEquals("runtime-2", Files.readString(
                    Path.of(fixture.first.canonicalRoot()).resolve("lib/runtime.txt")));
        } finally {
            dispose(frame);
        }
    }

    @Test
    void disposingFrameDuringPreparationCleansLatePreparedWorkspaceWithoutApply()
            throws Exception {
        Fixture fixture = fixture(false);
        fixture.services.network.blockNextBinary();
        LauncherFrame frame = onEdt(() -> new LauncherFrame(fixture.services));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            navigateToInstallations(frame);
            JButton update = checkAndWaitForUpdate(frame);
            SwingUtilities.invokeLater(update::doClick);
            click(waitForDialog("Update application"), "Update");
            assertTrue(fixture.services.network.binaryStarted.await(5, TimeUnit.SECONDS));

            SwingUtilities.invokeAndWait(frame::dispose);
            fixture.services.network.releaseBinary.countDown();
            Path metadata = fixture.services.registry().resolveSibling(
                    fixture.services.registry().getFileName() + ".metadata");
            long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
            boolean clean = false;
            while (System.nanoTime() < deadline) {
                try (var entries = Files.list(metadata)) {
                    clean = entries.noneMatch(path ->
                            path.getFileName().toString().startsWith(".preview-"));
                }
                if (clean) break;
                Thread.sleep(20);
            }
            assertTrue(clean, "late preparation result must discard its exact workspace");
            Path logs = fixture.services.registry().resolveSibling(
                    fixture.services.registry().getFileName() + ".launcher-logs");
            boolean logged = false;
            long logDeadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
            while (System.nanoTime() < logDeadline) {
                if (Files.isDirectory(logs)) {
                    try (var entries = Files.list(logs)) {
                        if (entries.anyMatch(path ->
                                path.getFileName().toString().startsWith("operation-"))) {
                            logged = true;
                            break;
                        }
                    }
                }
                Thread.sleep(20);
            }
            assertTrue(logged,
                    "disposed operation must finish its local terminal log before test cleanup");
            assertEquals(0, fixture.services.applyCalls);
            assertEquals(1, fixture.services.network.binaryRequests);
            assertEquals("runtime-1", Files.readString(
                    Path.of(fixture.first.canonicalRoot()).resolve("lib/runtime.txt")));
        } finally {
            fixture.services.network.releaseBinary.countDown();
            dispose(frame);
        }
    }

    @Test
    void recommendedPreferenceDriftAfterPreparationFailsWithoutSecondPackageOrRootWrite()
            throws Exception {
        Fixture fixture = fixture(false);
        fixture.services.blockNextApply();
        LauncherFrame frame = onEdt(() -> new LauncherFrame(fixture.services));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            navigateToInstallations(frame);
            JButton recommended = checkAndWaitForUpdate(frame);
            SwingUtilities.invokeLater(recommended::doClick);
            click(waitForDialog("Update application"), "Update");
            assertTrue(fixture.services.applyStarted.await(5, TimeUnit.SECONDS),
                    "test hook must pause after preparation and before backend revalidation");
            assertNull(showingDialog("Authorize update Apply"));

            ChannelPreferenceStore channels = new ChannelPreferenceStore();
            RegistryData data = new RegistryStore().read(fixture.services.registry());
            var selected = channels.read(
                    fixture.services.registry(), data, fixture.first).preference();
            channels.setCheckOnOpen(
                    fixture.services.registry(), fixture.first, selected, true);
            fixture.services.releaseApply.countDown();
            JDialog failed = waitForDialog("Update failed — recovery may be required");
            assertTrue(componentText(failed).contains("channel source")
                    || componentText(failed).contains("Channel check failed"));
            assertEquals(1, fixture.services.network.binaryRequests);
            assertEquals(fixture.services.archive.length,
                    fixture.services.network.binaryBytes);
            assertEquals("runtime-1", Files.readString(
                    Path.of(fixture.first.canonicalRoot()).resolve("lib/runtime.txt")));
            assertFalse(Files.exists(Path.of(fixture.first.canonicalRoot())
                    .resolve(RootCoordinator.UPDATE_NAMESPACE)));
        } finally {
            fixture.services.releaseApply.countDown();
            dispose(frame);
        }
    }

    @Test
    void applyFailureBeforeMutationDoesNotInventRecovery()
            throws Exception {
        Fixture fixture = fixture(true);
        fixture.services.failApply = true;
        LauncherFrame frame = onEdt(() -> new LauncherFrame(fixture.services));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            navigateToInstallations(frame);
            JButton update = checkAndWaitForUpdate(frame);
            SwingUtilities.invokeLater(update::doClick);
            click(waitForDialog("Update application"), "Update");
            waitUntil(() -> fixture.services.applyCalls == 1);

            JDialog failed = waitForDialog("Update failed — recovery may be required");
            assertTrue(failed.getTitle().contains("recovery"));
            assertNotNull(find(failed, "operationViewDetailsButton"));
            waitUntil(() -> fixture.services.loadHomeCalls >= 2);
            assertNull(find(frame, "recoverUpdateButton"),
                    "a failure before mutation must not invent a pending recovery");
        } finally {
            dispose(frame);
        }
    }

    @Test
    void cleanupWarningRemainsInStyledProgressSurface() throws Exception {
        Fixture fixture = fixture(false);
        fixture.services.cleanupWarning =
                "Update completed, but temporary package cleanup failed: fixture cleanup. "
                        + "The installation is updated; do not retry Apply.";
        LauncherFrame frame = onEdt(() -> new LauncherFrame(fixture.services));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            navigateToInstallations(frame);
            JButton update = checkAndWaitForUpdate(frame);
            SwingUtilities.invokeLater(update::doClick);
            click(waitForDialog("Update application"), "Update");

            JDialog warning = waitForDialog("Update complete — cleanup warning");
            assertTrue(warning instanceof OperationProgressDialog);
            assertEquals(OperationProgressDialog.BACKGROUND,
                    warning.getContentPane().getBackground());
            assertTrue(componentText(warning).contains(
                    "The update was applied, but temporary files could not be removed. "
                            + "Do not run Update again."));
            assertFalse(componentText(warning).contains("skipped"));
            assertFalse(componentText(warning).contains("Retained override"));
            assertFalse(componentText(warning).contains("Build:"));
            JButton close = find(warning, "operationCancelButton");
            JButton logs = find(warning, "operationViewDetailsButton");
            assertEquals("Close", close.getText());
            assertTrue(close.isVisible());
            assertEquals("View logs", logs.getText());
            assertTrue(logs.isVisible());
            assertNull(showingDialog("Update complete"));
            waitUntil(() -> fixture.services.loadHomeCalls >= 2);
            assertNotNull(find(frame, "installationCards"));
            click(warning, "Close");
        } finally {
            dispose(frame);
        }
    }

    private Fixture fixture(boolean unavailableInspection) throws Exception {
        Path firstRoot = createSuite(temp.resolve("first-root"), 1, "old");
        Path secondRoot = createSuite(temp.resolve("second-root"), 9, "other");
        Path registry = temp.resolve("fake-registry.json");
        RegistryStore store = new RegistryStore();
        InstallationRecord first = store.register(registry, "Original source", firstRoot, null);
        InstallationRecord second = store.register(registry, "New preference", secondRoot, null);
        store.select(registry, first.id());
        var firstBuild = new OwnershipPolicy().build(
                firstRoot, OfficialRepository.MEGAMEK, "v1.0.0");
        new ReceiptStore().write(registry, store.read(registry), first,
                OfficialRepository.MEGAMEK, "v1.0.0", "MegaMek-v1.0.0.tar.gz", 123,
                "sha256:" + "a".repeat(64), firstBuild);
        OwnershipReceipt firstReceipt =
                new ReceiptStore().read(registry, store.read(registry), first);
        var secondBuild = new OwnershipPolicy().build(
                secondRoot, OfficialRepository.MEGAMEK, "v-other");
        OwnershipReceipt secondReceipt = new OwnershipReceipt(1, OwnershipPolicy.VERSION,
                second.id(), second.canonicalRoot(), second.registeredAt(), "megamek", "v-other",
                "MegaMek-v-other.tar.gz", 456, "b".repeat(64), secondBuild.manifest(),
                secondBuild.excludedPaths());
        CurrentUpdateState firstCurrent = CurrentUpdateState.initial(firstReceipt);
        CurrentUpdateState secondCurrent = CurrentUpdateState.initial(secondReceipt);
        new ChannelPreferenceStore().initializeManaged(
                registry, first, firstReceipt, FollowChannel.MILESTONE, false);
        byte[] targetArchive = targetArchive();
        PreparedTransport transport = new PreparedTransport(targetArchive);
        FakeServices services = new FakeServices(registry, store, transport,
                first, second, first.products().getFirst(), firstReceipt, secondReceipt,
                firstCurrent, secondCurrent, unavailableInspection, targetArchive,
                new RootCoordinator(temp.resolve("swing-coordination")));
        services.selectDefaultJava(Path.of(System.getProperty("java.home")));
        return new Fixture(services, first, second, firstCurrent);
    }

    private static void navigateToInstallations(LauncherFrame frame) throws Exception {
        JButton installations = waitFor(() -> find(frame, "manageInstallationsButton"));
        SwingUtilities.invokeAndWait(installations::doClick);
        waitFor(() -> find(frame, "installationCards"));
    }

    private static JButton checkAndWaitForUpdate(LauncherFrame frame) throws Exception {
        JButton action;
        try {
            action = waitFor(() -> {
                JButton update = find(frame, "applyUpdateButton");
                return update != null ? update : find(frame, "checkUpdatesButton");
            });
        } catch (AssertionError error) {
            throw new AssertionError("No update action. Current UI: " + componentText(frame),
                    error);
        }
        if ("applyUpdateButton".equals(action.getName())) return action;
        JButton check = action;
        SwingUtilities.invokeAndWait(check::doClick);
        return waitFor(() -> find(frame, "applyUpdateButton"));
    }

    private static void click(Container dialog, String text) throws Exception {
        JButton button = findButtonText(dialog, text);
        assertNotNull(button, "missing dialog button " + text);
        SwingUtilities.invokeAndWait(button::doClick);
    }

    private static void cancelWithEscape(JDialog dialog) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            KeyStroke escape = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
            Object binding = dialog.getRootPane()
                    .getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(escape);
            assertNotNull(binding, "Escape must be bound in the download consent");
            var action = dialog.getRootPane().getActionMap().get(binding);
            assertNotNull(action, "Escape binding must have a cancel action");
            action.actionPerformed(new ActionEvent(dialog, 0, "escape"));
        });
    }

    private static JDialog waitForDialog(String title) throws Exception {
        try {
            return waitFor(() -> {
                for (Window window : Window.getWindows()) {
                    if (window instanceof JDialog dialog && dialog.isShowing()
                            && title.equals(dialog.getTitle())) {
                        return dialog;
                    }
                }
                return null;
            });
        } catch (AssertionError timeout) {
            String visible = onEdt(() -> java.util.Arrays.stream(Window.getWindows())
                    .filter(Window::isShowing).filter(JDialog.class::isInstance)
                    .map(JDialog.class::cast).map(JDialog::getTitle)
                    .toList().toString());
            throw new AssertionError("timed out waiting for dialog " + title
                    + "; showing dialogs: " + visible, timeout);
        }
    }

    private static JDialog showingDialog(String title) throws Exception {
        return onEdt(() -> {
            for (Window window : Window.getWindows()) {
                if (window instanceof JDialog dialog && dialog.isShowing()
                        && title.equals(dialog.getTitle())) {
                    return dialog;
                }
            }
            return null;
        });
    }

    private static JDialog showingProgressDialog() throws Exception {
        return onEdt(() -> {
            for (Window window : Window.getWindows()) {
                if (window instanceof OperationProgressDialog dialog && dialog.isShowing()) {
                    return dialog;
                }
            }
            return null;
        });
    }

    private static <T> T waitFor(java.util.function.Supplier<T> probe) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < deadline) {
            AtomicReference<T> value = new AtomicReference<>();
            SwingUtilities.invokeAndWait(() -> value.set(probe.get()));
            if (value.get() != null) return value.get();
            Thread.sleep(20);
        }
        throw new AssertionError("timed out waiting for Swing component");
    }

    private static void waitUntil(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < deadline) {
            boolean[] result = new boolean[1];
            SwingUtilities.invokeAndWait(() -> result[0] = condition.getAsBoolean());
            if (result[0]) return;
            Thread.sleep(20);
        }
        throw new AssertionError("timed out waiting for Swing state");
    }

    @SuppressWarnings("unchecked")
    private static <T extends Component> T find(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName())) return (T) child;
            if (child instanceof Container nested) {
                T found = find(nested, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JButton findButtonText(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof JButton button && text.equals(button.getText())) return button;
            if (child instanceof Container nested) {
                JButton found = findButtonText(nested, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static String componentText(Container root) {
        StringBuilder result = new StringBuilder();
        appendText(root, result);
        return result.toString();
    }

    private static void appendText(Container root, StringBuilder result) {
        for (Component child : root.getComponents()) {
            if (child instanceof JLabel label) result.append(label.getText()).append('\n');
            if (child instanceof JTextArea area) result.append(area.getText()).append('\n');
            if (child instanceof Container nested) appendText(nested, result);
        }
    }

    private static <T> T onEdt(ThrowingSupplier<T> supplier) throws Exception {
        AtomicReference<T> value = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                value.set(supplier.get());
            } catch (Throwable error) {
                failure.set(error);
            }
        });
        if (failure.get() != null) throw new RuntimeException(failure.get());
        return value.get();
    }

    private static void dispose(LauncherFrame frame) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (Window window : Window.getWindows()) {
                if (window == frame || window.getOwner() == frame
                        || window.getOwner() instanceof JDialog) {
                    window.dispose();
                }
            }
            frame.dispose();
        });
    }

    private static Path createSuite(Path root, int version, String document) throws Exception {
        Files.createDirectory(root);
        Files.createDirectories(root.resolve("data"));
        Files.createDirectories(root.resolve("mmconf"));
        Files.createDirectories(root.resolve("lib"));
        Files.createDirectories(root.resolve("docs"));
        Files.write(root.resolve("MegaMek.jar"), jar(version));
        Files.writeString(root.resolve("lib/runtime.txt"), "runtime-" + version);
        Files.writeString(root.resolve("data/default.txt"), "data-" + version);
        Files.writeString(root.resolve("mmconf/clientsettings.xml"), "protected-" + version);
        Files.writeString(root.resolve("docs/change.txt"), document);
        return root;
    }

    private static byte[] targetArchive() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("MegaMek.jar", jar(2));
        files.put("lib/runtime.txt", "runtime-2".getBytes(StandardCharsets.UTF_8));
        files.put("data/default.txt", "data-2".getBytes(StandardCharsets.UTF_8));
        files.put("mmconf/clientsettings.xml", "target-protected".getBytes(StandardCharsets.UTF_8));
        files.put("docs/change.txt", "new".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(output);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            tar.putArchiveEntry(new TarArchiveEntry("MegaMek-v-target/"));
            tar.closeArchiveEntry();
            for (Map.Entry<String, byte[]> item : files.entrySet()) {
                TarArchiveEntry entry =
                        new TarArchiveEntry("MegaMek-v-target/" + item.getKey());
                entry.setSize(item.getValue().length);
                tar.putArchiveEntry(entry);
                tar.write(item.getValue());
                tar.closeArchiveEntry();
            }
        }
        return output.toByteArray();
    }

    private static byte[] jar(int version) throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "megamek.MegaMek");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(output, manifest)) {
            jar.putNextEntry(new JarEntry("megamek/Version.properties"));
            jar.write(("major=" + version + "\nminor=0\npatch=0\n")
                    .getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return output.toByteArray();
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private record Fixture(FakeServices services, InstallationRecord first,
                           InstallationRecord second, CurrentUpdateState firstCurrent) {
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private static final class FakeServices extends LauncherServices {
        private final ReleaseCatalog.Asset asset;
        private final ReleaseCatalog.Release release;
        private final PreparedTransport network;
        private final byte[] archive;
        private final RegistryStore registryStore;
        private final InstallationRecord first;
        private final InstallationRecord second;
        private final Product product;
        private final OwnershipReceipt firstReceipt;
        private final OwnershipReceipt secondReceipt;
        private final CurrentUpdateState firstCurrent;
        private final CurrentUpdateState secondCurrent;
        private final boolean unavailableInspection;
        private volatile InstallationRecord preferred;
        private volatile int loadHomeCalls;
        private volatile int previewCalls;
        private volatile int applyCalls;
        private volatile boolean failApply;
        private volatile boolean blockApply;
        private volatile String cleanupWarning;
        private final CountDownLatch applyStarted = new CountDownLatch(1);
        private final CountDownLatch releaseApply = new CountDownLatch(1);
        private volatile InstallationRecord appliedRecord;
        private volatile CurrentUpdateState appliedState;
        private volatile String appliedTag;
        private volatile long appliedSize;
        private volatile String appliedDigest;
        private volatile String appliedConfirmation;
        private volatile boolean applyOnEdt;

        private FakeServices(Path registry, RegistryStore store, PreparedTransport network,
                             InstallationRecord first, InstallationRecord second,
                             Product product, OwnershipReceipt firstReceipt,
                             OwnershipReceipt secondReceipt, CurrentUpdateState firstCurrent,
                             CurrentUpdateState secondCurrent, boolean unavailableInspection,
                             byte[] archive, RootCoordinator coordinator) {
            super(registry, store, new InstallationInspector(), network, new JavaRuntime(),
                    new ApplicationLauncher(), coordinator);
            this.network = network;
            this.archive = archive;
            this.registryStore = store;
            try {
                this.asset = new ReleaseCatalog.Asset("MegaMek-v2.0.0.tar.gz", archive.length,
                        "sha256:" + sha(archive),
                        URI.create("https://github.com/MegaMek/megamek/releases/download/"
                                + "v2.0.0/MegaMek-v2.0.0.tar.gz"));
            } catch (Exception error) {
                throw new IllegalStateException(error);
            }
            this.release = new ReleaseCatalog.Release(
                    "v2.0.0", "Target", false, false,
                    URI.create("https://github.com/MegaMek/megamek/releases/tag/v2.0.0"),
                    List.of(asset));
            this.first = first;
            this.second = second;
            this.product = product;
            this.firstReceipt = firstReceipt;
            this.secondReceipt = secondReceipt;
            this.firstCurrent = firstCurrent;
            this.secondCurrent = secondCurrent;
            this.unavailableInspection = unavailableInspection;
            this.preferred = first;
        }

        @Override
        public HomeState loadHome() throws IOException {
            loadHomeCalls++;
            InstallationRecord record = preferred;
            OwnershipReceipt receipt = record.equals(first) ? firstReceipt : secondReceipt;
            CurrentUpdateState current = record.equals(first) ? firstCurrent : secondCurrent;
            RegistryData registry = currentHome();
            Inspection inspection = unavailableInspection ? null
                    : new Inspection(record.canonicalRoot(), List.of(product),
                    record.observedBuild(), "fixture");
            ChannelPreferenceStore.ReadResult channel =
                    new ChannelPreferenceStore().read(registry(), registry, record);
            return new HomeState(registry, record, inspection,
                    unavailableInspection ? "Fixture inspection unavailable" : null,
                    new UpdatePreviewService.Eligibility(record, true,
                            "Verified update provenance is valid", receipt, current), false,
                    channel);
        }

        private RegistryData currentHome() {
            return new RegistryData(RegistryStore.SCHEMA, preferred.id(),
                    List.of(first, second));
        }

        private void selectNewPreference() {
            try {
                registryStore.select(registry(), second.id());
            } catch (IOException error) {
                throw new IllegalStateException(error);
            }
            preferred = second;
        }

        private void blockNextApply() {
            blockApply = true;
        }

        @Override
        public ReleaseCatalog.Page releases(OfficialRepository repository, int page) {
            return new ReleaseCatalog.Page(1, 10, List.of(release), false);
        }

        @Override
        public ReleaseCatalog.Assessment assess(OfficialRepository repository,
                                                 ReleaseCatalog.Release ignored) {
            return new ReleaseCatalog.Assessment(true, asset, "Available");
        }

        @Override
        public PreparedUpdate prepareUpdate(
                InstallationRecord record, OwnershipReceipt receipt,
                CurrentUpdateState current, String tag, String assetName,
                long size, String digest, PrintStream progress)
                throws IOException, InterruptedException,
                org.megamek.launcher.manifest.ManifestException {
            previewCalls++;
            return super.prepareUpdate(
                    record, receipt, current, tag, assetName, size, digest, progress);
        }

        @Override
        public PreparedUpdate prepareUpdate(
                InstallationRecord record, OwnershipReceipt receipt,
                CurrentUpdateState current, String tag, String assetName,
                long size, String digest, PrintStream progress, OperationContext context)
                throws IOException, InterruptedException,
                org.megamek.launcher.manifest.ManifestException {
            previewCalls++;
            return super.prepareUpdate(
                    record, receipt, current, tag, assetName, size, digest, progress, context);
        }

        @Override
        public PreparedUpdate prepareRecommended(
                InstallationRecord record, OwnershipReceipt receipt,
                CurrentUpdateState current,
                ChannelUpdateChecker.Result expected, PrintStream progress)
                throws IOException, InterruptedException,
                org.megamek.launcher.manifest.ManifestException {
            previewCalls++;
            return super.prepareRecommended(record, receipt, current, expected, progress);
        }

        @Override
        public PreparedUpdate prepareRecommended(
                InstallationRecord record, OwnershipReceipt receipt,
                CurrentUpdateState current,
                ChannelUpdateChecker.Result expected, PrintStream progress,
                OperationContext context)
                throws IOException, InterruptedException,
                org.megamek.launcher.manifest.ManifestException {
            previewCalls++;
            return super.prepareRecommended(
                    record, receipt, current, expected, progress, context);
        }

        @Override
        public RealUpdateService.ApplyResult applyPrepared(
                PreparedUpdate prepared, String confirmation, PrintStream progress)
                throws IOException, InterruptedException,
                org.megamek.launcher.manifest.ManifestException {
            applyCalls++;
            UpdatePreviewService.Preview preview = prepared.preview();
            appliedRecord = preview.record();
            appliedState = preview.currentState();
            appliedTag = preview.targetRelease().tag();
            appliedSize = preview.targetAsset().size();
            appliedDigest = preview.targetAsset().digest();
            appliedConfirmation = confirmation;
            applyOnEdt = SwingUtilities.isEventDispatchThread();
            if (failApply) {
                prepared.close();
                throw new IOException("simulated apply failure");
            }
            awaitApplyRelease(prepared);
            return withCleanupWarning(
                    super.applyPrepared(prepared, confirmation, progress));
        }

        @Override
        public RealUpdateService.ApplyResult applyPrepared(
                PreparedUpdate prepared, String confirmation, PrintStream progress,
                OperationContext context)
                throws IOException, InterruptedException,
                org.megamek.launcher.manifest.ManifestException {
            applyCalls++;
            UpdatePreviewService.Preview preview = prepared.preview();
            appliedRecord = preview.record();
            appliedState = preview.currentState();
            appliedTag = preview.targetRelease().tag();
            appliedSize = preview.targetAsset().size();
            appliedDigest = preview.targetAsset().digest();
            appliedConfirmation = confirmation;
            applyOnEdt = SwingUtilities.isEventDispatchThread();
            if (failApply) {
                prepared.close();
                throw new IOException("simulated apply failure");
            }
            awaitApplyRelease(prepared);
            return withCleanupWarning(
                    super.applyPrepared(prepared, confirmation, progress, context));
        }

        private void awaitApplyRelease(PreparedUpdate prepared)
                throws IOException, InterruptedException {
            if (!blockApply) return;
            applyStarted.countDown();
            try {
                releaseApply.await();
            } catch (InterruptedException interrupted) {
                try {
                    prepared.close();
                } catch (IOException cleanup) {
                    interrupted.addSuppressed(cleanup);
                }
                throw interrupted;
            }
        }

        private RealUpdateService.ApplyResult withCleanupWarning(
                RealUpdateService.ApplyResult result) {
            if (cleanupWarning == null) return result;
            return new RealUpdateService.ApplyResult(
                    result.record(), result.state(), result.decisions(),
                    result.skippedDecisions(), result.retainedOverrides(), cleanupWarning);
        }
    }

    private static final class PreparedTransport implements ReleaseTransport {
        private final byte[] archive;
        private final String digest;
        private volatile int metadataRequests;
        private volatile int binaryRequests;
        private volatile long binaryBytes;
        private volatile boolean blockBinary;
        private final CountDownLatch binaryStarted = new CountDownLatch(1);
        private final CountDownLatch releaseBinary = new CountDownLatch(1);

        private PreparedTransport(byte[] archive) throws Exception {
            this.archive = archive;
            this.digest = sha(archive);
        }

        @Override
        public Response get(URI uri, String accept) throws IOException, InterruptedException {
            if ("application/octet-stream".equals(accept)) {
                assertFalse(SwingUtilities.isEventDispatchThread(),
                        "package transfer must not run on EDT");
                binaryRequests++;
                binaryStarted.countDown();
                if (blockBinary) releaseBinary.await();
                return new Response(200,
                        Map.of("content-length", List.of(Integer.toString(archive.length))),
                        new ByteArrayInputStream(archive) {
                            @Override
                            public synchronized int read(byte[] buffer, int offset, int length) {
                                int count = super.read(buffer, offset, length);
                                if (count > 0) binaryBytes += count;
                                return count;
                            }

                            @Override
                            public synchronized int read() {
                                int value = super.read();
                                if (value >= 0) binaryBytes++;
                                return value;
                            }
                        });
            }
            metadataRequests++;
            if (accept.contains("yaml")) {
                byte[] yaml = "stable: 2.0.0\ndev: 2.1.0\n"
                        .getBytes(StandardCharsets.UTF_8);
                return new Response(200,
                        Map.of("content-length", List.of(Integer.toString(yaml.length))),
                        new ByteArrayInputStream(yaml));
            }
            String json = """
                    {"tag_name":"v2.0.0","name":"Target","draft":false,"prerelease":false,
                    "html_url":"https://github.com/MegaMek/megamek/releases/tag/v2.0.0",
                    "assets":[{"name":"MegaMek-v2.0.0.tar.gz","size":%d,
                    "digest":"sha256:%s",
                    "browser_download_url":"https://github.com/MegaMek/megamek/releases/download/v2.0.0/MegaMek-v2.0.0.tar.gz"}]}
                    """.formatted(archive.length, digest);
            return new Response(200, Map.of(),
                    new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        }

        private void blockNextBinary() {
            blockBinary = true;
        }
    }
}
