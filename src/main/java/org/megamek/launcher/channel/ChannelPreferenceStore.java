package org.megamek.launcher.channel;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryData;
import org.megamek.launcher.registry.RegistryStore;
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
 * Atomic sidecar storage for a user's per-installation channel choice. Absence is UNKNOWN;
 * unreadable, stale, or malformed data is UNAVAILABLE and is never silently reset.
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

    public ChannelPreference set(Path registry, InstallationRecord expected,
                                 FollowChannel channel, boolean checkOnOpen) throws IOException {
        if (channel == null) throw new IOException("channel is required");
        Path registryPath = registry.toAbsolutePath().normalize();
        RegistryData initial = registries.read(registryPath);
        InstallationRecord initialRecord = registries.resolve(initial, expected.id());
        requireBinding(initialRecord, expected);
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

            if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("interrupted channel setting publication requires review");
            }
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                ReadResult existing = readStrict(registryPath, current, currentRecord);
                if (existing.status() != Status.CONFIGURED) {
                    throw new IOException(existing.reason());
                }
            }

            ChannelPreference preference = new ChannelPreference(SCHEMA, expected.id(),
                    expected.canonicalRoot(), expected.registeredAt(), channel, checkOnOpen);
            byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(preference);
            if (bytes.length <= 0 || bytes.length > MAX_BYTES) {
                throw new IOException("channel setting exceeds size limit");
            }
            try (FileChannel output = FileChannel.open(staging, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) output.write(buffer);
                output.force(true);
            }
            try {
                Files.move(staging, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.deleteIfExists(staging);
                throw new IOException("atomic channel setting publication is unsupported", error);
            } catch (IOException error) {
                Files.deleteIfExists(staging);
                throw error;
            }
            return preference;
        }
    }

    public Path path(Path registry, String id) throws IOException {
        return preferencePath(receipts.metadataDirectory(registry), id);
    }

    private ReadResult readStrict(Path registry, RegistryData data, InstallationRecord record)
            throws IOException {
        Path directory = receipts.metadataDirectory(registry);
        ReceiptStore.validateMetadataPlacement(directory, data);
        try {
            StrictPathSafety.requireDirectory(directory, "channel metadata directory");
        } catch (NoSuchFileException error) {
            return new ReadResult(Status.UNKNOWN, null,
                    "Choose Milestone or Development before checking for updates.");
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
                    "Choose Milestone or Development before checking for updates.");
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
