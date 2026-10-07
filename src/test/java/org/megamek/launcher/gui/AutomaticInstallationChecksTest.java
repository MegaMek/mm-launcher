/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.megamek.launcher.gui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.megamek.launcher.channel.ChannelPreference;
import org.megamek.launcher.channel.ChannelPreferenceStore;
import org.megamek.launcher.channel.ChannelUpdateChecker;
import org.megamek.launcher.channel.FollowChannel;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.update.UpdatePreviewService;

import javax.swing.SwingUtilities;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutomaticInstallationChecksTest {
    @TempDir Path temp;

    @Test
    void productionWorkerDoesNotBlockEdtAndCompletesOnEdt() throws Exception {
        FakeServices services = new FakeServices();
        services.release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        AtomicBoolean completedOnEdt = new AtomicBoolean();
        AutomaticInstallationChecks worker = new AutomaticInstallationChecks(
                services, List.of(record("one")), checked()) {
            @Override protected void done() {
                completedOnEdt.set(SwingUtilities.isEventDispatchThread());
                completed.countDown();
            }
        };
        try {
            SwingTestSupport.onEdt(() -> { worker.execute(); return null; });
            assertTrue(services.started.await(5, TimeUnit.SECONDS));
            assertFalse(services.checkedOnEdt.get());
            assertFalse(worker.isDone(), "the backend is deliberately held");
            assertTrue(SwingTestSupport.onEdt(SwingUtilities::isEventDispatchThread),
                    "healthy UI actions must remain reachable while checking");
            services.release.countDown();
            var results = worker.get(5, TimeUnit.SECONDS);
            assertNotNull(results.get("one").result());
            assertThrows(UnsupportedOperationException.class, results::clear);
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            assertTrue(completedOnEdt.get());
        } finally {
            services.release.countDown();
            worker.cancel(true);
        }
    }

    @Test
    void selectionRequiresOwnedConfiguredOptInCopiesAndCapsAt32() {
        List<InstallationRecord> records = IntStream.range(0, 40)
                .mapToObj(i -> record(Integer.toString(i))).toList();
        Set<String> checked = checked();
        checked.add(AutomaticInstallationChecks.key(preference(records.get(3), true)));
        var selected = AutomaticInstallationChecks.select(records, record -> {
            int id = Integer.parseInt(record.id());
            var preference = new ChannelPreferenceStore.ReadResult(
                    id == 1 ? ChannelPreferenceStore.Status.UNKNOWN : ChannelPreferenceStore.Status.CONFIGURED,
                    preference(record, id != 2), "fixture");
            var eligibility = new UpdatePreviewService.Eligibility(record, id != 0, "fixture", null, null);
            return new LauncherServices.InstallationStatus(preference, eligibility, false,
                    id == 4, null, null);
        }, checked);
        assertEquals(32, selected.size());
        assertEquals("5", selected.getFirst().id());
        assertEquals("36", selected.getLast().id());
    }

    @Test
    void authoritativeRechecksSkipUnconfiguredOptOutAndIneligibleCopies() {
        for (String change : List.of("unconfigured", "opt-out", "ineligible")) {
            FakeServices services = new FakeServices();
            var worker = worker(services, List.of(record("one")), checked());
            services.status = change.equals("unconfigured") ? ChannelPreferenceStore.Status.UNKNOWN
                    : ChannelPreferenceStore.Status.CONFIGURED;
            services.optIn = !change.equals("opt-out");
            services.eligible = !change.equals("ineligible");
            assertTrue(worker.doInBackground().isEmpty(), change);
            assertEquals(0, services.checks);
        }
    }

    @Test
    void deduplicationBindsRegistrationAndChannelRatherThanOnlyId() {
        FakeServices services = new FakeServices();
        InstallationRecord record = record("one");
        Set<String> checked = checked();
        var first = worker(services, List.of(record, record), checked);
        assertEquals(1, first.doInBackground().size());
        assertEquals(1, services.checks);
        var second = worker(services, List.of(record), checked);
        assertTrue(second.doInBackground().isEmpty());
        services.channel = FollowChannel.WEEKLY;
        var changed = worker(services, List.of(record), checked);
        assertEquals(1, changed.doInBackground().size());
        assertEquals(2, services.checks);
        assertNotEquals(AutomaticInstallationChecks.key(preference(record, true)),
                AutomaticInstallationChecks.key(new ChannelPreference(1, record.id(),
                        record.canonicalRoot(), "new-registration", FollowChannel.MILESTONE, true)));
    }

    @Test
    void changedBindingCannotApplyAnInFlightResult() {
        FakeServices services = new FakeServices();
        services.current = false;
        var worker = worker(services, List.of(record("one")), checked());
        assertTrue(worker.doInBackground().isEmpty());
        assertEquals(1, services.checks);
    }

    @Test
    void backendFailuresAreVisibleAndDoNotStopOtherCopies() {
        FakeServices services = new FakeServices();
        services.failure = new IOException("fixture check failed");
        var worker = worker(services, List.of(record("one"), record("two")), checked());
        var results = worker.doInBackground();
        assertEquals(2, results.size());
        assertEquals("IOException: fixture check failed", results.get("one").error());
        assertNull(results.get("one").result());
        assertEquals(2, services.checks);
    }

    @Test
    void cancellationInterruptsBackendAndDoesNotStartTheNextCopy() throws Exception {
        FakeServices services = new FakeServices();
        services.release = new CountDownLatch(1);
        var worker = worker(services, List.of(record("one"), record("two")), checked());
        try {
            worker.execute();
            assertTrue(services.started.await(5, TimeUnit.SECONDS));
            assertTrue(worker.cancel(true));
            assertTrue(services.interrupted.await(5, TimeUnit.SECONDS));
            assertTrue(worker.isCancelled());
            assertEquals(1, services.checks);
        } finally {
            services.release.countDown();
            worker.cancel(true);
        }
    }

    @Test
    void completionRejectsCancelledStaleAndDisposedOwners() {
        assertTrue(AutomaticInstallationChecks.canApply(false, 1, 1, true));
        assertFalse(AutomaticInstallationChecks.canApply(true, 1, 1, true));
        assertFalse(AutomaticInstallationChecks.canApply(false, 1, 2, true));
        assertFalse(AutomaticInstallationChecks.canApply(false, 1, 1, false));
    }

    private AutomaticInstallationChecks worker(FakeServices services, List<InstallationRecord> records,
                                                Set<String> checked) {
        return new AutomaticInstallationChecks(services, records, checked) {};
    }

    private static Set<String> checked() {
        return Collections.synchronizedSet(new HashSet<>());
    }

    private InstallationRecord record(String id) {
        return new InstallationRecord(id, id, temp.resolve(id).toString(), "0.51.0",
                List.of(), "keep", false, "2026-09-16T00:00:00Z");
    }

    private static ChannelPreference preference(InstallationRecord record, boolean optIn) {
        return new ChannelPreference(1, record.id(), record.canonicalRoot(), record.registeredAt(),
                FollowChannel.MILESTONE, optIn);
    }

    private final class FakeServices extends LauncherServices {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch interrupted = new CountDownLatch(1);
        private final AtomicBoolean checkedOnEdt = new AtomicBoolean();
        private CountDownLatch release = new CountDownLatch(0);
        private boolean optIn = true;
        private boolean eligible = true;
        private boolean current = true;
        private FollowChannel channel = FollowChannel.MILESTONE;
        private ChannelPreferenceStore.Status status = ChannelPreferenceStore.Status.CONFIGURED;
        private IOException failure;
        private int checks;

        FakeServices() { super(temp.resolve("registry.json")); }

        @Override public ChannelPreferenceStore.ReadResult channelPreference(InstallationRecord record) {
            return new ChannelPreferenceStore.ReadResult(status, new ChannelPreference(1, record.id(),
                    record.canonicalRoot(), record.registeredAt(), channel, optIn), "fixture");
        }

        @Override public boolean canCheckOnOpen(InstallationRecord record) { return eligible; }

        @Override public boolean isCheckBindingCurrent(InstallationRecord record, ChannelPreference preference) {
            return current;
        }

        @Override public ChannelUpdateChecker.Result checkUpdates(InstallationRecord record)
                throws IOException, InterruptedException {
            checks++;
            checkedOnEdt.set(SwingUtilities.isEventDispatchThread());
            started.countDown();
            try {
                release.await();
            } catch (InterruptedException error) {
                interrupted.countDown();
                throw error;
            }
            if (failure != null) throw failure;
            return new ChannelUpdateChecker.Result(ChannelUpdateChecker.Status.EXACT_CURRENT,
                    record, channelPreference(record).preference(), "v0.51.0", null, "fixture");
        }
    }
}
