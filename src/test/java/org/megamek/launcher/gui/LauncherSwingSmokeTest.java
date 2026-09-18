package org.megamek.launcher.gui;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.megamek.launcher.release.FreshInstaller;
import org.megamek.launcher.release.OfficialRepository;
import org.megamek.launcher.release.ReleaseCatalog;
import org.megamek.launcher.operation.OperationContext;
import org.megamek.launcher.operation.OperationPhase;
import org.megamek.launcher.operation.OperationType;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import javax.swing.JTextArea;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("gui-smoke")
class LauncherSwingSmokeTest {
    @TempDir Path temp;

    @Test
    void showsEmptyHomeNavigatesManageAndCloses() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing smoke requires a display");
        LauncherFrame[] holder = new LauncherFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            holder[0] = new LauncherFrame(
                    new LauncherServices(temp.resolve("registry.json")));
            holder[0].showWindow();
        });
        try {
            assertNotNull(waitForButton(holder[0], "downloadAndInstallButton"));
            JButton more = waitForButton(holder[0], "moreOptionsButton");
            assertNotNull(more);
            assertEquals(null, find(holder[0], "useExistingCopyButton"),
                    "first Home has no competing import primary action");
            SwingUtilities.invokeAndWait(more::doClick);
            assertNotNull(waitForButton(holder[0], "manageAddExistingButton"));
            assertNotNull(waitForButton(holder[0], "manageDownloadMegaMekButton"));
            JButton settings = waitForButton(holder[0], "settingsButton");
            SwingUtilities.invokeAndWait(settings::doClick);
            JButton saveSettings = waitForButton(holder[0], "saveSettingsButton");
            waitFor(saveSettings::isEnabled);
            JButton logs = waitForButton(holder[0], "viewOperationLogsButton");
            assertNotNull(logs);
            SwingUtilities.invokeAndWait(logs::doClick);
            JDialog logViewer = owned(holder[0], "Local operation logs");
            JTextArea logText = findText(logViewer, "operationLogViewer");
            waitFor(() -> logText.getText().contains("No local operation logs"));
            assertNotNull(find(logViewer, "copyOperationLogButton"));
            SwingUtilities.invokeAndWait(logViewer::dispose);
            JButton installations = waitForButton(holder[0], "installationsButton");
            SwingUtilities.invokeAndWait(installations::doClick);
            openDownloadFromManage(holder[0]);
        } finally {
            SwingUtilities.invokeAndWait(() -> {
                for (java.awt.Window window : holder[0].getOwnedWindows()) window.dispose();
                holder[0].dispose();
            });
        }
    }

    @Test
    void safeSubfolderRejectsEscapesReservedNamesAndInvalidInput() {
        assertEquals(Path.of("v0.51.0"), LauncherFrame.safeSubfolder("v0.51.0"));
        for (String unsafe : List.of("", ".", "..", "a/b", "a\\b", "C:", "NUL", "COM1.txt",
                "trailing.", "trailing ")) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> LauncherFrame.safeSubfolder(unsafe), unsafe);
            assertFalse(error.getMessage().isBlank(), "UI error detail for " + unsafe);
        }
    }

    @Test
    void corruptRegistryShowsNonblankRetrySurface() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing controls require a display");
        Path registry = java.nio.file.Files.writeString(temp.resolve("corrupt.json"), "{broken");
        LauncherFrame frame = onEdt(() -> new LauncherFrame(new LauncherServices(registry)));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            assertNotNull(waitForButton(frame, "retryRegistryButton"));
            assertEquals("{broken", java.nio.file.Files.readString(registry));
        } finally {
            dispose(frame);
        }
    }

    @Test
    void damagedPreferredStillRendersActionableHomeAndOpensManage() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing controls require a display");
        LauncherServices services = new LauncherServices(temp.resolve("damaged.json"));
        Path root = createSuite(temp.resolve("damaged-copy"));
        services.register("Damaged copy", root);
        java.nio.file.Files.delete(root.resolve("MegaMek.jar"));
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            JButton manage = waitForButton(frame, "manageInstallationsButton");
            assertNotNull(manage);
            SwingUtilities.invokeAndWait(manage::doClick);
            assertNotNull(waitForButton(frame, "manageAddExistingButton"));
            assertEquals(1, services.readRegistry().installations().size(),
                    "opening Manage must not inspect or remove the broken entry");
            openDownloadFromManage(frame);
        } finally {
            dispose(frame);
        }
    }

    @Test
    void registeredCopyCanDownloadFromManageWithoutRemovingExistingCopy() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing controls require a display");
        Path registry = temp.resolve("registered.json");
        PagingServices services = new PagingServices(registry);
        Path root = createSuite(temp.resolve("existing-copy"));
        services.register("Main", root);
        var before = services.readRegistry();
        byte[] registryBytes = java.nio.file.Files.readAllBytes(registry);
        var registryTime = java.nio.file.Files.getLastModifiedTime(registry);
        Path jar = root.resolve("MegaMek.jar");
        byte[] jarBytes = java.nio.file.Files.readAllBytes(jar);
        var jarTime = java.nio.file.Files.getLastModifiedTime(jar);
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            assertNotNull(waitForButton(frame, "launch-megamek-button"));
            JButton settings = waitForButton(frame, "settingsButton");
            SwingUtilities.invokeAndWait(settings::doClick);
            JButton saveSettings = waitForButton(frame, "saveSettingsButton");
            waitFor(saveSettings::isEnabled);
            JButton logs = waitForButton(frame, "viewOperationLogsButton");
            SwingUtilities.invokeAndWait(logs::doClick);
            JDialog logViewer = owned(frame, "Local operation logs");
            waitFor(() -> findText(logViewer, "operationLogViewer").getText()
                    .contains("No local operation logs"));
            SwingUtilities.invokeAndWait(logViewer::dispose);
            JButton installations = waitForButton(frame, "installationsButton");
            SwingUtilities.invokeAndWait(installations::doClick);
            JDialog download = openDownloadFromManage(frame);
            assertTrue(services.pages.isEmpty(), "opening the picker must not fetch releases");
            SwingUtilities.invokeAndWait(download::dispose);
            assertEquals(before, services.readRegistry());
            assertArrayEquals(registryBytes, java.nio.file.Files.readAllBytes(registry));
            assertEquals(registryTime, java.nio.file.Files.getLastModifiedTime(registry));
            assertArrayEquals(jarBytes, java.nio.file.Files.readAllBytes(jar));
            assertEquals(jarTime, java.nio.file.Files.getLastModifiedTime(jar));
        } finally {
            dispose(frame);
        }
    }

    @Test
    void releasePagingStartsDisabledAndRetriesFailedPage() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing controls require a display");
        PagingServices services = new PagingServices(temp.resolve("paging.json"));
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
        try {
            SwingUtilities.invokeAndWait(() -> {
                frame.setVisible(true);
                frame.downloadDialog();
            });
            JDialog dialog = owned(frame, "Download an official release");
            JButton next = find(dialog, "nextReleasePageButton");
            JButton fetch = find(dialog, "fetchReleasesButton");
            JComboBox<?> product = findCombo(dialog, "downloadProductCombo");
            assertFalse(next.isEnabled());
            SwingUtilities.invokeAndWait(() -> product.setSelectedIndex(1));
            assertFalse(next.isEnabled());

            SwingUtilities.invokeAndWait(fetch::doClick);
            waitFor(() -> next.isEnabled());
            assertEquals(List.of(1), services.pages);

            SwingUtilities.invokeAndWait(next::doClick);
            waitFor(() -> services.pages.size() == 2);
            waitFor(next::isEnabled);
            closeOwned(frame, "Fetching official releases failed");
            SwingUtilities.invokeAndWait(next::doClick);
            waitFor(() -> services.pages.size() == 3);
            assertEquals(List.of(1, 2, 2), services.pages);
        } finally {
            dispose(frame);
        }
    }

    @Test
    void failedInstallRunsOffEdtClosesStreamAndRetryRequiresFreshSelection() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing worker controls require a display");
        FailingInstallServices services = new FailingInstallServices(temp.resolve("install.json"));
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
        try {
            SwingUtilities.invokeAndWait(() -> {
                frame.setVisible(true);
                frame.install(OfficialRepository.MEGAMEK, "v-test", temp.resolve("destination"),
                        "Test");
            });
            assertTrue(services.started.await(5, TimeUnit.SECONDS));
            assertFalse(services.wasEdt);
            JDialog progress = owned(frame, "Downloading and installing");
            JButton retry = find(progress, "retryInstallButton");
            assertFalse(retry.isEnabled());
            SwingUtilities.invokeAndWait(retry::doClick);
            assertEquals(1, services.attempts);

            services.release.countDown();
            waitFor(retry::isEnabled);
            services.streams.getFirst().print("after close");
            assertTrue(services.streams.getFirst().checkError(), "failed stream must be closed");
            closeOwned(frame, "Downloading and installing failed");
            JButton viewLogs = find(progress, "operationViewLogsButton");
            SwingUtilities.invokeAndWait(viewLogs::doClick);
            JDialog logViewer = owned(frame, "Local operation logs");
            waitFor(() -> findText(logViewer, "operationLogViewer").getText()
                    .contains("fixture install failure"));
            SwingUtilities.invokeAndWait(logViewer::dispose);

            SwingUtilities.invokeAndWait(retry::doClick);
            JDialog picker = owned(frame, "Download an official release");
            assertTrue(picker.isVisible());
            assertEquals(1, services.attempts,
                    "retry must require a fresh release selection and confirmation");
        } finally {
            dispose(frame);
        }
    }

    @Test
    void activeOperationCancelButtonRemainsReachableWhileBusyAndDisposesWorkerResources()
            throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing worker controls require a display");
        FailingInstallServices services =
                new FailingInstallServices(temp.resolve("cancel-install.json"));
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
        try {
            SwingUtilities.invokeAndWait(() -> {
                frame.setVisible(true);
                frame.install(OfficialRepository.MEGAMEK, "v-test",
                        temp.resolve("cancel-destination"), "Cancel test");
            });
            assertTrue(services.started.await(5, TimeUnit.SECONDS));
            JDialog progress = owned(frame, "Downloading and installing");
            JButton cancel = find(progress, "operationCancelButton");
            assertTrue(cancel.isEnabled(),
                    "the blanket busy gate must not disable the authoritative Cancel control");
            SwingUtilities.invokeAndWait(cancel::doClick);
            waitFor(() -> progress.getTitle().equals("Cancelled"));
            services.streams.getFirst().print("after close");
            assertTrue(services.streams.getFirst().checkError(),
                    "cancelled worker must close its attempt stream");
            assertFalse(java.nio.file.Files.exists(temp.resolve("cancel-destination")));
            assertNotNull(find(progress, "operationViewLogsButton"));
        } finally {
            services.release.countDown();
            dispose(frame);
        }
    }

    @Test
    void progressDialogShowsAtomicCutoffReasonAndDeniesLateCancellation()
            throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing progress controls require a display");
        LauncherFrame frame = onEdt(() ->
                new LauncherFrame(new LauncherServices(temp.resolve("cutoff.json"))));
        AtomicReference<OperationProgressDialog> holder = new AtomicReference<>();
        OperationContext context = new OperationContext(OperationType.UPDATE_APPLY,
                event -> holder.get().onProgress(event));
        try {
            SwingUtilities.invokeAndWait(() -> {
                frame.setVisible(true);
                OperationProgressDialog dialog = new OperationProgressDialog(
                        frame, "Cutoff fixture", "cutoffFixtureLog", () -> {
                        });
                holder.set(dialog);
                dialog.bind(context);
                dialog.setVisible(true);
            });
            context.phase(OperationPhase.APPLY, "entering transaction");
            context.enterFinalization("The fixture transaction has begun.");
            JDialog dialog = owned(frame, "Cutoff fixture");
            JButton cancel = find(dialog, "operationCancelButton");
            JLabel detail = findLabel(dialog, "operationProgressDetail");
            waitFor(() -> !cancel.isEnabled()
                    && detail.getText().contains("transaction has begun"));
            assertFalse(context.requestCancellation().accepted());
            assertFalse(Thread.currentThread().isInterrupted());
        } finally {
            context.finish(org.megamek.launcher.operation.OperationOutcome.SUCCEEDED, "done");
            dispose(frame);
        }
    }

    private static JDialog openDownloadFromManage(LauncherFrame frame) throws Exception {
        JButton download = onEdt(() -> find(frame, "manageDownloadMegaMekButton"));
        assertNotNull(download);
        SwingUtilities.invokeAndWait(download::doClick);
        JDialog picker = owned(frame, "Download an official release");
        assertTrue(onEdt(picker::isVisible));
        JButton fetch = onEdt(() -> find(picker, "fetchReleasesButton"));
        assertNotNull(fetch);
        assertTrue(onEdt(fetch::isEnabled));
        assertFalse(onEdt(() -> find(picker, "installReleaseButton").isEnabled()));
        return picker;
    }

    private static JButton waitForButton(Container root, String name) throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            JButton[] found = new JButton[1];
            SwingUtilities.invokeAndWait(() -> found[0] = find(root, name));
            if (found[0] != null) return found[0];
            Thread.sleep(50);
        }
        return null;
    }

    private static JButton find(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (child instanceof JButton button && name.equals(button.getName())) return button;
            if (child instanceof Container container) {
                JButton found = find(container, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JComboBox<?> findCombo(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (child instanceof JComboBox<?> combo && name.equals(combo.getName())) return combo;
            if (child instanceof Container container) {
                JComboBox<?> found = findCombo(container, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JTextArea findText(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (child instanceof JTextArea area && name.equals(area.getName())) return area;
            if (child instanceof Container container) {
                JTextArea found = findText(container, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static javax.swing.JLabel findLabel(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (child instanceof javax.swing.JLabel label && name.equals(label.getName())) {
                return label;
            }
            if (child instanceof Container container) {
                javax.swing.JLabel found = findLabel(container, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JDialog owned(LauncherFrame frame, String title) throws Exception {
        final JDialog[] result = new JDialog[1];
        waitFor(() -> {
            for (java.awt.Window window : frame.getOwnedWindows()) {
                if (window instanceof JDialog dialog && title.equals(dialog.getTitle())) {
                    result[0] = dialog;
                    return true;
                }
            }
            return false;
        });
        return result[0];
    }

    private static void closeOwned(LauncherFrame frame, String title) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (java.awt.Window window : frame.getOwnedWindows()) {
                if (window instanceof JDialog dialog && title.equals(dialog.getTitle())) {
                    dialog.dispose();
                }
            }
        });
    }

    private static void dispose(LauncherFrame frame) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (java.awt.Window window : frame.getOwnedWindows()) window.dispose();
            frame.dispose();
        });
    }

    private static void waitFor(java.util.function.BooleanSupplier condition) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            boolean[] matched = new boolean[1];
            SwingUtilities.invokeAndWait(() -> matched[0] = condition.getAsBoolean());
            if (matched[0]) return;
            Thread.sleep(25);
        }
        throw new AssertionError("timed out waiting for Swing state");
    }

    private static <T> T onEdt(java.util.concurrent.Callable<T> operation) throws Exception {
        Object[] result = new Object[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                result[0] = operation.call();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        @SuppressWarnings("unchecked") T cast = (T) result[0];
        return cast;
    }

    private static Path createSuite(Path root) throws Exception {
        java.nio.file.Files.createDirectory(root);
        java.nio.file.Files.createDirectories(root.resolve("data"));
        java.nio.file.Files.createDirectories(root.resolve("mmconf"));
        java.nio.file.Files.createDirectories(root.resolve("lib"));
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "megamek.MegaMek");
        try (JarOutputStream ignored = new JarOutputStream(
                java.nio.file.Files.newOutputStream(root.resolve("MegaMek.jar")), manifest)) {
            // Static fixture.
        }
        return root;
    }

    private static final class PagingServices extends LauncherServices {
        final List<Integer> pages = java.util.Collections.synchronizedList(new ArrayList<>());

        PagingServices(Path registry) {
            super(registry);
        }

        @Override public ReleaseCatalog.Page releases(OfficialRepository repository, int page)
                throws IOException {
            pages.add(page);
            if (page == 2 && pages.stream().filter(value -> value == 2).count() == 1) {
                throw new IOException("fixture page failure");
            }
            return new ReleaseCatalog.Page(page, 10, List.of(), true);
        }
    }

    private static final class FailingInstallServices extends LauncherServices {
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final List<PrintStream> streams = java.util.Collections.synchronizedList(new ArrayList<>());
        volatile int attempts;
        volatile boolean wasEdt = true;

        FailingInstallServices(Path registry) {
            super(registry);
        }

        @Override public FreshInstaller.Result install(OfficialRepository repository, String tag,
                                                       Path destination, String name,
                                                       PrintStream progress)
                throws IOException, InterruptedException {
            attempts++;
            streams.add(progress);
            wasEdt = SwingUtilities.isEventDispatchThread();
            started.countDown();
            if (attempts == 1) release.await(5, TimeUnit.SECONDS);
            progress.println("NOT REGISTERED fixture detail");
            throw new IOException("fixture install failure");
        }

        @Override public FreshInstaller.Result install(OfficialRepository repository, String tag,
                                                       Path destination, String name,
                                                       PrintStream progress,
                                                       OperationContext context)
                throws IOException, InterruptedException {
            return install(repository, tag, destination, name, progress);
        }
    }
}
