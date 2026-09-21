package org.megamek.launcher.gui;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.megamek.launcher.channel.ChannelPreferenceStore;
import org.megamek.launcher.launch.ApplicationLauncher;
import org.megamek.launcher.launch.JavaRuntime;
import org.megamek.launcher.launch.ProcessRunner;
import org.megamek.launcher.onboarding.InstallationInspector;
import org.megamek.launcher.registry.RegistryData;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.release.ReleaseTransport;
import org.megamek.launcher.update.ReceiptStore;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("gui-smoke")
class ExistingImportSwingTest {
    @TempDir Path temp;

    @Test
    void directImportRemainsAvailableWhileQuickInstallMetadataIsLoading()
            throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing controls require a display");
        Path existing = suite("loading-import", List.of("megamek"));
        Path registry = temp.resolve("loading-import-registry.json");
        CountDownLatch snapshotStarted = new CountDownLatch(1);
        CountDownLatch releaseSnapshot = new CountDownLatch(1);
        AtomicInteger snapshots = new AtomicInteger();
        LauncherServices services = services(registry, new RecordingRunner(), () -> {
            snapshots.incrementAndGet();
            snapshotStarted.countDown();
            try {
                releaseSnapshot.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return QuickInstallTestData.snapshot("0.51.0", "0.52.0");
        });
        RecordingPrompts prompts = new RecordingPrompts(List.of(existing));
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services, prompts));
        try {
            onEdt(() -> {
                frame.showWindow();
                return null;
            });
            assertTrue(snapshotStarted.await(5, TimeUnit.SECONDS));
            JButton importButton = waitFor(() -> findButton(frame, "useExistingCopyButton"));
            assertTrue(importButton.isEnabled());
            assertFalse(waitFor(() -> findButton(
                    frame, "downloadAndInstallButton")).isEnabled());

            onEdt(() -> {
                importButton.doClick();
                return null;
            });

            assertNotNull(waitFor(() -> findButton(frame, "launch-megamek-button")));
            releaseSnapshot.countDown();
            Thread.sleep(100);
            assertNotNull(onEdt(() -> findButton(frame, "launch-megamek-button")));
            assertNull(onEdt(() -> find(frame, "firstLaunchSplitButton")));
            assertEquals(1, snapshots.get());
            assertEquals(1, prompts.folderRequests);
        } finally {
            releaseSnapshot.countDown();
            dispose(frame);
        }
    }

    @Test
    void visibleAndAdvancedActionsShareAtomicImportAndHomeShowsActualProducts()
            throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing controls require a display");
        Path megaMek = suite("MegaMek-only", List.of("megamek"));
        Path full = suite("MekHQ-bundle", List.of("megamek", "mekhq", "lab"));
        Path registry = temp.resolve("registry.json");
        RecordingRunner runner = new RecordingRunner();
        LauncherServices services = services(registry, runner);
        RecordingPrompts prompts = new RecordingPrompts(List.of(megaMek, full));
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services, prompts));
        try {
            onEdt(() -> {
                frame.showWindow();
                return null;
            });
            JButton visibleImport = waitFor(() -> findButton(frame, "useExistingCopyButton"));
            assertEquals(1, countNamed(frame, "useExistingCopyButton"));
            assertEquals("Use existing installation", visibleImport.getText());
            JLabel caption = waitFor(() -> (JLabel) find(frame, "existingCopyCaption"));
            assertEquals("MegaMek, MekHQ, or MegaMekLab", caption.getText());

            onEdt(() -> {
                visibleImport.doClick();
                return null;
            });
            assertNotNull(waitFor(() -> findButton(frame, "launch-megamek-button")));
            assertNull(onEdt(() -> findButton(frame, "launch-mekhq-button")));
            assertNull(onEdt(() -> findButton(frame, "launch-lab-button")));
            assertFalse(hasShowingDialog(frame, "Input"));
            assertFalse(hasShowingDialog(frame, "Confirm existing installation"));
            assertFalse(hasShowingDialog(frame, "Import complete"));
            assertEquals("MegaMek existing installation",
                    services.loadHome().preferred().name());

            JButton installations =
                    waitFor(() -> findButton(frame, "manageInstallationsButton"));
            onEdt(() -> {
                installations.doClick();
                return null;
            });
            JButton advancedImport =
                    waitFor(() -> findButton(frame, "manageAddExistingButton"));
            onEdt(() -> {
                advancedImport.doClick();
                return null;
            });
            assertNotNull(waitFor(() -> findButton(frame, "manageInstallationsButton")),
                    "successful import follows the normal install flow back to Home");
            assertFalse(hasShowingDialog(frame, "Input"));
            assertFalse(hasShowingDialog(frame, "Confirm existing installation"));
            assertFalse(hasShowingDialog(frame, "Import complete"));

            RegistryData data = services.readRegistry();
            assertEquals(2, data.installations().size());
            assertEquals("MegaMek existing installation",
                    services.loadHome().preferred().name());
            assertTrue(data.installations().stream().anyMatch(record ->
                    "MekHQ existing installation".equals(record.name())));
            assertFalse(Files.readString(registry).contains("javaExecutable"));
            assertEquals(2, prompts.folderRequests);
            assertTrue(runner.commands.isEmpty(), "import must not execute Java");
            ChannelPreferenceStore channels = new ChannelPreferenceStore();
            for (var record : data.installations()) {
                assertFalse(Files.exists(channels.path(registry, record.id())),
                        "an imported folder must not acquire a channel sidecar");
                assertFalse(Files.exists(new ReceiptStore().receiptPath(registry, record.id())),
                        "an imported folder must remain receipt-less");
            }

            JButton manageAgain = waitFor(() -> findButton(frame,
                    "manageInstallationsButton"));
            onEdt(() -> {
                manageAgain.doClick();
                return null;
            });
            for (var record : data.installations()) {
                JLabel provenance = waitFor(() -> (JLabel) find(frame,
                        "installationProvenance-" + record.id()));
                assertEquals("Imported copy · Launch only · Updates unavailable",
                        provenance.getText());
                assertNotNull(waitFor(() -> findButton(frame,
                        "enableManagedUpdatesButton-" + record.id())));
                assertNull(find(frame, "checkOnOpenCheckbox-" + record.id()));
            }
            assertNull(onEdt(() -> findButton(frame, "chooseChannelButton")));
            assertNull(onEdt(() -> findButton(frame, "updateChecksButton")));
            assertNull(onEdt(() -> findButton(frame, "checkUpdatesButton")));
            assertNull(onEdt(() -> findButton(frame, "applyUpdateButton")));
            assertNull(onEdt(() -> findButton(frame, "recoverUpdateButton")));
        } finally {
            dispose(frame);
        }
    }

    @Test
    void cancellingFolderChoiceLeavesRegistryAndSelectedFilesUntouched() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing controls require a display");
        Path root = suite("cancelled", List.of("megamek"));
        Map<Path, Long> before = inventory(root);
        Path registry = temp.resolve("cancelled-registry.json");
        RecordingPrompts prompts = new RecordingPrompts(List.of());
        LauncherFrame frame = onEdt(() -> new LauncherFrame(
                services(registry, new RecordingRunner()), prompts));
        try {
            onEdt(() -> {
                frame.showWindow();
                return null;
            });
            JButton importButton = waitFor(() -> findButton(frame, "useExistingCopyButton"));
            onEdt(() -> {
                importButton.doClick();
                return null;
            });
            assertFalse(Files.exists(registry));
            assertEquals(before, inventory(root));
            assertEquals(1, prompts.folderRequests);
            assertNotNull(waitFor(() -> findButton(frame, "useExistingCopyButton")));
        } finally {
            dispose(frame);
        }
    }

    @Test
    void adoptionStartIsCompactAndCancelOrEscapeMakesNoRequest() throws Exception {
            Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                    "actual Swing controls require a display");
            Path root = versionedMegaMekSuite("adoption-dialog");
            Path registry = temp.resolve("adoption-dialog-registry.json");
            AtomicInteger requests = new AtomicInteger();
            ReleaseTransport recording = (uri, accept) -> {
                requests.incrementAndGet();
                throw new IOException("network must not run before Continue");
            };
            LauncherServices services = new LauncherServices(
                    registry, new RegistryStore(), new InstallationInspector(), recording,
                    new JavaRuntime(new RecordingRunner()),
                    new ApplicationLauncher(new RecordingRunner()));
            var record = services.register("Imported", root);
            LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
            try {
                onEdt(() -> {
                    frame.showWindow();
                    return null;
                });
                JButton manage = waitFor(() -> findButton(frame, "manageInstallationsButton"));
                onEdt(() -> {
                    manage.doClick();
                    return null;
                });
                JButton enable = waitFor(() -> findButton(frame,
                        "enableManagedUpdatesButton-" + record.id()));
                onEdt(() -> {
                    enable.doClick();
                    return null;
                });
                JDialog dialog = waitFor(() -> findDialog(frame, "adoptionCandidateDialog"));
                JButton cancel = (JButton) find(dialog, "cancelAdoptionButton");
                JButton proceed = (JButton) find(dialog, "continueAdoptionButton");
                JLabel application = (JLabel) find(dialog,
                        "adoptionDetectedApplicationVersion");
                JComboBox<?> channel = (JComboBox<?>) find(dialog, "adoptionChannelCombo");
                assertEquals("MegaMek (0.50.7)", application.getText());
                assertEquals(org.megamek.launcher.channel.FollowChannel.MILESTONE,
                        channel.getSelectedItem());
                assertTrue(cancel.getX() < proceed.getX());
                assertTrue(dialog.getHeight() < dialog.getWidth(), "dialog remains compact");
                assertNull(find(dialog, "chooseAdoptionReleaseButton"));
                assertNull(find(dialog, "verifyAdoptionButton"));
                assertEquals(0, countButtonText(dialog, "Verify copy"));
                assertEquals(0, countButtonText(dialog, "Choose official release…"));
                onEdt(() -> {
                    dialog.getRootPane().getActionMap().get("cancelAdoption").actionPerformed(
                            new java.awt.event.ActionEvent(dialog, 0, "escape"));
                    return null;
                });
                assertEquals(0, requests.get());

                onEdt(() -> {
                    enable.doClick();
                    return null;
                });
                JDialog reopened = waitFor(() -> findDialog(frame, "adoptionCandidateDialog"));
                onEdt(() -> {
                    ((JButton) find(reopened, "cancelAdoptionButton")).doClick();
                    return null;
                });
                assertEquals(0, requests.get());
            } finally {
                dispose(frame);
            }
    }

    @Test
    void automaticMatchFailureIsSimpleDeduplicatedAndOnlyThenOffersChooser()
            throws Exception {
            Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                    "actual Swing controls require a display");
            Path root = versionedMegaMekSuite("adoption-failure");
            Path registry = temp.resolve("adoption-failure-registry.json");
            AtomicInteger requests = new AtomicInteger();
            ReleaseTransport missing = (uri, accept) -> {
                requests.incrementAndGet();
                return new ReleaseTransport.Response(404, Map.of(),
                        new java.io.ByteArrayInputStream(
                                "{\"message\":\"Not Found\"}".getBytes(
                                        java.nio.charset.StandardCharsets.UTF_8)));
            };
            LauncherServices services = new LauncherServices(
                    registry, new RegistryStore(), new InstallationInspector(), missing,
                    new JavaRuntime(new RecordingRunner()),
                    new ApplicationLauncher(new RecordingRunner()));
            var record = services.register("Imported", root);
            LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
            try {
                onEdt(() -> {
                    frame.showWindow();
                    return null;
                });
                JButton manage = waitFor(() -> findButton(frame, "manageInstallationsButton"));
                onEdt(() -> {
                    manage.doClick();
                    return null;
                });
                JButton enable = waitFor(() -> findButton(frame,
                        "enableManagedUpdatesButton-" + record.id()));
                onEdt(() -> {
                    enable.doClick();
                    return null;
                });
                JDialog start = waitFor(() -> findDialog(frame, "adoptionCandidateDialog"));
                JButton proceed = (JButton) find(start, "continueAdoptionButton");
                onEdt(() -> {
                    proceed.doClick();
                    proceed.doClick();
                    return null;
                });

                JButton fallback = waitFor(() -> {
                    JButton button = (JButton) findOwned(frame,
                            "browseAfterAdoptionFailureButton");
                    return button != null && button.isShowing() && button.isEnabled()
                            ? button : null;
                });
                JLabel detail = waitFor(() -> (JLabel) findOwned(
                        frame, "operationProgressDetail"));
                assertTrue(detail.getText().contains(
                        "We couldn’t find the matching official version."));
                assertFalse(detail.getText().contains("404"));
                assertEquals(1, requests.get(), "Continue is single-flight");
                assertTrue(services.operationLogsForViewer().contains("HTTP 404"),
                        "technical reason remains in sanitized local diagnostics");

                onEdt(() -> {
                    fallback.doClick();
                    return null;
                });
                assertNotNull(waitFor(() -> findDialog(frame, "adoptionReleaseBrowser")));
                assertEquals(1, requests.get(),
                        "opening fallback does not fetch until its explicit Fetch versions action");
            } finally {
                dispose(frame);
        }
    }

    private LauncherServices services(Path registry, RecordingRunner runner) {
        return services(registry, runner,
                () -> QuickInstallTestData.snapshot("0.51.0", "0.52.0"));
    }

    private LauncherServices services(
            Path registry, RecordingRunner runner,
            Supplier<org.megamek.launcher.channel.QuickInstallSnapshot> snapshots) {
        ReleaseTransport noNetwork = new ReleaseTransport() {
            @Override
            public Response get(URI uri, String accept) {
                throw new AssertionError("existing import must not use the network");
            }
        };
        return new LauncherServices(
                registry, new RegistryStore(), new InstallationInspector(),
                noNetwork, new JavaRuntime(runner), new ApplicationLauncher(runner)) {
            @Override public org.megamek.launcher.channel.QuickInstallSnapshot
                    quickInstallSnapshot() {
                return snapshots.get();
            }
        };
    }

    private Path suite(String name, List<String> products) throws Exception {
        Path root = Files.createDirectory(temp.resolve(name));
        Files.createDirectories(root.resolve("data"));
        Files.createDirectories(root.resolve("mmconf"));
        Files.createDirectories(root.resolve("lib"));
        Map<String, String> jars = Map.of(
                "megamek", "MegaMek.jar",
                "mekhq", "MekHQ.jar",
                "lab", "MegaMekLab.jar");
        Map<String, String> mains = Map.of(
                "megamek", "megamek.MegaMek",
                "mekhq", "mekhq.MekHQ",
                "lab", "megameklab.MegaMekLab");
        for (String product : products) {
            Manifest manifest = new Manifest();
            manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
            manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, mains.get(product));
            try (JarOutputStream ignored = new JarOutputStream(
                    Files.newOutputStream(root.resolve(jars.get(product))), manifest)) {
            }
        }
        return root;
    }

    private Path versionedMegaMekSuite(String name) throws Exception {
        Path root = Files.createDirectory(temp.resolve(name));
        Files.createDirectories(root.resolve("data"));
        Files.createDirectories(root.resolve("mmconf"));
        Files.createDirectories(root.resolve("lib"));
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "megamek.MegaMek");
        try (JarOutputStream jar = new JarOutputStream(
                Files.newOutputStream(root.resolve("MegaMek.jar")), manifest)) {
            jar.putNextEntry(new java.util.jar.JarEntry("megamek/Version.properties"));
            jar.write("major=0\nminor=50\npatch=7\n".getBytes(
                    java.nio.charset.StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return root;
    }

    private static Map<Path, Long> inventory(Path root) throws Exception {
        try (var files = Files.walk(root)) {
            return files.filter(Files::isRegularFile).collect(java.util.stream.Collectors.toMap(
                    root::relativize, path -> {
                        try {
                            return Files.size(path);
                        } catch (java.io.IOException error) {
                            throw new java.io.UncheckedIOException(error);
                        }
                    }));
        }
    }

    private static int countNamed(Container root, String name) {
        int count = 0;
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName())) count++;
            if (child instanceof Container nested) count += countNamed(nested, name);
        }
        return count;
    }

    private static Component find(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container nested) {
                Component found = find(nested, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JButton findButton(Container root, String name) {
        Component found = find(root, name);
        return found instanceof JButton button ? button : null;
    }

    private static int countButtonText(Container root, String text) {
        int count = 0;
        for (Component child : root.getComponents()) {
            if (child instanceof JButton button && text.equals(button.getText())) count++;
            if (child instanceof Container nested) count += countButtonText(nested, text);
        }
        return count;
    }

    private static JDialog findDialog(LauncherFrame frame, String name) {
        for (java.awt.Window window : frame.getOwnedWindows()) {
            if (window instanceof JDialog dialog && dialog.isShowing()
                    && name.equals(dialog.getName())) return dialog;
        }
        return null;
    }

    private static Component findOwned(LauncherFrame frame, String name) {
        for (java.awt.Window window : frame.getOwnedWindows()) {
            Component found = find(window, name);
            if (found != null) return found;
        }
        return null;
    }

    private static boolean hasShowingDialog(LauncherFrame frame, String title) {
        for (java.awt.Window window : frame.getOwnedWindows()) {
            if (window instanceof javax.swing.JDialog dialog && dialog.isShowing()
                    && title.equals(dialog.getTitle())) return true;
        }
        return false;
    }

    private static <T> T waitFor(java.util.function.Supplier<T> probe) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < deadline) {
            T value = onEdt(probe::get);
            if (value != null) return value;
            Thread.sleep(20);
        }
        throw new AssertionError("timed out waiting for Swing state");
    }

    private static void waitUntil(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < deadline) {
            if (onEdt(condition::getAsBoolean)) return;
            Thread.sleep(20);
        }
        throw new AssertionError("timed out waiting for Swing state");
    }

    private static <T> T onEdt(java.util.concurrent.Callable<T> action) throws Exception {
        Object[] result = new Object[1];
        Throwable[] failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                result[0] = action.call();
            } catch (Throwable error) {
                failure[0] = error;
            }
        });
        if (failure[0] != null) {
            if (failure[0] instanceof Exception exception) throw exception;
            throw (Error) failure[0];
        }
        @SuppressWarnings("unchecked") T cast = (T) result[0];
        return cast;
    }

    private static void dispose(LauncherFrame frame) throws Exception {
        onEdt(() -> {
            for (java.awt.Window window : frame.getOwnedWindows()) window.dispose();
            frame.dispose();
            return null;
        });
    }

    private static final class RecordingRunner implements ProcessRunner {
        private final List<List<String>> commands =
                java.util.Collections.synchronizedList(new ArrayList<>());

        @Override
        public Result run(List<String> command, Path workingDirectory, Duration timeout,
                          boolean inheritIo) {
            assertFalse(SwingUtilities.isEventDispatchThread(),
                    "any Java process must run off the EDT");
            commands.add(List.copyOf(command));
            return new Result(0, "openjdk version \"21.0.8\"", false);
        }
    }

    private static final class RecordingPrompts implements LauncherFrame.ExistingImportPrompts {
        private final Deque<Path> folders;
        private int folderRequests;

        private RecordingPrompts(List<Path> folders) {
            this.folders = new ArrayDeque<>(folders);
        }

        @Override
        public Path chooseFolder(Component parent) {
            assertTrue(SwingUtilities.isEventDispatchThread());
            folderRequests++;
            return folders.isEmpty() ? null : folders.removeFirst();
        }
    }
}
