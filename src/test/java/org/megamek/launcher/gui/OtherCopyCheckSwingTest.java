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
import javax.swing.JLabel;
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
import static org.junit.jupiter.api.Assertions.assertSame;
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
            Component originalHome = waitFor(() -> find(frame, "managedHomePanel"));
            services.release.countDown();
            waitUntil(() -> services.calls.get() == 1);
            JButton installations = waitFor(
                    () -> find(frame, "manageInstallationsButton"));
            waitUntil(() -> "Installations (1 update)".equals(installations.getText()));
            JLabel information = waitFor(() -> find(frame, "homeInformationMessage"));
            waitUntil(() -> "1 installation has an update".equals(information.getText()));
            assertSame(originalHome, onEdt(() -> find(frame, "managedHomePanel")),
                    "late other-copy checks update navigation without replacing Home");
            SwingUtilities.invokeAndWait(installations::doClick);
            JLabel status = waitFor(() -> find(frame,
                    "installationStatus-" + services.opted.id()));
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
    void leavingHomeDuringOtherCheckNeverAutoApplies() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        FakeServices services = new FakeServices(temp.resolve("stale.json"));
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            assertTrue(services.started.await(5, TimeUnit.SECONDS));
            JButton installations = waitFor(
                    () -> find(frame, "manageInstallationsButton"));
            SwingUtilities.invokeAndWait(installations::doClick);
            assertNotNull(waitFor(() -> find(frame, "installationCards")));

            services.release.countDown();
            waitUntil(() -> {
                JLabel status = find(frame, "installationStatus-" + services.opted.id());
                return status != null && status.getText().contains("Update available");
            });
            assertEquals(0, services.applies.get());
            assertEquals(1, services.calls.get());
        } finally {
            services.release.countDown();
            dispose(frame);
        }
    }

        @Test
        void multipleOptedInCopiesAreCheckedSerially() throws Exception {
            Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
            FakeServices services = new FakeServices(temp.resolve("serial.json"));
            services.preferences.put(services.newMain.id(),
                    FakeServices.configured(services.newMain, true));
            LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
            try {
                SwingUtilities.invokeAndWait(frame::showWindow);
                assertTrue(services.started.await(5, TimeUnit.SECONDS));
                assertEquals(1, services.calls.get(),
                        "the second automatic check must wait for the first");
                services.release.countDown();
                waitUntil(() -> {
                    JButton button = find(frame, "manageInstallationsButton");
                    return button != null && "Installations (2 updates)".equals(button.getText());
                });
                assertEquals(2, services.calls.get());
                assertEquals(List.of(services.opted.id(), services.newMain.id()),
                        services.checkedIds);
            } finally {
                services.release.countDown();
                dispose(frame);
            }
        }

    @Test
    void allOptedOutCopiesStartNoAutomaticWorkerOrAggregateMessage() throws Exception {
        Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        FakeServices services = new FakeServices(temp.resolve("all-off.json"));
        services.preferences.put(services.opted.id(),
                FakeServices.configured(services.opted, false));
        LauncherFrame frame = onEdt(() -> new LauncherFrame(services));
        try {
            SwingUtilities.invokeAndWait(frame::showWindow);
            JButton installations = waitFor(() -> find(frame, "manageInstallationsButton"));
            assertEquals("Installations", installations.getText(),
                    "unknown checks do not produce a false update count");
            assertEquals(0, services.calls.get());
            JLabel information = waitFor(() -> find(frame, "homeInformationMessage"));
            assertEquals("", information.getText());
        } finally {
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
            Map<String, InstallationStatus> statuses = new LinkedHashMap<>();
            for (InstallationRecord record : records) {
                ChannelPreferenceStore.ReadResult fixed = preferences.get(record.id());
                boolean managed = fixed.status() == ChannelPreferenceStore.Status.CONFIGURED;
                statuses.put(record.id(), new InstallationStatus(fixed,
                        new UpdatePreviewService.Eligibility(record, managed,
                                managed ? "fixture managed" : "fixture imported",
                                managed ? receipt(record) : null, null), false, null));
            }
            return new HomeState(new RegistryData(
                    org.megamek.launcher.registry.RegistryStore.SCHEMA,
                    main.id(), records), main,
                    new Inspection(main.canonicalRoot(), main.products(),
                            main.observedBuild(), "fixture"),
                    null, statuses.get(main.id()).previewEligibility(), false, channel,
                    Map.of("megamek", main), Map.copyOf(statuses));
        }

        @Override
        public ChannelPreferenceStore.ReadResult channelPreference(
                InstallationRecord expected) {
            return preferences.get(expected.id());
        }

        @Override
        public boolean canCheckOnOpen(InstallationRecord expected) {
            ChannelPreferenceStore.ReadResult selected = preferences.get(expected.id());
            return selected != null
                    && selected.status() == ChannelPreferenceStore.Status.CONFIGURED
                    && selected.preference().checkOnOpen();
        }

        @Override
        public ChannelUpdateChecker.Result checkUpdates(InstallationRecord expected) {
            checkedIds.add(expected.id());
            calls.incrementAndGet();
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
                    List.of(product), null, false,
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

        private static OwnershipReceipt receipt(InstallationRecord record) {
            return new OwnershipReceipt(1, OwnershipPolicy.VERSION, record.id(),
                    record.canonicalRoot(), record.registeredAt(), "megamek", "v1.0.0",
                    "MegaMek-v1.0.0.tar.gz", 100, "a".repeat(64), null, List.of());
        }
    }

    private static <T> T waitFor(java.util.function.Supplier<T> probe) throws Exception {
        return SwingTestSupport.await("other-copy presentation", probe::get);
    }

    private static void waitUntil(BooleanSupplier condition) throws Exception {
        SwingTestSupport.awaitCondition("other-copy state", condition::getAsBoolean);
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
        return SwingTestSupport.onEdt(action);
    }

    private static void dispose(LauncherFrame frame) throws Exception {
        SwingTestSupport.dispose(frame);
    }
}
