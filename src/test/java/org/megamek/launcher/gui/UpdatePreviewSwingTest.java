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
            JLabel status = findLabel(frame, "installationProvenance-" + fixture.record.id());
            assertNotNull(status);
            assertTrue(status.getText().contains("Updates unavailable"));
            JPopupMenu menu = openInstallationMenu(frame, fixture.record.id());
            assertNull(findMenuItem(menu, "Update checks…"),
                    "an imported copy must not offer channel/check configuration");
            assertNull(findMenuItem(menu, "Preview update"),
                    "an imported copy must not offer an unsupported preview action");
            assertNull(findMenuItem(menu, "Change Java"));
            assertNotNull(findMenuItem(menu, "Open location"));
            assertNotNull(findMenuItem(menu, "Remove from launcher…"));
            assertNull(findMenuItem(menu, "Uninstall…"));
            assertTrue(fixture.network.requests.isEmpty(), "home rendering must not use network");
        } finally {
            dispose(frame);
        }
    }

    @Test
    void validReceiptStillHasNoStandalonePreviewActionWhenLaunchIsDisabled()
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
        assertNull(preview);
        assertNotNull(findMenuItem(menu, "Open location"));
        assertNotNull(findMenuItem(menu, "Remove from launcher…"));
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
