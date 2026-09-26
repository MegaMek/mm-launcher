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

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryData;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.update.OwnershipReceipt;
import org.megamek.launcher.update.ReceiptStore;
import org.megamek.launcher.update.StrictPathSafety;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

/**
 * Atomic sidecar storage for an installation's immutable update channel and mutable
 * check-on-open flag. Absence is UNKNOWN; unreadable, stale, or malformed data is UNAVAILABLE
 * and is never silently reset.
 */
public final class ChannelPreferenceStore {
    public static final int SCHEMA = 1;
    private static final int MAX_BYTES = 8 * 1024;
    private final RegistryStore registries;
    private final ReceiptStore receipts;
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public ChannelPreferenceStore() {
        this(new RegistryStore(), new ReceiptStore());
    }

    ChannelPreferenceStore(RegistryStore registries, ReceiptStore receipts) {
        this.registries = registries;
        this.receipts = receipts;
    }

    public ReadResult read(Path registry, RegistryData data, InstallationRecord record) {
        try {
            return readStrict(registry, data, record);
        } catch (IOException | RuntimeException error) {
            return new ReadResult(Status.UNAVAILABLE, null,
                    "Channel setting is unavailable (not reset): " + detail(error));
        }
    }

    /**
     * Initializes the fixed channel for a freshly published managed installation.
     *
     * <p>The caller must provide the exact ownership receipt returned by verified package
     * publication. This method re-reads both registry and receipt state before writing, so an
     * imported or otherwise receipt-less copy cannot acquire managed-update provenance through
     * this API. A retry for the same binding and channel is idempotent; retargeting is refused.
     */
    public ChannelPreference initializeManaged(Path registry, InstallationRecord expected,
                                               OwnershipReceipt publishedReceipt,
                                               FollowChannel channel, boolean checkOnOpen)
            throws IOException {
        if (expected == null || publishedReceipt == null || channel == null) {
            throw new IOException("managed installation, ownership receipt, and channel "
                    + "are required");
        }
        Path registryPath = registry.toAbsolutePath().normalize();
        RegistryData initial = registries.read(registryPath);
        InstallationRecord initialRecord = registries.resolve(initial, expected.id());
        requireBinding(initialRecord, expected);
        requirePublishedReceipt(registryPath, initial, initialRecord, publishedReceipt);
        Path directory = receipts.metadataDirectory(registryPath);
        ReceiptStore.validateMetadataPlacement(directory, initial);
        ensureDirectory(directory);
        Path file = preferencePath(directory, expected.id());
        Path staging = stagingPath(file);
        Path lockPath = lockPath(directory, expected.id());
        rejectUnexpectedExisting(lockPath, "channel setting lock");
        try (FileChannel channelFile = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE); FileLock ignored = lock(channelFile, lockPath)) {
            RegistryData current = registries.read(registryPath);
            InstallationRecord currentRecord = registries.resolve(current, expected.id());
            requireBinding(currentRecord, expected);
            ReceiptStore.validateMetadataPlacement(directory, current);
            requirePublishedReceipt(registryPath, current, currentRecord, publishedReceipt);

            if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("interrupted channel setting publication requires review");
            }
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                ReadResult existing = readStrict(registryPath, current, currentRecord);
                if (existing.status() != Status.CONFIGURED) {
                    throw new IOException(existing.reason());
                }
                if (existing.preference().channel() != channel) {
                    throw new IOException("Channel is fixed; install another managed copy");
                }
                return existing.preference();
            }

            ChannelPreference preference = new ChannelPreference(SCHEMA, expected.id(),
                    expected.canonicalRoot(), expected.registeredAt(), channel, checkOnOpen);
            publish(preference, staging, file, false);
            return preference;
        }
    }

    /**
     * Changes only the check-on-open flag of an existing, valid fixed-channel sidecar.
     * Missing, corrupt, stale, or concurrently changed state is never recreated or repaired.
     */
    public ChannelPreference setCheckOnOpen(Path registry, InstallationRecord expected,
                                            ChannelPreference expectedPreference,
                                            boolean checkOnOpen) throws IOException {
        if (expected == null || expectedPreference == null) {
            throw new IOException("existing fixed channel setting is required");
        }
        Path registryPath = registry.toAbsolutePath().normalize();
        RegistryData initial = registries.read(registryPath);
        InstallationRecord initialRecord = registries.resolve(initial, expected.id());
        requireBinding(initialRecord, expected);
        validate(expectedPreference, initialRecord);
        ReadResult initialPreference = readStrict(registryPath, initial, initialRecord);
        requireExpectedPreference(initialPreference, expectedPreference);

        Path directory = receipts.metadataDirectory(registryPath);
        Path file = preferencePath(directory, expected.id());
        Path staging = stagingPath(file);
        Path lockPath = lockPath(directory, expected.id());
        rejectUnexpectedExisting(lockPath, "channel setting lock");
        try (FileChannel channelFile = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE); FileLock ignored = lock(channelFile, lockPath)) {
            RegistryData current = registries.read(registryPath);
            InstallationRecord currentRecord = registries.resolve(current, expected.id());
            requireBinding(currentRecord, expected);
            ReceiptStore.validateMetadataPlacement(directory, current);
            if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("interrupted channel setting publication requires review");
            }
            ReadResult selected = readStrict(registryPath, current, currentRecord);
            requireExpectedPreference(selected, expectedPreference);
            ChannelPreference existing = selected.preference();
            if (existing.checkOnOpen() == checkOnOpen) return existing;

            ChannelPreference changed = new ChannelPreference(existing.schemaVersion(),
                    existing.installationId(), existing.canonicalRoot(),
                    existing.registeredAt(), existing.channel(), checkOnOpen);
            publish(changed, staging, file, true);
            return changed;
        }
    }

    public Path path(Path registry, String id) throws IOException {
        return preferencePath(receipts.metadataDirectory(registry), id);
    }

    /**
     * Best-effort inverse used only by a failed multi-file metadata publication. It removes the
     * final channel setting only when its full value still equals the attempt-owned value.
     */
    public void rollbackInitialization(Path registry, InstallationRecord expected,
                                       ChannelPreference expectedPreference)
            throws IOException {
        if (expected == null || expectedPreference == null) return;
        Path registryPath = registry.toAbsolutePath().normalize();
        Path directory = receipts.metadataDirectory(registryPath);
        Path file = preferencePath(directory, expected.id());
        Path staging = stagingPath(file);
        if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
            StrictPathSafety.requireFile(staging, "channel setting staging");
            Files.delete(staging);
        }
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return;
        StrictPathSafety.requireFile(file, "channel setting");
        ChannelPreference actual;
        try {
            actual = mapper.readValue(Files.readAllBytes(file), ChannelPreference.class);
        } catch (IOException | RuntimeException error) {
            throw new IOException("refusing to remove unreadable channel setting", error);
        }
        if (!actual.equals(expectedPreference)) {
            throw new IOException("external channel setting edit blocks rollback");
        }
        Files.delete(file);
    }

    private ReadResult readStrict(Path registry, RegistryData data, InstallationRecord record)
            throws IOException {
        Path directory = receipts.metadataDirectory(registry);
        ReceiptStore.validateMetadataPlacement(directory, data);
        try {
            StrictPathSafety.requireDirectory(directory, "channel metadata directory");
        } catch (NoSuchFileException error) {
            return new ReadResult(Status.UNKNOWN, null,
                    "No fixed channel provenance is recorded; this copy is launch-only.");
        }
        Path file = preferencePath(directory, record.id());
        Path staging = stagingPath(file);
        if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("interrupted channel setting publication requires review");
        }
        try {
            StrictPathSafety.requireFile(file, "channel setting");
        } catch (NoSuchFileException error) {
            return new ReadResult(Status.UNKNOWN, null,
                    "No fixed channel provenance is recorded; this copy is launch-only.");
        }
        long size = Files.size(file);
        if (size <= 0 || size > MAX_BYTES) throw new IOException("channel setting size is invalid");
        try {
            ChannelPreference preference = mapper.readValue(Files.readAllBytes(file),
                    ChannelPreference.class);
            validate(preference, record);
            return new ReadResult(Status.CONFIGURED, preference, "Configured");
        } catch (IOException | RuntimeException error) {
            throw new IOException("invalid channel setting: " + detail(error), error);
        }
    }

    private static void validate(ChannelPreference preference, InstallationRecord record)
            throws IOException {
        if (preference == null || preference.schemaVersion() != SCHEMA
                || preference.installationId() == null || preference.canonicalRoot() == null
                || preference.registeredAt() == null || preference.channel() == null) {
            throw new IOException("unsupported or incomplete channel setting schema");
        }
        if (!preference.installationId().equals(record.id())
                || !preference.canonicalRoot().equals(record.canonicalRoot())
                || !preference.registeredAt().equals(record.registeredAt())) {
            throw new IOException("channel setting does not match installation id, root, "
                    + "and registration time");
        }
    }

    private static void requireBinding(InstallationRecord actual, InstallationRecord expected)
            throws IOException {
        if (!actual.id().equals(expected.id())
                || !actual.canonicalRoot().equals(expected.canonicalRoot())
                || !actual.registeredAt().equals(expected.registeredAt())) {
            throw new IOException("installation identity changed before channel setting was saved");
        }
    }

    private void requirePublishedReceipt(Path registry, RegistryData data,
                                         InstallationRecord record,
                                         OwnershipReceipt publishedReceipt)
            throws IOException {
        OwnershipReceipt current = receipts.read(registry, data, record);
        if (!current.equals(publishedReceipt)) {
            throw new IOException("ownership receipt changed before fixed channel publication");
        }
    }

    private static void requireExpectedPreference(ReadResult selected,
                                                  ChannelPreference expected)
            throws IOException {
        if (selected.status() != Status.CONFIGURED || selected.preference() == null) {
            throw new IOException(selected.reason());
        }
        if (!selected.preference().equals(expected)) {
            throw new IOException("fixed channel setting changed; reopen Update checks");
        }
    }

    private void publish(ChannelPreference preference, Path staging, Path file,
                         boolean replace) throws IOException {
        byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(preference);
        if (bytes.length <= 0 || bytes.length > MAX_BYTES) {
            throw new IOException("channel setting exceeds size limit");
        }
        try {
            try (FileChannel output = FileChannel.open(staging, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            if (replace) {
                Files.move(staging, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(staging, file, StandardCopyOption.ATOMIC_MOVE);
            }
        } catch (AtomicMoveNotSupportedException error) {
            Files.deleteIfExists(staging);
            throw new IOException("atomic channel setting publication is unsupported", error);
        } catch (IOException error) {
            Files.deleteIfExists(staging);
            throw error;
        }
    }

    private static void ensureDirectory(Path directory) throws IOException {
        Path parent = directory.getParent();
        StrictPathSafety.requireDirectory(parent, "channel metadata parent");
        try {
            Files.createDirectory(directory);
        } catch (java.nio.file.FileAlreadyExistsException ignored) {
            // The strict check below rejects links and non-directories.
        }
        StrictPathSafety.requireDirectory(directory, "channel metadata directory");
    }

    private static Path preferencePath(Path directory, String id) throws IOException {
        requireUuid(id);
        return directory.resolve(id + ".channel.json");
    }

    private static Path stagingPath(Path file) {
        return file.resolveSibling(file.getFileName() + ".new");
    }

    private static Path lockPath(Path directory, String id) {
        return directory.resolve(id + ".channel.json.lock");
    }

    private static void rejectUnexpectedExisting(Path path, String label) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            StrictPathSafety.requireFile(path, label);
        }
    }

    private static FileLock lock(FileChannel channel, Path path) throws IOException {
        try {
            FileLock result = channel.tryLock();
            if (result == null) throw new IOException("channel setting is busy: " + path);
            return result;
        } catch (OverlappingFileLockException error) {
            throw new IOException("channel setting is busy: " + path, error);
        }
    }

    private static void requireUuid(String id) throws IOException {
        try {
            if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException error) {
            throw new IOException("invalid installation UUID", error);
        }
    }

    private static String detail(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    public enum Status {
        UNKNOWN,
        CONFIGURED,
        UNAVAILABLE
    }

    public record ReadResult(Status status, ChannelPreference preference, String reason) {
    }
}
