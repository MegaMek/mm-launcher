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
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.io.IOException;
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
    void fixedChannelCheckPreferenceIsLocalAndSlowManualCheckLeavesActionsUsable()
            throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing channel integration requires a display");
        FakeServices services = new FakeServices(temp.resolve("registry.json"));
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            assertEquals("Launch MegaMek Milestone (0.51.0)",
                    waitFor(() -> find(frame, "launch-megamek-button")).getText());
            JButton installations = waitFor(() -> find(frame, "manageInstallationsButton"));
            SwingUtilities.invokeAndWait(installations::doClick);
            JLabel provenance = waitFor(() -> (JLabel) findNamed(frame,
                    "installationProvenance-" + services.record.id()));
            assertEquals("MegaMek", provenance.getText());
            JLabel details = waitFor(() -> (JLabel) findNamed(frame,
                    "installationDetails-" + services.record.id()));
            JLabel name = waitFor(() -> (JLabel) findNamed(frame,
                    "installationName-" + services.record.id()));
            assertEquals("MegaMek", name.getText());
            assertEquals("0.51.0 · Milestone", details.getText());
            assertNull(onEdt(() -> find(frame, "chooseChannelButton")));
            assertNull(onEdt(() -> find(frame, "channelChoiceCombo")));
            JCheckBox checks = waitFor(() -> (JCheckBox) findNamed(
                    frame, "checkOnOpenCheckbox-" + services.record.id()));
            assertFalse(checks.isSelected());
            SwingUtilities.invokeLater(checks::doClick);
            waitUntil(() -> services.saved.get() == 1);
            assertTrue(onEdt(checks::isSelected));
            assertEquals(0, services.checks.get(),
                    "saving a local check preference must not fetch metadata");
            assertEquals(FollowChannel.MILESTONE,
                    services.preference.preference().channel());
            assertNull(onEdt(() -> find(frame, "updateChecksButton")));
            assertNull(onEdt(() -> find(frame, "saveUpdateChecksButton")));
            assertNull(onEdt(() -> find(frame, "cancelUpdateChecksButton")));
            assertNull(onEdt(() -> find(frame, "updateChecksDialog")));

            JButton check = waitFor(() -> {
                JButton button = find(frame, "checkUpdatesButton");
                return button != null && button.isEnabled() ? button : null;
            });
            SwingUtilities.invokeLater(check::doClick);
            assertTrue(services.started.await(30, TimeUnit.SECONDS));
            JButton home = waitFor(() -> find(frame, "homeButton"));
            SwingUtilities.invokeAndWait(home::doClick);
            assertTrue(onEdt(() -> find(frame, "launch-megamek-button").isEnabled()));
            assertTrue(onEdt(() -> find(frame, "manageInstallationsButton").isEnabled()));
            services.release.countDown();
            waitUntil(() -> services.checks.get() == 1);
            assertEquals(1, services.checks.get());
            JLabel information = waitFor(() -> {
                Component found = findNamed(frame, "homeInformationMessage");
                return found instanceof JLabel label
                        && "All installations are up to date".equals(label.getText())
                        ? label : null;
            });
            assertEquals("All installations are up to date", information.getText());
            assertNull(onEdt(() -> findNamed(frame, "homeStatusLabel")));

            SwingUtilities.invokeAndWait(
                    () -> find(frame, "manageInstallationsButton").doClick());
            JButton addRelease = waitFor(() -> find(frame, "installAnotherVersionButton"));
            SwingUtilities.invokeAndWait(addRelease::doClick);
            JDialog download = waitForDialog("Download an official release");
            JComboBox<?> freshChannel = findCombo(download, "downloadChannelCombo");
            assertNotNull(freshChannel);
            assertEquals(FollowChannel.MILESTONE, onEdt(freshChannel::getSelectedItem),
                    "the install picker defaults to the authoritative Milestone channel");
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
            assertTrue(services.started.await(30, TimeUnit.SECONDS));
            assertEquals(1, services.checks.get());
            assertTrue(onEdt(() -> find(frame, "launch-megamek-button").isEnabled()));
            assertTrue(onEdt(() -> find(frame, "manageInstallationsButton").isEnabled()));
            services.release.countDown();
            JButton installations = waitFor(() -> {
                JButton found = find(frame, "manageInstallationsButton");
                return found != null && "Installations".equals(found.getText())
                        ? found : null;
            });
            assertEquals("Installations", installations.getText());
            assertNull(onEdt(() -> find(frame, "checkUpdatesButton")),
                    "Home has no per-installation Check again action");
            assertNull(onEdt(() -> find(frame, "recoverUpdateButton")),
                    "a healthy receipt alone does not expose Home recovery");
        } finally {
            services.release.countDown();
            SwingUtilities.invokeAndWait(frame::dispose);
        }
    }

        @Test
        void failedImmediatePreferenceSaveReloadsAuthoritativeValueWithoutCheckingNetwork()
                throws Exception {
            Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                    "actual Swing channel integration requires a display");
            FakeServices services = new FakeServices(temp.resolve("save-failure.json"));
            services.saveFailure = new IOException("fixture preference write failed");
            LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
            try {
                SwingUtilities.invokeAndWait(frame::showWindow);
                JButton installations = waitFor(() -> find(frame, "manageInstallationsButton"));
                SwingUtilities.invokeAndWait(installations::doClick);
                JCheckBox checkbox = waitFor(() -> (JCheckBox) findNamed(
                        frame, "checkOnOpenCheckbox-" + services.record.id()));
                assertFalse(checkbox.isSelected());

                SwingUtilities.invokeLater(checkbox::doClick);
                JDialog error = waitForDialog("Update check setting was not saved");
                JCheckBox restored = waitFor(() -> (JCheckBox) findNamed(
                        frame, "checkOnOpenCheckbox-" + services.record.id()));
                assertFalse(restored.isSelected());
                assertTrue(restored.isEnabled());
                assertEquals(0, services.checks.get());
                SwingUtilities.invokeAndWait(error::dispose);
            } finally {
                services.release.countDown();
                SwingUtilities.invokeAndWait(frame::dispose);
            }
        }

            @Test
            void manualCheckRemainsAvailableWhenAutomaticCheckIsOff() throws Exception {
                Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                        "actual Swing channel integration requires a display");
                FakeServices services = new FakeServices(temp.resolve("manual-off.json"));
                LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
                try {
                    SwingUtilities.invokeAndWait(frame::showWindow);
                    Thread.sleep(100);
                    assertEquals(0, services.checks.get());
                    JButton installations = waitFor(() -> find(frame, "manageInstallationsButton"));
                    SwingUtilities.invokeAndWait(installations::doClick);
                    JCheckBox checkbox = waitFor(() -> (JCheckBox) findNamed(
                            frame, "checkOnOpenCheckbox-" + services.record.id()));
                    assertFalse(checkbox.isSelected());
                    JButton check = waitFor(() -> find(frame, "checkUpdatesButton"));

                    SwingUtilities.invokeLater(check::doClick);
                    assertTrue(services.started.await(30, TimeUnit.SECONDS));
                    assertEquals(1, services.checks.get());
                    services.release.countDown();
                } finally {
                    services.release.countDown();
                    SwingUtilities.invokeAndWait(frame::dispose);
                }
            }

    @Test
    void failedAutomaticCheckShowsCouldNotCheckAndExplicitRetry() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless(),
                "actual Swing channel integration requires a display");
        FakeServices services = new FakeServices(temp.resolve("failed-check.json"), true);
        services.checkFailure = new IOException("fixture metadata unavailable");
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            assertTrue(services.started.await(30, TimeUnit.SECONDS));
            services.release.countDown();
            JButton installations = waitFor(() -> find(frame, "manageInstallationsButton"));
            assertEquals("Installations", installations.getText());
            SwingUtilities.invokeAndWait(installations::doClick);
            JButton retry = waitFor(() -> find(frame, "checkUpdatesButton"));
            assertEquals("Retry check", retry.getText());
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
        private volatile IOException checkFailure;
        private volatile IOException saveFailure;

        FakeServices(Path registry) {
            this(registry, false);
        }

        FakeServices(Path registry, boolean checkOnOpen) {
            super(registry);
            Product product = new Product("megamek", "MegaMek.jar", "megamek.MegaMek",
                    "0.51.0", List.of());
            record = new InstallationRecord("00000000-0000-0000-0000-000000000001",
                    "MegaMek Milestone (0.51.0)",
                    registry.getParent().resolve("copy").toString(),
                    "0.51.0", List.of(product), "keep", false,
                    "2026-09-16T00:00:00Z");
            inspection = new Inspection(record.canonicalRoot(), record.products(),
                    record.observedBuild(), "fixture");
            receipt = new OwnershipReceipt(1, OwnershipPolicy.VERSION, record.id(),
                    record.canonicalRoot(), record.registeredAt(), "megamek", "v0.51.0",
                    "MegaMek-v0.51.0.tar.gz", 123, "a".repeat(64), null, List.of());
            ChannelPreference selected = new ChannelPreference(1, record.id(),
                    record.canonicalRoot(), record.registeredAt(),
                    FollowChannel.MILESTONE, checkOnOpen);
            preference = new ChannelPreferenceStore.ReadResult(
                    ChannelPreferenceStore.Status.CONFIGURED, selected, "Configured");
        }

        @Override
        public HomeState loadHome() {
            RegistryData data = new RegistryData(
                    org.megamek.launcher.registry.RegistryStore.SCHEMA,
                    record.id(), List.of(record));
            UpdatePreviewService.Eligibility eligible = new UpdatePreviewService.Eligibility(
                    record, true, "fixture", receipt, null);
            return new HomeState(data, record, inspection, null, eligible, false, preference);
        }

        @Override
        public ChannelPreference setCheckOnOpen(InstallationRecord expected,
                                                ChannelPreference expectedPreference,
                                                boolean checkOnOpen) throws IOException {
            assertEquals(preference.preference(), expectedPreference);
            if (saveFailure != null) throw saveFailure;
            ChannelPreference selected = new ChannelPreference(1, expected.id(),
                    expected.canonicalRoot(), expected.registeredAt(),
                    expectedPreference.channel(), checkOnOpen);
            preference = new ChannelPreferenceStore.ReadResult(
                    ChannelPreferenceStore.Status.CONFIGURED, selected, "Configured");
            saved.incrementAndGet();
            return selected;
        }

        @Override
        public ChannelPreferenceStore.ReadResult channelPreference(InstallationRecord expected) {
            return preference;
        }

        @Override
        public boolean canCheckOnOpen(InstallationRecord expected) {
            return record.equals(expected)
                    && preference.status() == ChannelPreferenceStore.Status.CONFIGURED
                    && preference.preference().checkOnOpen();
        }

        @Override
        public boolean isCheckBindingCurrent(InstallationRecord expected,
                                             ChannelPreference selected) {
            return record.equals(expected) && preference.preference().equals(selected);
        }

        @Override
        public HomeState loadInstallation(String id) {
            return loadHome();
        }

        @Override
        public ChannelUpdateChecker.Result checkUpdates(InstallationRecord expected)
                throws IOException, InterruptedException {
            checks.incrementAndGet();
            started.countDown();
            release.await();
            if (checkFailure != null) throw checkFailure;
            return new ChannelUpdateChecker.Result(ChannelUpdateChecker.Status.EXACT_CURRENT,
                    record, preference.preference(), "v0.51.0", null,
                    "The exact followed release is installed.");
        }
    }
}
