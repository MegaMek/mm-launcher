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

package org.megamek.launcher.channel;

import org.megamek.launcher.release.OfficialRepository;
import org.megamek.launcher.release.ReleaseCatalog;
import org.megamek.launcher.release.ReleaseTransport;
import org.megamek.launcher.release.VersionIdentity;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Builds one bounded install-browser page from the current official channel pointers and one
 * repository history page. The fixed YAML is the only channel-membership authority: every
 * historical identity other than either current pointer remains explicitly unknown.
 */
public final class SelectedChannelReleaseCatalog {
    public static final int MAX_PAGE = 1000;

    private final ReleaseTransport transport;

    public SelectedChannelReleaseCatalog(ReleaseTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
    }

    public Result page(OfficialRepository repository, FollowChannel channel,
                       int page, int perPage)
            throws IOException, InterruptedException {
        if (repository == null || channel == null) {
            throw new IOException("product and channel are required");
        }
        if (page < 1 || page > MAX_PAGE
                || perPage < 1 || perPage > ReleaseCatalog.MAX_PAGE_SIZE) {
            throw new IOException("page must be 1-" + MAX_PAGE + " and per-page must be 1-"
                    + ReleaseCatalog.MAX_PAGE_SIZE);
        }

        OfficialYamlChannelCatalog.CurrentPointers pointers =
                new OfficialYamlChannelCatalog(transport).currentPointers();
        String selectedVersion = pointers.version(channel);
        String selectedTag = "v" + selectedVersion;

        ReleaseCatalog releases = new ReleaseCatalog(transport);
        ReleaseCatalog.Page history = releases.list(repository, page, perPage);
        Map<Identity, Entry> unique = new LinkedHashMap<>();
        Entry selectedFromHistory = null;

        for (ReleaseCatalog.Release release : history.releases()) {
            Identity identity = new Identity(repository, release.tag());
            if (unique.containsKey(identity)) {
                continue;
            }
            OfficialYamlChannelCatalog.CurrentIdentity current =
                    VersionIdentity.fromExact(release.tag())
                            .map(releaseIdentity -> pointers.classify(
                                    channel, releaseIdentity))
                            .orElse(OfficialYamlChannelCatalog.CurrentIdentity.UNKNOWN);
            if (current == OfficialYamlChannelCatalog.CurrentIdentity.OPPOSITE_CURRENT) {
                continue;
            }
            boolean selectedCurrent =
                    current == OfficialYamlChannelCatalog.CurrentIdentity.SELECTED_CURRENT
                            || current == OfficialYamlChannelCatalog.CurrentIdentity.SHARED_CURRENT;
            if (selectedCurrent) {
                if (page == 1 && release.tag().equals(selectedTag)
                        && selectedFromHistory == null) {
                    selectedFromHistory = new Entry(identity,
                            Classification.SELECTED_CHANNEL, release,
                            releases.assess(repository, release));
                }
                continue;
            }
            Classification classification = Classification.UNKNOWN;
            Entry entry = new Entry(identity, classification, release,
                    releases.assess(repository, release));
            unique.put(identity, entry);
        }

        List<Entry> rows = new ArrayList<>();
        if (page == 1) {
            Entry selected = selectedFromHistory;
            if (selected == null || !selected.assessment().eligible()) {
                ReleaseCatalog.Release exact = releases.exact(repository, selectedTag);
                selected = new Entry(new Identity(repository, exact.tag()),
                        Classification.SELECTED_CHANNEL, exact,
                        releases.assess(repository, exact));
            }
            rows.add(selected);
        }
        rows.addAll(unique.values());

        return new Result(repository, channel, page, perPage, selectedVersion,
                selectedTag, List.copyOf(rows), history.mayHaveNextPage());
    }

    public enum Classification {
        SELECTED_CHANNEL,
        UNKNOWN
    }

    /** Repository and exact tag form the immutable identity used by the official exact endpoint. */
    public record Identity(OfficialRepository repository, String tag) {
        public Identity {
            Objects.requireNonNull(repository, "repository");
            if (tag == null || tag.isBlank()) {
                throw new IllegalArgumentException("tag is required");
            }
        }
    }

    public record Entry(Identity identity, Classification classification,
                        ReleaseCatalog.Release release,
                        ReleaseCatalog.Assessment assessment) {
        public Entry {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(classification, "classification");
            Objects.requireNonNull(release, "release");
            Objects.requireNonNull(assessment, "assessment");
            if (!identity.tag().equals(release.tag())) {
                throw new IllegalArgumentException("entry identity and release tag differ");
            }
        }
    }

    public record Result(OfficialRepository repository, FollowChannel channel,
                         int page, int perPage, String selectedVersion, String selectedTag,
                         List<Entry> entries, boolean mayHaveNextPage) {
        public Result {
            Objects.requireNonNull(repository, "repository");
            Objects.requireNonNull(channel, "channel");
            Objects.requireNonNull(selectedVersion, "selectedVersion");
            Objects.requireNonNull(selectedTag, "selectedTag");
            entries = List.copyOf(entries);
            if (page < 1 || page > MAX_PAGE
                    || perPage < 1 || perPage > ReleaseCatalog.MAX_PAGE_SIZE) {
                throw new IllegalArgumentException("result page is outside catalog bounds");
            }
            if (!selectedTag.equals("v" + selectedVersion)) {
                throw new IllegalArgumentException("selected pointer is inconsistent");
            }
            if (page == 1 && entries.stream()
                    .filter(entry -> entry.classification() == Classification.SELECTED_CHANNEL)
                    .count() != 1) {
                throw new IllegalArgumentException(
                        "page one must contain the selected current target exactly once");
            }
        }
    }
}
