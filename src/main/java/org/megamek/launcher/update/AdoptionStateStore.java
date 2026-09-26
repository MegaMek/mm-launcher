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
import org.megamek.launcher.channel.ChannelPreference;
import org.megamek.launcher.registry.InstallationRecord;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Final commit marker for imported-copy adoption. The fixed-channel sidecar is still published
 * last, but this marker binds the preceding receipt/current-state writes into one adoption.
 */
final class AdoptionStateStore {
    private static final int SCHEMA = 1;
    private static final int MAX_BYTES = 16 * 1024;
    private final ReceiptStore receipts;
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    AdoptionStateStore(ReceiptStore receipts) {
        this.receipts = receipts;
    }

    Path path(Path registry, String id) throws IOException {
        // receiptPath performs the strict UUID check.
        receipts.receiptPath(registry, id);
        return receipts.metadataDirectory(registry).resolve(id + ".adoption.json");
    }

    Marker write(Path registry, InstallationRecord record, OwnershipReceipt receipt,
                 CurrentUpdateState current, ChannelPreference channel, String attemptId)
            throws IOException {
        Marker marker = new Marker(SCHEMA, attemptId, record.id(), record.canonicalRoot(),
                record.registeredAt(), receipt.repository(), receipt.tag(), receipt.assetName(),
                receipt.assetSize(), receipt.assetSha256(), channel.channel().name());
        validate(marker, record, receipt, current, channel);
        Path file = path(registry, record.id());
        Path staging = file.resolveSibling(file.getFileName() + ".new");
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                || Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("adoption publication state already exists");
        }
        byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(marker);
        if (bytes.length <= 0 || bytes.length > MAX_BYTES) {
            throw new IOException("adoption publication state exceeds size limit");
        }
        try {
            try (FileChannel output = FileChannel.open(staging, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                output.write(ByteBuffer.wrap(bytes));
                output.force(true);
            }
            Files.move(staging, file, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException error) {
            Files.deleteIfExists(staging);
            throw new IOException("atomic adoption publication is unsupported", error);
        } catch (IOException error) {
            Files.deleteIfExists(staging);
            throw error;
        }
        return marker;
    }

    void validateForUse(Path registry, InstallationRecord record, OwnershipReceipt receipt,
                        CurrentUpdateState current, ChannelPreference channel)
            throws IOException {
        Path file = path(registry, record.id());
        Path staging = file.resolveSibling(file.getFileName() + ".new");
        if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("interrupted adoption publication requires review");
        }
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            if (isAdoptionTransaction(current.lastTransactionId())) {
                throw new IOException("adoption publication is incomplete");
            }
            return; // Ordinary fresh installs predate and do not need an adoption marker.
        }
        StrictPathSafety.requireFile(file, "adoption publication");
        if (Files.size(file) <= 0 || Files.size(file) > MAX_BYTES) {
            throw new IOException("adoption publication size is invalid");
        }
        final Marker marker;
        try {
            marker = mapper.readValue(Files.readAllBytes(file), Marker.class);
        } catch (IOException | RuntimeException error) {
            throw new IOException("adoption publication is invalid", error);
        }
        validate(marker, record, receipt, current, channel);
    }

    void rollback(Path registry, String id, Marker expected) throws IOException {
        Path file = path(registry, id);
        Files.deleteIfExists(file.resolveSibling(file.getFileName() + ".new"));
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return;
        StrictPathSafety.requireFile(file, "adoption publication");
        Marker actual = mapper.readValue(Files.readAllBytes(file), Marker.class);
        if (!actual.equals(expected)) {
            throw new IOException("external adoption metadata edit blocks rollback");
        }
        Files.delete(file);
    }

    private static void validate(Marker marker, InstallationRecord record,
                                 OwnershipReceipt receipt, CurrentUpdateState current,
                                 ChannelPreference channel) throws IOException {
        if (marker == null || marker.schemaVersion() != SCHEMA
                || !isAdoptionTransaction(marker.attemptId())
                || !record.id().equals(marker.installationId())
                || !record.canonicalRoot().equals(marker.canonicalRoot())
                || !record.registeredAt().equals(marker.registeredAt())
                || !receipt.repository().equals(marker.repository())
                || !receipt.tag().equals(marker.tag())
                || !receipt.assetName().equals(marker.assetName())
                || receipt.assetSize() != marker.assetSize()
                || !receipt.assetSha256().equals(marker.assetSha256())
                || !channel.channel().name().equals(marker.channel())
                || !current.installationId().equals(marker.installationId())
                || (isAdoptionTransaction(current.lastTransactionId())
                && !marker.attemptId().equals(current.lastTransactionId()))) {
            throw new IOException("adoption publication binding is invalid");
        }
    }

    private static boolean isAdoptionTransaction(String value) {
        try {
            return value != null && java.util.UUID.fromString(value).version() == 3;
        } catch (RuntimeException error) {
            return false;
        }
    }

    record Marker(int schemaVersion, String attemptId, String installationId,
                  String canonicalRoot, String registeredAt, String repository, String tag,
                  String assetName, long assetSize, String assetSha256, String channel) {
    }
}
