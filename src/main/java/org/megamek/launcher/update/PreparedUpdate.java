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

package org.megamek.launcher.update;

import org.megamek.launcher.channel.ChannelUpdateChecker;
import org.megamek.launcher.manifest.ManifestReader;
import org.megamek.launcher.onboarding.Inspection;
import org.megamek.launcher.release.ReleaseCatalog;
import org.megamek.launcher.release.VerifiedPackageFetcher;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Opaque, process-local ownership handle for one explicitly consented GUI update attempt.
 *
 * <p>The handle deliberately exposes only its report. It is not serializable and does not expose
 * the retained archive or extraction paths. Only the service instance that prepared it can claim
 * it for Apply. Closing discards this attempt's private workspace; a failed cleanup remains
 * explicit and may be retried by closing again.</p>
 */
public final class PreparedUpdate implements AutoCloseable {
    private final Object owner;
    private final RealUpdateService.Snapshot source;
    private final UpdatePreviewService.Preview preview;
    private final VerifiedPackageFetcher.Workspace workspace;
    private final OwnershipPolicy.Build target;
    private final Inspection targetInspection;
    private final ManifestReader.PreviewPairValidation pair;
    private final ChannelUpdateChecker.Recommendation recommendation;
    private final Cleanup cleanup;
    private final AtomicReference<State> state = new AtomicReference<>(State.ACTIVE);

    PreparedUpdate(Object owner, RealUpdateService.Snapshot source,
                   UpdatePreviewService.Preview preview,
                   VerifiedPackageFetcher.Workspace workspace,
                   OwnershipPolicy.Build target, Inspection targetInspection,
                   ManifestReader.PreviewPairValidation pair,
                   ChannelUpdateChecker.Recommendation recommendation) {
        this(owner, source, preview, workspace, target, targetInspection, pair, recommendation,
                workspace::close);
    }

    PreparedUpdate(Object owner, RealUpdateService.Snapshot source,
                   UpdatePreviewService.Preview preview,
                   VerifiedPackageFetcher.Workspace workspace,
                   OwnershipPolicy.Build target, Inspection targetInspection,
                   ManifestReader.PreviewPairValidation pair,
                   ChannelUpdateChecker.Recommendation recommendation, Cleanup cleanup) {
        this.owner = Objects.requireNonNull(owner);
        this.source = Objects.requireNonNull(source);
        this.preview = Objects.requireNonNull(preview);
        this.workspace = Objects.requireNonNull(workspace);
        this.target = Objects.requireNonNull(target);
        this.targetInspection = Objects.requireNonNull(targetInspection);
        this.pair = Objects.requireNonNull(pair);
        this.recommendation = recommendation;
        this.cleanup = Objects.requireNonNull(cleanup);
    }

    public UpdatePreviewService.Preview preview() {
        if (state.get() != State.ACTIVE) {
            throw new IllegalStateException("prepared update is no longer active");
        }
        return preview;
    }

    UpdatePreviewService.Preview capturedPreview() {
        return preview;
    }

    void claim(Object expectedOwner) throws IOException {
        if (owner != expectedOwner) {
            throw new IOException("prepared update belongs to a different launcher service");
        }
        if (!state.compareAndSet(State.ACTIVE, State.APPLYING)) {
            throw new IOException("prepared update was already applied, discarded, or claimed");
        }
    }

    RealUpdateService.Snapshot source() {
        return source;
    }

    VerifiedPackageFetcher.Workspace workspace() {
        return workspace;
    }

    OwnershipPolicy.Build target() {
        return target;
    }

    Inspection targetInspection() {
        return targetInspection;
    }

    ManifestReader.PreviewPairValidation pair() {
        return pair;
    }

    ChannelUpdateChecker.Recommendation recommendation() {
        return recommendation;
    }

    IOException finishClaim() {
        if (!state.compareAndSet(State.APPLYING, State.CLOSING)) {
            return new IOException("prepared update ownership state changed during Apply");
        }
        return clean();
    }

    @Override
    public void close() throws IOException {
        while (true) {
            State current = state.get();
            if (current == State.CLOSED) return;
            if (current == State.APPLYING || current == State.CLOSING) {
                throw new IOException("prepared update is currently being applied or cleaned");
            }
            if ((current == State.ACTIVE || current == State.CLEANUP_FAILED)
                    && state.compareAndSet(current, State.CLOSING)) {
                IOException failure = clean();
                if (failure != null) throw failure;
                return;
            }
        }
    }

    private IOException clean() {
        try {
            cleanup.close();
            state.set(State.CLOSED);
            return null;
        } catch (IOException error) {
            state.set(State.CLEANUP_FAILED);
            return error;
        } catch (RuntimeException error) {
            state.set(State.CLEANUP_FAILED);
            return new IOException("prepared update cleanup failed", error);
        }
    }

    @FunctionalInterface
    interface Cleanup {
        void close() throws IOException;
    }

    private enum State {
        ACTIVE, APPLYING, CLOSING, CLEANUP_FAILED, CLOSED
    }
}
