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
import org.megamek.launcher.channel.ChannelPreferenceStore;
import org.megamek.launcher.channel.FollowChannel;
import org.megamek.launcher.launch.ApplicationLauncher;
import org.megamek.launcher.launch.JavaRuntime;
import org.megamek.launcher.onboarding.InstallationInspector;
import org.megamek.launcher.operation.OperationContext;
import org.megamek.launcher.plan.Action;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.release.OfficialRepository;
import org.megamek.launcher.release.ReleaseCatalog;
import org.megamek.launcher.release.ReleaseTransport;
import org.megamek.launcher.update.OwnershipPolicy;
import org.megamek.launcher.update.OwnershipReceipt;
import org.megamek.launcher.update.ReceiptStore;
import org.megamek.launcher.update.UpdatePreviewService;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.MenuSelectionManager;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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

class UpdatePreviewConsentMaterialTest {
    @TempDir Path temp;

    @Test
    void standalonePreviewActionIsAbsent()
            throws Exception {
        Fixture fixture = fixture();
        LauncherFrame frame = onEdt(() -> new LauncherFrame(fixture.services));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            JButton installations = waitFor(
                    () -> find(frame, "manageInstallationsButton"));
            SwingUtilities.invokeAndWait(installations::doClick);
            JPopupMenu menu = openInstallationMenu(frame, fixture.first.id());
            assertNull(findMenuItem(menu, "Preview update"));
            assertEquals(0, fixture.services.previewCalls,
                    "the GUI must not expose standalone preview");
        } finally {
            SwingTestSupport.dispose(frame);
        }
    }

    private Fixture fixture() throws Exception {
        Path registry = temp.resolve("registry.json");
        RegistryStore store = new RegistryStore();
        InstallationRecord first = store.register(registry, "First",
                createInstallation(temp.resolve("first")), null);
        InstallationRecord second = store.register(registry, "Second",
                createInstallation(temp.resolve("second")), null);
        ReceiptStore receipts = new ReceiptStore();
        var firstBuild = new OwnershipPolicy().build(Path.of(first.canonicalRoot()),
                OfficialRepository.MEGAMEK, "v-source");
        OwnershipReceipt firstReceipt = receipts.write(registry, store.read(registry), first,
                OfficialRepository.MEGAMEK, "v-source", "MegaMek-v-source.tar.gz", 10,
                "sha256:" + "a".repeat(64), firstBuild);
        var secondBuild = new OwnershipPolicy().build(Path.of(second.canonicalRoot()),
                OfficialRepository.MEGAMEK, "v-second");
        OwnershipReceipt secondReceipt = receipts.write(
                registry, store.read(registry), second, OfficialRepository.MEGAMEK,
                "v-second", "MegaMek-v-second.tar.gz", 10,
                "sha256:" + "b".repeat(64), secondBuild);
        ChannelPreferenceStore channels = new ChannelPreferenceStore();
        channels.initializeManaged(
                registry, first, firstReceipt, FollowChannel.MILESTONE, false);
        channels.initializeManaged(
                registry, second, secondReceipt, FollowChannel.DEVELOPMENT, false);
        FakeServices services = new FakeServices(registry, store);
        return new Fixture(services, first, second, firstReceipt);
    }

    private static Path createInstallation(Path root) throws Exception {
        Files.createDirectory(root);
        Files.createDirectories(root.resolve("data"));
        Files.createDirectories(root.resolve("lib"));
        Files.createDirectories(root.resolve("mmconf"));
        Files.writeString(root.resolve("data/file.txt"), "official");
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

    private static <T> T onEdt(ThrowingSupplier<T> supplier) throws Exception {
        return SwingTestSupport.onEdt(supplier::get);
    }

    private static JDialog waitForDialog(Window owner, String title) throws Exception {
        return SwingTestSupport.dialog(owner, title);
    }

    private static <T> T waitFor(java.util.function.Supplier<T> probe) throws Exception {
        return SwingTestSupport.await("consent presentation", probe::get);
    }

    private static void waitUntil(BooleanSupplier condition) throws Exception {
        SwingTestSupport.awaitCondition("consent state", condition::getAsBoolean);
    }

    private static JPopupMenu openInstallationMenu(Container root, String recordId)
            throws Exception {
        return SwingTestSupport.installationMenu(root, recordId);
    }

    private static JMenuItem findMenuItem(JPopupMenu menu, String text) {
        for (Component component : menu.getComponents()) {
            if (component instanceof JMenuItem item && text.equals(item.getText())) return item;
        }
        return null;
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
    private static JList<?> findList(Container root, String name) {
        return find(root, name);
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
    private static JTextArea findTextArea(Container root) {
        for (Component child : root.getComponents()) {
            if (child instanceof JTextArea area) return area;
            if (child instanceof Container nested) {
                JTextArea found = findTextArea(nested);
                if (found != null) return found;
            }
        }
        return null;
    }

    private record Fixture(FakeServices services, InstallationRecord first,
                           InstallationRecord second, OwnershipReceipt firstReceipt) {}
    @FunctionalInterface private interface ThrowingSupplier<T> { T get() throws Exception; }

    private static final class FakeServices extends LauncherServices {
        private final ReleaseCatalog.Asset asset = new ReleaseCatalog.Asset(
                "MegaMek-v-target.tar.gz", 123, "sha256:" + "c".repeat(64),
                URI.create("https://github.com/MegaMek/megamek/releases/download/v-target/"
                        + "MegaMek-v-target.tar.gz"));
        private final ReleaseCatalog.Release release = new ReleaseCatalog.Release(
                "v-target", "Target", false, false,
                URI.create("https://github.com/MegaMek/megamek/releases/tag/v-target"),
                List.of(asset));
        private int releaseCalls;
        private int previewCalls;
        private InstallationRecord capturedRecord;
        private OwnershipReceipt capturedReceipt;
        private String capturedTag;

        FakeServices(Path registry, RegistryStore store) {
            super(registry, store, new InstallationInspector(), new NoNetworkTransport(),
                    new JavaRuntime(), new ApplicationLauncher());
        }
        @Override public ReleaseCatalog.Page releases(OfficialRepository repository, int page) {
            releaseCalls++;
            return new ReleaseCatalog.Page(1, 10, List.of(release), false);
        }
        @Override public ReleaseCatalog.Assessment assess(OfficialRepository repository,
                                                          ReleaseCatalog.Release ignored) {
            return new ReleaseCatalog.Assessment(true, asset, "Available");
        }
        @Override public UpdatePreviewService.Preview previewUpdate(
                InstallationRecord record, OwnershipReceipt receipt, String tag,
                PrintStream progress) {
            previewCalls++;
            capturedRecord = record;
            capturedReceipt = receipt;
            capturedTag = tag;
            EnumMap<Action, Long> counts = new EnumMap<>(Action.class);
            for (Action action : Action.values()) counts.put(action, 0L);
            return new UpdatePreviewService.Preview(record, receipt, release, asset,
                    receipt.officialManifest(), receipt.excludedOfficialPaths(),
                    List.of(), Map.copyOf(counts));
        }

        @Override public UpdatePreviewService.Preview previewUpdate(
                InstallationRecord record, OwnershipReceipt receipt, String tag,
                PrintStream progress, OperationContext context) {
            return previewUpdate(record, receipt, tag, progress);
        }
    }

    private static final class NoNetworkTransport implements ReleaseTransport {
        @Override public Response get(URI uri, String accept) throws IOException {
            throw new IOException("GUI fixture must not perform network I/O");
        }
    }
}
