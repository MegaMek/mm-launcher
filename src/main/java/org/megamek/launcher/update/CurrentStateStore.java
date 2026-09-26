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

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.megamek.launcher.manifest.ManifestException;
import org.megamek.launcher.manifest.ManifestReader;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryData;
import org.megamek.launcher.sandbox.OverrideEntry;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict atomic storage for latest provenance; absence means the immutable receipt is current. */
final class CurrentStateStore {
    static final int SCHEMA = 1;
    private static final int MAX_BYTES = 32 * 1024 * 1024;
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private final ReceiptStore receipts;
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    CurrentStateStore(ReceiptStore receipts) {
        this.receipts = receipts;
    }

    Path path(Path registry, String id) throws IOException {
        return receipts.metadataDirectory(registry).resolve(requireId(id) + ".current.json");
    }

    CurrentUpdateState read(Path registry, RegistryData data, InstallationRecord record,
                            OwnershipReceipt original) throws IOException {
        Path file = path(registry, record.id());
        Path staging = file.resolveSibling(file.getFileName() + ".new");
        if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("interrupted current-provenance publication requires recovery");
        }
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return CurrentUpdateState.initial(original);
        }
        StrictPathSafety.requireFile(file, "current provenance");
        long size = Files.size(file);
        if (size <= 0 || size > MAX_BYTES) {
            throw new IOException("current provenance size is invalid");
        }
        try {
            CurrentUpdateState state = mapper.readValue(Files.readAllBytes(file),
                    CurrentUpdateState.class);
            validate(state, record, original);
            return state;
        } catch (IOException | RuntimeException | ManifestException e) {
            throw new IOException("current provenance is invalid: " + detail(e), e);
        }
    }

    boolean isPublished(Path registry, String id) throws IOException {
        return Files.exists(path(registry, id), LinkOption.NOFOLLOW_LINKS);
    }

    void write(Path registry, InstallationRecord record, OwnershipReceipt original,
               CurrentUpdateState state) throws IOException {
        try {
            validate(state, record, original);
        } catch (ManifestException e) {
            throw new IOException("refusing invalid current provenance: " + e.getMessage(), e);
        }
        Path directory = receipts.metadataDirectory(registry);
        StrictPathSafety.requireDirectory(directory, "receipt metadata");
        Path file = path(registry, record.id());
        Path staging = file.resolveSibling(file.getFileName() + ".new");
        if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("interrupted current-provenance publication requires recovery");
        }
        byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(state);
        if (bytes.length > MAX_BYTES) throw new IOException("current provenance exceeds size limit");
        try (FileChannel output = FileChannel.open(staging, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            output.write(ByteBuffer.wrap(bytes));
            output.force(true);
        }
        try {
            Files.move(staging, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.deleteIfExists(staging);
            throw new IOException("atomic current-provenance publication is unsupported", e);
        }
    }

    void rollbackPublication(Path registry, String id, boolean previouslyPublished,
                             CurrentUpdateState expectedCurrent) throws IOException {
        Path file = path(registry, id);
        Files.deleteIfExists(file.resolveSibling(file.getFileName() + ".new"));
        if (previouslyPublished) return;
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return;
        CurrentUpdateState current;
        try {
            current = mapper.readValue(Files.readAllBytes(file), CurrentUpdateState.class);
        } catch (IOException e) {
            throw new IOException("refusing to remove unreadable current provenance", e);
        }
        if (!current.equals(expectedCurrent)) {
            throw new IOException("external current-provenance edit blocks recovery");
        }
        Files.delete(file);
    }

    void clearInterruptedStaging(Path registry, String id, CurrentUpdateState previous,
                                 CurrentUpdateState next) throws IOException {
        Path file = path(registry, id);
        Path staging = file.resolveSibling(file.getFileName() + ".new");
        if (!Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) return;
        StrictPathSafety.requireFile(staging, "current provenance staging");
        final CurrentUpdateState candidate;
        try {
            candidate = mapper.readValue(Files.readAllBytes(staging), CurrentUpdateState.class);
        } catch (IOException e) {
            throw new IOException("corrupt current-provenance staging blocks recovery", e);
        }
        if (!candidate.equals(previous) && !candidate.equals(next)) {
            throw new IOException("unexpected current-provenance staging blocks recovery");
        }
        Files.delete(staging);
    }

    void validate(CurrentUpdateState state, InstallationRecord record,
                  OwnershipReceipt original) throws IOException, ManifestException {
        if (state == null || state.schemaVersion() != SCHEMA
                || state.ownershipPolicyVersion() != OwnershipPolicy.VERSION
                || state.installationId() == null || state.canonicalRoot() == null
                || state.registeredAt() == null || state.repository() == null
                || state.tag() == null || state.assetName() == null
                || state.assetSha256() == null || state.officialManifest() == null
                || state.excludedOfficialPaths() == null || state.overrides() == null
                || state.caseOverrides() == null) {
            throw new IOException("unsupported or incomplete current provenance schema");
        }
        if (!state.installationId().equals(original.installationId())
                || !state.canonicalRoot().equals(original.canonicalRoot())
                || !state.registeredAt().equals(original.registeredAt())
                || !state.repository().equals(original.repository())) {
            throw new IOException("current provenance does not preserve original ownership binding");
        }
        receipts.validate(state.asReceipt(), record);
        Set<String> aliases = new HashSet<>();
        for (OverrideEntry override : state.overrides()) {
            if (override == null || override.path() == null || override.kind() == null
                    || override.officialHashes() == null) {
                throw new IOException("invalid override history");
            }
            ManifestReader.validatePortablePath(override.path(), "override path",
                    "current provenance");
            if (!OwnershipPolicy.managed(override.path()) || !aliases.add(key(override.path()))) {
                throw new IOException("override history is outside ownership or duplicated");
            }
            for (String hash : override.officialHashes()) {
                if (hash == null || !HASH.matcher(hash).matches()) {
                    throw new IOException("override history contains an invalid official hash");
                }
            }
        }
        Set<String> caseKeys = new HashSet<>();
        for (CaseOverride item : state.caseOverrides()) {
            if (item == null || item.foldedPrefix() == null || item.localPrefix() == null
                    || item.officialSpellings() == null) {
                throw new IOException("invalid case-prefix override history");
            }
            ManifestReader.validatePortablePath(item.localPrefix(), "case override prefix",
                    "current provenance");
            String folded = key(item.localPrefix());
            // Managed directory roots can themselves be the outermost case-only prefix.
            if (!folded.equals(item.foldedPrefix())
                    || !(OwnershipPolicy.managed(item.localPrefix())
                    || Set.of("data", "docs", "licenses").contains(folded))
                    || !caseKeys.add(folded) || item.officialSpellings().isEmpty()) {
                throw new IOException("invalid case-prefix override binding");
            }
            for (String spelling : item.officialSpellings()) {
                if (spelling == null) throw new IOException("null case override spelling");
                ManifestReader.validatePortablePath(spelling, "case override spelling",
                        "current provenance");
                if (!key(spelling).equals(folded)) {
                    throw new IOException("case override spelling does not match its prefix");
                }
            }
        }
        if (state.lastTransactionId() != null) requireId(state.lastTransactionId());
    }

    private static String requireId(String id) throws IOException {
        try {
            return java.util.UUID.fromString(id).toString();
        } catch (RuntimeException e) {
            throw new IOException("invalid update state UUID", e);
        }
    }

    static String key(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    private static String detail(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
