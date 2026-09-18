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
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.JTextArea;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimpleHomeSwingTest {
    @TempDir Path temp;

    @Test
    void firstHomeSplitRoutesExplicitChoicesWithoutOpeningNetworkOrChangingDefault()
            throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing controls require a display");
        FakeServices services = new FakeServices(temp.resolve("registry.json"));
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            JButton primary = waitButton(frame, "downloadAndInstallButton");
            JButton options = waitButton(frame, "downloadOptionsButton");
            FirstLaunchSplitButton split = waitFor(
                    () -> find(frame, "firstLaunchSplitButton"));
            assertNotNull(primary);
            assertNotNull(options);
            assertNotNull(find(frame, "useExistingCopyButton"));
            assertNull(find(frame, "downloadProductCombo"));

            invokeKeyBinding(primary, KeyStroke.getKeyStroke(
                    KeyEvent.VK_DOWN, InputEvent.ALT_DOWN_MASK));
            waitUntil(() -> split.popupMenu().isVisible());
            JPopupMenu popup = split.popupMenu();
            assertEquals(2, popup.getComponentCount());
            assertEquals("Latest Development",
                    ((JMenuItem) popup.getComponent(0)).getText());
            assertEquals("Choose another version or application…",
                    ((JMenuItem) popup.getComponent(1)).getText());
            assertEquals(0, services.plans.get());
            assertEquals(0, services.releaseFetches.get());
            invokePopupEscape(popup);
            waitUntil(() -> !popup.isVisible());

            SwingUtilities.invokeAndWait(() -> {
                options.doClick();
            });
            invokeMenuSelection((JMenuItem) popup.getComponent(0));
            JDialog development = waitDialog(frame, "Confirm Download & install");
            assertEquals(FollowChannel.DEVELOPMENT, services.lastPlannedChannel);
            assertEquals(1, services.plans.get());
            assertEquals(0, services.installs.get());
            JTextArea developmentDetails =
                    find(development, "normalInstallConfirmationDetails");
            assertTrue(developmentDetails.getText()
                    .contains("Current official Development MekHQ suite"));
            SwingUtilities.invokeAndWait(
                    () -> ((JButton) find(development,
                            "cancelNormalInstallButton")).doClick());
            waitUntil(() -> !development.isDisplayable());
            waitUntil(() -> {
                JLabel label = find(frame, "releaseChannelLabel");
                return label != null
                        && "Latest Milestone (1.2.3)".equals(label.getText());
            });
            assertFalse(Files.exists(temp.resolve("registry.json")));

            SwingUtilities.invokeAndWait(() -> {
                options.doClick();
                ((JMenuItem) popup.getComponent(1)).doClick();
            });
            JDialog picker = waitDialog(frame, "Download an official release");
            assertEquals(0, services.releaseFetches.get(),
                    "the full picker must wait for its Fetch action");
            assertFalse(services.installed);
            assertNull(services.installedChannel);
            SwingUtilities.invokeAndWait(picker::dispose);

            services.planStarted = new CountDownLatch(1);
            services.releasePlan = new CountDownLatch(1);
            SwingUtilities.invokeAndWait(primary::doClick);
            assertTrue(services.planStarted.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(onEdt(primary::isEnabled));
            assertFalse(onEdt(options::isEnabled));
            services.releasePlan.countDown();
            JDialog confirmation = waitDialog(frame, "Confirm Download & install");
            assertEquals(FollowChannel.MILESTONE, services.lastPlannedChannel);
            assertEquals(2, services.plans.get());
            assertTrue(onEdt(primary::isEnabled));
            assertTrue(onEdt(options::isEnabled));
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
            assertEquals(FollowChannel.MILESTONE, services.installedChannel);
            assertFalse(services.launched);

            JButton installations = waitButton(frame, "manageInstallationsButton");
            SwingUtilities.invokeAndWait(installations::doClick);
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
                    int text = action.getFontMetrics(action.getFont())
                            .stringWidth(action.getText());
                    assertTrue(text + action.getInsets().left + action.getInsets().right
                            <= bounds.width, name + " text must not be clipped");
                }
            });
            JButton settings = waitButton(frame, "settingsButton");
            SwingUtilities.invokeAndWait(settings::doClick);
            assertNotNull(waitButton(frame, "viewOperationLogsButton"));
        } finally {
            dispose(frame);
        }
    }

    @Test
    void latestDevelopmentCompletesThroughSharedInstallerAndManagedHomeShowsDevelopment()
            throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing controls require a display");
        FakeServices services = new FakeServices(temp.resolve("development.json"));
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            JButton options = waitButton(frame, "downloadOptionsButton");
            FirstLaunchSplitButton split = waitFor(
                    () -> find(frame, "firstLaunchSplitButton"));
            SwingUtilities.invokeAndWait(options::doClick);
            waitUntil(() -> split.popupMenu().isVisible());
            invokeMenuSelection((JMenuItem) split.popupMenu().getComponent(0));

            JDialog confirmation = waitDialog(frame, "Confirm Download & install");
            JTextArea quote = find(confirmation, "normalInstallConfirmationDetails");
            assertTrue(quote.getText().contains("Development MekHQ suite"));
            assertEquals(0, services.installs.get(),
                    "confirmation must precede the shared install backend");
            SwingUtilities.invokeAndWait(
                    () -> ((JButton) find(confirmation,
                            "confirmNormalInstallButton")).doClick());
            JDialog complete = waitDialog(frame, "Install complete");
            click(complete, "OK");

            JLabel channel = waitFor(() -> {
                JLabel current = find(frame, "releaseChannelLabel");
                return current != null && "Development".equals(current.getText())
                        ? current : null;
            });
            assertEquals("Development", channel.getText());
            assertEquals(FollowChannel.DEVELOPMENT, services.lastPlannedChannel);
            assertEquals(FollowChannel.DEVELOPMENT, services.installedChannel);
            assertEquals(1, services.plans.get());
            assertEquals(1, services.installs.get());
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
        private final AtomicInteger releaseFetches = new AtomicInteger();
        private volatile boolean installed;
        private volatile boolean pending;
        private volatile boolean launched;
        private volatile InstallationRecord main;
        private volatile FollowChannel lastPlannedChannel;
        private volatile FollowChannel installedChannel;
        private volatile CountDownLatch planStarted;
        private volatile CountDownLatch releasePlan;

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

        @Override public String latestMilestoneVersion() {
            return "1.2.3";
        }

        @Override
        public Path normalInstallDestination() {
            return destination;
        }

        @Override
        public NormalInstallService.Plan prepareNormalInstall(FollowChannel channel, Path target,
                                                              Path java)
                throws InterruptedException {
            plans.incrementAndGet();
            lastPlannedChannel = channel;
            CountDownLatch started = planStarted;
            CountDownLatch releasePlanning = releasePlan;
            if (started != null) started.countDown();
            if (releasePlanning != null) releasePlanning.await();
            RegistryData empty = new RegistryData(RegistryStore.SCHEMA, null, List.of());
            return new NormalInstallService.Plan(
                    new NormalInstallService.RegistrySnapshot(false, empty), target,
                    List.of(), Path.of(System.getProperty("java.home"), "bin", "java.exe"),
                    21, channel, OfficialRepository.MEKHQ, "1.2.3",
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
            installedChannel = plan.channel();
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
                    installedChannel == null ? FollowChannel.MILESTONE : installedChannel, true);
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
        public ReleaseCatalog.Page releases(OfficialRepository repository, int page) {
            releaseFetches.incrementAndGet();
            return new ReleaseCatalog.Page(page, 10, List.of(), false);
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

    private static void invokeKeyBinding(JButton button, KeyStroke stroke) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Object key = button.getInputMap(JComponent.WHEN_FOCUSED).get(stroke);
            assertNotNull(key);
            assertNotNull(button.getActionMap().get(key));
            button.getActionMap().get(key).actionPerformed(
                    new ActionEvent(button, ActionEvent.ACTION_PERFORMED, key.toString()));
        });
    }

    private static void invokePopupEscape(JPopupMenu popup) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            KeyStroke escape = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
            Object key = popup.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(escape);
            assertNotNull(key);
            assertNotNull(popup.getActionMap().get(key));
            popup.getActionMap().get(key).actionPerformed(
                    new ActionEvent(popup, ActionEvent.ACTION_PERFORMED, key.toString()));
        });
    }

    private static void invokeMenuSelection(JMenuItem item) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            javax.swing.Action action = item.getActionMap().get("doClick");
            assertNotNull(action);
            action.actionPerformed(new ActionEvent(
                    item, ActionEvent.ACTION_PERFORMED, "keyboard-select"));
        });
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
