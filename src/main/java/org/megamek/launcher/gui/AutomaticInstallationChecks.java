/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package org.megamek.launcher.gui;

import org.megamek.launcher.channel.ChannelPreference;
import org.megamek.launcher.channel.ChannelPreferenceStore;
import org.megamek.launcher.channel.ChannelUpdateChecker;
import org.megamek.launcher.diagnostics.SanitizedErrors;
import org.megamek.launcher.registry.InstallationRecord;

import javax.swing.SwingWorker;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

abstract class AutomaticInstallationChecks
        extends SwingWorker<Map<String, AutomaticInstallationChecks.InstallationCheck>, Void> {
    private final LauncherServices services;
    private final List<InstallationRecord> records;
    private final Set<String> checked;

    AutomaticInstallationChecks(LauncherServices services, List<InstallationRecord> records,
                                Set<String> checked) {
        this.services = services;
        this.records = List.copyOf(records);
        this.checked = checked;
    }

    static boolean available(LauncherServices.InstallationStatus local) {
        return local != null && local.previewEligibility() != null
                && local.previewEligibility().available()
                && local.channelPreference() != null
                && local.channelPreference().status() == ChannelPreferenceStore.Status.CONFIGURED
                && local.channelPreference().preference() != null
                && !local.pendingUninstall();
    }

    static List<InstallationRecord> select(List<InstallationRecord> records,
                                          Function<InstallationRecord, LauncherServices.InstallationStatus> status,
                                          Set<String> checked) {
        return records.stream().filter(record -> {
            LauncherServices.InstallationStatus local = status.apply(record);
            return available(local) && local.channelPreference().preference().checkOnOpen()
                    && !checked.contains(key(local.channelPreference().preference()));
        }).limit(32).toList();
    }

    static String key(ChannelPreference preference) {
        return preference.installationId() + "|" + preference.registeredAt() + "|" + preference.channel();
    }

    static boolean canApply(boolean cancelled, long generation, long currentGeneration, boolean displayable) {
        return !cancelled && generation == currentGeneration && displayable;
    }

    @Override
    protected final Map<String, InstallationCheck> doInBackground() {
        Map<String, InstallationCheck> results = new HashMap<>();
        for (InstallationRecord record : records) {
            if (isCancelled() || Thread.currentThread().isInterrupted()) break;
            try {
                ChannelPreferenceStore.ReadResult selected = services.channelPreference(record);
                if (selected.status() != ChannelPreferenceStore.Status.CONFIGURED) continue;
                if (!selected.preference().checkOnOpen() || !services.canCheckOnOpen(record)) continue;
                if (!checked.add(key(selected.preference()))) continue;
                ChannelUpdateChecker.Result result = services.checkUpdates(record);
                if (services.isCheckBindingCurrent(record, result.preference())) {
                    results.put(record.id(), new InstallationCheck(result, null));
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                break;
            } catch (IOException | RuntimeException error) {
                results.put(record.id(), new InstallationCheck(null, SanitizedErrors.display(error)));
            }
        }
        return Map.copyOf(results);
    }

    record InstallationCheck(ChannelUpdateChecker.Result result, String error, String channel) {
        InstallationCheck(ChannelUpdateChecker.Result result, String error) {
            this(result, error, result == null || result.preference() == null
                    ? null : result.preference().channel().toString());
        }
    }
}
