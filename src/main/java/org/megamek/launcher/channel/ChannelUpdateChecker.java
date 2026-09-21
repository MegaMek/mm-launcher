package org.megamek.launcher.channel;

import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryData;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.release.OfficialRepository;
import org.megamek.launcher.release.ReleaseCatalog;
import org.megamek.launcher.release.ReleaseTransport;
import org.megamek.launcher.release.VersionIdentity;
import org.megamek.launcher.update.RealUpdateService;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;

/** Shared CLI/GUI model for a metadata-only, channel-aware availability check. */
public final class ChannelUpdateChecker {
    private final Path registry;
    private final RegistryStore registries;
    private final ChannelPreferenceStore preferences;
    private final ChannelCatalog catalog;
    private final ReleaseTransport transport;

    public ChannelUpdateChecker(Path registry, ReleaseTransport transport) {
        this(registry, transport, new RegistryStore(), new ChannelPreferenceStore(),
                new OfficialYamlChannelCatalog(transport));
    }

    public ChannelUpdateChecker(Path registry, ReleaseTransport transport,
                                RegistryStore registries,
                                ChannelPreferenceStore preferences,
                                ChannelCatalog catalog) {
        this.registry = registry.toAbsolutePath().normalize();
        this.transport = transport;
        this.registries = registries;
        this.preferences = preferences;
        this.catalog = catalog;
    }

    public Result check(String id) throws IOException, InterruptedException {
        RegistryData data = registries.read(registry);
        InstallationRecord record = registries.resolve(data, id);
        ChannelPreferenceStore.ReadResult selected = preferences.read(registry, data, record);
        if (selected.status() == ChannelPreferenceStore.Status.UNKNOWN) {
            return unavailable(Status.UNCONFIGURED, record, null, null, selected.reason());
        }
        if (selected.status() == ChannelPreferenceStore.Status.UNAVAILABLE) {
            return unavailable(Status.UNAVAILABLE, record, null, null, selected.reason());
        }
        ChannelPreference preference = selected.preference();
        final RealUpdateService.Snapshot snapshot;
        try {
            snapshot = new RealUpdateService(transport).snapshot(registry, record.id());
        } catch (IOException error) {
            return unavailable(Status.UNAVAILABLE, record, preference, null,
                    "Verified current package is unavailable: " + detail(error));
        }
        OfficialRepository repository;
        try {
            repository = OfficialRepository.parse(snapshot.current().repository());
        } catch (IOException error) {
            return unavailable(Status.UNAVAILABLE, record, preference, snapshot.current().tag(),
                    "Verified current repository is unsupported: " + detail(error));
        }
        final ChannelCatalog.Target target;
        try {
            target = catalog.target(preference.channel(), repository);
        } catch (IOException error) {
            return unavailable(Status.UNAVAILABLE, record, preference, snapshot.current().tag(),
                    "Channel check failed: " + detail(error));
        }
        if (target.repository() != repository
                || !target.release().tag().equals("v" + target.version())) {
            return unavailable(Status.UNAVAILABLE, record, preference, snapshot.current().tag(),
                    "Channel source returned a mismatched repository or tag");
        }
        Recommendation recommendation = new Recommendation(record.id(), record.canonicalRoot(),
                record.registeredAt(), preference, repository, target.source(),
                target.release().tag(), target.asset().name(), target.asset().size(),
                target.asset().digest(), target.release().notesUrl());

        String currentTag = snapshot.current().tag();
        if (currentTag.equals(target.release().tag())) {
            return result(Status.EXACT_CURRENT, record, preference, currentTag, recommendation,
                    "The verified current package is the exact followed-channel release.");
        }
        Status comparison = compareTags(currentTag, target.release().tag());
        if (comparison == Status.NON_COMPARABLE) {
            return result(Status.NON_COMPARABLE, record, preference, currentTag, recommendation,
                    "The verified current tag or target is not a supported numeric release tag; "
                            + "no update or downgrade is recommended.");
        }
        if (comparison == Status.UPDATE_AVAILABLE) {
            return result(Status.UPDATE_AVAILABLE, record, preference, currentTag, recommendation,
                    "A newer release is available on this installation's fixed channel.");
        }
        if (comparison == Status.INSTALLED_AHEAD) {
            return result(Status.INSTALLED_AHEAD, record, preference, currentTag, recommendation,
                    "The verified installed release is newer than this channel target; "
                            + "no downgrade is recommended.");
        }
        return result(Status.NON_COMPARABLE, record, preference, currentTag, recommendation,
                "Current and target tags have equal numeric versions but different identities; "
                        + "no automatic equivalence or downgrade is assumed.");
    }

    static Status compareTags(String currentTag, String targetTag) {
        if (currentTag != null && currentTag.equals(targetTag)) return Status.EXACT_CURRENT;
        VersionIdentity current = numericTag(currentTag);
        VersionIdentity target = numericTag(targetTag);
        if (current == null || target == null) return Status.NON_COMPARABLE;
        int order = compareNumeric(current, target);
        if (order < 0) return Status.UPDATE_AVAILABLE;
        if (order > 0) return Status.INSTALLED_AHEAD;
        return Status.NON_COMPARABLE;
    }

    private static Result unavailable(Status status, InstallationRecord record,
                                      ChannelPreference preference, String currentTag,
                                      String reason) {
        return new Result(status, record, preference, currentTag, null, reason);
    }

    private static Result result(Status status, InstallationRecord record,
                                 ChannelPreference preference, String currentTag,
                                 Recommendation recommendation, String reason) {
        return new Result(status, record, preference, currentTag, recommendation, reason);
    }

    private static String detail(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private static VersionIdentity numericTag(String tag) {
        if (tag == null || !tag.startsWith("v")) return null;
        VersionIdentity parsed = VersionIdentity.fromExact(tag).orElse(null);
        return parsed == null || !parsed.suffix().isEmpty() ? null : parsed;
    }

    private static int compareNumeric(VersionIdentity first, VersionIdentity second) {
        int count = Math.max(first.components().size(), second.components().size());
        for (int index = 0; index < count; index++) {
            java.math.BigInteger left = index < first.components().size()
                    ? first.components().get(index) : java.math.BigInteger.ZERO;
            java.math.BigInteger right = index < second.components().size()
                    ? second.components().get(index) : java.math.BigInteger.ZERO;
            int compared = left.compareTo(right);
            if (compared != 0) return compared;
        }
        return 0;
    }

    public enum Status {
        UNCONFIGURED,
        UNAVAILABLE,
        UPDATE_AVAILABLE,
        EXACT_CURRENT,
        INSTALLED_AHEAD,
        NON_COMPARABLE
    }

    public record Recommendation(
            String installationId,
            String canonicalRoot,
            String registeredAt,
            ChannelPreference preference,
            OfficialRepository repository,
            String source,
            String targetTag,
            String assetName,
            long assetSize,
            String assetDigest,
            URI notesUrl) {
    }

    public record Result(Status status, InstallationRecord record,
                         ChannelPreference preference, String currentTag,
                         Recommendation recommendation, String reason) {
        public boolean updateAvailable() {
            return status == Status.UPDATE_AVAILABLE && recommendation != null;
        }
    }
}
