package org.megamek.launcher.gui;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.megamek.launcher.launch.ApplicationLauncher;
import org.megamek.launcher.launch.JavaRuntime;
import org.megamek.launcher.onboarding.InstallationInspector;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.release.OfficialRepository;
import org.megamek.launcher.release.ReleaseTransport;
import org.megamek.launcher.update.OwnershipPolicy;
import org.megamek.launcher.update.ReceiptStore;

import javax.swing.JButton;
import javax.swing.JLabel;
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
            JButton preview = waitForButton(frame, "previewUpdateButton");
            assertNotNull(preview);
            assertFalse(preview.isEnabled());
            assertNotNull(findButton(frame, "launch-megamek-button"));
            assertNotNull(findButton(frame, "manageInstallationsButton"));
            JLabel note = findLabel(frame, "previewEligibilityNote");
            assertNotNull(note);
            assertTrue(note.getText().contains("Preview unavailable:"));
            assertTrue(note.getText().contains("receipt is absent"));
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
            JButton preview = waitForButton(frame, "previewUpdateButton");
            assertNotNull(preview);
            assertTrue(preview.isEnabled());
            assertTrue(findLabel(frame, "previewEligibilityNote").getText()
                    .contains("Receipt verified"));
            assertTrue(findButton(frame, "launch-megamek-button") == null);
            assertNotNull(findButton(frame, "manageInstallationsButton"));
            assertTrue(fixture.network.requests.isEmpty(), "startup must not check releases");
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
            new ReceiptStore().write(registry, store.read(registry), record,
                    OfficialRepository.MEGAMEK, "v-old", "MegaMek-v-old.tar.gz", 10,
                    "sha256:" + "a".repeat(64), build);
        }
        CountingTransport network = new CountingTransport();
        LauncherServices services = new LauncherServices(registry, store,
                new InstallationInspector(), network, new JavaRuntime(),
                new ApplicationLauncher());
        return new Fixture(root, services, network);
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

    private static void dispose(LauncherFrame frame) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (java.awt.Window window : frame.getOwnedWindows()) window.dispose();
            frame.dispose();
        });
    }

    private record Fixture(Path root, LauncherServices services, CountingTransport network) {
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
