package org.megamek.launcher.update;

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

    private final ReleaseCatalog catalog;

    public AutomaticAdoptionResolver(ReleaseTransport transport) {
        catalog = new ReleaseCatalog(Objects.requireNonNull(transport, "transport"));
    }

    public Resolution resolve(OfficialRepository repository, String observedVersion,
                              PrintStream diagnostics, OperationContext context)
            throws InterruptedException {
        Objects.requireNonNull(repository, "repository");
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
                    "matched one eligible immutable release");
        }
        return noMatch(Status.NO_UNIQUE_MATCH, null, diagnostics,
                "official release search found " + candidates.size()
                        + " eligible matching immutable tags after " + pages
                        + " pages and " + scanned + " entries");
    }

    private static Resolution noMatch(Status status, Throwable failure,
                                      PrintStream diagnostics, String detail) {
        diagnostics.println("ADOPTION automatic-resolution " + SanitizedErrors.text(detail));
        return new Resolution(status, null, failure, SanitizedErrors.text(detail));
    }

    public enum Status {
        MATCHED,
        NO_UNIQUE_MATCH,
        REQUEST_FAILED,
        SEARCH_INCOMPLETE
    }

    public record Resolution(Status status, ReleaseCatalog.Release release, Throwable failure,
                             String diagnostic) {
        public Resolution {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(diagnostic, "diagnostic");
            if ((status == Status.MATCHED) != (release != null)) {
                throw new IllegalArgumentException(
                        "only a matched resolution may contain a release");
            }
        }

        public boolean matched() {
            return status == Status.MATCHED;
        }
    }
}
