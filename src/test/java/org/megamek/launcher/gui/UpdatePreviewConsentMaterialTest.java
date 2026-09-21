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
            SwingUtilities.invokeAndWait(() -> {
                for (Window window : Window.getWindows()) {
                    if (window == frame || window.getOwner() == frame) window.dispose();
                }
                frame.dispose();
            });
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

    private static JDialog waitForDialog(Window owner, String title) throws Exception {
        return waitFor(() -> {
            for (Window window : Window.getWindows()) {
                if (window instanceof JDialog dialog && dialog.isShowing()
                        && title.equals(dialog.getTitle())
                        && (dialog.getOwner() == owner || owner == null
                        || dialog.getOwner() instanceof JDialog)) {
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

    private static JPopupMenu openInstallationMenu(Container root, String recordId)
            throws Exception {
        JButton button = waitFor(() -> find(root, "installationMenuButton-" + recordId));
        SwingUtilities.invokeAndWait(button::doClick);
        return waitFor(() -> {
            for (var element : MenuSelectionManager.defaultManager().getSelectedPath()) {
                if (element instanceof JPopupMenu menu) return menu;
            }
            return null;
        });
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
