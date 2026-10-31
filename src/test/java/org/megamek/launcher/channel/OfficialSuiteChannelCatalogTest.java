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
    void emptyInventoryHasNoCurrentIdentitiesAndASeparateUnpublishedChannelFailure() throws Exception {
        SuiteTestData network = new SuiteTestData();
        var pointers = catalog(network).currentPointers(OfficialRepository.MEGAMEK);
        assertTrue(pointers.versions().isEmpty());
        assertEquals(OfficialSuiteChannelCatalog.CurrentIdentity.UNKNOWN,
                pointers.classify(FollowChannel.CORE,
                        VersionIdentity.fromExact("0.50.07.1").orElseThrow()));
        for (FollowChannel channel : FollowChannel.values()) {
            var error = assertThrows(ChannelCatalog.UnpublishedChannelException.class,
                    () -> catalog(network).target(channel, OfficialRepository.MEGAMEK));
            assertEquals(channel, error.channel());
            assertTrue(error.getMessage().contains("No complete " + channel));
        }
        assertThrows(ChannelCatalog.UnpublishedChannelException.class,
                () -> catalog(network).quickInstallSnapshot(false));
        assertThrows(ChannelCatalog.UnpublishedChannelException.class,
                () -> catalog(network).quickInstallSnapshot(true));
        assertNoPackages(network);
    }

    @Test
    void anExperimentalRecordDoesNotManufactureACoreTarget() throws Exception {
        SuiteTestData network = new SuiteTestData();
        network.suite("0.51.02.1", FollowChannel.EXPERIMENTAL);
        var pointers = catalog(network).currentPointers(OfficialRepository.MEGAMEK);
        assertEquals(Map.of(FollowChannel.EXPERIMENTAL, "0.51.02.1"), pointers.versions());
        assertThrows(ChannelCatalog.UnpublishedChannelException.class,
                () -> catalog(network).target(FollowChannel.CORE, OfficialRepository.MEGAMEK));
        assertNoPackages(network);
    }

    @Test
    void quickInstallOffersOnlyCoreUntilExperimentalIsShown() throws Exception {
        SuiteTestData network = new SuiteTestData();
        network.suite("0.51.01.40", FollowChannel.CORE);
        network.suite("0.51.02.3", FollowChannel.EXPERIMENTAL);

        QuickInstallSnapshot core = catalog(network).quickInstallSnapshot(false);
        assertFalse(core.experimentalShown());
        assertEquals(QuickInstallSnapshot.CORE_KEYS,
                core.options().stream().map(QuickInstallOption::key).toList());
        assertEquals(new QuickInstallOption.Key(OfficialRepository.MEKHQ, FollowChannel.CORE),
                QuickInstallSnapshot.DEFAULT_KEY);
        assertTrue(core.options().stream().allMatch(QuickInstallOption::available));
        assertEquals("0.51.01.40", core.option(QuickInstallSnapshot.DEFAULT_KEY).version());
        assertEquals(QuickInstallSnapshot.CORE_KEYS.subList(1, 3), core.menuKeys());
        assertThrows(IllegalArgumentException.class, () -> core.option(
                new QuickInstallOption.Key(OfficialRepository.MEKHQ, FollowChannel.EXPERIMENTAL)));
        for (OfficialRepository repository : OfficialRepository.values()) {
            assertFalse(network.requests.contains(SuiteTestData.releaseUri(repository,
                    SuiteTestData.releaseId(repository, "0.51.02.3"))),
                    "hidden Experimental targets are never resolved");
        }

        QuickInstallSnapshot both = catalog(network).quickInstallSnapshot(true);
        assertTrue(both.experimentalShown());
        assertEquals(6, both.options().size());
        assertTrue(both.options().stream().allMatch(QuickInstallOption::available));
        assertEquals("0.51.02.3", both.option(new QuickInstallOption.Key(
                OfficialRepository.MEGAMEK, FollowChannel.EXPERIMENTAL)).version());
        assertEquals(QuickInstallSnapshot.DEFAULT_KEY, both.options().getFirst().key());
        assertEquals(5, both.menuKeys().size());
        assertNoPackages(network);
    }

    @Test
    void experimentalOnlyBootstrapLeavesCoreDefaultExplicitlyUnavailable() throws Exception {
        SuiteTestData network = new SuiteTestData();
        network.suite("0.51.02.1", FollowChannel.EXPERIMENTAL);
        assertThrows(ChannelCatalog.UnpublishedChannelException.class,
                () -> catalog(network).quickInstallSnapshot(false));
        QuickInstallSnapshot snapshot = catalog(network).quickInstallSnapshot(true);
        assertEquals(6, snapshot.options().size());
        assertFalse(snapshot.option(QuickInstallSnapshot.DEFAULT_KEY).available());
        assertEquals(3, snapshot.options().stream().filter(QuickInstallOption::available).count());
        assertTrue(snapshot.options().stream().filter(QuickInstallOption::available)
                .allMatch(option -> option.key().channel() == FollowChannel.EXPERIMENTAL));
        assertTrue(snapshot.option(QuickInstallSnapshot.DEFAULT_KEY).unavailableReason().contains("Core"));
        assertThrows(IllegalStateException.class,
                () -> snapshot.option(QuickInstallSnapshot.DEFAULT_KEY).version());
        assertNoPackages(network);
    }

    @Test
    void targetIsTheNewestFourPartVersionInTheRecordChannelAcrossLines() throws Exception {
        SuiteTestData network = new SuiteTestData();
        network.suite("0.51.01.40", FollowChannel.CORE);
        for (int build = 1; build <= 32; build++) {
            network.suite("0.51.02." + build, FollowChannel.EXPERIMENTAL);
        }
        assertEquals("0.51.02.32", catalog(network)
                .target(FollowChannel.EXPERIMENTAL, OfficialRepository.MEKHQ).version());
        assertEquals("0.51.01.40", catalog(network)
                .target(FollowChannel.CORE, OfficialRepository.MEKHQ).version());

        network.suite("0.51.02.33", FollowChannel.CORE);
        network.suite("0.51.03.1", FollowChannel.EXPERIMENTAL);
        for (OfficialRepository repository : OfficialRepository.values()) {
            ChannelCatalog.Target core = catalog(network).target(FollowChannel.CORE, repository);
            assertEquals("0.51.02.33", core.version(), "Core follows the new line");
            assertEquals("v0.51.02.33", core.release().tag());
            assertEquals(SuiteTestData.recordUri("0.51.02.33").toString(), core.source());
            assertEquals("sha256:" + "a".repeat(64), core.asset().digest());
            assertTrue(core.release().assets().contains(core.asset()));
            assertEquals("0.51.03.1",
                    catalog(network).target(FollowChannel.EXPERIMENTAL, repository).version());
        }
        assertTrue(SuiteReleaseRecord.compareVersions("0.51.02.9", "0.51.02.10") < 0);
        assertTrue(SuiteReleaseRecord.compareVersions("0.51.02.33", "0.51.03.1") < 0);
        assertTrue(SuiteReleaseRecord.compareVersions("0.51.99.1", "0.51.100.1") < 0);
        assertEquals(0, SuiteReleaseRecord.compareVersions("0.51.02.1", "0.51.02.1"));
        assertNoPackages(network);
    }

    @Test
    void channelComesFromTheRecordNeverFromThePrereleaseFlag() throws Exception {
        SuiteTestData network = new SuiteTestData();
        network.suite("0.51.02.33", FollowChannel.CORE);
        network.suite("0.51.03.1", FollowChannel.EXPERIMENTAL);
        for (OfficialRepository repository : OfficialRepository.values()) {
            network.product(repository, "0.51.02.33").put("prerelease", true);
            network.product(repository, "0.51.03.1").put("prerelease", false);
        }
        assertEquals("0.51.02.33",
                catalog(network).target(FollowChannel.CORE, OfficialRepository.MEKHQ).version());
        assertEquals("0.51.03.1", catalog(network)
                .target(FollowChannel.EXPERIMENTAL, OfficialRepository.MEKHQ).version());
    }

    @Test
    void retiredSchemaRecordsAreSkippedAndNeverOffered() throws Exception {
        SuiteTestData network = new SuiteTestData();
        retire(network, network.suite("0.51.05", FollowChannel.CORE), "milestone");
        retire(network, network.suite("0.51.06.2", FollowChannel.CORE), "weekly");
        network.suite("0.51.02.33", FollowChannel.CORE);
        assertEquals("0.51.02.33",
                catalog(network).target(FollowChannel.CORE, OfficialRepository.MEKHQ).version());
        assertEquals(Map.of(FollowChannel.CORE, "0.51.02.33"),
                catalog(network).currentPointers(OfficialRepository.MEKHQ).versions());
        assertEquals(1, new SelectedChannelReleaseCatalog(network).page(
                OfficialRepository.MEKHQ, FollowChannel.CORE, 1, 10).entries().size());
    }

    @Test
    void discoversLaterPagesWithoutChoosingGitHubPublicationOrder() throws Exception {
        SuiteTestData network = new SuiteTestData();
        for (int i = 1; i <= 50; i++) network.suite("0.51.02." + i, FollowChannel.EXPERIMENTAL);
        network.suite("0.51.02.100", FollowChannel.EXPERIMENTAL);
        assertEquals(SuiteTestData.recordUri("0.51.02.100").toString(),
                catalog(network).target(FollowChannel.EXPERIMENTAL, OfficialRepository.MEKHQ).source());
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
                () -> catalog(network).target(FollowChannel.CORE, OfficialRepository.MEKHQ));
        assertTrue(error.getMessage().contains("pagination limit"));
        assertEquals(OfficialSuiteChannelCatalog.MAX_PAGES, requests[0]);
    }

    @Test
    void capturedRecordAndHistoricalBrowserDoNotRetargetToNewerSuite() throws Exception {
        SuiteTestData network = new SuiteTestData();
        network.suite("0.51.02.1", FollowChannel.EXPERIMENTAL);
        String captured = catalog(network)
                .target(FollowChannel.EXPERIMENTAL, OfficialRepository.MEKHQ).source();
        network.suite("0.51.02.2", FollowChannel.EXPERIMENTAL);
        network.suite("0.51.01.40", FollowChannel.CORE);
        assertEquals("v0.51.02.1", catalog(network).targetBySource(
                FollowChannel.EXPERIMENTAL, OfficialRepository.MEKHQ, captured).release().tag());
        assertThrows(IOException.class, () -> catalog(network).targetBySource(
                FollowChannel.CORE, OfficialRepository.MEKHQ, captured));
        var first = new SelectedChannelReleaseCatalog(network).page(
                OfficialRepository.MEKHQ, FollowChannel.EXPERIMENTAL, 1, 1);
        var second = new SelectedChannelReleaseCatalog(network).page(
                OfficialRepository.MEKHQ, FollowChannel.EXPERIMENTAL, 2, 1);
        assertTrue(first.mayHaveNextPage());
        assertFalse(second.mayHaveNextPage());
        assertEquals(SelectedChannelReleaseCatalog.Classification.SELECTED_CHANNEL,
                first.entries().getFirst().classification());
        assertEquals(SelectedChannelReleaseCatalog.Classification.CHANNEL_HISTORY,
                second.entries().getFirst().classification());
        assertEquals(captured, second.entries().getFirst().source());
        assertEquals("v0.51.02.1", catalog(network).targetByTag(
                FollowChannel.EXPERIMENTAL, OfficialRepository.MEKHQ, "v0.51.02.1").release().tag());
        assertThrows(IOException.class, () -> catalog(network).targetByTag(
                FollowChannel.CORE, OfficialRepository.MEKHQ, "v0.51.02.1"));
        assertThrows(IOException.class, () -> new SelectedChannelReleaseCatalog(network).page(
                OfficialRepository.MEKHQ, FollowChannel.EXPERIMENTAL, 0, 1));
        assertNoPackages(network);
    }

    @Test
    void incompatibleNewestNeverFallsBackAndDoesNotHideOtherAvailableChannel() throws Exception {
        SuiteTestData network = new SuiteTestData();
        network.suite("0.51.02.1", FollowChannel.EXPERIMENTAL);
        network.suite("0.51.02.2", FollowChannel.CORE);
        network.suite("0.51.03.1", FollowChannel.EXPERIMENTAL)
                .put("minimumLauncherVersion", "0.15.0");
        network.refreshSize("0.51.03.1");
        assertTrue(assertThrows(IOException.class, () -> catalog(network).target(
                FollowChannel.EXPERIMENTAL, OfficialRepository.MEKHQ)).getMessage().contains("0.15.0"));
        var snapshot = catalog(network).quickInstallSnapshot(true);
        assertTrue(snapshot.option(QuickInstallSnapshot.DEFAULT_KEY).available());
        assertFalse(snapshot.option(new QuickInstallOption.Key(
                OfficialRepository.MEKHQ, FollowChannel.EXPERIMENTAL)).available());
    }

    @Test
    void validatesEveryReferencedProductBeforeOfferingAnyProduct() throws Exception {
        List<Consumer<ObjectNode>> mutations = List.of(
                node -> node.put("id", 1),
                node -> node.put("tag_name", "v0.51.02.2"),
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
            network.suite("0.51.02.1", FollowChannel.CORE);
            mutation.accept(network.product(OfficialRepository.MEKHQ, "0.51.02.1"));
            assertThrows(IOException.class,
                    () -> catalog(network).target(FollowChannel.CORE, OfficialRepository.MEGAMEK));
            assertNoPackages(network);
        }
    }

    @Test
    void missingAndDuplicateRecordsAreExplicitUnavailable() throws Exception {
        assertThrows(IOException.class, () -> catalog(new SuiteTestData()).quickInstallSnapshot(false));
        SuiteTestData network = new SuiteTestData();
        network.suite("0.51.02.1", FollowChannel.CORE);
        network.suite("0.51.02.1", FollowChannel.CORE);
        assertThrows(IOException.class,
                () -> catalog(network).target(FollowChannel.CORE, OfficialRepository.MEKHQ));
    }

    @Test
    void aggregateMetadataLimitAcceptsExactly32MiBAndRejectsTheNextRecord() throws Exception {
        SuiteTestData network = new SuiteTestData();
        for (int i = 1; i <= 33; i++) {
            String version = "0.51.02." + i;
            ObjectNode record = network.suite(version, FollowChannel.EXPERIMENTAL);
            byte[] padded = new byte[SuiteReleaseRecord.MAX_BYTES];
            java.util.Arrays.fill(padded, (byte) ' ');
            byte[] json = SuiteTestData.bytes(record);
            System.arraycopy(json, 0, padded, 0, json.length);
            ObjectNode asset = network.recordAsset(version);
            asset.put("size", padded.length);
            network.put(java.net.URI.create(asset.path("browser_download_url").asText()), padded);
            if (i == 32) {
                assertEquals(SuiteTestData.recordUri(version).toString(), catalog(network)
                        .target(FollowChannel.EXPERIMENTAL, OfficialRepository.MEKHQ).source());
                network.requests.clear();
            }
        }
        IOException error = assertThrows(IOException.class,
                () -> catalog(network).target(FollowChannel.EXPERIMENTAL, OfficialRepository.MEKHQ));
        assertTrue(error.getMessage().contains("total metadata limit"));
        assertEquals(32, network.requests.stream().filter(uri -> uri.getPath().endsWith(".json")).count());
        assertNoPackages(network);
    }

    @Test
    void parserAcceptsOnlySchemaTwoWithMatchingProductsAtExactByteBound() throws Exception {
        ObjectNode valid = new SuiteTestData().suite("0.51.02.7", FollowChannel.CORE);
        SuiteReleaseRecord parsed = SuiteReleaseRecord.parse(SuiteTestData.bytes(valid));
        assertEquals(FollowChannel.CORE, parsed.channel());
        assertEquals("v0.51.02.7", parsed.tag());
        for (SuiteReleaseRecord.Product product : parsed.products().values()) {
            assertEquals("0.51.02.7", product.version());
            assertEquals("v0.51.02.7", product.tag());
        }
        assertEquals(FollowChannel.EXPERIMENTAL, SuiteReleaseRecord.parse(SuiteTestData.bytes(
                valid.deepCopy().put("channel", "experimental"))).channel());

        List<Consumer<ObjectNode>> mutations = List.of(
                node -> node.put("schemaVersion", 3),
                node -> node.put("schemaVersion", "2"),
                node -> node.remove("schemaVersion"),
                node -> node.put("unexpected", true),
                node -> node.remove("channel"),
                node -> node.put("channel", "Core"),
                node -> node.put("channel", "milestone"),
                node -> node.put("channel", "weekly"),
                node -> node.put("membership", "core"),
                node -> node.put("version", "0.51.02").put("tag", "v0.51.02"),
                node -> node.put("version", "0.51.2.7").put("tag", "v0.51.2.7"),
                node -> node.put("version", "0.51.02.07").put("tag", "v0.51.02.07"),
                node -> node.put("version", "0.51.02.0").put("tag", "v0.51.02.0"),
                node -> node.put("tag", "v0.51.02.8"),
                node -> ((ObjectNode) node.get("products")).remove("MegaMekLab"),
                node -> ((ObjectNode) node.at("/products/MekHQ")).put("version", "0.51.02.6"),
                node -> ((ObjectNode) node.at("/products/MekHQ")).put("tag", "v0.51.02.6"),
                node -> ((ObjectNode) node.at("/products/MekHQ/asset"))
                        .put("name", "MekHQ-0.51.02.6.tar.gz"),
                node -> ((ObjectNode) node.at("/products/MekHQ")).put("releaseId", 0),
                node -> ((ObjectNode) node.at("/products/MekHQ/asset")).put("size", "1234"),
                node -> ((ObjectNode) node.at("/products/MekHQ/asset")).put("sha256", "A".repeat(64)),
                node -> ((ObjectNode) node.at("/mmData")).put("repository", "https://example.com/data"));
        for (Consumer<ObjectNode> mutation : mutations) {
            ObjectNode invalid = valid.deepCopy();
            mutation.accept(invalid);
            IOException error = assertThrows(IOException.class,
                    () -> SuiteReleaseRecord.parse(SuiteTestData.bytes(invalid)), invalid::toString);
            assertFalse(error instanceof SuiteReleaseRecord.RetiredSchemaException);
        }
        String json = valid.toString();
        assertThrows(IOException.class, () -> SuiteReleaseRecord.parse(
                (json + "{}").getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> SuiteReleaseRecord.parse(
                json.replace("\"schemaVersion\":2", "\"schemaVersion\":2,\"schemaVersion\":2")
                        .getBytes(StandardCharsets.UTF_8)));
        byte[] padded = new byte[SuiteReleaseRecord.MAX_BYTES];
        java.util.Arrays.fill(padded, (byte) ' ');
        byte[] bytes = SuiteTestData.bytes(valid);
        System.arraycopy(bytes, 0, padded, 0, bytes.length);
        assertEquals("0.51.02.7", SuiteReleaseRecord.parse(padded).version());
        assertThrows(IOException.class, () -> SuiteReleaseRecord.parse(new byte[padded.length + 1]));
    }

    @Test
    void schemaOneRecordsAreRejectedAsRetired() {
        ObjectNode retired = new SuiteTestData().suite("0.51.02.7", FollowChannel.CORE);
        retired.put("schemaVersion", 1).remove("channel");
        retired.put("membership", "milestone");
        assertThrows(SuiteReleaseRecord.RetiredSchemaException.class,
                () -> SuiteReleaseRecord.parse(SuiteTestData.bytes(retired)));
    }

    @Test
    void currentIdentityDistinguishesCoreFromExperimentalAndOnlyTwoChannelsParse() {
        var pointers = new OfficialSuiteChannelCatalog.CurrentPointers(Map.of(
                FollowChannel.CORE, "0.51.02.33", FollowChannel.EXPERIMENTAL, "0.51.03.1"));
        var core = VersionIdentity.fromExact("v0.51.02.33").orElseThrow();
        assertEquals(OfficialSuiteChannelCatalog.CurrentIdentity.SELECTED_CURRENT,
                pointers.classify(FollowChannel.CORE, core));
        assertEquals(OfficialSuiteChannelCatalog.CurrentIdentity.OPPOSITE_CURRENT,
                pointers.classify(FollowChannel.EXPERIMENTAL, core));
        assertEquals(FollowChannel.CORE, pointers.opposite(FollowChannel.EXPERIMENTAL, core));
        assertEquals(List.of(FollowChannel.CORE, FollowChannel.EXPERIMENTAL),
                List.of(FollowChannel.values()));
        assertEquals(FollowChannel.CORE, FollowChannel.parse("core"));
        assertEquals(FollowChannel.EXPERIMENTAL, FollowChannel.parse("experimental"));
        for (String retired : List.of("milestone", "development", "weekly", "nightly")) {
            assertThrows(IllegalArgumentException.class, () -> FollowChannel.parse(retired));
        }
        assertEquals(List.of(FollowChannel.CORE), FollowChannel.offered(false));
        assertEquals(List.of(FollowChannel.CORE, FollowChannel.EXPERIMENTAL),
                FollowChannel.offered(true));
    }

    private static void retire(SuiteTestData network, ObjectNode record, String membership) {
        String version = record.path("version").asText();
        record.put("schemaVersion", 1).remove("channel");
        record.put("membership", membership);
        network.refreshSize(version);
    }

    private static OfficialSuiteChannelCatalog catalog(ReleaseTransport network) {
        return new OfficialSuiteChannelCatalog(network, "0.14.5");
    }

    private static void assertNoPackages(SuiteTestData network) {
        assertTrue(network.requests.stream().noneMatch(uri -> uri.getPath().endsWith(".tar.gz")));
    }
}
