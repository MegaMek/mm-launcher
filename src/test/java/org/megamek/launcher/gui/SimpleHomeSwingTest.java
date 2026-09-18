package org.megamek.launcher.gui;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.megamek.launcher.channel.ChannelPreference;
import org.megamek.launcher.channel.ChannelPreferenceStore;
import org.megamek.launcher.channel.FollowChannel;
import org.megamek.launcher.onboarding.Inspection;
import org.megamek.launcher.onboarding.NormalInstallService;
import org.megamek.launcher.onboarding.Product;
import org.megamek.launcher.operation.OperationContext;
import org.megamek.launcher.operation.OperationPhase;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryData;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.release.OfficialRepository;
import org.megamek.launcher.release.ReleaseCatalog;
import org.megamek.launcher.update.UpdatePreviewService;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.SwingUtilities;
import javax.swing.JTextArea;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleHomeSwingTest {
    @TempDir Path temp;

    @Test
    void firstHomeHasOnePrimaryCtaAdvancedPagesAndExactNormalConfirmation()
            throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing controls require a display");
        FakeServices services = new FakeServices(temp.resolve("registry.json"));
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            JButton primary = waitButton(frame, "downloadAndInstallButton");
            JButton more = waitButton(frame, "moreOptionsButton");
            assertNotNull(primary);
            assertNotNull(more);
            assertNull(find(frame, "useExistingCopyButton"));
            assertNull(find(frame, "downloadProductCombo"));

            SwingUtilities.invokeAndWait(more::doClick);
            assertNotNull(waitButton(frame, "manageAddExistingButton"));
            assertNotNull(waitButton(frame, "manageDownloadMegaMekButton"));
            assertNotNull(waitButton(frame, "chooseChannelButton"));
            SwingUtilities.invokeAndWait(() -> {
                frame.setSize(900, 600);
                frame.validate();
                Rectangle visible = new Rectangle(frame.getContentPane().getSize());
                for (String name : List.of("makePreferredButton", "removeRecordButton",
                        "manageAddExistingButton", "manageDownloadMegaMekButton",
                        "selectJavaButton", "chooseChannelButton", "checkUpdatesButton",
                        "previewUpdateButton", "applyUpdateButton", "recoverUpdateButton",
                        "showInstallationLocationButton")) {
                    JButton action = find(frame, name);
                    Rectangle bounds = SwingUtilities.convertRectangle(action.getParent(),
                            action.getBounds(), frame.getContentPane());
                    assertTrue(bounds.width > 0 && bounds.height > 0);
                    assertTrue(visible.contains(bounds), name + " must remain reachable");
                    int text = action.getFontMetrics(action.getFont()).stringWidth(action.getText());
                    assertTrue(text + action.getInsets().left + action.getInsets().right <= bounds.width,
                            name + " text must not be clipped");
                }
            });
            JButton settings = waitButton(frame, "settingsButton");
            SwingUtilities.invokeAndWait(settings::doClick);
            assertNotNull(waitButton(frame, "viewOperationLogsButton"));
            JButton saveSettings = waitButton(frame, "saveSettingsButton");
            waitUntil(saveSettings::isEnabled);
            JButton home = waitButton(frame, "homeButton");
            SwingUtilities.invokeAndWait(home::doClick);

            primary = waitButton(frame, "downloadAndInstallButton");
            SwingUtilities.invokeAndWait(primary::doClick);
            JDialog confirmation = waitDialog(frame, "Confirm Download & install");
            assertEquals(1, services.plans.get());
            assertNull(find(confirmation, "downloadProductCombo"));
            assertNull(find(confirmation, "downloadChannelCombo"));
            JTextArea details = find(confirmation, "normalInstallConfirmationDetails");
            assertTrue(details.getText().contains("MekHQ suite"));
            assertTrue(details.getText().contains("MegaMekLab"));
            assertTrue(details.getText().contains("MekHQ-v1.2.3.tar.gz"));
            assertTrue(details.getText().contains("sha256:"));
            assertTrue(details.getText().contains(services.destination.toString()));
            assertTrue(details.getText().contains(
                    "Automatic checks when the launcher opens: Off"));

            SwingUtilities.invokeAndWait(
                    () -> ((JButton) find(confirmation, "confirmNormalInstallButton")).doClick());
            JDialog complete = waitDialog(frame, "Install complete");
            click(complete, "OK");
            assertNotNull(waitButton(frame, "launch-megamek-button"));
            assertNotNull(waitButton(frame, "launch-mekhq-button"));
            assertNotNull(waitButton(frame, "launch-lab-button"));
            assertEquals(1, services.installs.get());
            assertFalse(services.launched);
        } finally {
            dispose(frame);
        }
    }

    @Test
    void selectingMainChangesOnlyHomeAndMissingJavaRecoveryStayActionable()
            throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing controls require a display");
        FakeServices services = new FakeServices(temp.resolve("multi.json"));
        services.installed = true;
        services.pending = true;
        services.main = services.firstWithoutJava;
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            assertNotNull(waitButton(frame, "selectJavaButton"));
            assertNotNull(waitButton(frame, "recoverUpdateButton"));
            JButton installations = waitButton(frame, "manageInstallationsButton");
            SwingUtilities.invokeAndWait(installations::doClick);
            JList<?> list = waitFor(() -> find(frame, "installationList"));
            SwingUtilities.invokeAndWait(() -> list.setSelectedIndex(1));
            SwingUtilities.invokeAndWait(
                    () -> ((JButton) find(frame, "makePreferredButton")).doClick());
            JLabel name = waitFor(() -> find(frame, "mainInstallationName"));
            assertEquals("Other copy", name.getText());
            assertEquals(services.second.id(), services.main.id());
            assertEquals(2, services.records.size(), "selection must not move or copy records");
        } finally {
            dispose(frame);
        }
    }

    private static final class FakeServices extends LauncherServices {
        private final Path destination;
        private final ReleaseCatalog.Asset asset;
        private final ReleaseCatalog.Release release;
        private final InstallationRecord first;
        private final InstallationRecord firstWithoutJava;
        private final InstallationRecord second;
        private final List<InstallationRecord> records;
        private final AtomicInteger plans = new AtomicInteger();
        private final AtomicInteger installs = new AtomicInteger();
        private volatile boolean installed;
        private volatile boolean pending;
        private volatile boolean launched;
        private volatile InstallationRecord main;

        private FakeServices(Path registry) {
            super(registry);
            destination = registry.getParent().resolve("installations").resolve("Main");
            asset = new ReleaseCatalog.Asset("MekHQ-v1.2.3.tar.gz", 1234,
                    "sha256:" + "a".repeat(64),
                    URI.create("https://github.com/MegaMek/mekhq/releases/download/"
                            + "v1.2.3/MekHQ-v1.2.3.tar.gz"));
            release = new ReleaseCatalog.Release("v1.2.3", "Milestone 1.2.3",
                    false, false,
                    URI.create("https://github.com/MegaMek/mekhq/releases/tag/v1.2.3"),
                    List.of(asset));
            List<Product> products = List.of(
                    new Product("megamek", "MegaMek.jar", "megamek.MegaMek",
                            "1.2.3", List.of()),
                    new Product("mekhq", "MekHQ.jar", "mekhq.MekHQ",
                            "1.2.3", List.of()),
                    new Product("lab", "MegaMekLab.jar", "megameklab.MegaMekLab",
                            "1.2.3", List.of()));
            first = record("1", "Main", destination, products,
                    Path.of(System.getProperty("java.home"), "bin", "java.exe").toString());
            firstWithoutJava = new InstallationRecord(first.id(), first.name(),
                    first.canonicalRoot(), first.observedBuild(), first.products(), null,
                    first.pin(), false, first.registeredAt());
            second = record("2", "Other copy", registry.getParent().resolve("Other"),
                    products, first.javaExecutable());
            records = List.of(firstWithoutJava, second);
            main = first;
        }

        @Override
        public Path normalInstallDestination() {
            return destination;
        }

        @Override
        public NormalInstallService.Plan prepareNormalInstall(Path target, Path java) {
            plans.incrementAndGet();
            RegistryData empty = new RegistryData(RegistryStore.SCHEMA, null, List.of());
            return new NormalInstallService.Plan(
                    new NormalInstallService.RegistrySnapshot(false, empty), target,
                    List.of(), Path.of(System.getProperty("java.home"), "bin", "java.exe"),
                    21, FollowChannel.MILESTONE, OfficialRepository.MEKHQ, "1.2.3",
                    release, asset,
                    "https://raw.githubusercontent.com/MegaMek/megamek.github.io/main/"
                            + "_data/current_releases.yml",
                    new NormalInstallService.CheckConfiguration(false, "fixture:false"));
        }

        @Override
        public NormalInstallService.Result installNormal(NormalInstallService.Plan plan,
                                                         PrintStream progress,
                                                         OperationContext context)
                throws InterruptedException {
            context.phase(OperationPhase.METADATA, "fixture metadata");
            context.phase(OperationPhase.DOWNLOAD, "fixture download");
            installs.incrementAndGet();
            installed = true;
            main = first;
            return new NormalInstallService.Result(first, release, asset, destination, null);
        }

        @Override
        public HomeState loadHome() {
            if (!installed) {
                return new HomeState(new RegistryData(1, null, List.of()),
                        null, null, null, null, false, null);
            }
            InstallationRecord current = main;
            Inspection inspection = new Inspection(current.canonicalRoot(), current.products(),
                    current.observedBuild(), "fixture");
            ChannelPreference preference = new ChannelPreference(1, current.id(),
                    current.canonicalRoot(), current.registeredAt(),
                    FollowChannel.MILESTONE, true);
            return new HomeState(new RegistryData(1, current.id(), records), current,
                    inspection, null,
                    new UpdatePreviewService.Eligibility(current, false,
                            "fixture imported copy", null, null),
                    pending && current.id().equals(first.id()),
                    new ChannelPreferenceStore.ReadResult(
                            ChannelPreferenceStore.Status.CONFIGURED,
                            preference, "Configured"));
        }

        @Override
        public void select(InstallationRecord selected) {
            main = selected;
        }

        @Override
        public int launch(InstallationRecord record, String product) {
            launched = true;
            return 0;
        }

        private static InstallationRecord record(String suffix, String name, Path root,
                                                 List<Product> products, String java) {
            return new InstallationRecord("00000000-0000-0000-0000-00000000000" + suffix,
                    name, root.toAbsolutePath().normalize().toString(), "1.2.3", products,
                    java, null, false, "2026-09-17T00:00:0" + suffix + "Z");
        }
    }

    private static void click(JDialog dialog, String text) throws Exception {
        JButton button = findByText(dialog, text);
        assertNotNull(button);
        SwingUtilities.invokeLater(button::doClick);
        waitUntil(() -> !dialog.isDisplayable());
    }

    private static JDialog waitDialog(LauncherFrame frame, String title) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < deadline) {
            JDialog[] found = new JDialog[1];
            SwingUtilities.invokeAndWait(() -> {
                for (java.awt.Window window : java.awt.Window.getWindows()) {
                    if (window instanceof JDialog dialog && dialog.isShowing()
                            && title.equals(dialog.getTitle())) {
                        found[0] = dialog;
                    }
                }
            });
            if (found[0] != null) return found[0];
            Thread.sleep(20);
        }
        List<String> titles = java.util.Arrays.stream(java.awt.Window.getWindows())
                .filter(JDialog.class::isInstance).map(JDialog.class::cast)
                .filter(JDialog::isShowing).map(JDialog::getTitle).toList();
        throw new AssertionError("timed out waiting for " + title + "; showing=" + titles);
    }

    private static JButton waitButton(Container root, String name) throws Exception {
        return waitFor(() -> find(root, name));
    }

    private static <T> T waitFor(java.util.function.Supplier<T> probe) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < deadline) {
            Object[] value = new Object[1];
            SwingUtilities.invokeAndWait(() -> value[0] = probe.get());
            if (value[0] != null) {
                @SuppressWarnings("unchecked") T cast = (T) value[0];
                return cast;
            }
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

    private static JButton findByText(Container root, String text) {
        for (Component child : root.getComponents()) {
            if (child instanceof JButton button && text.equals(button.getText())) return button;
            if (child instanceof Container nested) {
                JButton found = findByText(nested, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static <T> T onEdt(java.util.concurrent.Callable<T> work) throws Exception {
        Object[] value = new Object[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                value[0] = work.call();
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        @SuppressWarnings("unchecked") T cast = (T) value[0];
        return cast;
    }

    private static void dispose(LauncherFrame frame) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (java.awt.Window window : frame.getOwnedWindows()) window.dispose();
            frame.dispose();
        });
    }
}
