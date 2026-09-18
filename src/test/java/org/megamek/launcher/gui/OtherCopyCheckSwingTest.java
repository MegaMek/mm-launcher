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
import org.megamek.launcher.update.UpdatePreviewService;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OtherCopyCheckSwingTest {
    @TempDir Path temp;

    @Test
    void backgroundChecksOnlyExplicitTrueAndShowsSelectedRowStatus() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        FakeServices services = new FakeServices(temp.resolve("registry.json"));
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            assertTrue(services.started.await(5, TimeUnit.SECONDS));
            services.release.countDown();
            waitUntil(() -> services.calls.get() == 1);
            JButton installations = waitFor(
                    () -> find(frame, "manageInstallationsButton"));
            SwingUtilities.invokeAndWait(installations::doClick);
            JList<?> list = waitFor(() -> find(frame, "installationList"));
            SwingUtilities.invokeAndWait(() -> list.setSelectedIndex(1));
            JLabel status = waitFor(() -> find(frame, "channelCheckResult"));
            waitUntil(() -> status.getText().contains("Update available"));

            assertEquals(List.of(services.opted.id()), services.checkedIds);
            assertEquals(0, services.checkedIds.stream()
                    .filter(id -> id.equals(services.off.id())
                            || id.equals(services.unknown.id())).count());
        } finally {
            services.release.countDown();
            dispose(frame);
        }
    }

    @Test
    void mainSwitchDuringOtherCheckDiscardsLateResultWithoutAutoApply() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        FakeServices services = new FakeServices(temp.resolve("stale.json"));
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            assertTrue(services.started.await(5, TimeUnit.SECONDS));
            JButton installations = waitFor(
                    () -> find(frame, "manageInstallationsButton"));
            SwingUtilities.invokeAndWait(installations::doClick);
            JList<?> list = waitFor(() -> find(frame, "installationList"));
            SwingUtilities.invokeAndWait(() -> list.setSelectedIndex(4));
            SwingUtilities.invokeAndWait(
                    () -> ((JButton) find(frame, "makePreferredButton")).doClick());
            JLabel main = waitFor(() -> find(frame, "mainInstallationName"));
            assertEquals("New Main", main.getText());

            services.release.countDown();
            Thread.sleep(150);
            assertNull(onEdt(() -> find(frame, "otherUpdatesButton")));
            assertEquals(0, services.applies.get());
            assertEquals(1, services.calls.get());
        } finally {
            services.release.countDown();
            dispose(frame);
        }
    }

    private static final class FakeServices extends LauncherServices {
        private final Product product = new Product("megamek", "MegaMek.jar",
                "megamek.MegaMek", "1.0.0", List.of());
        private final InstallationRecord original;
        private final InstallationRecord opted;
        private final InstallationRecord off;
        private final InstallationRecord unknown;
        private final InstallationRecord newMain;
        private final List<InstallationRecord> records;
        private final Map<String, ChannelPreferenceStore.ReadResult> preferences =
                new LinkedHashMap<>();
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger applies = new AtomicInteger();
        private final java.util.concurrent.CopyOnWriteArrayList<String> checkedIds =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        private volatile InstallationRecord main;

        private FakeServices(Path registry) {
            super(registry);
            original = record("1", "Original Main", registry.getParent().resolve("main"));
            opted = record("2", "Opted in", registry.getParent().resolve("opted"));
            off = record("3", "Explicitly off", registry.getParent().resolve("off"));
            unknown = record("4", "Unknown", registry.getParent().resolve("unknown"));
            newMain = record("5", "New Main", registry.getParent().resolve("new-main"));
            records = List.of(original, opted, off, unknown, newMain);
            main = original;
            preferences.put(original.id(), configured(original, false));
            preferences.put(opted.id(), configured(opted, true));
            preferences.put(off.id(), configured(off, false));
            preferences.put(unknown.id(), new ChannelPreferenceStore.ReadResult(
                    ChannelPreferenceStore.Status.UNKNOWN, null, "Choose a channel"));
            preferences.put(newMain.id(), configured(newMain, false));
        }

        @Override
        public HomeState loadHome() {
            ChannelPreferenceStore.ReadResult channel = preferences.get(main.id());
            return new HomeState(new RegistryData(1, main.id(), records), main,
                    new Inspection(main.canonicalRoot(), main.products(),
                            main.observedBuild(), "fixture"),
                    null, new UpdatePreviewService.Eligibility(main, false,
                    "fixture imported", null, null), false, channel);
        }

        @Override
        public ChannelPreferenceStore.ReadResult channelPreference(
                InstallationRecord expected) {
            return preferences.get(expected.id());
        }

        @Override
        public boolean canCheckOnOpen(InstallationRecord expected) {
            return expected.equals(opted);
        }

        @Override
        public ChannelUpdateChecker.Result checkUpdates(InstallationRecord expected) {
            calls.incrementAndGet();
            checkedIds.add(expected.id());
            started.countDown();
            boolean waiting = true;
            while (waiting) {
                try {
                    waiting = !release.await(50, TimeUnit.MILLISECONDS);
                } catch (InterruptedException ignored) {
                    // Deliberately finish late so the frame's generation/cancellation guard is
                    // exercised instead of relying on a cooperative fixture.
                    Thread.interrupted();
                }
            }
            ChannelPreference preference = preferences.get(expected.id()).preference();
            ChannelUpdateChecker.Recommendation recommendation =
                    new ChannelUpdateChecker.Recommendation(expected.id(),
                            expected.canonicalRoot(), expected.registeredAt(), preference,
                            org.megamek.launcher.release.OfficialRepository.MEGAMEK,
                            "fixed-source", "v2.0.0", "MegaMek-v2.0.0.tar.gz",
                            100, "sha256:" + "a".repeat(64),
                            URI.create("https://github.com/MegaMek/megamek/releases/tag/v2.0.0"));
            return new ChannelUpdateChecker.Result(
                    ChannelUpdateChecker.Status.UPDATE_AVAILABLE, expected, preference,
                    "v1.0.0", recommendation, "A newer release is available.");
        }

        @Override
        public boolean isCheckBindingCurrent(InstallationRecord expected,
                                             ChannelPreference preference) {
            return records.contains(expected)
                    && preferences.get(expected.id()).preference().equals(preference);
        }

        @Override
        public void select(InstallationRecord selected) {
            main = selected;
        }

        private InstallationRecord record(String suffix, String name, Path root) {
            return new InstallationRecord("00000000-0000-0000-0000-00000000000" + suffix,
                    name, root.toAbsolutePath().normalize().toString(), "1.0.0",
                    List.of(product), "C:\\Java\\java.exe", null, false,
                    "2026-09-17T00:00:0" + suffix + "Z");
        }

        private static ChannelPreferenceStore.ReadResult configured(
                InstallationRecord record, boolean enabled) {
            ChannelPreference preference = new ChannelPreference(1, record.id(),
                    record.canonicalRoot(), record.registeredAt(),
                    FollowChannel.MILESTONE, enabled);
            return new ChannelPreferenceStore.ReadResult(
                    ChannelPreferenceStore.Status.CONFIGURED, preference, "Configured");
        }
    }

    private static <T> T waitFor(java.util.function.Supplier<T> probe) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < deadline) {
            T value = onEdt(probe::get);
            if (value != null) return value;
            Thread.sleep(20);
        }
        throw new AssertionError("timed out waiting for Swing component");
    }

    private static void waitUntil(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < deadline) {
            if (onEdt(condition::getAsBoolean)) return;
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

    private static <T> T onEdt(java.util.concurrent.Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return action.call();
        Object[] result = new Object[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                result[0] = action.call();
            } catch (Exception error) {
                throw new RuntimeException(error);
            }
        });
        @SuppressWarnings("unchecked") T cast = (T) result[0];
        return cast;
    }

    private static void dispose(LauncherFrame frame) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (java.awt.Window window : frame.getOwnedWindows()) window.dispose();
            frame.dispose();
        });
    }
}
