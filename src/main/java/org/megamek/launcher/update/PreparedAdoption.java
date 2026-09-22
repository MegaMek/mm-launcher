package org.megamek.launcher.update;

import org.megamek.launcher.channel.FollowChannel;
import org.megamek.launcher.onboarding.Inspection;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.release.OfficialRepository;
import org.megamek.launcher.release.ReleaseCatalog;
import org.megamek.launcher.release.VerifiedPackageFetcher;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Opaque, process-local handle for one imported-copy adoption attempt.
 *
 * <p>The retained workspace and immutable official/local evidence are deliberately inaccessible
 * to callers. Publication uses the prepared in-memory evidence rather than reopening the package;
 * only the creating service can consume the handle, and every terminal path attempts to remove
 * the attempt-owned workspace.</p>
 */
public final class PreparedAdoption implements AutoCloseable {
    private final Object owner;
    private final InstallationRecord record;
    private final OfficialRepository repository;
    private final FollowChannel channel;
    private final ReleaseCatalog.Release release;
    private final ReleaseCatalog.Asset asset;
    private final VerifiedPackageFetcher.Workspace workspace;
    private final OwnershipPolicy.Build ownership;
    private final Inspection officialInspection;
    private final RootSnapshot officialSnapshot;
    private final RootSnapshot localSnapshot;
    private final ImportedCopyAdoptionService.Comparison comparison;
    private final Report report;
    private final Cleanup cleanup;
    private final AtomicReference<State> state = new AtomicReference<>(State.ACTIVE);

    PreparedAdoption(Object owner, InstallationRecord record, OfficialRepository repository,
                     FollowChannel channel, ReleaseCatalog.Release release,
                     ReleaseCatalog.Asset asset, VerifiedPackageFetcher.Workspace workspace,
                     OwnershipPolicy.Build ownership, Inspection officialInspection,
                     RootSnapshot officialSnapshot, RootSnapshot localSnapshot,
                     ImportedCopyAdoptionService.Comparison comparison, Report report,
                     Cleanup cleanup) {
        this.owner = Objects.requireNonNull(owner);
        this.record = Objects.requireNonNull(record);
        this.repository = Objects.requireNonNull(repository);
        this.channel = Objects.requireNonNull(channel);
        this.release = Objects.requireNonNull(release);
        this.asset = Objects.requireNonNull(asset);
        this.workspace = Objects.requireNonNull(workspace);
        this.ownership = Objects.requireNonNull(ownership);
        this.officialInspection = Objects.requireNonNull(officialInspection);
        this.officialSnapshot = Objects.requireNonNull(officialSnapshot);
        this.localSnapshot = Objects.requireNonNull(localSnapshot);
        this.comparison = Objects.requireNonNull(comparison);
        this.report = Objects.requireNonNull(report);
        this.cleanup = Objects.requireNonNull(cleanup);
    }

    public Report report() {
        if (state.get() != State.ACTIVE) {
            throw new IllegalStateException("prepared adoption is no longer active");
        }
        return report;
    }

    void claim(Object expectedOwner) throws IOException {
        if (owner != expectedOwner) {
            throw new IOException("prepared adoption belongs to a different launcher service");
        }
        if (!state.compareAndSet(State.ACTIVE, State.COMMITTING)) {
            throw new IOException("prepared adoption was already enabled, discarded, or claimed");
        }
    }

    InstallationRecord record() {
        return record;
    }

    OfficialRepository repository() {
        return repository;
    }

    FollowChannel channel() {
        return channel;
    }

    ReleaseCatalog.Release release() {
        return release;
    }

    ReleaseCatalog.Asset asset() {
        return asset;
    }

    VerifiedPackageFetcher.Workspace workspace() {
        return workspace;
    }

    OwnershipPolicy.Build ownership() {
        return ownership;
    }

    Inspection officialInspection() {
        return officialInspection;
    }

    RootSnapshot officialSnapshot() {
        return officialSnapshot;
    }

    RootSnapshot localSnapshot() {
        return localSnapshot;
    }

    ImportedCopyAdoptionService.Comparison comparison() {
        return comparison;
    }

    IOException finishClaim() {
        state.set(State.CLOSING);
        return clean();
    }

    @Override
    public void close() throws IOException {
        while (true) {
            State current = state.get();
            if (current == State.CLOSED) return;
            if (current == State.COMMITTING || current == State.CLOSING) {
                throw new IOException("prepared adoption is currently being enabled or cleaned");
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
            return new IOException("prepared adoption cleanup failed", error);
        }
    }

    public record Report(boolean eligible, String detectedApplication, String detectedVersion,
                         FollowChannel fixedChannel, String message) {
        public Report {
            Objects.requireNonNull(detectedApplication);
            Objects.requireNonNull(detectedVersion);
            Objects.requireNonNull(fixedChannel);
            Objects.requireNonNull(message);
        }
    }

    @FunctionalInterface
    interface Cleanup {
        void close() throws IOException;
    }

    private enum State {
        ACTIVE, COMMITTING, CLOSING, CLEANUP_FAILED, CLOSED
    }
}
