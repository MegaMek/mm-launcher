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

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.megamek.launcher.release.OfficialRepository;
import org.megamek.launcher.release.ReleaseTransport;
import org.megamek.launcher.release.VersionIdentity;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OfficialSuiteChannelCatalogTest {
    @Test
    void weeklyOnlyBootstrapLeavesMilestoneDefaultExplicitlyUnavailable() throws Exception {
        SuiteTestData network = new SuiteTestData();
        network.suite("0.51.01", FollowChannel.WEEKLY, "0.51.01");
        QuickInstallSnapshot snapshot = catalog(network).quickInstallSnapshot();
        assertEquals(9, snapshot.options().size());
        assertEquals(FollowChannel.MILESTONE, QuickInstallSnapshot.DEFAULT_KEY.channel());
        assertFalse(snapshot.option(QuickInstallSnapshot.DEFAULT_KEY).available());
        assertEquals(3, snapshot.options().stream().filter(QuickInstallOption::available).count());
        assertTrue(snapshot.options().stream().filter(QuickInstallOption::available)
                .allMatch(option -> option.key().channel() == FollowChannel.WEEKLY));
        assertTrue(snapshot.option(QuickInstallSnapshot.DEFAULT_KEY).unavailableReason().contains("Milestone"));
        assertThrows(IllegalStateException.class,
                () -> snapshot.option(QuickInstallSnapshot.DEFAULT_KEY).version());
        assertNoPackages(network);
    }

    @Test
    void ordersNumericallyAndUsesIndependentProductVersionsAndRecordChecksums() throws Exception {
        SuiteTestData network = new SuiteTestData();
        network.suite("0.51.99", FollowChannel.WEEKLY, "0.51.99");
        network.suite("0.51.100", FollowChannel.WEEKLY, Map.of(
                OfficialRepository.MEGAMEK, "0.51.98", OfficialRepository.LAB, "0.51.99",
                OfficialRepository.MEKHQ, "0.51.100"), Map.of(), Map.of());
        for (OfficialRepository repository : OfficialRepository.values()) {
            ChannelCatalog.Target target = catalog(network).target(FollowChannel.WEEKLY, repository);
            assertEquals(SuiteTestData.recordUri("0.51.100").toString(), target.source());
            assertEquals(repository == OfficialRepository.MEGAMEK ? "0.51.98"
                    : repository == OfficialRepository.LAB ? "0.51.99" : "0.51.100", target.version());
            assertEquals("sha256:" + "a".repeat(64), target.asset().digest());
            assertTrue(target.release().assets().contains(target.asset()));
        }
        assertNoPackages(network);
    }

    @Test
    void discoversLaterPagesWithoutChoosingGitHubPublicationOrder() throws Exception {
        SuiteTestData network = new SuiteTestData();
        for (int i = 1; i <= 50; i++) network.suite("0.51.%02d".formatted(i),
                FollowChannel.WEEKLY, "0.51.01");
        network.suite("0.51.100", FollowChannel.WEEKLY, "0.51.01");
        assertEquals(SuiteTestData.recordUri("0.51.100").toString(),
                catalog(network).target(FollowChannel.WEEKLY, OfficialRepository.MEKHQ).source());
        assertTrue(network.requests.stream().anyMatch(uri -> uri.toString().endsWith("page=2")));
    }

    @Test
    void neverTreatsPartialInventoryAsCompleteAtPaginationBound() {
        String release = """
                {"tag_name":"unrelated","name":"unrelated","draft":false,"prerelease":false,
                 "html_url":"https://github.com/MegaMek/megamek/releases/tag/unrelated","assets":[]}
                """;
        byte[] body = ("[" + String.join(",", java.util.Collections.nCopies(50, release)) + "]")
                .getBytes(StandardCharsets.UTF_8);
        int[] requests = {0};
        ReleaseTransport network = (uri, accept) -> {
            requests[0]++;
            return new ReleaseTransport.Response(200, Map.of(), new ByteArrayInputStream(body));
        };
        IOException error = assertThrows(IOException.class,
                () -> catalog(network).target(FollowChannel.WEEKLY, OfficialRepository.MEKHQ));
        assertTrue(error.getMessage().contains("pagination limit"));
        assertEquals(OfficialSuiteChannelCatalog.MAX_PAGES, requests[0]);
    }

    @Test
    void capturedRecordAndHistoricalBrowserDoNotRetargetToNewerSuite() throws Exception {
        SuiteTestData network = new SuiteTestData();
        network.suite("0.51.01", FollowChannel.WEEKLY, "0.51.01");
        String captured = catalog(network).target(FollowChannel.WEEKLY, OfficialRepository.MEKHQ).source();
        network.suite("0.51.02", FollowChannel.WEEKLY, "0.51.02");
        assertEquals("v0.51.01", catalog(network).targetBySource(
                FollowChannel.WEEKLY, OfficialRepository.MEKHQ, captured).release().tag());
        var first = new SelectedChannelReleaseCatalog(network).page(
                OfficialRepository.MEKHQ, FollowChannel.WEEKLY, 1, 1);
        var second = new SelectedChannelReleaseCatalog(network).page(
                OfficialRepository.MEKHQ, FollowChannel.WEEKLY, 2, 1);
        assertTrue(first.mayHaveNextPage());
        assertFalse(second.mayHaveNextPage());
        assertEquals(SelectedChannelReleaseCatalog.Classification.SELECTED_CHANNEL,
                first.entries().getFirst().classification());
        assertEquals(SelectedChannelReleaseCatalog.Classification.CHANNEL_HISTORY,
                second.entries().getFirst().classification());
        assertEquals(captured, second.entries().getFirst().source());
        assertThrows(IOException.class, () -> catalog(network).targetByTag(
                FollowChannel.DEVELOPMENT, OfficialRepository.MEKHQ, "v0.51.01"));
        assertThrows(IOException.class, () -> new SelectedChannelReleaseCatalog(network).page(
                OfficialRepository.MEKHQ, FollowChannel.WEEKLY, 0, 1));
        assertNoPackages(network);
    }

    @Test
    void reusedProductIsDeduplicatedInMembershipHistory() throws Exception {
        SuiteTestData network = new SuiteTestData();
        network.suite("0.51.01", FollowChannel.WEEKLY, "0.51.01");
        network.suite("0.51.02", FollowChannel.WEEKLY, "0.51.01");
        var page = new SelectedChannelReleaseCatalog(network).page(
                OfficialRepository.MEGAMEK, FollowChannel.WEEKLY, 1, 10);
        assertEquals(1, page.entries().size());
        assertEquals(SuiteTestData.recordUri("0.51.02").toString(), page.entries().getFirst().source());
    }

    @Test
    void incompatibleNewestNeverFallsBackAndDoesNotHideOtherAvailableChannel() throws Exception {
        SuiteTestData network = new SuiteTestData();
        network.suite("0.51.01", FollowChannel.WEEKLY, "0.51.01");
        network.suite("0.51.02", FollowChannel.MILESTONE, "0.51.01");
        network.suite("0.51.03", FollowChannel.WEEKLY, "0.51.01")
                .put("minimumLauncherVersion", "0.15.0");
        network.refreshSize("0.51.03");
        assertTrue(assertThrows(IOException.class, () -> catalog(network).target(
                FollowChannel.WEEKLY, OfficialRepository.MEKHQ)).getMessage().contains("0.15.0"));
        var snapshot = catalog(network).quickInstallSnapshot();
        assertTrue(snapshot.option(QuickInstallSnapshot.DEFAULT_KEY).available());
        assertFalse(snapshot.option(new QuickInstallOption.Key(
                OfficialRepository.MEKHQ, FollowChannel.WEEKLY)).available());
    }

    @Test
    void validatesEveryReferencedProductBeforeOfferingAnyProduct() throws Exception {
        List<Consumer<ObjectNode>> mutations = List.of(
                node -> node.put("id", 1),
                node -> node.put("tag_name", "v0.51.02"),
                node -> node.put("draft", true),
                node -> node.withArray("assets").removeAll(),
                node -> ((ObjectNode) node.withArray("assets").get(0)).put("id", 1),
                node -> ((ObjectNode) node.withArray("assets").get(0)).put("state", "new"),
                node -> ((ObjectNode) node.withArray("assets").get(0)).put("name", "MekHQ-wrong.tar.gz"),
                node -> ((ObjectNode) node.withArray("assets").get(0)).put("size", 1),
                node -> ((ObjectNode) node.withArray("assets").get(0)).put("digest", "sha256:" + "d".repeat(64)),
                node -> ((ObjectNode) node.withArray("assets").get(0)).put("digest", "malformed"));
        for (Consumer<ObjectNode> mutation : mutations) {
            SuiteTestData network = new SuiteTestData();
            network.suite("0.51.01", FollowChannel.WEEKLY, "0.51.01");
            mutation.accept(network.product(OfficialRepository.MEKHQ, "0.51.01"));
            assertThrows(IOException.class,
                    () -> catalog(network).target(FollowChannel.WEEKLY, OfficialRepository.MEGAMEK));
            assertNoPackages(network);
        }
    }

    @Test
    void missingAndDuplicateRecordsAreExplicitUnavailable() throws Exception {
        assertThrows(IOException.class, () -> catalog(new SuiteTestData()).quickInstallSnapshot());
        SuiteTestData network = new SuiteTestData();
        network.suite("0.51.01", FollowChannel.WEEKLY, "0.51.01");
        network.suite("0.51.01", FollowChannel.WEEKLY, "0.51.01");
        assertThrows(IOException.class,
                () -> catalog(network).target(FollowChannel.WEEKLY, OfficialRepository.MEKHQ));
    }

    @Test
    void aggregateMetadataLimitAcceptsExactly32MiBAndRejectsTheNextRecord() throws Exception {
        SuiteTestData network = new SuiteTestData();
        for (int i = 1; i <= 33; i++) {
            String version = "0.51.%02d".formatted(i);
            ObjectNode record = network.suite(version, FollowChannel.WEEKLY, "0.51.01");
            byte[] padded = new byte[SuiteReleaseRecord.MAX_BYTES];
            java.util.Arrays.fill(padded, (byte) ' ');
            byte[] json = SuiteTestData.bytes(record);
            System.arraycopy(json, 0, padded, 0, json.length);
            ObjectNode asset = network.recordAsset(version);
            asset.put("size", padded.length);
            network.put(java.net.URI.create(asset.path("browser_download_url").asText()), padded);
            if (i == 32) {
                assertEquals(SuiteTestData.recordUri(version).toString(), catalog(network)
                        .target(FollowChannel.WEEKLY, OfficialRepository.MEKHQ).source());
                network.requests.clear();
            }
        }
        IOException error = assertThrows(IOException.class,
                () -> catalog(network).target(FollowChannel.WEEKLY, OfficialRepository.MEKHQ));
        assertTrue(error.getMessage().contains("total metadata limit"));
        assertEquals(32, network.requests.stream().filter(uri -> uri.getPath().endsWith(".json")).count());
        assertNoPackages(network);
    }

    @Test
    void parserRejectsMalformedIncompleteAndTrailingRecordsAtExactByteBound() throws Exception {
        ObjectNode valid = new SuiteTestData().suite("0.51.01", FollowChannel.WEEKLY, "0.51.01");
        List<Consumer<ObjectNode>> mutations = List.of(
                node -> node.put("schemaVersion", 2),
                node -> node.put("unexpected", true),
                node -> node.put("membership", "Weekly"),
                node -> node.put("membership", "nightly"),
                node -> node.put("version", "0.51.1"),
                node -> node.put("tag", "v0.51.02"),
                node -> ((ObjectNode) node.get("products")).remove("MegaMekLab"),
                node -> ((ObjectNode) node.at("/products/MekHQ")).put("releaseId", 0),
                node -> ((ObjectNode) node.at("/products/MekHQ/asset")).put("size", "1234"),
                node -> ((ObjectNode) node.at("/products/MekHQ/asset")).put("sha256", "A".repeat(64)),
                node -> ((ObjectNode) node.at("/mmData")).put("repository", "https://example.com/data"));
        for (Consumer<ObjectNode> mutation : mutations) {
            ObjectNode invalid = valid.deepCopy();
            mutation.accept(invalid);
            assertThrows(IOException.class, () -> SuiteReleaseRecord.parse(SuiteTestData.bytes(invalid)));
        }
        String json = valid.toString();
        assertThrows(IOException.class, () -> SuiteReleaseRecord.parse(
                (json + "{}").getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> SuiteReleaseRecord.parse(
                json.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1")
                        .getBytes(StandardCharsets.UTF_8)));
        byte[] padded = new byte[SuiteReleaseRecord.MAX_BYTES];
        java.util.Arrays.fill(padded, (byte) ' ');
        byte[] bytes = SuiteTestData.bytes(valid);
        System.arraycopy(bytes, 0, padded, 0, bytes.length);
        assertEquals("0.51.01", SuiteReleaseRecord.parse(padded).version());
        assertThrows(IOException.class, () -> SuiteReleaseRecord.parse(new byte[padded.length + 1]));
    }

    @Test
    void sharedCurrentIdentityWorksAcrossThreeChannelsAndWeeklyIsNotNightly() throws Exception {
        var pointers = new OfficialSuiteChannelCatalog.CurrentPointers(Map.of(
                FollowChannel.MILESTONE, "0.51.01", FollowChannel.DEVELOPMENT, "0.51.01",
                FollowChannel.WEEKLY, "0.51.02"));
        var identity = VersionIdentity.fromExact("v0.51.01").orElseThrow();
        assertEquals(OfficialSuiteChannelCatalog.CurrentIdentity.SHARED_CURRENT,
                pointers.classify(FollowChannel.MILESTONE, identity));
        assertEquals(OfficialSuiteChannelCatalog.CurrentIdentity.OPPOSITE_CURRENT,
                pointers.classify(FollowChannel.WEEKLY, identity));
        assertEquals(FollowChannel.MILESTONE, pointers.opposite(FollowChannel.WEEKLY, identity));
        assertEquals(FollowChannel.WEEKLY, FollowChannel.parse("weekly"));
        assertThrows(IllegalArgumentException.class, () -> FollowChannel.parse("nightly"));
    }

    private static OfficialSuiteChannelCatalog catalog(ReleaseTransport network) {
        return new OfficialSuiteChannelCatalog(network, "0.14.5");
    }

    private static void assertNoPackages(SuiteTestData network) {
        assertTrue(network.requests.stream().noneMatch(uri -> uri.getPath().endsWith(".tar.gz")));
    }
}
