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

import java.io.IOException;
import java.util.List;
import java.util.Objects;

/**
 * Builds a bounded page of product releases authorized by complete records of the selected
 * channel. Reused product tags appear once, backed by their newest referencing record.
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

        OfficialSuiteChannelCatalog.History history =
                new OfficialSuiteChannelCatalog(transport).history(repository, channel, page, perPage);
        String selectedTag = history.current().release().tag();
        List<Entry> rows = history.entries().stream().map(target -> new Entry(
                new Identity(repository, target.release().tag()),
                target.release().tag().equals(selectedTag)
                        ? Classification.SELECTED_CHANNEL : Classification.CHANNEL_HISTORY,
                target.release(), new ReleaseCatalog.Assessment(true,
                target.asset(), "Verified complete suite record"), target.source())).toList();
        return new Result(repository, channel, page, perPage, history.current().version(),
                selectedTag, rows, history.mayHaveNextPage());
    }

    public enum Classification {
        SELECTED_CHANNEL,
        CHANNEL_HISTORY,
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
                        ReleaseCatalog.Assessment assessment, String source) {
        public Entry(Identity identity, Classification classification,
                     ReleaseCatalog.Release release, ReleaseCatalog.Assessment assessment) {
            this(identity, classification, release, assessment,
                    exactSource(identity));
        }

        private static String exactSource(Identity identity) {
            try {
                return ReleaseCatalog.exactMetadataUri(identity.repository(), identity.tag()).toString();
            } catch (IOException error) {
                throw new IllegalArgumentException("invalid official release identity", error);
            }
        }

        public Entry {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(classification, "classification");
            Objects.requireNonNull(release, "release");
            Objects.requireNonNull(assessment, "assessment");
            Objects.requireNonNull(source, "source");
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
