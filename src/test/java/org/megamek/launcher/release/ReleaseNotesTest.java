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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ReleaseNotesTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final OfficialRepository MM = OfficialRepository.MEGAMEK;

    private static String body(String changes) {
        return "**Channel: Experimental**\n\n| Download | File |\n|---|---|\n| Windows | x.zip |\n\n"
                + "## What's Changed\n" + changes
                + "\n## New Contributors\n* @newbie made their first contribution in "
                + "https://github.com/MegaMek/megamek/pull/1\n\n"
                + "**Full Changelog**: https://github.com/MegaMek/megamek/compare/v1...v2\n";
    }

    private static String item(String title, String author, int pr) {
        return "* " + title + " by @" + author + " in https://github.com/MegaMek/megamek/pull/" + pr + "\n";
    }

    @Test
    void parseKeepsOnlyChangesGroupedByHeadingWithoutDependencyBumps() {
        List<ReleaseNotes.Section> sections = ReleaseNotes.parse(MM, body(
                "### New features\n" + item("Add a <b>bold</b> map", "alice", 10)
                        + "### Behind the scenes\n" + item("Bump gradle from 8 to 9", "dependabot[bot]", 11)
                        + item("Bump gradle/actions from 6.4.0 to 6.4.1", "maintainer", 13)
                        + item("Tidy tests", "bob", 12)));

        assertEquals(List.of("New features", "Behind the scenes"),
                sections.stream().map(ReleaseNotes.Section::heading).toList());
        ReleaseNotes.Change added = sections.getFirst().changes().getFirst();
        assertEquals("Add a <b>bold</b> map", added.title());
        assertEquals(10, added.pullRequest().getAsInt());
        assertEquals(URI.create("https://github.com/MegaMek/megamek/pull/10"), added.link());
        assertEquals(List.of("Tidy tests"),
                sections.get(1).changes().stream().map(ReleaseNotes.Change::title).toList());
    }

    @Test
    void parseGroupsUncategorizedNotesAndRefusesForeignLinks() {
        List<ReleaseNotes.Section> sections = ReleaseNotes.parse(MM, body(
                item("Fix a crash", "carol", 20)
                        + "* Sneaky by @eve in https://evil.example/MegaMek/megamek/pull/21\n"
                        + "- Written by hand\n"));

        assertEquals(1, sections.size());
        assertEquals(ReleaseNotes.UNGROUPED, sections.getFirst().heading());
        List<ReleaseNotes.Change> changes = sections.getFirst().changes();
        assertEquals(List.of("Fix a crash", "Sneaky", "Written by hand"),
                changes.stream().map(ReleaseNotes.Change::title).toList());
        assertNull(changes.get(1).link());
        assertTrue(changes.get(1).pullRequest().isEmpty());
        assertTrue(ReleaseNotes.parse(MM, "").isEmpty());
        assertTrue(ReleaseNotes.parse(MM, null).isEmpty());
    }

    @Test
    void mergeOrdersHeadingsAndListsEachPullRequestOnce() {
        List<ReleaseNotes.Section> newer = ReleaseNotes.parse(MM, body("### Bug fixes\n" + item("Fix B", "a", 2)
                + "### Custom\n" + item("Custom thing", "a", 4)));
        List<ReleaseNotes.Section> older = ReleaseNotes.parse(MM, body("### Behind the scenes\n"
                + item("Refactor", "a", 3) + "### New features\n" + item("Feature A", "a", 1)
                + "### Bug fixes\n" + item("Fix B", "a", 2)));

        List<ReleaseNotes.Section> merged = ReleaseNotes.merge(List.of(newer, older));

        assertEquals(List.of("New features", "Bug fixes", "Custom", "Behind the scenes"),
                merged.stream().map(ReleaseNotes.Section::heading).toList());
        assertEquals(1, merged.get(1).changes().size());
    }

    @Test
    void compareOrdersNumericallyWithRevisionsAfterTheirBase() {
        assertTrue(ReleaseNotes.compare(v("0.51.1"), v("v0.51.01.0")) < 0);
        assertTrue(ReleaseNotes.compare(v("0.51.01.10"), v("0.51.01.9")) > 0);
        assertEquals(0, ReleaseNotes.compare(v("0.51.1.3"), v("v0.51.01.3")));
    }

    /** Every 0.51.01.N is Experimental and every 0.50.N Core, as the suite records would say. */
    private static final ReleaseNotes.Channels CHANNELS = tag -> tag.startsWith("v0.51.01.") ? "experimental"
            : tag.startsWith("v0.50.") ? "core" : null;

    @Test
    void combinesEverySkippedReleaseOfTheSameChannel() throws Exception {
        // The prerelease flags disagree with the channels on purpose: the suite records decide.
        FakeTransport transport = new FakeTransport()
                .release("v0.51.01.5", false, body("### Bug fixes\n" + item("Fix five", "a", 5)))
                .release("v0.51.01.4", true, body("### New features\n" + item("Feature four", "a", 4)))
                .release("v0.51.01.3", false, body("### Bug fixes\n" + item("Core fix", "a", 90)))
                .release("v0.51.01.2", false, body("### Bug fixes\n" + item("Fix two", "a", 2)))
                .release("v0.51.01.1", false, body("### Bug fixes\n" + item("Fix one", "a", 1)));
        Map<String, String> records = Map.of("v0.51.01.5", "experimental", "v0.51.01.4", "experimental",
                "v0.51.01.3", "core", "v0.51.01.2", "experimental", "v0.51.01.1", "experimental");

        ReleaseNotes.Application notes = ReleaseNotes.application(new ReleaseCatalog(transport), records::get,
                MM, "0.51.1.1", "0.51.01.5");

        assertNull(notes.problem());
        assertFalse(notes.incomplete());
        assertEquals("0.51.01.5", notes.version());
        assertEquals(List.of("v0.51.01.5", "v0.51.01.4", "v0.51.01.2"), notes.tags());
        assertEquals(List.of("New features", "Bug fixes"),
                notes.sections().stream().map(ReleaseNotes.Section::heading).toList());
        assertEquals(List.of("Fix five", "Fix two"),
                notes.sections().get(1).changes().stream().map(ReleaseNotes.Change::title).toList());
        assertEquals(URI.create("https://github.com/MegaMek/megamek/releases"), notes.page());
    }

    @Test
    void keepsListingPastOlderVersionLinesUntilThePlayersRelease() throws Exception {
        FakeTransport transport = new FakeTransport()
                .release("v0.51.01.60", false, body(item("Newest", "a", 60)));
        // A busy Core line fills the first page with numerically older releases.
        for (int i = 70; i > 21; i--) transport.release("v0.50.07." + i, false, body(item("Core", "a", 1000 + i)));
        transport.release("v0.51.01.59", false, body(item("Second page", "a", 59)))
                .release("v0.51.01.58", false, body(item("Player had this", "a", 58)))
                .release("v0.51.01.57", false, body(item("Older", "a", 57)));

        ReleaseNotes.Application notes = ReleaseNotes.application(new ReleaseCatalog(transport), CHANNELS, MM,
                "0.51.01.58", "0.51.01.60");

        assertEquals(List.of("v0.51.01.60", "v0.51.01.59"), notes.tags());
        assertFalse(notes.incomplete());
        assertEquals(List.of(1, 2), transport.pages, "stops at the page with the player's release");
    }

    @Test
    void marksHistoryItCannotShowAsIncomplete() throws Exception {
        FakeTransport many = new FakeTransport();
        for (int i = 40; i >= 1; i--) many.release("v0.51.01." + i, false, body(item("Change " + i, "a", i)));
        ReleaseNotes.Application capped = ReleaseNotes.application(new ReleaseCatalog(many), CHANNELS, MM,
                "0.51.01.1", "0.51.01.40");
        assertEquals(ReleaseNotes.MAX_RELEASES, capped.tags().size());
        assertEquals("v0.51.01.40", capped.tags().getFirst());
        assertTrue(capped.incomplete());

        FakeTransport unlisted = new FakeTransport().release("v0.51.01.5", false, body(item("Five", "a", 5)));
        assertTrue(ReleaseNotes.application(new ReleaseCatalog(unlisted), CHANNELS, MM,
                "0.51.01.2", "0.51.01.5").incomplete(), "the player's release was never listed");

        FakeTransport known = new FakeTransport().release("v0.51.01.5", false, body(item("Five", "a", 5)))
                .release("v0.51.01.4", false, body(item("Four", "a", 4)))
                .release("v0.51.01.2", false, body(item("Two", "a", 2)));
        ReleaseNotes.Application noRecords = ReleaseNotes.application(new ReleaseCatalog(known), tag -> null, MM,
                "0.51.01.2", "0.51.01.5");
        assertEquals(List.of("v0.51.01.5"), noRecords.tags(), "without suite records only the target is certain");
        assertTrue(noRecords.incomplete());
    }

    @Test
    void showsOnlyTheTargetReleaseWithoutAnOlderVersion() throws Exception {
        FakeTransport transport = new FakeTransport()
                .release("v0.51.01.5", false, body(item("Five", "a", 5)))
                .release("v0.51.01.4", false, body(item("Four", "a", 4)));
        ReleaseCatalog catalog = new ReleaseCatalog(transport);

        for (String since : new String[]{null, "0.51.01.5", "0.51.01.9", "not a version"}) {
            ReleaseNotes.Application notes = ReleaseNotes.application(catalog, CHANNELS, MM, since, "0.51.1.5");
            assertEquals(List.of("v0.51.01.5"), notes.tags(), "since " + since);
            assertFalse(notes.incomplete());
            assertEquals(URI.create("https://github.com/MegaMek/megamek/releases/tag/v0.51.01.5"), notes.page());
        }
    }

    @Test
    void reportsAMissingReleaseOrFailureWithoutHidingOtherApplications() throws Exception {
        FakeTransport transport = new FakeTransport().release("v0.51.01.5", false, body(item("Five", "a", 5)));
        transport.failing = "MegaMek/mekhq";

        ReleaseNotes.WhatsNew whatsNew = ReleaseNotes.load(new ReleaseCatalog(transport), CHANNELS, null,
                "0.51.01.5");

        assertEquals("0.51.01.5", whatsNew.version());
        assertEquals(3, whatsNew.applications().size());
        assertNull(whatsNew.applications().get(0).problem());
        assertEquals("This version has no published release notes.", whatsNew.applications().get(1).problem());
        assertEquals("The release notes could not be loaded right now.", whatsNew.applications().get(2).problem());
        assertEquals("This version has no published release notes.",
                ReleaseNotes.application(new ReleaseCatalog(transport), CHANNELS, MM, null, "nightly").problem());
    }

    @Test
    void describesEachApplicationAtItsOwnVersion() throws Exception {
        FakeTransport transport = new FakeTransport().release("v0.51.01.5", false, body(item("Five", "a", 5)))
                .release("v0.50.07.1", false, body(item("Old", "a", 1)));

        ReleaseNotes.WhatsNew whatsNew = ReleaseNotes.load(new ReleaseCatalog(transport), CHANNELS, Map.of(),
                Map.of(OfficialRepository.MEGAMEK, "0.51.01.5", OfficialRepository.MEKHQ, "0.50.07.1"));

        assertNull(whatsNew.version(), "the applications run different versions");
        assertEquals(List.of(OfficialRepository.MEGAMEK, OfficialRepository.MEKHQ),
                whatsNew.applications().stream().map(ReleaseNotes.Application::repository).toList());
        assertEquals(List.of("v0.51.01.5"), whatsNew.applications().getFirst().tags());
        assertEquals("0.50.07.1", whatsNew.applications().get(1).version());
    }

    @Test
    void startsEachApplicationWhereItsInstallationWas() throws Exception {
        FakeTransport transport = new FakeTransport().release("v0.51.01.5", false, body(item("Five", "a", 5)))
                .release("v0.51.01.4", false, body(item("Four", "a", 4)))
                .release("v0.51.01.3", false, body(item("Three", "a", 3)));
        ReleaseCatalog catalog = new ReleaseCatalog(transport);

        ReleaseNotes.WhatsNew updated = ReleaseNotes.load(catalog, CHANNELS,
                Map.of(OfficialRepository.MEGAMEK, "0.51.01.3"), Map.of(OfficialRepository.MEGAMEK, "0.51.01.5"));
        assertEquals("0.51.01.3", updated.since());
        assertEquals(List.of("v0.51.01.5", "v0.51.01.4"), updated.applications().getFirst().tags());

        Map<OfficialRepository, String> since = new java.util.HashMap<>();
        since.put(OfficialRepository.MEGAMEK, "0.51.01.3");
        since.put(OfficialRepository.MEKHQ, null);
        ReleaseNotes.WhatsNew mixed = ReleaseNotes.load(catalog, CHANNELS, since,
                Map.of(OfficialRepository.MEGAMEK, "0.51.01.5", OfficialRepository.MEKHQ, "0.51.01.5"));
        assertNull(mixed.since(), "the applications start at different versions");
        assertEquals(List.of("v0.51.01.5", "v0.51.01.4"), mixed.applications().getFirst().tags());

        ReleaseNotes.WhatsNew unknown = ReleaseNotes.load(catalog, CHANNELS, Map.of(),
                Map.of(OfficialRepository.MEGAMEK, "0.51.01.5"));
        assertNull(unknown.since());
        assertEquals(List.of("v0.51.01.5"), unknown.applications().getFirst().tags(),
                "without a previous installed version only the release's own notes are shown");
    }

    private static VersionIdentity v(String value) {
        return VersionIdentity.fromExact(value).orElseThrow();
    }

    /** Serves the same releases for MegaMek and an empty list elsewhere; exact tags fall back to 404. */
    private static final class FakeTransport implements ReleaseTransport {
        private final List<ObjectNode> releases = new ArrayList<>();
        private final List<Integer> pages = new ArrayList<>();
        String failing;

        FakeTransport release(String tag, boolean prerelease, String body) {
            ObjectNode node = JSON.createObjectNode().put("tag_name", tag).put("draft", false)
                    .put("prerelease", prerelease).put("body", body);
            releases.add(node);
            return this;
        }

        @Override
        public Response get(URI uri, String accept) {
            String path = uri.getPath();
            if (failing != null && path.contains(failing)) return response(500, "{}");
            boolean megamek = path.startsWith("/repos/MegaMek/megamek/");
            if (path.endsWith("/releases")) {
                ArrayNode page = JSON.createArrayNode();
                Map<String, Integer> query = new java.util.HashMap<>();
                for (String pair : uri.getQuery().split("&")) {
                    String[] parts = pair.split("=");
                    query.put(parts[0], Integer.parseInt(parts[1]));
                }
                int size = query.get("per_page");
                int from = (query.get("page") - 1) * size;
                if (megamek) {
                    releases.subList(Math.min(from, releases.size()), Math.min(from + size, releases.size()))
                            .forEach(page::add);
                }
                pages.add(query.get("page"));
                return response(200, page.toString());
            }
            String tag = path.substring(path.lastIndexOf('/') + 1);
            return releases.stream().filter(r -> megamek && r.get("tag_name").asText().equals(tag))
                    .findFirst().map(r -> response(200, r.toString())).orElse(response(404, "{}"));
        }

        private static Response response(int status, String json) {
            return new Response(status, Map.of(),
                    new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        }
    }
}