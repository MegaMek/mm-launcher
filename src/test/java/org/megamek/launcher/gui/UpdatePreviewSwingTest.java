package org.megamek.launcher.gui;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.megamek.launcher.channel.ChannelPreferenceStore;
import org.megamek.launcher.channel.FollowChannel;
import org.megamek.launcher.launch.ApplicationLauncher;
import org.megamek.launcher.launch.JavaRuntime;
import org.megamek.launcher.onboarding.InstallationInspector;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.release.OfficialRepository;
import org.megamek.launcher.release.ReleaseTransport;
import org.megamek.launcher.update.OwnershipPolicy;
import org.megamek.launcher.update.ReceiptStore;
import org.megamek.launcher.launch.RootCoordinator;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.MenuSelectionManager;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdatePreviewSwingTest {
    @TempDir Path temp;

    @Test
    void importedCopyKeepsLaunchAndManageWhileExplainingPreviewUnavailable() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing controls require a display");
        Fixture fixture = fixture("imported", false);
        LauncherFrame frame = show(fixture);
        try {
            assertNotNull(waitForButton(frame, "launch-megamek-button"));
            JButton installations = waitForButton(frame, "manageInstallationsButton");
            assertNotNull(installations);
            assertTrue(installations.getText().equals("Installations"));
            assertTrue(findButton(frame, "recoverUpdateButton") == null,
                    "an imported copy without a pending update has no Home recovery action");
            SwingUtilities.invokeAndWait(installations::doClick);
            JLabel status = findLabel(frame, "installationStatus-" + fixture.record.id());
            assertNotNull(status);
            assertTrue(status.getText().contains("unavailable"));
            JPopupMenu menu = openInstallationMenu(frame, fixture.record.id());
            assertNull(findMenuItem(menu, "Update checks…"),
                    "an imported copy must not offer channel/check configuration");
            assertNull(findMenuItem(menu, "Preview update"),
                    "an imported copy must not offer an unsupported preview action");
            assertTrue(fixture.network.requests.isEmpty(), "home rendering must not use network");
        } finally {
            dispose(frame);
        }
    }

    @Test
    void validReceiptRemainsPreviewAvailableWhenLocalJarIsDamagedAndLaunchIsDisabled()
            throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing controls require a display");
        Fixture fixture = fixture("owned", true);
        Files.writeString(fixture.root.resolve("MegaMek.jar"), "locally changed",
                StandardCharsets.UTF_8);
        LauncherFrame frame = show(fixture);
        try {
            JButton installations = waitForButton(frame, "manageInstallationsButton");
        JButton launch = waitForButton(frame, "launch-megamek-button");
        assertNotNull(launch);
        assertFalse(launch.isEnabled());
        assertNotNull(installations);
        SwingUtilities.invokeAndWait(installations::doClick);
        JPopupMenu menu = openInstallationMenu(frame, fixture.record.id());
        JMenuItem preview = findMenuItem(menu, "Preview update");
        assertNotNull(preview);
        assertTrue(preview.isEnabled());
            assertTrue(fixture.network.requests.isEmpty(), "startup must not check releases");
        } finally {
            dispose(frame);
        }
    }

    @Test
    void recoveryRemainsReachableWhenPendingRootCannotBeInspected() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing controls require a display");
        Fixture fixture = fixture("pending-damaged", true);
        Files.createDirectory(fixture.root.resolve(RootCoordinator.UPDATE_NAMESPACE));
        Files.delete(fixture.root.resolve("MegaMek.jar"));
        LauncherFrame frame = show(fixture);
        try {
            JButton blocker = waitForButton(frame, "resolveHomeBlockerButton");
            assertNotNull(blocker);
            SwingUtilities.invokeAndWait(blocker::doClick);
            JButton recover = waitForButton(frame, "recoverUpdateButton");
            assertNotNull(recover);
            assertTrue(recover.isEnabled());
            assertNotNull(findButton(frame, "homeButton"));
            assertTrue(fixture.network.requests.isEmpty());
        } finally {
            dispose(frame);
        }
    }

    private Fixture fixture(String name, boolean receipt) throws Exception {
        Path area = Files.createDirectory(temp.resolve(name));
        Path root = createSuite(area.resolve("installed"));
        Path registry = area.resolve("registry.json");
        RegistryStore store = new RegistryStore();
        var record = store.register(registry, "Fixture", root, null);
        if (receipt) {
            var build = new OwnershipPolicy().build(root, OfficialRepository.MEGAMEK, "v-old");
            var ownership = new ReceiptStore().write(registry, store.read(registry), record,
                    OfficialRepository.MEGAMEK, "v-old", "MegaMek-v-old.tar.gz", 10,
                    "sha256:" + "a".repeat(64), build);
            new ChannelPreferenceStore().initializeManaged(registry, record, ownership,
                    FollowChannel.MILESTONE, false);
        }
        CountingTransport network = new CountingTransport();
        LauncherServices services = new LauncherServices(registry, store,
                new InstallationInspector(), network, new JavaRuntime(),
                new ApplicationLauncher());
        return new Fixture(root, record, services, network);
    }

    private static LauncherFrame show(Fixture fixture) throws Exception {
        LauncherFrame[] result = new LauncherFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            result[0] = new LauncherFrame(fixture.services);
            result[0].showWindow();
        });
        return result[0];
    }

    private static Path createSuite(Path root) throws Exception {
        Files.createDirectory(root);
        Files.createDirectories(root.resolve("data"));
        Files.createDirectories(root.resolve("lib"));
        Files.createDirectories(root.resolve("mmconf"));
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "megamek.MegaMek");
        try (JarOutputStream jar = new JarOutputStream(
                Files.newOutputStream(root.resolve("MegaMek.jar")), manifest)) {
            jar.putNextEntry(new JarEntry("megamek/Version.properties"));
            jar.write("major=1\nminor=2\npatch=3\n".getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return root;
    }

    private static JButton waitForButton(Container root, String name) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            JButton[] result = new JButton[1];
            SwingUtilities.invokeAndWait(() -> result[0] = findButton(root, name));
            if (result[0] != null) return result[0];
            Thread.sleep(25);
        }
        return null;
    }

    private static JButton findButton(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (child instanceof JButton button && name.equals(button.getName())) return button;
            if (child instanceof Container nested) {
                JButton found = findButton(nested, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JLabel findLabel(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (child instanceof JLabel label && name.equals(label.getName())) return label;
            if (child instanceof Container nested) {
                JLabel found = findLabel(nested, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static JPopupMenu openInstallationMenu(Container root, String recordId)
            throws Exception {
        JButton button = waitForButton(root, "installationMenuButton-" + recordId);
        assertNotNull(button);
        SwingUtilities.invokeAndWait(button::doClick);
        for (int attempt = 0; attempt < 100; attempt++) {
            JPopupMenu[] result = new JPopupMenu[1];
            SwingUtilities.invokeAndWait(() -> {
                for (var element : MenuSelectionManager.defaultManager().getSelectedPath()) {
                    if (element instanceof JPopupMenu menu) {
                        result[0] = menu;
                        break;
                    }
                }
            });
            if (result[0] != null) return result[0];
            Thread.sleep(25);
        }
        throw new AssertionError("timed out waiting for installation menu");
    }

    private static JMenuItem findMenuItem(JPopupMenu menu, String text) {
        for (Component component : menu.getComponents()) {
            if (component instanceof JMenuItem item && text.equals(item.getText())) return item;
        }
        return null;
    }

    private static void dispose(LauncherFrame frame) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (java.awt.Window window : frame.getOwnedWindows()) window.dispose();
            frame.dispose();
        });
    }

    private record Fixture(Path root, org.megamek.launcher.registry.InstallationRecord record,
                           LauncherServices services, CountingTransport network) {
    }

    private static final class CountingTransport implements ReleaseTransport {
        private final List<URI> requests = new ArrayList<>();

        @Override
        public Response get(URI uri, String accept) throws IOException {
            requests.add(uri);
            throw new IOException("unexpected network request");
        }
    }
}
