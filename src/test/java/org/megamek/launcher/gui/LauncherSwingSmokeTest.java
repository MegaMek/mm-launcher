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
import org.megamek.launcher.operation.ProgressUnit;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("gui-smoke")
class LauncherSwingSmokeTest {
    @TempDir Path temp;

    @Test
    void showsAccessibleEmptyHomeSplitAndCloses() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing smoke requires a display");
        LauncherFrame[] holder = new LauncherFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            holder[0] = new LauncherFrame(
                    new LauncherServices(temp.resolve("registry.json")) {
                        @Override public org.megamek.launcher.channel.QuickInstallSnapshot
                                quickInstallSnapshot() {
                            return QuickInstallTestData.snapshot("0.51.0", "0.52.0");
                        }
                    });
            holder[0].showWindow();
        });
        try {
            assertNotNull(waitForButton(holder[0], "downloadAndInstallButton"));
            JButton options = waitForButton(holder[0], "downloadOptionsButton");
            assertNotNull(options);
            JButton existing = waitForButton(holder[0], "useExistingCopyButton");
            assertNotNull(existing, "first Home exposes the secondary import action");
            assertEquals("Use existing installation", existing.getText());
            FirstLaunchSplitButton split = (FirstLaunchSplitButton) options.getParent();
            SwingUtilities.invokeAndWait(options::doClick);
            waitFor(() -> split.popupMenu().isVisible());
            assertEquals(5, split.popupMenu().getComponentCount());
            SwingUtilities.invokeAndWait(split::closePopup);
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
            waitFor(() -> component(frame,
                    "installedVersionsCheckMasterCheckbox") != null);
            assertNotNull(component(frame, "installedVersionsCheckMasterCheckbox"));
            assertNull(find(frame, "saveSettingsButton"),
                    "settings are persisted immediately without a save workflow");
            JPanel settingsSections = component(frame, "settingsSections");
            assertEquals(FirstLaunchPanel.BACKGROUND, settingsSections.getBackground());
            assertNotNull(component(frame, "settingsUpdatesSection"));
            assertNotNull(component(frame, "settingsGameJavaSection"));
            assertNotNull(component(frame, "settingsDiagnosticsSection"));
            assertNotNull(component(frame, "defaultJavaPath"));
            assertNotNull(waitForButton(frame, "changeDefaultJavaButton"));
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
    void failedInstallUsesOneCompactFailureSurfaceAndClosesStream() throws Exception {
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
            JButton repair = find(progress, "repairPublishedInstallButton");
            JButton cancel = find(progress, "operationCancelButton");
            JButton details = find(progress, "operationViewDetailsButton");
            assertFalse(repair.isEnabled());
            assertFalse(repair.isVisible());
            assertTrue(cancel.isVisible());
            assertEquals("Cancel", cancel.getText());
            assertFalse(details.isVisible());
            assertNull(find(progress, "operationViewLogsButton"));
            assertNull(find(progress, "copyOperationDetailsButton"));
            assertNull(findText(progress, "installProgressLog"),
                    "the bounded legacy log is not in the visible component hierarchy");
            assertEquals(1, services.attempts);

            services.release.countDown();
            waitFor(details::isVisible);
            assertEquals("Close", cancel.getText());
            assertTrue(cancel.isVisible());
            assertFalse(repair.isVisible(),
                    "an unpublished failure must not offer repair or generic Retry");
            services.streams.getFirst().print("after close");
            assertTrue(services.streams.getFirst().checkError(), "failed stream must be closed");
            assertFalse(hasShowingDialog(frame, "Downloading and installing failed"),
                    "the progress failure state replaces the generic error dialog");
            SwingUtilities.invokeAndWait(details::doClick);
            JDialog failureDetails = owned(frame, "Installation failed details");
            assertTrue(findText(failureDetails, "errorDetails").getText()
                    .contains("fixture install failure"));
            JButton viewLogs = find(failureDetails, "viewOperationLogsButton");
            SwingUtilities.invokeAndWait(viewLogs::doClick);
            JDialog logViewer = owned(frame, "Local operation logs");
            waitFor(() -> findText(logViewer, "operationLogViewer").getText()
                    .contains("fixture install failure"));
            SwingUtilities.invokeAndWait(logViewer::dispose);
            SwingUtilities.invokeAndWait(failureDetails::dispose);
            assertEquals(1, services.attempts,
                    "failure does not expose an implicit retry");
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
            assertNull(find(progress, "operationViewLogsButton"));
            assertNull(find(progress, "copyOperationDetailsButton"));
            SwingUtilities.invokeAndWait(cancel::doClick);
            waitFor(() -> !progress.isDisplayable());
            services.streams.getFirst().print("after close");
            assertTrue(services.streams.getFirst().checkError(),
                    "cancelled worker must close its attempt stream");
            assertFalse(java.nio.file.Files.exists(temp.resolve("cancel-destination")));
        } finally {
            services.release.countDown();
            dispose(frame);
        }
    }

    @Test
    void progressDialogIsCompactStyledAndKeepsOnlyBoundedHiddenDetails() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing progress controls require a display");
        LauncherFrame frame = onEdt(() ->
                new LauncherFrame(new LauncherServices(temp.resolve("styled-progress.json"))));
        AtomicReference<OperationProgressDialog> holder = new AtomicReference<>();
        AtomicReference<JButton> repairHolder = new AtomicReference<>();
        OperationContext context = new OperationContext(OperationType.FRESH_INSTALL,
                event -> holder.get().onProgress(event));
        try {
            SwingUtilities.invokeAndWait(() -> {
                frame.setVisible(true);
                OperationProgressDialog dialog = new OperationProgressDialog(
                        frame, "Installing MekHQ 0.51.0", "styledFixtureLog", () -> {
                        });
                JButton repair = new JButton("Open Installations");
                repair.setName("styledFixtureRepair");
                repair.setEnabled(false);
                dialog.addActionButton(repair);
                holder.set(dialog);
                repairHolder.set(repair);
                dialog.bind(context);
                dialog.append("x".repeat(70_000) + "tail");
                dialog.setVisible(true);
            });

            JDialog dialog = holder.get();
            JPanel content = component(dialog, "operationProgressContent");
            JPanel summary = component(dialog, "operationSummary");
            JLabel phase = findLabel(dialog, "operationPhaseLabel");
            JProgressBar bar = component(dialog, "operationProgressBar");
            JButton cancel = find(dialog, "operationCancelButton");
            JButton details = find(dialog, "operationViewDetailsButton");
            assertEquals(OperationProgressDialog.BACKGROUND, content.getBackground());
            assertFalse(summary.isOpaque(),
                    "phase, progress, and detail sit directly on the dialog background");
            assertEquals(0, summary.getBorder().getBorderInsets(summary).top);
            assertEquals(0, summary.getBorder().getBorderInsets(summary).left);
            assertEquals(OperationProgressDialog.GOLD, phase.getForeground());
            assertEquals(OperationProgressDialog.GOLD, bar.getForeground());
            assertTrue(cancel instanceof FirstLaunchButton,
                    "Cancel uses the local vector-painted secondary control");
            assertFalse(cancel.isOpaque());
            assertFalse(cancel.isContentAreaFilled());
            assertFalse(cancel.isFocusPainted(),
                    "the custom whole-plate focus outline replaces the OS focus artifact");
            assertTrue(cancel.isVisible());
            assertFalse(details.isVisible());
            assertFalse(repairHolder.get().isVisible());
            assertNull(find(dialog, "operationViewLogsButton"));
            assertNull(find(dialog, "copyOperationDetailsButton"));
            assertNull(findText(dialog, "styledFixtureLog"));
            assertNull(holder.get().logArea().getParent());
            assertTrue(holder.get().logArea().getDocument().getLength() <= 64_000);
            assertTrue(holder.get().logArea().getText().endsWith("tail"));

            context.phase(OperationPhase.DOWNLOAD,
                    "C:\\private\\technical\\package.tar.gz");
            waitFor(() -> "Downloading package".equals(phase.getText()));
            assertEquals("Downloading the verified package…",
                    findLabel(dialog, "operationProgressDetail").getText());

            context.progress(OperationPhase.DOWNLOAD, 282_000_000, 690_000_000,
                    ProgressUnit.BYTES, "282000000 / 690000000 bytes");
            waitFor(() -> "41%".equals(bar.getString()));
            assertEquals("Downloaded 282 MB of 690 MB",
                    findLabel(dialog, "operationProgressDetail").getText());
            assertFalse(bar.getString().contains("282000000"));
            assertTrue(bar.getAccessibleContext().getAccessibleDescription()
                    .contains("Downloaded 282 MB of 690 MB"));

            context.progress(OperationPhase.EXTRACT, 4_218, -1, ProgressUnit.FILES,
                    "Extracted 4218 archive entries");
            waitFor(() -> "Extracting application files".equals(phase.getText()));
            assertEquals("4,218 files processed",
                    findLabel(dialog, "operationProgressDetail").getText());
            assertTrue(bar.isIndeterminate(),
                    "unknown archive totals remain truthfully indeterminate");
            assertFalse(bar.isStringPainted(),
                    "extraction remains indeterminate without a progress-bar string");

            SwingUtilities.invokeAndWait(() -> holder.get().showFailure(
                    new IOException("The package could not be installed."),
                    "fixture logging warning"));
            assertFalse(bar.isVisible());
            assertEquals("Close", cancel.getText());
            assertTrue(cancel.isVisible());
            assertTrue(details.isVisible());
            assertFalse(repairHolder.get().isVisible());
            JLabel logging = findLabel(dialog, "operationLoggingWarning");
            assertTrue(logging.isVisible());
            assertTrue(logging.getText().contains("original failure"));

            SwingUtilities.invokeAndWait(() -> repairHolder.get().setEnabled(true));
            assertTrue(repairHolder.get().isVisible(),
                    "a needed repair action appears only after failure");
        } finally {
            context.finish(org.megamek.launcher.operation.OperationOutcome.FAILED, "done");
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
                    && !cancel.isVisible()
                    && detail.getText().equals(
                    "Finishing update — do not close the launcher."));
            assertFalse(context.requestCancellation().accepted());
            assertFalse(Thread.currentThread().isInterrupted());
            SwingUtilities.invokeAndWait(() -> dialog.dispatchEvent(
                    new java.awt.event.WindowEvent(dialog,
                            java.awt.event.WindowEvent.WINDOW_CLOSING)));
            assertTrue(dialog.isDisplayable(),
                    "window close cannot dismiss progress after the atomic cutoff");
            assertFalse(hasShowingDialog(frame, "Cancellation unavailable"));
        } finally {
            context.finish(org.megamek.launcher.operation.OperationOutcome.SUCCEEDED, "done");
            dispose(frame);
        }
    }

    private static JDialog openDownloadFromManage(LauncherFrame frame) throws Exception {
        JButton download = onEdt(() -> find(frame, "installAnotherVersionButton"));
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

    private static boolean hasShowingDialog(LauncherFrame frame, String title) throws Exception {
        boolean[] result = new boolean[1];
        SwingUtilities.invokeAndWait(() -> {
            for (java.awt.Window window : frame.getOwnedWindows()) {
                if (window instanceof JDialog dialog && dialog.isShowing()
                        && title.equals(dialog.getTitle())) {
                    result[0] = true;
                }
            }
        });
        return result[0];
    }

    private static <T extends Component> T component(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName())) {
                @SuppressWarnings("unchecked") T cast = (T) child;
                return cast;
            }
            if (child instanceof Container container) {
                T found = component(container, name);
                if (found != null) return found;
            }
        }
        return null;
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
