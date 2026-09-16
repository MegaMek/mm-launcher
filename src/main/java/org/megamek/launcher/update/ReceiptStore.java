package org.megamek.launcher.update;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.megamek.launcher.manifest.ManifestException;
import org.megamek.launcher.manifest.ManifestReader;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryData;
import org.megamek.launcher.release.OfficialRepository;
import org.megamek.launcher.release.ReleaseCatalog;
import org.megamek.launcher.release.FreshInstaller;
import org.megamek.launcher.release.SafeTarExtractor;

import java.io.InputStream;
import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.text.Normalizer;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Strict sidecar storage adjacent to, but deliberately outside, application roots. */
public class ReceiptStore {
    public static final int SCHEMA_VERSION = 1;
    private static final int MAX_RECEIPT_BYTES = 32 * 1024 * 1024;
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern TAG = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,99}");
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_NULL_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public Path metadataDirectory(Path registry) {
        Path file = registry.toAbsolutePath().normalize();
        return file.resolveSibling(file.getFileName() + ".metadata");
    }

    public Path receiptPath(Path registry, String id) throws IOException {
        requireUuid(id);
        return metadataDirectory(registry).resolve(id + ".json");
    }

    public OwnershipReceipt read(Path registry, RegistryData data, InstallationRecord record)
            throws IOException {
        Path directory = metadataDirectory(registry);
        validateMetadataPlacement(directory, data);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("ownership receipt is absent; install one new official release "
                    + "with this launcher to enable preview");
        }
        requireRealPath(directory, true);
        Path file = receiptPath(registry, record.id());
        try {
            requireRealPath(file, false);
        } catch (NoSuchFileException e) {
            throw new IOException("ownership receipt is absent for installation " + record.id()
                    + "; only copies freshly installed by this launcher have preview provenance", e);
        }
        long size = Files.size(file);
        if (size <= 0 || size > MAX_RECEIPT_BYTES) {
            throw new IOException("ownership receipt size is invalid");
        }
        try {
            OwnershipReceipt receipt;
            try (InputStream input = new LimitedInputStream(Files.newInputStream(file),
                    MAX_RECEIPT_BYTES)) {
                receipt = mapper.readValue(input, OwnershipReceipt.class);
            }
            validate(receipt, record);
            return receipt;
        } catch (IOException | RuntimeException | ManifestException e) {
            throw new IOException("ownership receipt is invalid: " + detail(e), e);
        }
    }

    public OwnershipReceipt write(Path registry, RegistryData data, InstallationRecord record,
                                  OfficialRepository repository, String tag,
                                  String assetName, long assetSize, String assetDigest,
                                  OwnershipPolicy.Build build) throws IOException {
        if (repository == null || build == null || build.manifest() == null
                || build.excludedPaths() == null) {
            throw new IOException("refusing incomplete ownership receipt input");
        }
        String digest = assetDigest != null && assetDigest.startsWith("sha256:")
                ? assetDigest.substring("sha256:".length()).toLowerCase(Locale.ROOT) : null;
        OwnershipReceipt receipt = new OwnershipReceipt(SCHEMA_VERSION, OwnershipPolicy.VERSION,
                record.id(), record.canonicalRoot(), record.registeredAt(), repository.key(), tag,
                assetName, assetSize, digest, build.manifest(), build.excludedPaths());
        try {
            validate(receipt, record);
        } catch (ManifestException e) {
            throw new IOException("refusing invalid ownership receipt: " + e.getMessage(), e);
        }
        Path directory = metadataDirectory(registry);
        validateMetadataPlacement(directory, data);
        ensureMetadataDirectory(directory);
        Path file = receiptPath(registry, record.id());
        byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(receipt);
        if (bytes.length > MAX_RECEIPT_BYTES) throw new IOException("ownership receipt exceeds size limit");
        Path lockPath = directory.resolve(record.id() + ".json.lock");
        if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("ownership receipt lock has an unexpected type: " + lockPath);
        }
        try (FileChannel lockChannel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE); FileLock ignored = lock(lockChannel, lockPath)) {
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileAlreadyExistsException(
                        "refusing to overwrite ownership receipt state: " + file);
            }
            Path staging = directory.resolve(record.id() + ".json.new-" + UUID.randomUUID());
            boolean owned = false;
            try {
                try (FileChannel output = FileChannel.open(staging, StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE)) {
                    owned = true;
                    ByteBuffer buffer = ByteBuffer.wrap(bytes);
                    while (buffer.hasRemaining()) output.write(buffer);
                    output.force(true);
                }
                // Creating the final hard link is an atomic create-new publication. Unlike move,
                // it cannot replace a final receipt which appears concurrently.
                Files.createLink(file, staging);
                Files.delete(staging);
                owned = false;
            } finally {
                if (owned) Files.deleteIfExists(staging);
            }
        }
        return receipt;
    }

    void validate(OwnershipReceipt receipt, InstallationRecord record)
            throws IOException, ManifestException {
        if (receipt == null || receipt.schemaVersion() != SCHEMA_VERSION) {
            throw new IOException("unsupported receipt schema");
        }
        if (receipt.ownershipPolicyVersion() != OwnershipPolicy.VERSION) {
            throw new IOException("unsupported ownership policy version");
        }
        if (!record.id().equals(receipt.installationId())
                || !record.canonicalRoot().equals(receipt.canonicalRoot())
                || !record.registeredAt().equals(receipt.registeredAt())) {
            throw new IOException("receipt does not match installation id, root, and registration time");
        }
        Path root = Path.of(record.canonicalRoot()).toAbsolutePath().normalize();
        StrictPathSafety.requireDirectory(root, "registered installation root");
        OfficialRepository repository;
        try {
            repository = OfficialRepository.parse(receipt.repository());
        } catch (IOException | RuntimeException e) {
            throw new IOException("receipt official repository is invalid", e);
        }
        if (receipt.tag() == null || !TAG.matcher(receipt.tag()).matches()
                || !ReleaseCatalog.isInstallAssetName(repository, receipt.assetName())
                || receipt.assetSize() <= 0 || receipt.assetSize() > FreshInstaller.MAX_DOWNLOAD
                || receipt.assetSha256() == null || !HASH.matcher(receipt.assetSha256()).matches()) {
            throw new IOException("receipt official source metadata is invalid");
        }
        if (receipt.officialManifest() == null) {
            throw new IOException("receipt official manifest is missing");
        }
        if (!repository.key().equals(receipt.officialManifest().product())
                || !(repository.key() + "-official-package")
                .equals(receipt.officialManifest().packageId())
                || !receipt.tag().equals(receipt.officialManifest().releaseId())
                || !OwnershipPolicy.PROTECTED_PATHS
                .equals(receipt.officialManifest().protectedPaths())) {
            throw new IOException("receipt manifest source/policy binding is invalid");
        }
        new ManifestReader().validateInMemory(receipt.officialManifest(), "ownership receipt manifest");
        if (receipt.officialManifest().files().size() > SafeTarExtractor.MAX_ENTRIES) {
            throw new IOException("receipt managed inventory exceeds entry limit");
        }
        Set<String> managedAliases = new HashSet<>();
        for (var entry : receipt.officialManifest().files()) {
            if (!OwnershipPolicy.managed(entry.path())) {
                throw new IOException("receipt claims a path outside the ownership allowlist: "
                        + entry.path());
            }
            managedAliases.add(alias(entry.path()));
        }
        if (receipt.excludedOfficialPaths() == null
                || receipt.excludedOfficialPaths().size() > SafeTarExtractor.MAX_ENTRIES
                || receipt.excludedOfficialPaths().size()
                > SafeTarExtractor.MAX_ENTRIES - receipt.officialManifest().files().size()) {
            throw new IOException("receipt exclusion inventory is invalid");
        }
        Set<String> aliases = new HashSet<>();
        String previous = null;
        for (String path : receipt.excludedOfficialPaths()) {
            ManifestReader.validatePortablePath(path, "excluded path", "ownership receipt");
            String key = alias(path);
            if (OwnershipPolicy.managed(path) || managedAliases.contains(key)) {
                throw new IOException("receipt exclusion is managed or overlaps managed inventory: "
                        + path);
            }
            if (!aliases.add(key) || previous != null && previous.compareTo(key) > 0) {
                throw new IOException("receipt exclusion paths contain aliases or are not ordered");
            }
            previous = key;
        }
        try {
            Instant.parse(receipt.registeredAt());
        } catch (DateTimeParseException e) {
            throw new IOException("receipt registration time is invalid", e);
        }
    }

    /**
     * Applies the single placement rule used by both receipt I/O and install preflight.
     * Callers must pass the normalized metadata directory returned by {@link #metadataDirectory}.
     */
    public static void validateMetadataPlacement(Path directory, RegistryData data)
            throws IOException {
        Path normalized = directory.toAbsolutePath().normalize();
        for (InstallationRecord item : data.installations()) {
            Path root = Path.of(item.canonicalRoot()).toAbsolutePath().normalize();
            if (normalized.equals(root) || normalized.startsWith(root) || root.startsWith(normalized)) {
                throw new IOException("receipt metadata directory overlaps registered installation: "
                        + root);
            }
        }
    }

    private static void ensureMetadataDirectory(Path directory) throws IOException {
        Path parent = directory.getParent();
        requireRealPath(parent, true);
        try {
            Files.createDirectory(directory);
        } catch (FileAlreadyExistsException ignored) {
            // Validate below; never replace an existing object.
        }
        requireRealPath(directory, true);
    }

    private static void requireRealPath(Path path, boolean directory) throws IOException {
        if (directory) StrictPathSafety.requireDirectory(path, "receipt path");
        else StrictPathSafety.requireFile(path, "receipt path");
    }

    private static void requireUuid(String id) throws IOException {
        try {
            if (!UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw new IOException("invalid installation UUID", e);
        }
    }

    private static String detail(Throwable error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    private static String alias(String path) {
        return Normalizer.normalize(path, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    private static FileLock lock(FileChannel channel, Path path) throws IOException {
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException("ownership receipt is busy: " + path);
            return lock;
        } catch (OverlappingFileLockException e) {
            throw new IOException("ownership receipt is busy: " + path, e);
        }
    }

    private static final class LimitedInputStream extends InputStream {
        private final InputStream input;
        private long remaining;

        private LimitedInputStream(InputStream input, long limit) {
            this.input = input;
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining == 0) return overflow();
            int value = input.read();
            if (value >= 0) remaining--;
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (remaining == 0) return overflow();
            int read = input.read(bytes, offset, (int) Math.min(length, remaining));
            if (read > 0) remaining -= read;
            return read;
        }

        private int overflow() throws IOException {
            if (input.read() < 0) return -1;
            throw new IOException("ownership receipt exceeds size limit while reading");
        }

        @Override
        public void close() throws IOException {
            input.close();
        }
    }

}
