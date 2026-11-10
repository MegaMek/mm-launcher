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
package org.megamek.launcher.release;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns the suite's GitHub release notes into "What's new" for players: only the list of changes,
 * grouped by heading, without dependency bumps, and combined across every release a player skipped.
 *
 * <p>Each release's notes list the pull requests merged since the previous release of the same
 * channel, so going from 0.51.01.2 to 0.51.01.5 combines the notes of .3, .4 and .5. Notes are
 * display-only and never take part in choosing or installing a release.</p>
 */
public final class ReleaseNotes {
    static final int PAGE_SIZE = 50;
    static final int MAX_PAGES = 3;
    static final int MAX_RELEASES = 30;
    /** Heading for changes listed before any heading, as in notes from before the categories. */
    static final String UNGROUPED = "Changes";
    /** The order of the headings in .github/release.yml of the suite repositories. */
    private static final List<String> HEADINGS = List.of("new features", "rules", "improvements",
            "bug fixes", "other changes", "behind the scenes");
    private static final Pattern SECTION = Pattern.compile("^##\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern SUBSECTION = Pattern.compile("^###\\s+(.+?)\\s*#*\\s*$");
    private static final Pattern ITEM = Pattern.compile("^[*-]\\s+(.+)$");
    private static final Pattern GENERATED = Pattern.compile(
            "^(.+?) by @([A-Za-z0-9-]+(?:\\[bot])?) in (https://\\S+)$");
    /** A dependency bump in Dependabot's wording, even when a person merged it. */
    private static final Pattern BUMP = Pattern.compile("^Bump \\S+ from \\S+ to \\S+$");

    private ReleaseNotes() {
    }

    /** One change, usually a pull request title. {@code link} is null when there is no safe link. */
    public record Change(String title, OptionalInt pullRequest, URI link) {
    }

    public record Section(String heading, List<Change> changes) {
        public Section {
            changes = List.copyOf(changes);
        }
    }

    /**
     * One application's changes. {@code problem} explains why there are none to show, or is null.
     * {@code page} is where the full notes can be read on GitHub.
     */
    public record Application(OfficialRepository repository, List<String> tags, List<Section> sections,
                              URI page, String problem) {
        public Application {
            tags = List.copyOf(tags);
            sections = List.copyOf(sections);
        }
    }

    /** What changed in {@code version}, since {@code since} when that is an older version. */
    public record WhatsNew(String version, String since, List<Application> applications) {
        public WhatsNew {
            applications = List.copyOf(applications);
        }
    }

    /**
     * Loads what changed in every suite application when going from {@code since} (null for a
     * fresh install) to {@code version}. A failure for one application is reported in its
     * {@link Application#problem()} rather than hiding the others.
     */
    public static WhatsNew load(ReleaseCatalog catalog, String since, String version)
            throws InterruptedException {
        List<Application> applications = new ArrayList<>();
        for (OfficialRepository repository : OfficialRepository.values()) {
            applications.add(application(catalog, repository, since, version));
        }
        return new WhatsNew(version, since, applications);
    }

    static Application application(ReleaseCatalog catalog, OfficialRepository repository,
                                   String since, String version) throws InterruptedException {
        URI releases = URI.create("https://github.com/" + repository.slug() + "/releases");
        Optional<VersionIdentity> target = VersionIdentity.fromExact(version);
        if (target.isEmpty()) {
            return new Application(repository, List.of(), List.of(), releases,
                    "This version has no published release notes.");
        }
        try {
            List<ReleaseCatalog.Notes> chosen = choose(catalog, repository,
                    VersionIdentity.fromExact(since).orElse(null), target.get(), tag(version));
            List<List<Section>> parsed = new ArrayList<>();
            for (ReleaseCatalog.Notes notes : chosen) parsed.add(parse(repository, notes.body()));
            URI page = chosen.size() == 1 ? URI.create(releases + "/tag/" + chosen.getFirst().tag())
                    : releases;
            return new Application(repository, chosen.stream().map(ReleaseCatalog.Notes::tag).toList(),
                    merge(parsed), page, null);
        } catch (GitHubRateLimitException limited) {
            return new Application(repository, List.of(), List.of(), releases, limited.getMessage());
        } catch (IOException failure) {
            return new Application(repository, List.of(), List.of(), releases,
                    failure.getMessage() != null && failure.getMessage().contains("HTTP 404")
                            ? "This version has no published release notes."
                            : "The release notes could not be loaded right now.");
        }
    }

    /** The releases whose notes make up the change from {@code since} to {@code target}, newest first. */
    private static List<ReleaseCatalog.Notes> choose(ReleaseCatalog catalog, OfficialRepository repository,
                                                     VersionIdentity since, VersionIdentity target,
                                                     String targetTag)
            throws IOException, InterruptedException {
        boolean combine = since != null && compare(since, target) < 0;
        VersionIdentity lowest = combine ? since : target;
        List<ReleaseCatalog.Notes> listed = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            List<ReleaseCatalog.Notes> notes = catalog.notes(repository, page, PAGE_SIZE);
            listed.addAll(notes);
            boolean reached = notes.stream().map(n -> version(n.tag()))
                    .anyMatch(v -> v != null && compare(v, lowest) < 0);
            if (notes.size() < PAGE_SIZE || reached) break;
        }
        ReleaseCatalog.Notes newest = listed.stream()
                .filter(n -> !n.draft() && target.equals(version(n.tag()))).findFirst().orElse(null);
        if (newest == null) return List.of(catalog.notes(repository, targetTag));
        if (!combine) return List.of(newest);
        return listed.stream()
                .filter(n -> !n.draft() && n.prerelease() == newest.prerelease())
                .filter(n -> {
                    VersionIdentity v = version(n.tag());
                    return v != null && compare(v, since) > 0 && compare(v, target) <= 0;
                })
                .sorted(Comparator.comparing((ReleaseCatalog.Notes n) -> version(n.tag()),
                        ReleaseNotes::compare).reversed())
                .limit(MAX_RELEASES)
                .toList();
    }

