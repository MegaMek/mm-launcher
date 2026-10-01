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

import org.megamek.launcher.diagnostics.SanitizedErrors;
import org.megamek.launcher.release.OfficialRepository;
import org.megamek.launcher.release.PackageDigest;
import org.megamek.launcher.release.ReleaseCatalog;
import org.megamek.launcher.release.ReleaseTransport;
import org.megamek.launcher.release.VerifiedPackageFetcher;
import org.megamek.launcher.release.VersionIdentity;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.regex.Pattern;

/** Reads complete immutable records, never website pointers or independently selected releases. */
public final class OfficialSuiteChannelCatalog implements ChannelCatalog {
    public static final URI SOURCE = URI.create("https://api.github.com/repos/MegaMek/megamek/releases");
    public static final int MAX_PAGES = 100;
    public static final int MAX_RECORD_BYTES_TOTAL = 32 * 1024 * 1024;
    private static final String ASSET_SOURCE = SOURCE + "/assets/";
    private static final Pattern RECORD_NAME = Pattern.compile("suite-record-([0-9]+\\.[0-9]+\\.[0-9]+)\\.json");
    private final ReleaseTransport transport;
    private final String suppliedLauncherVersion;

    public OfficialSuiteChannelCatalog(ReleaseTransport transport) {
        this(transport, null);
    }

    public OfficialSuiteChannelCatalog(ReleaseTransport transport, String launcherVersion) {
        this.transport = Objects.requireNonNull(transport, "transport");
        suppliedLauncherVersion = launcherVersion;
    }

    @Override
    public Target target(FollowChannel channel, OfficialRepository repository)
            throws IOException, InterruptedException {
        requireSelection(channel, repository);
        Located located = latest(records(), channel);
        return resolve(located, new HashMap<>()).get(repository);
    }

    @Override
    public Target targetBySource(FollowChannel channel, OfficialRepository repository, String source)
            throws IOException, InterruptedException {
        requireSelection(channel, repository);
        if (!isSource(source)) throw new IOException("approved complete suite record source required");
        for (Located located : records()) {
            if (source.equals(located.source()) && located.record().membership() == channel) {
                return resolve(located, new HashMap<>()).get(repository);
            }
        }
        throw new IOException("captured complete suite record is unavailable or has different membership");
    }

    @Override
    public Target targetByTag(FollowChannel channel, OfficialRepository repository, String tag)
            throws IOException, InterruptedException {
        requireSelection(channel, repository);
        for (Located located : records()) {
            if (located.record().membership() == channel
                    && located.record().products().get(repository).tag().equals(tag)) {
                return resolve(located, new HashMap<>()).get(repository);
            }
        }
        throw new IOException("exact product release is not referenced by a complete " + channel + " suite");
    }

    public CurrentPointers currentPointers(OfficialRepository repository)
            throws IOException, InterruptedException {
        Objects.requireNonNull(repository, "repository");
        List<Located> records = records();
        if (records.isEmpty()) throw new IOException("No complete suite records have been published yet.");
        Map<FollowChannel, String> versions = new EnumMap<>(FollowChannel.class);
        Map<ReleaseKey, ReleaseCatalog.Release> cache = new HashMap<>();
        for (FollowChannel channel : FollowChannel.values()) {
            Located located = newest(records, channel);
            if (located != null) versions.put(channel, resolve(located, cache).get(repository).version());
        }
        return new CurrentPointers(versions);
    }

    public QuickInstallSnapshot quickInstallSnapshot() throws IOException, InterruptedException {
        List<Located> records = records();
        if (records.isEmpty()) throw new IOException("No complete suite records have been published yet.");
        Map<ReleaseKey, ReleaseCatalog.Release> cache = new HashMap<>();
        Map<FollowChannel, Map<OfficialRepository, Target>> targets = new EnumMap<>(FollowChannel.class);
        Map<FollowChannel, String> failures = new EnumMap<>(FollowChannel.class);
        for (FollowChannel channel : FollowChannel.values()) {
            Located located = newest(records, channel);
            if (located == null) {
                failures.put(channel, "No complete " + channel + " suite record has been published.");
                continue;
            }
            try {
                targets.put(channel, resolve(located, cache));
            } catch (IOException error) {
                failures.put(channel, SanitizedErrors.display(error));
            }
        }
        if (targets.isEmpty()) {
            throw new IOException("No supported complete suite is available: " + failures);
        }
        List<QuickInstallOption> options = new ArrayList<>();
        for (QuickInstallOption.Key key : QuickInstallSnapshot.ALL_KEYS) {
            Map<OfficialRepository, Target> channelTargets = targets.get(key.channel());
            options.add(channelTargets == null
                    ? new QuickInstallOption(key, null, failures.get(key.channel()))
                    : new QuickInstallOption(key, channelTargets.get(key.repository())));
        }
        return new QuickInstallSnapshot(options);
    }

