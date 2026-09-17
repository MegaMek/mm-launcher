package org.megamek.launcher.gui;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.megamek.launcher.channel.ChannelPreference;
import org.megamek.launcher.channel.ChannelPreferenceStore;
import org.megamek.launcher.channel.ChannelUpdateChecker;
import org.megamek.launcher.channel.FollowChannel;
import org.megamek.launcher.onboarding.Inspection;
import org.megamek.launcher.onboarding.Product;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryData;
import org.megamek.launcher.update.OwnershipPolicy;
import org.megamek.launcher.update.OwnershipReceipt;
import org.megamek.launcher.update.UpdatePreviewService;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelSwingIntegrationTest {
    @TempDir Path temp;

    @Test
    void channelChoiceIsLocalAndSlowManualCheckLeavesLaunchAndManageUsable()
            throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing channel integration requires a display");
        FakeServices services = new FakeServices(temp.resolve("registry.json"));
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            JButton choose = waitFor(() -> find(frame, "chooseChannelButton"));
            SwingUtilities.invokeLater(choose::doClick);
            JDialog choice = waitForDialog("Choose update channel");
            JComboBox<?> combo = findCombo(choice, "channelChoiceCombo");
            assertNotNull(combo);
            SwingUtilities.invokeAndWait(() -> combo.setSelectedItem(FollowChannel.DEVELOPMENT));
            click(choice, "OK");
            waitUntil(() -> services.saved.get() == 1);
            assertEquals(0, services.checks.get(),
                    "pure local channel choice must not fetch metadata");

            JButton check = waitFor(() -> find(frame, "checkUpdatesButton"));
            SwingUtilities.invokeLater(check::doClick);
            assertTrue(services.started.await(5, TimeUnit.SECONDS));
            assertTrue(onEdt(() -> find(frame, "launch-megamek-button").isEnabled()));
            assertTrue(onEdt(() -> find(frame, "manageInstallationsButton").isEnabled()));
            assertFalse(onEdt(() -> find(frame, "checkUpdatesButton").isEnabled()));
            services.release.countDown();
            waitFor(() -> findNamed(frame, "channelCheckResult"));
            assertEquals(1, services.checks.get());

            SwingUtilities.invokeAndWait(frame::downloadDialog);
            JDialog download = waitForDialog("Download an official release");
            JComboBox<?> freshChannel = findCombo(download, "downloadChannelCombo");
            assertNotNull(freshChannel);
            assertNull(onEdt(freshChannel::getSelectedItem),
                    "fresh download must require an explicit independent channel choice");
            assertEquals(1, services.checks.get(),
                    "opening a fresh download picker must not perform a hidden check");
        } finally {
            services.release.countDown();
            SwingUtilities.invokeAndWait(() -> {
                for (Window window : frame.getOwnedWindows()) window.dispose();
                frame.dispose();
            });
        }
    }

    @Test
    void explicitlyOptedInCheckStartsOffEdtWithoutBlockingHealthyActions() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing channel integration requires a display");
        FakeServices services = new FakeServices(temp.resolve("opt-in-registry.json"), true);
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            assertTrue(services.started.await(5, TimeUnit.SECONDS));
            assertEquals(1, services.checks.get());
            assertTrue(onEdt(() -> find(frame, "launch-megamek-button").isEnabled()));
            assertTrue(onEdt(() -> find(frame, "manageInstallationsButton").isEnabled()));
            services.release.countDown();
            waitFor(() -> findNamed(frame, "channelCheckResult"));
        } finally {
            services.release.countDown();
            SwingUtilities.invokeAndWait(frame::dispose);
        }
    }

    private static JDialog waitForDialog(String title) throws Exception {
        final JDialog[] found = new JDialog[1];
        waitUntil(() -> {
            for (Window window : Window.getWindows()) {
                if (window instanceof JDialog dialog && dialog.isShowing()
                        && title.equals(dialog.getTitle())) {
                    found[0] = dialog;
                    return true;
                }
            }
            return false;
        });
        return found[0];
    }

    private static void click(JDialog dialog, String text) throws Exception {
        JButton button = onEdt(() -> findByText(dialog, text));
        assertNotNull(button);
        SwingUtilities.invokeLater(button::doClick);
        waitUntil(() -> !dialog.isDisplayable());
    }

    private static <T extends Component> T waitFor(java.util.concurrent.Callable<T> lookup)
            throws Exception {
        final Component[] found = new Component[1];
        waitUntil(() -> {
            try {
                found[0] = onEdt(lookup);
                return found[0] != null;
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        @SuppressWarnings("unchecked") T cast = (T) found[0];
        return cast;
    }

    private static void waitUntil(BooleanSupplier condition) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < end) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(25);
        }
        throw new AssertionError("timed out waiting for Swing condition");
    }

    private static JButton find(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (child instanceof JButton button && name.equals(button.getName())) return button;
            if (child instanceof Container nested) {
                JButton found = find(nested, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static Component findNamed(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container nested) {
                Component found = findNamed(nested, name);
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

    private static JComboBox<?> findCombo(Container root, String name) {
        for (Component child : root.getComponents()) {
            if (child instanceof JComboBox<?> combo && name.equals(combo.getName())) return combo;
            if (child instanceof Container nested) {
                JComboBox<?> found = findCombo(nested, name);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static <T> T onEdt(java.util.concurrent.Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return action.call();
        Object[] value = new Object[1];
        Throwable[] failure = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                value[0] = action.call();
            } catch (Throwable error) {
                failure[0] = error;
            }
        });
        if (failure[0] != null) throw new RuntimeException(failure[0]);
        @SuppressWarnings("unchecked") T cast = (T) value[0];
        return cast;
    }

    private static final class FakeServices extends LauncherServices {
        private final InstallationRecord record;
        private final Inspection inspection;
        private final OwnershipReceipt receipt;
        private volatile ChannelPreferenceStore.ReadResult preference =
                new ChannelPreferenceStore.ReadResult(ChannelPreferenceStore.Status.UNKNOWN,
                        null, "Choose a channel");
        private final AtomicInteger saved = new AtomicInteger();
        private final AtomicInteger checks = new AtomicInteger();
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        FakeServices(Path registry) {
            this(registry, false);
        }

        FakeServices(Path registry, boolean checkOnOpen) {
            super(registry);
            Product product = new Product("megamek", "MegaMek.jar", "megamek.MegaMek",
                    "0.51.0", List.of());
            record = new InstallationRecord("00000000-0000-0000-0000-000000000001",
                    "Channel fixture", registry.getParent().resolve("copy").toString(),
                    "0.51.0", List.of(product), "C:\\Java\\java.exe", "keep", false,
                    "2026-09-16T00:00:00Z");
            inspection = new Inspection(record.canonicalRoot(), record.products(),
                    record.observedBuild(), "fixture");
            receipt = new OwnershipReceipt(1, OwnershipPolicy.VERSION, record.id(),
                    record.canonicalRoot(), record.registeredAt(), "megamek", "v0.51.0",
                    "MegaMek-v0.51.0.tar.gz", 123, "a".repeat(64), null, List.of());
            if (checkOnOpen) {
                ChannelPreference selected = new ChannelPreference(1, record.id(),
                        record.canonicalRoot(), record.registeredAt(),
                        FollowChannel.MILESTONE, true);
                preference = new ChannelPreferenceStore.ReadResult(
                        ChannelPreferenceStore.Status.CONFIGURED, selected, "Configured");
            }
        }

        @Override
        public HomeState loadHome() {
            RegistryData data = new RegistryData(1, record.id(), List.of(record));
            UpdatePreviewService.Eligibility eligible = new UpdatePreviewService.Eligibility(
                    record, true, "fixture", receipt, null);
            return new HomeState(data, record, inspection, null, eligible, false, preference);
        }

        @Override
        public ChannelPreference setChannel(InstallationRecord expected, FollowChannel channel,
                                            boolean checkOnOpen) {
            ChannelPreference selected = new ChannelPreference(1, expected.id(),
                    expected.canonicalRoot(), expected.registeredAt(), channel, checkOnOpen);
            preference = new ChannelPreferenceStore.ReadResult(
                    ChannelPreferenceStore.Status.CONFIGURED, selected, "Configured");
            saved.incrementAndGet();
            return selected;
        }

        @Override
        public ChannelUpdateChecker.Result checkUpdates(InstallationRecord expected)
                throws InterruptedException {
            checks.incrementAndGet();
            started.countDown();
            release.await();
            return new ChannelUpdateChecker.Result(ChannelUpdateChecker.Status.EXACT_CURRENT,
                    record, preference.preference(), "v0.51.0", null,
                    "The exact followed release is installed.");
        }
    }
}