    /** A release version without a suffix, or null for anything else such as a development draft. */
    private static VersionIdentity version(String tag) {
        return VersionIdentity.fromExact(tag).filter(v -> v.suffix().isEmpty()).orElse(null);
    }

    private static String tag(String version) {
        String trimmed = version.trim();
        return trimmed.startsWith("v") ? trimmed : "v" + trimmed;
    }

    /** Whether going from {@code since} to {@code version} combines the notes of several releases. */
    public static boolean combines(String since, String version) {
        Optional<VersionIdentity> from = VersionIdentity.fromExact(since);
        Optional<VersionIdentity> to = VersionIdentity.fromExact(version);
        return from.isPresent() && to.isPresent() && compare(from.get(), to.get()) < 0;
    }

    /** Orders versions numerically; a version without the fourth component sorts below its first revision. */
    static int compare(VersionIdentity left, VersionIdentity right) {
        int length = Math.max(left.components().size(), right.components().size());
        for (int i = 0; i < length; i++) {
            BigInteger a = i < left.components().size() ? left.components().get(i) : BigInteger.ONE.negate();
            BigInteger b = i < right.components().size() ? right.components().get(i) : BigInteger.ONE.negate();
            int result = a.compareTo(b);
            if (result != 0) return result;
        }
        return 0;
    }

    /**
     * Reads the "What's Changed" part of GitHub's generated notes: its headings and the changes
     * under them. The download table above it, the new contributors and the full changelog link
     * below it, and Dependabot's dependency bumps are left out.
     */
    static List<Section> parse(OfficialRepository repository, String body) {
        Map<String, List<Change>> sections = new LinkedHashMap<>();
        boolean changes = false;
        String heading = UNGROUPED;
        for (String raw : (body == null ? "" : body).split("\\R")) {
            String line = raw.strip();
            Matcher section = SECTION.matcher(line);
            if (section.matches() && !line.startsWith("###")) {
                changes = section.group(1).toLowerCase(Locale.ROOT).startsWith("what");
                heading = UNGROUPED;
                continue;
            }
            if (!changes) continue;
            if (line.startsWith("**Full Changelog**")) break;
            Matcher subsection = SUBSECTION.matcher(line);
            if (subsection.matches()) {
                heading = subsection.group(1).strip();
                continue;
            }
            Matcher item = ITEM.matcher(line);
            if (!item.matches()) continue;
            Change change = change(repository, item.group(1).strip());
            if (change != null) sections.computeIfAbsent(heading, ignored -> new ArrayList<>()).add(change);
        }
        List<Section> parsed = new ArrayList<>();
        sections.forEach((name, list) -> parsed.add(new Section(name, list)));
        return parsed;
    }

    private static Change change(OfficialRepository repository, String text) {
        Matcher generated = GENERATED.matcher(text);
        if (!generated.matches()) return new Change(text, OptionalInt.empty(), null);
        if (generated.group(2).toLowerCase(Locale.ROOT).startsWith("dependabot")) return null;
        String title = generated.group(1).strip();
        if (BUMP.matcher(title).matches()) return null;
        Matcher pull = Pattern.compile("^https://github\\.com/" + Pattern.quote(repository.slug())
                + "/pull/([0-9]{1,9})$", Pattern.CASE_INSENSITIVE).matcher(generated.group(3));
        if (!pull.matches()) return new Change(title, OptionalInt.empty(), null);
        int number = Integer.parseInt(pull.group(1));
        return new Change(title, OptionalInt.of(number),
                URI.create("https://github.com/" + repository.slug() + "/pull/" + number));
    }

    /**
     * Combines the sections of several releases, newest first, into one list per heading in the
     * order the release notes use. A pull request listed by more than one release appears once.
     */
    static List<Section> merge(List<List<Section>> releases) {
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, List<Change>> merged = new LinkedHashMap<>();
        Set<Integer> seen = new HashSet<>();
        for (List<Section> release : releases) {
            for (Section section : release) {
                String key = section.heading().toLowerCase(Locale.ROOT);
                names.putIfAbsent(key, section.heading());
                List<Change> target = merged.computeIfAbsent(key, ignored -> new ArrayList<>());
                for (Change change : section.changes()) {
                    if (change.pullRequest().isEmpty() || seen.add(change.pullRequest().getAsInt())) {
                        target.add(change);
                    }
                }
            }
        }
        List<String> order = new ArrayList<>(merged.keySet());
        order.sort(Comparator.comparingInt(ReleaseNotes::rank));
        List<Section> sections = new ArrayList<>();
        for (String key : order) {
            if (!merged.get(key).isEmpty()) sections.add(new Section(names.get(key), merged.get(key)));
        }
        return sections;
    }

    /** Known headings keep the release notes' order; others go just before "Behind the scenes". */
    private static int rank(String key) {
        int known = HEADINGS.indexOf(key);
        return known >= 0 ? known * 2 : HEADINGS.indexOf("behind the scenes") * 2 - 1;
    }
}