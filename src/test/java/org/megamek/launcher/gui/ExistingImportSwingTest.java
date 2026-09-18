package org.megamek.launcher.gui;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.megamek.launcher.launch.ApplicationLauncher;
import org.megamek.launcher.launch.JavaRuntime;
import org.megamek.launcher.launch.ProcessRunner;
import org.megamek.launcher.onboarding.InstallationInspector;
import org.megamek.launcher.registry.RegistryData;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.release.ReleaseTransport;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
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
    void visibleAndAdvancedActionsShareAtomicImportAndHomeShowsActualProducts()
            throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing controls require a display");
        Path megaMek = suite("MegaMek-only", List.of("megamek"));
        Path full = suite("MekHQ-bundle", List.of("megamek", "mekhq", "lab"));
        Path registry = temp.resolve("registry.json");
        RecordingRunner runner = new RecordingRunner();
        LauncherServices services = services(registry, runner);
        RecordingPrompts prompts = new RecordingPrompts(
                List.of(megaMek, full), List.of("Imported MegaMek", "Imported MekHQ"),
                List.of(true, true));
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
            waitUntil(() -> prompts.completed.size() == 1);
            assertTrue(prompts.confirmations.getFirst().contains("Programs: MegaMek"));
            assertFalse(prompts.confirmations.getFirst().contains("MegaMekLab"));
            assertTrue(prompts.confirmations.getFirst().contains("Java 21 detected"));
            assertTrue(prompts.confirmations.getFirst().contains(
                    "Existing files will stay where they are and will not be moved"));
            assertNotNull(waitFor(() -> findButton(frame, "launch-megamek-button")));
            assertNull(onEdt(() -> findButton(frame, "launch-mekhq-button")));
            assertNull(onEdt(() -> findButton(frame, "launch-lab-button")));
            assertTrue(prompts.completed.getFirst().contains("made it Main"));

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
            waitUntil(() -> prompts.completed.size() == 2);
            assertTrue(prompts.confirmations.get(1).contains(
                    "Programs: MegaMek, MegaMekLab, MekHQ"));
            assertTrue(prompts.completed.get(1).contains("existing Main installation was not "
                    + "changed"));
            assertNotNull(waitFor(() -> find(frame, "installationList")),
                    "advanced import remains on Installations");

            RegistryData data = services.readRegistry();
            String launcherJava = currentJava();
            assertEquals(2, data.installations().size());
            assertEquals("Imported MegaMek", services.loadHome().preferred().name());
            assertTrue(data.installations().stream().allMatch(record ->
                    record.javaExecutable().equals(launcherJava)));
            assertEquals(2, prompts.folderRequests);
            assertTrue(runner.commands.stream().allMatch(command ->
                    command.equals(List.of(launcherJava, "-version"))));
        } finally {
            dispose(frame);
        }
    }

    @Test
    void cancellingRealConfirmationLeavesRegistryAndSelectedFilesUntouched() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing controls require a display");
        Path root = suite("cancelled", List.of("megamek"));
        Map<Path, Long> before = inventory(root);
        Path registry = temp.resolve("cancelled-registry.json");
        RecordingPrompts prompts = new RecordingPrompts(List.of(root), List.of("Cancelled"),
                List.of(false));
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
            waitUntil(() -> prompts.confirmations.size() == 1);
            waitUntil(() -> !hasShowingDialog(frame, "Inspecting existing installation"));
            assertFalse(Files.exists(registry));
            assertEquals(before, inventory(root));
            assertTrue(prompts.completed.isEmpty());
            assertNotNull(waitFor(() -> findButton(frame, "useExistingCopyButton")));
        } finally {
            dispose(frame);
        }
    }

    private LauncherServices services(Path registry, RecordingRunner runner) {
        ReleaseTransport noNetwork = new ReleaseTransport() {
            @Override
            public Response get(URI uri, String accept) {
                throw new AssertionError("existing import must not use the network");
            }
        };
        return new LauncherServices(registry, new RegistryStore(), new InstallationInspector(),
                noNetwork, new JavaRuntime(runner), new ApplicationLauncher(runner)) {
            @Override public String latestMilestoneVersion() {
                return "0.51.0";
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

    private static String currentJava() throws Exception {
        return new JavaRuntime(new RecordingRunner()).currentExecutable().toString();
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
                    "Java validation and registration preparation must run off the EDT");
            commands.add(List.copyOf(command));
            return new Result(0, "openjdk version \"21.0.8\"", false);
        }
    }

    private static final class RecordingPrompts implements LauncherFrame.ExistingImportPrompts {
        private final Deque<Path> folders;
        private final Deque<String> names;
        private final Deque<Boolean> answers;
        private final List<String> confirmations = new ArrayList<>();
        private final List<String> completed = new ArrayList<>();
        private int folderRequests;

        private RecordingPrompts(List<Path> folders, List<String> names, List<Boolean> answers) {
            this.folders = new ArrayDeque<>(folders);
            this.names = new ArrayDeque<>(names);
            this.answers = new ArrayDeque<>(answers);
        }

        @Override
        public Path chooseFolder(Component parent) {
            assertTrue(SwingUtilities.isEventDispatchThread());
            folderRequests++;
            return folders.removeFirst();
        }

        @Override
        public String chooseName(Component parent, String defaultName) {
            assertTrue(SwingUtilities.isEventDispatchThread());
            assertFalse(defaultName.isBlank());
            return names.removeFirst();
        }

        @Override
        public boolean confirm(Component parent, String details) {
            assertTrue(SwingUtilities.isEventDispatchThread());
            confirmations.add(details);
            return answers.removeFirst();
        }

        @Override
        public void completed(Component parent, String message) {
            assertTrue(SwingUtilities.isEventDispatchThread());
            completed.add(message);
        }
    }
}
