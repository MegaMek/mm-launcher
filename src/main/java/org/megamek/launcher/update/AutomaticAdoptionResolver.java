package org.megamek.launcher.update;

import org.megamek.launcher.channel.FollowChannel;
import org.megamek.launcher.channel.OfficialYamlChannelCatalog;
import org.megamek.launcher.diagnostics.SanitizedErrors;
import org.megamek.launcher.operation.OperationContext;
import org.megamek.launcher.operation.OperationPhase;
import org.megamek.launcher.operation.ProgressUnit;
import org.megamek.launcher.release.OfficialRepository;
import org.megamek.launcher.release.ReleaseCatalog;
import org.megamek.launcher.release.ReleaseTransport;
import org.megamek.launcher.release.VersionIdentity;

import java.io.IOException;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Resolves an observed local build against bounded official release-list metadata.
 * Package bodies and guessed exact-tag endpoints are intentionally outside this step.
 */
public final class AutomaticAdoptionResolver {
    public static final int PAGE_SIZE = ReleaseCatalog.MAX_PAGE_SIZE;
    public static final int MAX_PAGES = 10;
    public static final int MAX_RELEASES = PAGE_SIZE * MAX_PAGES;

    private final ReleaseTransport transport;
    private final ReleaseCatalog catalog;

    public AutomaticAdoptionResolver(ReleaseTransport transport) {
        this.transport = Objects.requireNonNull(transport, "transport");
        catalog = new ReleaseCatalog(transport);
    }

    public Resolution resolve(OfficialRepository repository, String observedVersion,
                              FollowChannel selectedChannel, PrintStream diagnostics,
                              OperationContext context)
            throws InterruptedException {
        Objects.requireNonNull(repository, "repository");
        Objects.requireNonNull(selectedChannel, "selectedChannel");
        Objects.requireNonNull(diagnostics, "diagnostics");
        Objects.requireNonNull(context, "context");
        Optional<VersionIdentity> observed = VersionIdentity.fromExact(observedVersion);
        if (observed.isEmpty()) {
            return noMatch(Status.NO_UNIQUE_MATCH, null, diagnostics,
                    "observed build is not one supported dotted version");
        }

        Map<String, ReleaseCatalog.Release> candidates = new LinkedHashMap<>();
        int scanned = 0;
        int pages = 0;
        try {
            OfficialYamlChannelCatalog.CurrentPointers pointers =
                    new OfficialYamlChannelCatalog(transport).currentPointers();
            OfficialYamlChannelCatalog.CurrentIdentity current =
                    pointers.classify(selectedChannel, observed.get());
            if (current == OfficialYamlChannelCatalog.CurrentIdentity.OPPOSITE_CURRENT) {
                FollowChannel requiredChannel = pointers.opposite(selectedChannel);
                String currentVersion = pointers.version(requiredChannel);
                String detail = "observed version " + currentVersion + " is currently "
                        + requiredChannel + ", not " + selectedChannel;
                diagnostics.println("ADOPTION automatic-resolution "
                        + SanitizedErrors.text(detail));
                return new Resolution(Status.CHANNEL_MISMATCH, null, null,
                        SanitizedErrors.text(detail),
                        new ChannelMismatch(selectedChannel, requiredChannel, currentVersion));
            }
            for (int page = 1; page <= MAX_PAGES; page++) {
                context.checkpoint();
                context.progress(OperationPhase.METADATA, scanned, MAX_RELEASES,
                        ProgressUnit.FILES, "Searching official release information");
                ReleaseCatalog.Page result = catalog.list(repository, page, PAGE_SIZE);
                pages++;
                for (ReleaseCatalog.Release release : result.releases()) {
                    scanned++;
                    if (release.draft() || !matches(observed.get(), release)
                            || !catalog.assess(repository, release).eligible()) {
                        continue;
                    }
                    candidates.putIfAbsent(release.tag(), release);
                }
                if (!result.mayHaveNextPage()) {
                    return finish(candidates, scanned, pages, diagnostics);
                }
            }
            return noMatch(Status.SEARCH_INCOMPLETE, null, diagnostics,
                    "official release search reached its bound after " + pages
                            + " pages and " + scanned + " entries");
        } catch (IOException | RuntimeException error) {
            return noMatch(Status.REQUEST_FAILED, error, diagnostics,
                    "official release search failed: " + SanitizedErrors.display(error));
        }
    }

    private static boolean matches(VersionIdentity observed, ReleaseCatalog.Release release) {
        Optional<VersionIdentity> tag = VersionIdentity.fromExact(release.tag());
        if (tag.isPresent()) return tag.get().equals(observed);
        return VersionIdentity.fromDisplay(release.title())
                .map(observed::equals).orElse(false);
    }

    private static Resolution finish(Map<String, ReleaseCatalog.Release> candidates,
                                     int scanned, int pages, PrintStream diagnostics) {
        if (candidates.size() == 1) {
            ReleaseCatalog.Release release = candidates.values().iterator().next();
            diagnostics.printf("ADOPTION automatic-resolution matched tag=%s pages=%d "
                    + "entries=%d%n", release.tag(), pages, scanned);
            return new Resolution(Status.MATCHED, release, null,
                    "matched one eligible immutable release", null);
        }
        return noMatch(Status.NO_UNIQUE_MATCH, null, diagnostics,
                "official release search found " + candidates.size()
                        + " eligible matching immutable tags after " + pages
                        + " pages and " + scanned + " entries");
    }

    private static Resolution noMatch(Status status, Throwable failure,
                                      PrintStream diagnostics, String detail) {
        diagnostics.println("ADOPTION automatic-resolution " + SanitizedErrors.text(detail));
        return new Resolution(status, null, failure, SanitizedErrors.text(detail), null);
    }

    public enum Status {
        MATCHED,
        CHANNEL_MISMATCH,
        NO_UNIQUE_MATCH,
        REQUEST_FAILED,
        SEARCH_INCOMPLETE
    }

    public record Resolution(Status status, ReleaseCatalog.Release release, Throwable failure,
                             String diagnostic, ChannelMismatch channelMismatch) {
        public Resolution {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(diagnostic, "diagnostic");
            if ((status == Status.MATCHED) != (release != null)) {
                throw new IllegalArgumentException(
                        "only a matched resolution may contain a release");
            }
            if ((status == Status.CHANNEL_MISMATCH) != (channelMismatch != null)) {
                throw new IllegalArgumentException(
                        "only a channel mismatch may contain channel mismatch details");
            }
        }

        public boolean matched() {
            return status == Status.MATCHED;
        }
    }

    public record ChannelMismatch(FollowChannel selectedChannel,
                                  FollowChannel requiredChannel,
                                  String currentVersion) {
        public ChannelMismatch {
            Objects.requireNonNull(selectedChannel, "selectedChannel");
            Objects.requireNonNull(requiredChannel, "requiredChannel");
            if (selectedChannel == requiredChannel) {
                throw new IllegalArgumentException("channel mismatch requires distinct channels");
            }
            if (currentVersion == null || currentVersion.isBlank()) {
                throw new IllegalArgumentException("current version is required");
            }
        }
    }
}
