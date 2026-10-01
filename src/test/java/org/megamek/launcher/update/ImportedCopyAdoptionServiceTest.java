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

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.megamek.launcher.channel.ChannelPreferenceStore;
import org.megamek.launcher.channel.FollowChannel;
import org.megamek.launcher.launch.RootCoordinator;
import org.megamek.launcher.onboarding.InstallationInspector;
import org.megamek.launcher.operation.OperationContext;
import org.megamek.launcher.operation.OperationCancelledException;
import org.megamek.launcher.operation.OperationPhase;
import org.megamek.launcher.operation.OperationProgress;
import org.megamek.launcher.operation.OperationType;
import org.megamek.launcher.operation.ProgressUnit;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryData;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.release.ReleaseTransport;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImportedCopyAdoptionServiceTest {
    private static final byte[] DEPENDENCY = {1, 2, 3, 4};
    @TempDir Path temp;

    @Test
    void snapshotContentIdentityIgnoresDirectorySizeButRequiresFileBytes() {
        var rootIdentity = new RootIdentity("root-key", 1L, 2L);
        var directory = new EntryEvidence(
                "data/boards", false, true, 20_480L, 3L, "directory-key", null);
        var file = new EntryEvidence(
                "data/boards/map.board", true, false, 4L, 4L, "file-key", "file-hash");
        var baseline = new RootSnapshot(
                "official", rootIdentity, List.of(directory, file));

        var differentDirectorySize = new RootSnapshot(
                "fresh", rootIdentity, List.of(
                new EntryEvidence(
                        "data/boards", false, true, 24_576L, 3L,
                        "directory-key", null),
                file));
        assertTrue(ImportedCopyAdoptionService.sameContents(
                baseline, differentDirectorySize));

        var differentFileSize = new RootSnapshot(
                "fresh", rootIdentity, List.of(directory,
                new EntryEvidence(
                        "data/boards/map.board", true, false, 5L, 4L,
                        "file-key", "file-hash")));
        assertFalse(ImportedCopyAdoptionService.sameContents(
                baseline, differentFileSize));

        var differentFileHash = new RootSnapshot(
                "fresh", rootIdentity, List.of(directory,
                new EntryEvidence(
                        "data/boards/map.board", true, false, 4L, 4L,
                        "file-key", "different-hash")));
        assertFalse(ImportedCopyAdoptionService.sameContents(
                baseline, differentFileHash));
    }

    @Test
    void explicitWeeklyAdoptionPublishesImmutableWeeklyPreferenceWithoutChangingFiles() throws Exception {
        byte[] jar = jar(null);
        byte[] archive = archive(jar, "official-data", "official-setting");
        Fixture fixture = fixture(jar, "official-data", "local-setting", transport(archive), point -> {});
        Map<String, Evidence> before = rootEvidence(fixture.root());
        try (PreparedAdoption prepared = fixture.service().prepare(fixture.record(),
                org.megamek.launcher.release.OfficialRepository.MEGAMEK, "v1.2.3",
                FollowChannel.WEEKLY, new PrintStream(new ByteArrayOutputStream()),
                OperationContext.none(OperationType.ADOPT_EXISTING))) {
            assertTrue(prepared.report().eligible());
            assertEquals(FollowChannel.WEEKLY, fixture.service().commit(prepared).channel());
        }
        RegistryData data = fixture.registries().read(fixture.registry());
        var record = fixture.registries().resolve(data, fixture.record().id());
        assertEquals(FollowChannel.WEEKLY, new ChannelPreferenceStore()
                .read(fixture.registry(), data, record).preference().channel());
        assertEquals(before, rootEvidence(fixture.root()));
    }

    @Test
    void pristineImportPublishesExactAncestorWithoutChangingRootOrDownloadingTwice()
            throws Exception {
        byte[] jar = jar(null);
        byte[] archive = archive(jar, "official-data", "official-setting");
        QueueTransport transport = transport(archive);
        Fixture fixture = fixture(jar, "official-data", "local-setting",
                transport, point -> {});
        Map<String, Evidence> before = rootEvidence(fixture.root());

        try (PreparedAdoption prepared = fixture.service().prepare(fixture.record(),
                org.megamek.launcher.release.OfficialRepository.MEGAMEK, "v1.2.3",
                FollowChannel.MILESTONE, new PrintStream(new ByteArrayOutputStream()),
                OperationContext.none(OperationType.ADOPT_EXISTING))) {
            assertTrue(prepared.report().eligible());
            assertEquals("This copy can be managed safely. Existing files will not be changed.",
                    prepared.report().message());
            ImportedCopyAdoptionService.CommitResult result =
                    fixture.service().commit(prepared);
            assertEquals(FollowChannel.MILESTONE, result.channel());
            assertThrows(IOException.class, () -> fixture.service().commit(prepared),
                    "a committed handle is one-use");
        }

        assertEquals(before, rootEvidence(fixture.root()),
                "adoption must preserve every root byte and timestamp");
        RegistryData data = fixture.registries().read(fixture.registry());
        InstallationRecord record = fixture.registries().resolve(data, fixture.record().id());
        OwnershipReceipt receipt = fixture.receipts().read(fixture.registry(), data, record);
        CurrentUpdateState current = new CurrentStateStore(fixture.receipts()).read(
                fixture.registry(), data, record, receipt);
        assertEquals("v1.2.3", receipt.tag());
        assertEquals("v1.2.3", current.tag());
        assertTrue(current.overrides().isEmpty());
        var preference = new ChannelPreferenceStore()
                .read(fixture.registry(), data, record).preference();
        assertEquals(FollowChannel.MILESTONE, preference.channel());
        assertTrue(preference.checkOnOpen());
        assertTrue(new UpdatePreviewService(transport)
                .eligibility(fixture.registry(), record.id()).available());
        assertEquals(ImportedCopyAdoptionService.Availability.MANAGED,
                fixture.service().availability(data, record, false));
        assertEquals(1, transport.packageRequests);
        assertEquals(3, transport.requests.size(),
                "continuous publication performs no additional network request");
        assertFalse(record.updateEligible(),
                "registry membership remains non-authoritative; sidecars gate updates");

        Files.delete(new AdoptionStateStore(fixture.receipts()).path(
                fixture.registry(), record.id()));
        assertFalse(new UpdatePreviewService(transport)
                .eligibility(fixture.registry(), record.id()).available(),
                "partial/corrupt adopted provenance must not authorize updates");
        assertEquals(ImportedCopyAdoptionService.Availability.INCOMPLETE,
                fixture.service().availability(data, record, false));
    }

    @Test
    void preparationReusesOfficialHashesAndPublicationUsesOneMonotonicContext()
            throws Exception {
        byte[] jar = jar(null);
        byte[] archive = archive(jar, "official-data", "official-setting");
        QueueTransport transport = transport(archive);
        Fixture fixture = fixture(jar, "official-data", "local-setting",
                transport, point -> {});
        List<OperationProgress> events = new ArrayList<>();
        OperationContext context = new OperationContext(
                OperationType.ADOPT_EXISTING, events::add);
        int localEntryCount;
        int officialEntryCount;

        try (PreparedAdoption prepared = fixture.service().prepare(fixture.record(),
                org.megamek.launcher.release.OfficialRepository.MEGAMEK, "v1.2.3",
                FollowChannel.MILESTONE, new PrintStream(new ByteArrayOutputStream()),
                context)) {
            assertTrue(prepared.report().eligible());
            localEntryCount = prepared.localSnapshot().entries().size();
            officialEntryCount = prepared.officialSnapshot().entries().size();
            for (var owned : prepared.ownership().manifest().files()) {
                EntryEvidence scanned = prepared.officialSnapshot().entries().stream()
                        .filter(entry -> entry.path().equals(owned.path()))
                        .findFirst().orElseThrow();
                assertSame(scanned.sha256(), owned.sha256(),
                        "ownership must retain the exact SHA-256 computed by the root scan");
            }
            int requestsAfterPreparation = transport.requests.size();
            assertFalse(events.stream().anyMatch(event ->
                            event.phase() == OperationPhase.FINAL),
                    "preparation must not report Finished while publication remains");
            fixture.service().commit(prepared, context,
                    new PrintStream(new ByteArrayOutputStream()));
            assertEquals(requestsAfterPreparation, transport.requests.size(),
                    "publication must use only prepared in-memory official evidence");
        }

        List<OperationPhase> phases = events.stream().map(OperationProgress::phase).toList();
        assertTrue(phases.indexOf(OperationPhase.PREPARE_INSTALL)
                        > phases.lastIndexOf(OperationPhase.PLAN),
                "final revalidation follows prepared-copy comparison");
        assertTrue(phases.indexOf(OperationPhase.APPLY)
                        > phases.indexOf(OperationPhase.PREPARE_INSTALL),
                "metadata publication follows final revalidation");
        for (int index = 1; index < phases.size(); index++) {
            assertTrue(phases.get(index).ordinal() >= phases.get(index - 1).ordinal(),
                    "one operation context must never regress phases");
        }
        assertTrue(events.stream().allMatch(event ->
                        event.operationId().equals(context.id())),
                "preparation and publication report through the same operation context");
        assertScanProgress(events, OperationPhase.METADATA,
                "Checking existing installation", localEntryCount);
        assertScanProgress(events, OperationPhase.PLAN,
                "Checking official package", officialEntryCount);
        List<OperationProgress> finalSnapshotProgress = assertScanProgress(
                events, OperationPhase.PREPARE_INSTALL,
                "Confirming installation has not changed", localEntryCount);
        assertEquals(localEntryCount, finalSnapshotProgress.size(),
                "publication takes exactly one final local root snapshot");
        assertTrue(finalSnapshotProgress.stream().allMatch(
                        OperationProgress::cancellationAllowed),
                "the final local snapshot remains cancellable");
        OperationProgress publication = events.stream()
                .filter(event -> event.phase() == OperationPhase.APPLY)
                .findFirst().orElseThrow();
        assertFalse(publication.cancellationAllowed(),
                "cancellation is blocked only after metadata publication begins");
        assertTrue(publication.detail().contains("Publishing managed-update metadata"));
        assertEquals(1, transport.packageRequests,
                "publication must not download another package");
        assertFalse(context.requestCancellation().accepted(),
                "late cancellation cannot cross the publication barrier");
    }

    private static List<OperationProgress> assertScanProgress(
            List<OperationProgress> events, OperationPhase phase,
            String detailPrefix, int expectedEntries) {
        List<OperationProgress> scan = events.stream()
                .filter(event -> event.phase() == phase
                        && event.unit() == ProgressUnit.FILES
                        && event.cancellationAllowed())
                .toList();
        assertEquals(expectedEntries, scan.size(),
                detailPrefix + " must scan every entry exactly once");
        assertTrue(scan.stream().allMatch(event ->
                        event.detail().startsWith(detailPrefix + " — ")
                                && event.detail().endsWith(" files and folders")),
                detailPrefix + " must include useful entry counts");
        assertTrue(events.stream().anyMatch(event ->
                        event.phase() == phase
                                && event.unit() == ProgressUnit.NONE
                                && event.detail().equals(detailPrefix)),
                detailPrefix + " must be announced in its expected phase");
        return scan;
    }

    @Test
    void publicationUsesImmutablePreparedEvidenceAndCleansWorkspaceExactlyOnce()
            throws Exception {
        byte[] jar = jar(null);
        byte[] archive = archive(jar, "official-data", "official-setting");
        QueueTransport transport = transport(archive);
        Fixture fixture = fixture(jar, "official-data", "local-setting",
                transport, point -> {});
        PreparedAdoption prepared = fixture.service().prepare(fixture.record(),
                org.megamek.launcher.release.OfficialRepository.MEGAMEK, "v1.2.3",
                FollowChannel.MILESTONE, new PrintStream(new ByteArrayOutputStream()),
                OperationContext.none(OperationType.ADOPT_EXISTING));
        Path staging = prepared.workspace().staging();
        Files.write(staging.resolve("package.tar.gz"), new byte[]{9, 8, 7});
        Files.delete(prepared.workspace().extracted().resolve("data/base.txt"));
        int requestsAfterPreparation = transport.requests.size();

        fixture.service().commit(prepared);

        assertEquals(requestsAfterPreparation, transport.requests.size(),
                "publication must not consult remote metadata");
        assertEquals(ImportedCopyAdoptionService.Availability.MANAGED,
                fixture.service().availability(
                        fixture.registries().read(fixture.registry()),
                        fixture.record(), false));
        assertFalse(Files.exists(staging),
                "the consumed prepared workspace must be removed exactly once");
    }

    @Test
    void attemptCleanupRemovesNestedResidualEntriesWithoutTouchingNeighboringFiles()
            throws Exception {
        byte[] jar = jar(null);
        Fixture fixture = fixture(jar, "official-data", "local-setting",
                transport(archive(jar, "official-data", "official-setting")), point -> {});
        Path neighbor = Files.writeString(temp.resolve("unrelated-user-file"), "keep");
        PreparedAdoption prepared = fixture.service().prepare(fixture.record(),
                org.megamek.launcher.release.OfficialRepository.MEGAMEK, "v1.2.3",
                FollowChannel.MILESTONE, new PrintStream(new ByteArrayOutputStream()),
                OperationContext.none(OperationType.ADOPT_EXISTING));
        Path attempt = prepared.workspace().staging().getParent();
        Files.createDirectories(attempt.resolve("residual/nested"));
        Files.writeString(attempt.resolve("residual/nested/owned.txt"), "temporary");

        prepared.close();

        assertFalse(Files.exists(attempt), "remove the entire exact attempt-owned tree");
        assertEquals("keep", Files.readString(neighbor));
        assertNoProvenance(fixture);
    }

    @Test
    void replacedAttemptRootCannotRedirectNestedWorkspaceCleanup() throws Exception {
        byte[] jar = jar(null);
        Fixture fixture = fixture(jar, "official-data", "local-setting",
                transport(archive(jar, "official-data", "official-setting")), point -> {});
        PreparedAdoption prepared = fixture.service().prepare(fixture.record(),
                org.megamek.launcher.release.OfficialRepository.MEGAMEK, "v1.2.3",
                FollowChannel.MILESTONE, new PrintStream(new ByteArrayOutputStream()),
                OperationContext.none(OperationType.ADOPT_EXISTING));
        Path staging = prepared.workspace().staging();
        Path attempt = staging.getParent();
        Path original = attempt.resolveSibling(attempt.getFileName() + ".saved");
        Files.move(attempt, original);
        Path impostorStaging = Files.createDirectories(attempt.resolve(staging.getFileName()));
        Path userFile = Files.writeString(impostorStaging.resolve("user-file"), "keep");

        assertThrows(IOException.class, prepared::close);
        assertEquals("keep", Files.readString(userFile),
                "nested workspace close must not enter a replaced attempt root");
        Files.delete(userFile);
        Files.delete(impostorStaging);
        Files.delete(attempt);
        Files.move(original, attempt);
        prepared.close();
        assertFalse(Files.exists(attempt));
        assertNoProvenance(fixture);
    }

    @Test
    void attemptCleanupRefusesLinksAndNeverTraversesTheOutsideTarget() throws Exception {
        byte[] jar = jar(null);
        Fixture fixture = fixture(jar, "official-data", "local-setting",
                transport(archive(jar, "official-data", "official-setting")), point -> {});
        PreparedAdoption prepared = fixture.service().prepare(fixture.record(),
                org.megamek.launcher.release.OfficialRepository.MEGAMEK, "v1.2.3",
                FollowChannel.MILESTONE, new PrintStream(new ByteArrayOutputStream()),
                OperationContext.none(OperationType.ADOPT_EXISTING));
        Path attempt = prepared.workspace().staging().getParent();
        Path outside = Files.createDirectory(temp.resolve("outside-owned-attempt"));
        Path neighbor = Files.writeString(outside.resolve("do-not-delete"), "keep");
        Path link = attempt.resolve("unexpected-link");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (IOException | UnsupportedOperationException | SecurityException unavailable) {
            prepared.close();
            Assumptions.assumeTrue(false, "symlinks unavailable: " + unavailable);
        }

        assertThrows(IOException.class, prepared::close);
        assertEquals("keep", Files.readString(neighbor));
        assertTrue(Files.exists(link, java.nio.file.LinkOption.NOFOLLOW_LINKS));
        Files.delete(link);
        prepared.close();
        assertFalse(Files.exists(attempt));
        assertEquals("keep", Files.readString(neighbor));
    }

    @Test
    void ineligibleReportSurvivesInjectedCleanupFailureWithDetailsOnlyInDiagnostics()
            throws Exception {
        byte[] jar = jar(null);
        byte[] wrong = jarVersion(9, 9, 9, null);
        String privatePath = temp.resolve(".adoption-attempt-private").toString();
        Fixture fixture = fixture(jar, "official-data", "local-setting",
                transport(archive(wrong, "official-data", "official-setting")),
                point -> {
                    if ("AFTER_ATTEMPT_CLEANUP".equals(point)) {
                        throw new IOException("injected cleanup failure: " + privatePath);
                    }
                });
        ByteArrayOutputStream logs = new ByteArrayOutputStream();
        PreparedAdoption prepared = fixture.service().prepare(fixture.record(),
                org.megamek.launcher.release.OfficialRepository.MEGAMEK, "v1.2.3",
                FollowChannel.MILESTONE, new PrintStream(logs),
                OperationContext.none(OperationType.ADOPT_EXISTING));
        Path attempt = prepared.workspace().staging().getParent();

        PreparedAdoption.Report result = prepared.discardIneligible(new PrintStream(logs));

        assertFalse(result.eligible());
        assertFalse(result.message().contains(privatePath));
        assertTrue(logs.toString().contains("temporary cleanup warning"));
        assertTrue(logs.toString().contains(privatePath));
        assertFalse(Files.exists(attempt));
        assertNoProvenance(fixture);
    }

    @Test
    void preparationFailureKeepsItsTypeWhenAttemptCleanupAlsoFails() throws Exception {
        byte[] jar = jar(null);
        String privatePath = temp.resolve(".adoption-attempt-private").toString();
        Fixture fixture = fixture(jar, "official-data", "local-setting",
                new QueueTransport(), point -> {
                    if ("AFTER_ATTEMPT_CLEANUP".equals(point)) {
                        throw new IOException("injected cleanup failure: " + privatePath);
                    }
                });
        ByteArrayOutputStream logs = new ByteArrayOutputStream();

        ImportedCopyAdoptionService.CandidateResolutionException failure = assertThrows(
                ImportedCopyAdoptionService.CandidateResolutionException.class,
                () -> fixture.service().prepare(fixture.record(),
                        org.megamek.launcher.release.OfficialRepository.MEGAMEK, "v1.2.3",
                        FollowChannel.MILESTONE, new PrintStream(logs),
                        OperationContext.none(OperationType.ADOPT_EXISTING)));

        assertEquals(ImportedCopyAdoptionService.AUTOMATIC_MATCH_UNAVAILABLE,
                failure.getMessage());
        assertFalse(failure.getMessage().contains(privatePath));
        assertEquals(1, failure.getSuppressed().length);
        assertTrue(logs.toString().contains("temporary cleanup warning"));
        try (var children = Files.list(temp)) {
            assertFalse(children.anyMatch(path ->
                    path.getFileName().toString().startsWith(".adoption-attempt-")));
        }
        assertNoProvenance(fixture);
    }

    @Test
    void automaticResolutionUsesListBeforeExactMetadataAndDownloadsOnePackage()
            throws Exception {
        byte[] jar = jarVersion(0, 50, 7, null);
        byte[] archive = archive(jar, "official-data", "official-setting");
        String metadata = releaseJson(archive, "v0.50.07", "0.50.07");
        QueueTransport transport = new QueueTransport(
                () -> org.megamek.launcher.channel.SuiteTestData.channels("0.51.0", "0.52.0"),
                response("[" + metadata + "]"), response(metadata),
                response(metadata), binary(archive));
        Fixture fixture = fixture(jar, "official-data", "local-setting",
                transport, point -> {});

        try (PreparedAdoption prepared = fixture.service().prepareAutomatically(
                fixture.record(),
                org.megamek.launcher.release.OfficialRepository.MEGAMEK,
                FollowChannel.DEVELOPMENT, new PrintStream(new ByteArrayOutputStream()),
                OperationContext.none(OperationType.ADOPT_EXISTING))) {
            assertTrue(prepared.report().eligible());
            assertEquals("v0.50.07", prepared.release().tag());
            assertEquals(FollowChannel.DEVELOPMENT, prepared.report().fixedChannel(),
                    "historical unknown identity keeps the selected fixed channel");
        }

        assertEquals(1, transport.packageRequests);
        assertEquals(13, transport.requests.size());
        assertEquals(org.megamek.launcher.channel.OfficialSuiteChannelCatalog.SOURCE
                        .resolve("releases?per_page=50&page=1"),
                transport.requests.get(0));
        assertTrue(transport.requests.get(9).toString().contains(
                "/releases?per_page=50&page=1"));
        assertTrue(transport.requests.get(10).toString().endsWith(
                "/releases/tags/v0.50.07"));
        assertTrue(transport.requests.get(11).toString().endsWith(
                "/releases/tags/v0.50.07"));
        assertFalse(transport.requests.get(9).toString().contains("/tags/"),
                "automatic matching must not probe a guessed exact tag");
    }

    @Test
    void modifiedDataProtectedAndUnknownFilesArePreservedAndSeeded()
            throws Exception {
        byte[] jar = jar(null);
        byte[] archive = archive(jar, "official-data", "official-setting");
        QueueTransport transport = transport(archive);
        Fixture fixture = fixture(jar, "locally-modified", "my-setting", transport, point -> {});
        Files.writeString(fixture.root().resolve("notes.custom"), "keep");
        Map<String, Evidence> before = rootEvidence(fixture.root());

        PreparedAdoption prepared = fixture.service().prepare(fixture.record(),
                org.megamek.launcher.release.OfficialRepository.MEGAMEK, "v1.2.3",
                FollowChannel.DEVELOPMENT, new PrintStream(new ByteArrayOutputStream()),
                OperationContext.none(OperationType.ADOPT_EXISTING));
        assertTrue(prepared.report().eligible());
        fixture.service().commit(prepared);

        RegistryData data = fixture.registries().read(fixture.registry());
        OwnershipReceipt receipt = fixture.receipts().read(
                fixture.registry(), data, fixture.record());
        CurrentUpdateState current = new CurrentStateStore(fixture.receipts()).read(
                fixture.registry(), data, fixture.record(), receipt);
        assertEquals(List.of("data/base.txt"),
                current.overrides().stream().map(
                        org.megamek.launcher.sandbox.OverrideEntry::path).toList());
        assertEquals(before, rootEvidence(fixture.root()));
        assertEquals("my-setting", Files.readString(
                fixture.root().resolve("mmconf/user.cfg")));
        assertEquals("keep", Files.readString(fixture.root().resolve("notes.custom")));
    }

    @Test
    void modifiedRuntimeFilesRemainLaunchOnly() throws Exception {
        byte[] officialJar = jar(null);
        byte[] archive = archive(officialJar, "official-data", "official-setting");

        QueueTransport mixedTransport = transport(archive);
        Fixture mixed = fixture(jar("local-extra"), "official-data", "local-setting",
                mixedTransport, point -> {});
        try (PreparedAdoption prepared = mixed.service().prepare(mixed.record(),
                org.megamek.launcher.release.OfficialRepository.MEGAMEK, "v1.2.3",
                FollowChannel.MILESTONE, new PrintStream(new ByteArrayOutputStream()),
                OperationContext.none(OperationType.ADOPT_EXISTING))) {
            assertFalse(prepared.report().eligible());
            assertEquals("Core application files differ from the official release.",
                    prepared.report().message());
            assertThrows(IOException.class, () -> mixed.service().commit(prepared));
        }
        assertNoProvenance(mixed);

        QueueTransport dependencyTransport = transport(archive);
        Fixture dependency = fixture(officialJar, "official-data", "local-setting",
                dependencyTransport, point -> {});
        Files.write(dependency.root().resolve("lib/library.jar"), new byte[]{9, 8, 7});
        try (PreparedAdoption prepared = dependency.service().prepare(dependency.record(),
                org.megamek.launcher.release.OfficialRepository.MEGAMEK, "v1.2.3",
                FollowChannel.MILESTONE, new PrintStream(new ByteArrayOutputStream()),
                OperationContext.none(OperationType.ADOPT_EXISTING))) {
            assertFalse(prepared.report().eligible(),
                    "a mixed dependency JAR must block adoption");
        }
        assertNoProvenance(dependency);
    }

    @Test
    void caseOnlyRuntimePathAndStructuralManagedConflictRemainIneligible() throws Exception {
        byte[] jar = jar(null);
        byte[] official = archive(jar, "official-data", "setting",
                Map.of("lib/runtime.txt", "runtime"));
        Fixture runtime = fixture(jar, "official-data", "setting",
                transport(official), point -> {});
        Files.writeString(runtime.root().resolve("lib/Runtime.txt"), "runtime");
        try (PreparedAdoption prepared = runtime.service().prepare(runtime.record(),
                org.megamek.launcher.release.OfficialRepository.MEGAMEK, "v1.2.3",
                FollowChannel.MILESTONE, new PrintStream(new ByteArrayOutputStream()),
                OperationContext.none(OperationType.ADOPT_EXISTING))) {
            assertFalse(prepared.report().eligible());
            assertTrue(prepared.comparison().conflicts().contains("lib/runtime.txt"));
        }
        assertNoProvenance(runtime);

        Fixture structural = fixture(jar, "official-data", "setting",
                transport(archive(jar, "official-data", "setting")), point -> {});
        Files.delete(structural.root().resolve("data/base.txt"));
        Files.createDirectory(structural.root().resolve("data/base.txt"));
        Files.writeString(structural.root().resolve("data/base.txt/unknown.txt"), "unknown");
        try (PreparedAdoption prepared = structural.service().prepare(structural.record(),
                org.megamek.launcher.release.OfficialRepository.MEGAMEK, "v1.2.3",
                FollowChannel.MILESTONE, new PrintStream(new ByteArrayOutputStream()),
                OperationContext.none(OperationType.ADOPT_EXISTING))) {
            assertFalse(prepared.report().eligible());
            assertTrue(prepared.comparison().conflicts().contains("data/base.txt"));
        }
        assertNoProvenance(structural);

        Fixture parent = fixture(jar, "official-data", "setting",
                transport(archive(jar, "official-data", "setting",
                        Map.of("docs/manual.txt", "manual"))), point -> {});
        Files.writeString(parent.root().resolve("docs"), "local-file-parent");
        try (PreparedAdoption prepared = parent.service().prepare(parent.record(),
                org.megamek.launcher.release.OfficialRepository.MEGAMEK, "v1.2.3",
                FollowChannel.MILESTONE, new PrintStream(new ByteArrayOutputStream()),
                OperationContext.none(OperationType.ADOPT_EXISTING))) {
            assertFalse(prepared.report().eligible());
            assertTrue(prepared.comparison().conflicts().contains("docs/manual.txt"));
        }
        assertNoProvenance(parent);
    }

    @Test
    void caseOnlyManagedFileRetainsContentOverrideAndExcludedScriptIsUnknown()
            throws Exception {
        byte[] jar = jar(null);
        byte[] archive = archive(jar, "official-data", "setting",
                Map.of("bin/MegaMek.bat", "official-script"));
        Fixture fixture = fixture(jar, "official-data", "setting",
                transport(archive), point -> {});
        Files.delete(fixture.root().resolve("data/base.txt"));
        Files.writeString(fixture.root().resolve("data/Base.txt"), "user-data");
        Files.createDirectories(fixture.root().resolve("bin"));
        Files.writeString(fixture.root().resolve("bin/MegaMek.bat"), "user-script");
        Map<String, Evidence> before = rootEvidence(fixture.root());

        try (PreparedAdoption prepared = fixture.service().prepare(fixture.record(),
                org.megamek.launcher.release.OfficialRepository.MEGAMEK, "v1.2.3",
                FollowChannel.MILESTONE, new PrintStream(new ByteArrayOutputStream()),
                OperationContext.none(OperationType.ADOPT_EXISTING))) {
            assertTrue(prepared.report().eligible());
            assertEquals(List.of(new CaseOverride("data/base.txt", "data/Base.txt",
                    List.of("data/Base.txt", "data/base.txt"))),
                    prepared.comparison().caseOverrides());
            assertTrue(prepared.comparison().unknown().contains("bin/MegaMek.bat"));
            fixture.service().commit(prepared);
        }
        RegistryData data = fixture.registries().read(fixture.registry());
        OwnershipReceipt receipt = fixture.receipts().read(
                fixture.registry(), data, fixture.record());
        CurrentUpdateState current = new CurrentStateStore(fixture.receipts()).read(
                fixture.registry(), data, fixture.record(), receipt);
        assertEquals(List.of(new CaseOverride("data/base.txt", "data/Base.txt",
                List.of("data/Base.txt", "data/base.txt"))), current.caseOverrides());
        assertEquals(List.of(new org.megamek.launcher.sandbox.OverrideEntry(
                "data/base.txt", org.megamek.launcher.sandbox.OverrideEntry.Kind.MODIFIED,
                List.of(receipt.officialManifest().files().stream()
                        .filter(file -> file.path().equals("data/base.txt"))
                        .findFirst().orElseThrow().sha256()))), current.overrides());
        assertTrue(current.excludedOfficialPaths().contains("bin/MegaMek.bat"));
        assertEquals(before, rootEvidence(fixture.root()));
    }

    @Test
    void outermostManagedDirectoryCaseIsPublishedWithoutFileOverrides()
            throws Exception {
        byte[] jar = jar(null);
        Fixture fixture = fixture(jar, "official-data", "setting",
                transport(archive(jar, "official-data", "setting",
                        Map.of("docs/Guide.txt", "guide"))), point -> {});
        Files.createDirectories(fixture.root().resolve("Docs"));
        Files.writeString(fixture.root().resolve("Docs/Guide.txt"), "guide");
        try (PreparedAdoption prepared = fixture.service().prepare(fixture.record(),
                org.megamek.launcher.release.OfficialRepository.MEGAMEK, "v1.2.3",
                FollowChannel.MILESTONE, new PrintStream(new ByteArrayOutputStream()),
                OperationContext.none(OperationType.ADOPT_EXISTING))) {
            assertTrue(prepared.report().eligible());
            assertEquals(List.of(new CaseOverride("docs", "Docs",
                    List.of("Docs", "docs"))), prepared.comparison().caseOverrides());
            fixture.service().commit(prepared);
        }
        RegistryData data = fixture.registries().read(fixture.registry());
        OwnershipReceipt receipt = fixture.receipts().read(
                fixture.registry(), data, fixture.record());
        CurrentUpdateState current = new CurrentStateStore(fixture.receipts()).read(
                fixture.registry(), data, fixture.record(), receipt);
        assertEquals(List.of(new CaseOverride("docs", "Docs",
                List.of("Docs", "docs"))), current.caseOverrides());
        assertTrue(current.overrides().isEmpty());
    }

    @Test
    void missingManagedFilesDoNotBlockAdoptionAndAreRestoredByTheNextUpdate()
            throws Exception {
        byte[] officialJar = jar(null);
        byte[] archive = archive(officialJar, "official-data", "official-setting");

        // A missing critical runtime dependency carries no unknown content to trust; the
        // ordinary update planner already restores any official path that isn't present
        // locally, so it is no riskier here than it is for any later managed update.
        QueueTransport criticalMissingTransport = transport(archive);
        Fixture criticalMissing = fixture(officialJar, "official-data", "local-setting",
                criticalMissingTransport, point -> {});
        Files.delete(criticalMissing.root().resolve("lib/library.jar"));
        try (PreparedAdoption prepared = criticalMissing.service().prepare(
                criticalMissing.record(),
                org.megamek.launcher.release.OfficialRepository.MEGAMEK, "v1.2.3",
                FollowChannel.MILESTONE, new PrintStream(new ByteArrayOutputStream()),
                OperationContext.none(OperationType.ADOPT_EXISTING))) {
            assertTrue(prepared.report().eligible(),
                    "a missing dependency no longer blocks adoption");
            assertEquals("This copy can be managed safely. 1 missing file(s) will be "
                    + "restored by the next update.", prepared.report().message());
            criticalMissing.service().commit(prepared);
        }

        QueueTransport missingTransport = transport(archive);
        Fixture missing = fixture(officialJar, "official-data", "local-setting",
                missingTransport, point -> {});
        Files.delete(missing.root().resolve("data/base.txt"));
        try (PreparedAdoption prepared = missing.service().prepare(missing.record(),
                org.megamek.launcher.release.OfficialRepository.MEGAMEK, "v1.2.3",
                FollowChannel.MILESTONE, new PrintStream(new ByteArrayOutputStream()),
                OperationContext.none(OperationType.ADOPT_EXISTING))) {
            assertTrue(prepared.report().eligible(),
                    "an intentionally removed managed file no longer blocks adoption");
            missing.service().commit(prepared);
        }
        RegistryData missingData = missing.registries().read(missing.registry());
        assertEquals(ImportedCopyAdoptionService.Availability.MANAGED,
                missing.service().availability(missingData, missing.record(), false));
    }

    @Test
    void driftCrossFacadeAndInjectedPublicationFailureCannotAuthorizeUpdates()
            throws Exception {
        byte[] jar = jar(null);
        byte[] archive = archive(jar, "official-data", "official-setting");

        QueueTransport driftTransport = transport(archive);
        Fixture drift = fixture(jar, "official-data", "local-setting",
                driftTransport, point -> {});
        PreparedAdoption changed = drift.service().prepare(drift.record(),
                org.megamek.launcher.release.OfficialRepository.MEGAMEK, "v1.2.3",
                FollowChannel.MILESTONE, new PrintStream(new ByteArrayOutputStream()),
                OperationContext.none(OperationType.ADOPT_EXISTING));
        Files.writeString(drift.root().resolve("custom-after-verify.txt"), "changed");
        assertThrows(IOException.class, () -> drift.service().commit(changed));
        assertNoProvenance(drift);

        QueueTransport ownerTransport = transport(archive);
        Fixture owner = fixture(jar, "official-data", "local-setting",
                ownerTransport, point -> {});
        PreparedAdoption owned = owner.service().prepare(owner.record(),
                org.megamek.launcher.release.OfficialRepository.MEGAMEK, "v1.2.3",
                FollowChannel.MILESTONE, new PrintStream(new ByteArrayOutputStream()),
                OperationContext.none(OperationType.ADOPT_EXISTING));
        assertThrows(IOException.class, () -> owner.service().prepare(owner.record(),
                org.megamek.launcher.release.OfficialRepository.MEGAMEK, "v1.2.3",
                FollowChannel.MILESTONE, new PrintStream(new ByteArrayOutputStream()),
                OperationContext.none(OperationType.ADOPT_EXISTING)),
                "one service must not prepare two attempts for the same record");
        ImportedCopyAdoptionService other = new ImportedCopyAdoptionService(owner.registry(),
                ownerTransport, new RootCoordinator(temp.resolve("other-coordination")));
        assertThrows(IOException.class, () -> other.commit(owned));
        owned.close();
        assertNoProvenance(owner);

        for (String failurePoint : List.of(
                "AFTER_RECEIPT", "AFTER_CURRENT", "AFTER_MARKER")) {
            QueueTransport failedTransport = transport(archive);
            Fixture failed = fixture(jar, "official-data", "local-setting",
                    failedTransport, point -> {
                        if (failurePoint.equals(point)) throw new IOException("injected");
                    });
            PreparedAdoption publication = failed.service().prepare(failed.record(),
                    org.megamek.launcher.release.OfficialRepository.MEGAMEK, "v1.2.3",
                    FollowChannel.MILESTONE, new PrintStream(new ByteArrayOutputStream()),
                    OperationContext.none(OperationType.ADOPT_EXISTING));
            assertThrows(IOException.class, () -> failed.service().commit(publication),
                    failurePoint);
            assertNoProvenance(failed);
        }
    }

    @Test
    void digestFailureCleansOwnedWorkspaceAndWritesNoMetadataOrRootFiles()
            throws Exception {
        byte[] jar = jar(null);
        byte[] archive = archive(jar, "official-data", "official-setting");
        String badMetadata = releaseJson(archive, "0".repeat(64));
        QueueTransport transport = new QueueTransport(response(badMetadata),
                response(badMetadata), binary(archive));
        Fixture fixture = fixture(jar, "official-data", "local-setting",
                transport, point -> {});
        Map<String, Evidence> before = rootEvidence(fixture.root());

        assertThrows(IOException.class, () -> fixture.service().prepare(fixture.record(),
                org.megamek.launcher.release.OfficialRepository.MEGAMEK, "v1.2.3",
                FollowChannel.MILESTONE, new PrintStream(new ByteArrayOutputStream()),
                OperationContext.none(OperationType.ADOPT_EXISTING)));

        assertEquals(before, rootEvidence(fixture.root()));
        assertNoProvenance(fixture);
        try (var children = Files.list(temp)) {
            assertFalse(children.anyMatch(path ->
                    path.getFileName().toString().startsWith(".adoption-")));
        }
    }

    @Test
    void pendingRecoveryAndCancellationBlockBeforeNetwork() throws Exception {
        byte[] jar = jar(null);
        QueueTransport pendingTransport = new QueueTransport();
        Fixture pending = fixture(jar, "official-data", "local-setting",
                pendingTransport, point -> {});
        Files.createDirectory(pending.root().resolve(RootCoordinator.UPDATE_NAMESPACE));
        assertThrows(IOException.class, () -> pending.service().prepare(pending.record(),
                org.megamek.launcher.release.OfficialRepository.MEGAMEK, "v1.2.3",
                FollowChannel.MILESTONE, new PrintStream(new ByteArrayOutputStream()),
                OperationContext.none(OperationType.ADOPT_EXISTING)));
        assertTrue(pendingTransport.requests.isEmpty());

        QueueTransport cancelledTransport = new QueueTransport();
        Fixture cancelled = fixture(jar, "official-data", "local-setting",
                cancelledTransport, point -> {});
        OperationContext context = new OperationContext(OperationType.ADOPT_EXISTING);
        assertTrue(context.requestCancellation().accepted());
        assertThrows(OperationCancelledException.class,
                () -> cancelled.service().prepare(cancelled.record(),
                        org.megamek.launcher.release.OfficialRepository.MEGAMEK, "v1.2.3",
                        FollowChannel.MILESTONE, new PrintStream(new ByteArrayOutputStream()),
                        context));
        assertTrue(cancelledTransport.requests.isEmpty());
        assertNoProvenance(cancelled);
    }

    @Test
    void cancellationAfterPreparationConsumesHandleAndCleansRetainedWorkspace()
            throws Exception {
        byte[] jar = jar(null);
        byte[] archive = archive(jar, "official-data", "official-setting");
        QueueTransport transport = transport(archive);
        Fixture fixture = fixture(jar, "official-data", "local-setting",
                transport, point -> {});
        OperationContext context = new OperationContext(OperationType.ADOPT_EXISTING);
        PreparedAdoption prepared = fixture.service().prepare(fixture.record(),
                org.megamek.launcher.release.OfficialRepository.MEGAMEK, "v1.2.3",
                FollowChannel.MILESTONE, new PrintStream(new ByteArrayOutputStream()),
                context);

        assertTrue(context.requestCancellation().accepted());
        assertThrows(OperationCancelledException.class,
                () -> fixture.service().commit(prepared, context,
                        new PrintStream(new ByteArrayOutputStream())));
        assertThrows(IOException.class,
                () -> fixture.service().commit(prepared,
                        OperationContext.none(OperationType.ADOPT_EXISTING),
                        new PrintStream(new ByteArrayOutputStream())),
                "a cancelled commit still consumes its one-use prepared handle");
        assertNoProvenance(fixture);
        try (var children = Files.list(temp)) {
            assertFalse(children.anyMatch(path ->
                            path.getFileName().toString().startsWith(".adoption-attempt-")),
                    "cancellation must remove the retained adoption workspace");
        }
        assertEquals(1, transport.packageRequests);
    }

    @Test
    void officialPackageWithDifferentDetectedVersionIsIneligible() throws Exception {
        byte[] localJar = jar(null);
        byte[] wrongJar = jarVersion(9, 9, 9, null);
        byte[] archive = archive(wrongJar, "official-data", "official-setting");
        QueueTransport transport = transport(archive);
        Fixture fixture = fixture(localJar, "official-data", "local-setting",
                transport, point -> {});
        try (PreparedAdoption prepared = fixture.service().prepare(fixture.record(),
                org.megamek.launcher.release.OfficialRepository.MEGAMEK, "v1.2.3",
                FollowChannel.MILESTONE, new PrintStream(new ByteArrayOutputStream()),
                OperationContext.none(OperationType.ADOPT_EXISTING))) {
            assertFalse(prepared.report().eligible());
        }
        assertNoProvenance(fixture);
    }

    private Fixture fixture(byte[] localJar, String localData, String setting,
                            QueueTransport transport,
                            ImportedCopyAdoptionService.FailureHook failureHook)
            throws Exception {
        Path root = Files.createDirectory(temp.resolve("copy-" + System.nanoTime()));
        Files.createDirectories(root.resolve("data"));
        Files.createDirectories(root.resolve("mmconf"));
        Files.createDirectories(root.resolve("lib"));
        Files.write(root.resolve("MegaMek.jar"), localJar);
        Files.write(root.resolve("lib/library.jar"), DEPENDENCY);
        Files.writeString(root.resolve("data/base.txt"), localData);
        Files.writeString(root.resolve("mmconf/user.cfg"), setting);
        Path registry = temp.resolve("registry-" + System.nanoTime() + ".json");
        RegistryStore registries = new RegistryStore();
        InstallationRecord record = registries.register(registry, "Imported", root, null);
        ReceiptStore receipts = new ReceiptStore();
        ImportedCopyAdoptionService service = new ImportedCopyAdoptionService(
                registry, transport, registries, receipts, new ChannelPreferenceStore(),
                new InstallationInspector(),
                new RootCoordinator(temp.resolve("coord-" + System.nanoTime())), failureHook);
        return new Fixture(root, registry, record, registries, receipts, service);
    }

    private static QueueTransport transport(byte[] archive) throws Exception {
        String metadata = releaseJson(archive);
        return new QueueTransport(response(metadata), response(metadata),
                binary(archive));
    }

    private static void assertNoProvenance(Fixture fixture) throws Exception {
        RegistryData data = fixture.registries().read(fixture.registry());
        assertEquals(ImportedCopyAdoptionService.Availability.TRUE_IMPORTED,
                fixture.service().availability(data, fixture.record(), false));
        assertFalse(Files.exists(fixture.receipts().receiptPath(
                fixture.registry(), fixture.record().id())));
        assertFalse(Files.exists(new ChannelPreferenceStore().path(
                fixture.registry(), fixture.record().id())));
    }

    private static Map<String, Evidence> rootEvidence(Path root) throws Exception {
        Map<String, Evidence> result = new LinkedHashMap<>();
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted().toList()) {
                String relative = root.relativize(path).toString().replace('\\', '/');
                result.put(relative, new Evidence(Files.isDirectory(path),
                        Files.getLastModifiedTime(path).toMillis(),
                        Files.isRegularFile(path)
                                ? HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                                .digest(Files.readAllBytes(path))) : null));
            }
        }
        return result;
    }

    private static byte[] jar(String extra) throws IOException {
        return jarVersion(1, 2, 3, extra);
    }

    private static byte[] jarVersion(int major, int minor, int patch, String extra)
            throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "megamek.MegaMek");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream output = new JarOutputStream(bytes, manifest)) {
            output.putNextEntry(new JarEntry("megamek/Version.properties"));
            output.write(("major=" + major + "\nminor=" + minor + "\npatch=" + patch + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
            if (extra != null) {
                output.putNextEntry(new JarEntry("local.txt"));
                output.write(extra.getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static byte[] archive(byte[] jar, String data, String setting) throws IOException {
        return archive(jar, data, setting, Map.of());
    }

    private static byte[] archive(byte[] jar, String data, String setting,
                                  Map<String, String> extras) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(bytes);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            add(tar, "MegaMek-1.2.3/", null);
            add(tar, "MegaMek-1.2.3/data/", null);
            add(tar, "MegaMek-1.2.3/mmconf/", null);
            add(tar, "MegaMek-1.2.3/lib/", null);
            add(tar, "MegaMek-1.2.3/MegaMek.jar", jar);
            add(tar, "MegaMek-1.2.3/lib/library.jar", DEPENDENCY);
            add(tar, "MegaMek-1.2.3/data/base.txt",
                    data.getBytes(StandardCharsets.UTF_8));
            add(tar, "MegaMek-1.2.3/mmconf/user.cfg",
                    setting.getBytes(StandardCharsets.UTF_8));
            for (var entry : extras.entrySet()) {
                add(tar, "MegaMek-1.2.3/" + entry.getKey(),
                        entry.getValue().getBytes(StandardCharsets.UTF_8));
            }
        }
        return bytes.toByteArray();
    }

    private static void add(TarArchiveOutputStream tar, String name, byte[] content)
            throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(content == null ? 0 : content.length);
        if (content == null) entry.setMode(0755);
        tar.putArchiveEntry(entry);
        if (content != null) tar.write(content);
        tar.closeArchiveEntry();
    }

    private static String releaseJson(byte[] archive) throws Exception {
        String digest = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(archive));
        return releaseJson(archive, digest);
    }

    private static String releaseJson(byte[] archive, String digest) {
        return releaseJson(archive, "v1.2.3", "1.2.3", digest);
    }

    private static String releaseJson(byte[] archive, String tag, String version)
            throws Exception {
        String digest = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(archive));
        return releaseJson(archive, tag, version, digest);
    }

    private static String releaseJson(byte[] archive, String tag, String version,
                                      String digest) {
        return """
                {"tag_name":"%s","name":"Version %s","draft":false,"prerelease":false,
                "html_url":"https://github.com/MegaMek/megamek/releases/tag/%s",
                "assets":[{"name":"MegaMek-%s.tar.gz","size":%d,
                "digest":"sha256:%s",
                "browser_download_url":"https://github.com/MegaMek/megamek/releases/download/%s/MegaMek-%s.tar.gz"}]}
                """.formatted(tag, version, tag, version, archive.length, digest, tag, version);
    }

    private static Supplier<ReleaseTransport.Response> response(String body) {
        return () -> new ReleaseTransport.Response(200, Map.of(),
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static Supplier<ReleaseTransport.Response> binary(byte[] body) {
        return () -> new ReleaseTransport.Response(200, Map.of(),
                new ByteArrayInputStream(body));
    }

    private static final class QueueTransport implements ReleaseTransport {
        private final ArrayDeque<Supplier<Response>> responses;
        private final List<URI> requests = new ArrayList<>();
        private int packageRequests;
        private final org.megamek.launcher.channel.SuiteTestData.MetadataRouter metadata =
                new org.megamek.launcher.channel.SuiteTestData.MetadataRouter();

        @SafeVarargs
        private QueueTransport(Supplier<Response>... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public Response get(URI uri, String accept) throws IOException {
            requests.add(uri);
            Response generated = metadata.response(uri, accept, responses.peekFirst());
            if (generated != null) return generated;
            if (uri.getPath().endsWith(".tar.gz")) packageRequests++;
            Supplier<Response> response = responses.poll();
            if (response == null) throw new IOException("unexpected request: " + uri);
            return response.get();
        }
    }

    private record Fixture(Path root, Path registry, InstallationRecord record,
                           RegistryStore registries, ReceiptStore receipts,
                           ImportedCopyAdoptionService service) {
    }

    private record Evidence(boolean directory, long modifiedMillis, String sha256) {
    }
}