    History history(OfficialRepository repository, FollowChannel channel, int page, int perPage)
            throws IOException, InterruptedException {
        requireSelection(channel, repository);
        Map<String, Located> unique = new LinkedHashMap<>();
        Map<ReleaseKey, ReleaseCatalog.Release> cache = new HashMap<>();
        for (Located located : records()) {
            if (located.record().membership() == channel) {
                unique.putIfAbsent(located.record().products().get(repository).tag(), located);
            }
        }
        if (unique.isEmpty()) throw new IOException("No complete " + channel + " suite record has been published.");
        List<Located> members = List.copyOf(unique.values());
        Target current = resolve(members.getFirst(), cache).get(repository);
        int start = Math.min((page - 1) * perPage, members.size());
        int end = Math.min(start + perPage, members.size());
        List<Target> entries = new ArrayList<>();
        for (Located located : members.subList(start, end)) {
            entries.add(resolve(located, cache).get(repository));
        }
        return new History(current, List.copyOf(entries), end < members.size());
    }

    record History(Target current, List<Target> entries, boolean mayHaveNextPage) {}

    public static boolean isSource(String source) {
        if (source == null || !source.startsWith(ASSET_SOURCE)) return false;
        String id = source.substring(ASSET_SOURCE.length());
        if (!id.matches("[1-9][0-9]{0,18}")) return false;
        try {
            return Long.parseLong(id) > 0;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    private List<Located> records() throws IOException, InterruptedException {
        ReleaseCatalog catalog = new ReleaseCatalog(transport);
        VerifiedPackageFetcher fetcher = new VerifiedPackageFetcher(transport);
        List<Located> records = new ArrayList<>();
        var versions = new HashSet<String>();
        long totalBytes = 0;
        for (int page = 1; page <= MAX_PAGES; page++) {
            ReleaseCatalog.Page releases = catalog.list(OfficialRepository.MEGAMEK, page,
                    ReleaseCatalog.MAX_PAGE_SIZE);
            for (ReleaseCatalog.Release release : releases.releases()) {
                if (release.draft()) continue;
                for (ReleaseCatalog.Asset asset : release.assets()) {
                    if (!asset.name().startsWith("suite-record-")) continue;
                    var name = RECORD_NAME.matcher(asset.name());
                    if (!name.matches()) throw new IOException("malformed complete suite record asset name");
                    String version = SuiteReleaseRecord.canonicalVersion(name.group(1), "record filename");
                    if (!versions.add(version)) throw new IOException("duplicate complete suite version: " + version);
                    if (!release.tag().equals("v" + version) || release.id().isEmpty()
                            || asset.id().isEmpty() || !asset.state().orElse("").equals("uploaded")
                            || asset.size() <= 0 || asset.size() > SuiteReleaseRecord.MAX_BYTES) {
                        throw new IOException("incomplete suite record release/asset identity");
                    }
                    totalBytes += asset.size();
                    if (totalBytes > MAX_RECORD_BYTES_TOTAL) {
                        throw new IOException("suite record inventory exceeds total metadata limit");
                    }
                    SuiteReleaseRecord record = SuiteReleaseRecord.parse(fetcher.readMetadataAsset(
                            OfficialRepository.MEGAMEK, release, asset, SuiteReleaseRecord.MAX_BYTES));
                    if (!record.version().equals(version) || !record.tag().equals(release.tag())) {
                        throw new IOException("suite record content differs from its release identity");
                    }
                    records.add(new Located(record, ASSET_SOURCE + asset.id().getAsLong()));
                }
            }
            if (!releases.mayHaveNextPage()) {
                records.sort((first, second) -> SuiteReleaseRecord.compareVersions(
                        second.record().version(), first.record().version()));
                return List.copyOf(records);
            }
        }
        throw new IOException("suite release inventory is incomplete at the pagination limit");
    }

    private Map<OfficialRepository, Target> resolve(Located located,
                                                   Map<ReleaseKey, ReleaseCatalog.Release> cache)
            throws IOException, InterruptedException {
        located.record().requireSupportedLauncher(launcherVersion());
        ReleaseCatalog catalog = new ReleaseCatalog(transport);
        Map<OfficialRepository, Target> targets = new EnumMap<>(OfficialRepository.class);
        for (OfficialRepository repository : OfficialRepository.values()) {
            SuiteReleaseRecord.Product product = located.record().products().get(repository);
            ReleaseKey key = new ReleaseKey(repository, product.releaseId());
            ReleaseCatalog.Release release = cache.get(key);
            if (release == null) {
                release = catalog.byId(repository, product.releaseId());
                cache.put(key, release);
            }
            if (!release.tag().equals(product.tag())) throw new IOException("suite product release tag mismatch");
            ReleaseCatalog.Asset asset = catalog.selectInstallAsset(repository, release);
            if (asset.id().isEmpty() || asset.id().getAsLong() != product.assetId()
                    || !asset.name().equals(product.name()) || asset.size() != product.size()
                    || !asset.state().orElse("").equals("uploaded")) {
                throw new IOException(repository.productName() + " suite asset identity mismatch");
            }
            if (asset.publishedDigest().orElse(null) instanceof PackageDigest.MalformedPublished
                    || asset.publishedDigest().orElse(null) instanceof PackageDigest.ValidPublished published
                    && !published.value().hex().equals(product.sha256())) {
                throw new IOException(repository.productName() + " suite asset SHA-256 mismatch");
            }
            ReleaseCatalog.Asset attested = new ReleaseCatalog.Asset(asset.name(), asset.size(),
                    PackageDigest.published("sha256:" + product.sha256()), asset.url(), asset.id(), asset.state());
            ReleaseCatalog.Release attestedRelease = ReleaseCatalog.withAsset(release, asset, attested);
            targets.put(repository, new Target(located.record().membership(), product.version(),
                    repository, attestedRelease, attested, located.source()));
        }
        return Map.copyOf(targets);
    }

    private String launcherVersion() throws IOException {
        if (suppliedLauncherVersion != null) return suppliedLauncherVersion;
        try (var input = OfficialSuiteChannelCatalog.class.getResourceAsStream(
                "/org/megamek/launcher/version.properties")) {
            if (input == null) throw new IOException("running launcher version metadata is missing");
            Properties properties = new Properties();
            properties.load(input);
            String version = properties.getProperty("version");
            if (version == null) throw new IOException("running launcher version metadata is incomplete");
            return version;
        }
    }

    private static void requireSelection(FollowChannel channel, OfficialRepository repository)
            throws IOException {
        if (channel == null || repository == null) throw new IOException("channel and product are required");
    }

    private static Located newest(List<Located> records, FollowChannel channel) {
        for (Located record : records) if (record.record().membership() == channel) return record;
        return null;
    }

    private static Located latest(List<Located> records, FollowChannel channel) throws IOException {
        Located latest = newest(records, channel);
        if (latest == null) throw new IOException("No complete " + channel + " suite record has been published.");
        return latest;
    }

    private record Located(SuiteReleaseRecord record, String source) {}
    private record ReleaseKey(OfficialRepository repository, long id) {}

    public record CurrentPointers(Map<FollowChannel, String> versions) {
        public CurrentPointers {
            versions = Map.copyOf(versions);
        }

        public String version(FollowChannel channel) {
            String version = versions.get(channel);
            if (version == null) throw new IllegalArgumentException("no complete " + channel + " suite record");
            return version;
        }

        public CurrentIdentity classify(FollowChannel selected, VersionIdentity identity) {
            Objects.requireNonNull(selected, "selected channel");
            Objects.requireNonNull(identity, "release identity");
            boolean selectedMatch = matches(selected, identity);
            boolean otherMatch = false;
            for (FollowChannel channel : FollowChannel.values()) {
                if (channel != selected && matches(channel, identity)) otherMatch = true;
            }
            if (selectedMatch) return otherMatch ? CurrentIdentity.SHARED_CURRENT : CurrentIdentity.SELECTED_CURRENT;
            return otherMatch ? CurrentIdentity.OPPOSITE_CURRENT : CurrentIdentity.UNKNOWN;
        }

        public FollowChannel opposite(FollowChannel selected, VersionIdentity identity) {
            Objects.requireNonNull(selected, "selected channel");
            Objects.requireNonNull(identity, "release identity");
            for (FollowChannel channel : FollowChannel.values()) {
                if (channel != selected && matches(channel, identity)) return channel;
            }
            throw new IllegalArgumentException("no matching opposite channel");
        }

        private boolean matches(FollowChannel channel, VersionIdentity identity) {
            String version = versions.get(channel);
            return version != null && VersionIdentity.fromExact(version).orElseThrow().equals(identity);
        }
    }

    public enum CurrentIdentity {
        SELECTED_CURRENT, SHARED_CURRENT, OPPOSITE_CURRENT, UNKNOWN
    }
}
