package org.megamek.launcher.registry;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.megamek.launcher.onboarding.Inspection;
import org.megamek.launcher.onboarding.InstallationInspector;
import org.megamek.launcher.onboarding.Product;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

public final class RegistryStore {
    public static final int SCHEMA = 1;
    public static final String CONFIRM = "REGISTER-LAUNCH-ONLY";
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public RegistryData read(Path requested) throws IOException {
        Path file = requested.toAbsolutePath().normalize();
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("registry does not exist or is not a regular file: " + file);
        }
        rejectLink(file);
        try {
            RegistryData data = mapper.readValue(Files.readAllBytes(file), RegistryData.class);
            validate(data, file);
            return data;
        } catch (IOException e) {
            throw new IOException("invalid or unreadable registry (not reset): " + e.getMessage(), e);
        }
    }

    public InstallationRecord register(Path registry, String name, Path root, String pin)
            throws IOException {
        if (name == null || name.isBlank() || name.length() > 120
                || name.chars().anyMatch(Character::isISOControl)) {
            throw new IOException("installation name must be 1-120 printable characters");
        }
        Inspection inspection = new InstallationInspector().inspect(root);
        Path file = registry.toAbsolutePath().normalize();
        rejectRegistryPlacement(file, Path.of(inspection.canonicalRoot()));
        InstallationRecord record = new InstallationRecord(UUID.randomUUID().toString(),
                name, inspection.canonicalRoot(), inspection.observedBuild(), inspection.products(),
                null, normalizePin(pin), false, Instant.now().toString());
        mutate(file, current -> {
            List<InstallationRecord> records = new ArrayList<>(current.installations());
            Path candidate = Path.of(record.canonicalRoot());
            for (InstallationRecord existing : records) {
                Path prior = Path.of(existing.canonicalRoot());
                if (candidate.equals(prior)) throw failure("installation path is already registered");
                if (candidate.startsWith(prior) || prior.startsWith(candidate)) {
                    throw failure("installation roots overlap: " + candidate + " and " + prior);
                }
            }
            records.add(record);
            String defaultId = current.defaultInstallationId() == null
                    ? record.id() : current.defaultInstallationId();
            return new RegistryData(SCHEMA, defaultId, records);
        });
        return record;
    }

    public void select(Path registry, String id) throws IOException {
        mutate(registry.toAbsolutePath().normalize(), current -> {
            requirePresent(current, id);
            return new RegistryData(SCHEMA, id, current.installations());
        });
    }

    public void selectJava(Path registry, String id, String executable) throws IOException {
        mutate(registry.toAbsolutePath().normalize(), current -> {
            List<InstallationRecord> records = new ArrayList<>();
            for (InstallationRecord record : current.installations()) {
                records.add(record.id().equals(id)
                        ? new InstallationRecord(record.id(), record.name(), record.canonicalRoot(),
                        record.observedBuild(), record.products(), executable, record.pin(), false,
                        record.registeredAt()) : record);
            }
            requirePresent(current, id);
            return new RegistryData(SCHEMA, current.defaultInstallationId(), records);
        });
    }

    public void remove(Path registry, String id) throws IOException {
        mutate(registry.toAbsolutePath().normalize(), current -> {
            requirePresent(current, id);
            List<InstallationRecord> records = current.installations().stream()
                    .filter(record -> !record.id().equals(id)).toList();
            String defaultId = id.equals(current.defaultInstallationId())
                    ? (records.isEmpty() ? null : records.getFirst().id())
                    : current.defaultInstallationId();
            return new RegistryData(SCHEMA, defaultId, records);
        });
    }

    /**
     * Publishes only the statically observed build/product fields after a verified update.
     * Name, Java, pin, registration time, default selection, and unrelated records are retained
     * from the registry version held under the mutation lock.
     */
    public InstallationRecord refreshAfterUpdate(Path registry, InstallationRecord expected,
                                                 String observedBuild, List<Product> products)
            throws IOException {
        InstallationRecord[] result = new InstallationRecord[1];
        mutate(registry.toAbsolutePath().normalize(), current -> {
            List<InstallationRecord> records = new ArrayList<>();
            boolean found = false;
            for (InstallationRecord record : current.installations()) {
                if (!record.id().equals(expected.id())) {
                    records.add(record);
                    continue;
                }
                found = true;
                if (!record.canonicalRoot().equals(expected.canonicalRoot())
                        || !record.registeredAt().equals(expected.registeredAt())) {
                    throw failure("selected update record identity changed");
                }
                boolean priorLayout = record.observedBuild().equals(expected.observedBuild())
                        && record.products().equals(expected.products());
                boolean targetLayout = record.observedBuild().equals(observedBuild)
                        && record.products().equals(products);
                if (!priorLayout && !targetLayout) {
                    throw failure("selected update record program layout changed concurrently");
                }
                InstallationRecord updated = new InstallationRecord(record.id(), record.name(),
                        record.canonicalRoot(), observedBuild, products, record.javaExecutable(),
                        record.pin(), false, record.registeredAt());
                records.add(updated);
                result[0] = updated;
            }
            if (!found) throw failure("selected update record was removed");
            return new RegistryData(SCHEMA, current.defaultInstallationId(), records);
        });
        return result[0];
    }

    public InstallationRecord resolve(RegistryData data, String id) throws IOException {
        String selected = id == null ? data.defaultInstallationId() : id;
        if (selected == null) throw new IOException("registry has no default installation");
        return find(data, selected);
    }

    private void mutate(Path file, UnaryOperator<RegistryData> operation) throws IOException {
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) read(file); // fail before side effects
        Path parent = file.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("registry parent must already be a real directory: " + parent);
        }
        rejectLink(parent);
        Path lockPath = file.resolveSibling(file.getFileName() + ".lock");
        if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(lockPath, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("registry lock path is not a regular file: " + lockPath);
        }
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE); FileLock ignored = tryLock(channel)) {
            RegistryData current = Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                    ? read(file) : new RegistryData(SCHEMA, null, List.of());
            RegistryData next;
            try {
                next = operation.apply(current);
            } catch (MutationFailure e) {
                throw new IOException(e.getMessage());
            }
            validate(next, file);
            writeAtomic(file, next);
        }
    }

    private FileLock tryLock(FileChannel channel) throws IOException {
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException("registry is locked by another process");
            return lock;
        } catch (OverlappingFileLockException e) {
            throw new IOException("registry is locked by this process", e);
        }
    }

    private void writeAtomic(Path file, RegistryData data) throws IOException {
        Path staging = file.resolveSibling(file.getFileName() + ".new");
        if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("refusing unexpected registry staging path: " + staging);
        }
        byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(data);
        try (FileChannel channel = FileChannel.open(staging, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(bytes));
            channel.force(true);
        }
        try {
            Files.move(staging, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.deleteIfExists(staging);
            throw new IOException("atomic registry replacement is unsupported", e);
        }
    }

    private void validate(RegistryData data, Path registry) throws IOException {
        if (data == null || data.schemaVersion() != SCHEMA || data.installations() == null) {
            throw new IOException("unsupported registry schema");
        }
        Set<String> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        List<Path> roots = new ArrayList<>();
        for (InstallationRecord record : data.installations()) {
            if (record == null || record.id() == null
                    || !record.id().matches("[0-9a-f]{8}-[0-9a-f-]{27}")
                    || record.name() == null || record.name().isBlank()
                    || record.canonicalRoot() == null || record.observedBuild() == null
                    || record.products() == null || record.products().isEmpty()
                    || record.registeredAt() == null || record.updateEligible()
                    || !ids.add(record.id()) || !names.add(record.name())) {
                throw new IOException("invalid installation record");
            }
            Path root = Path.of(record.canonicalRoot()).toAbsolutePath().normalize();
            if (!root.toString().equals(record.canonicalRoot())) {
                throw new IOException("installation root is not canonical");
            }
            rejectRegistryPlacement(registry, root);
            for (Path prior : roots) {
                if (root.equals(prior) || root.startsWith(prior) || prior.startsWith(root)) {
                    throw new IOException("duplicate or overlapping installation roots");
                }
            }
            roots.add(root);
            Set<String> productKeys = new HashSet<>();
            for (Product product : record.products()) {
                if (product == null || product.key() == null || product.jar() == null
                        || product.mainClass() == null || product.build() == null
                        || product.classPath() == null || !productKeys.add(product.key())) {
                    throw new IOException("invalid product record");
                }
            }
        }
        if (data.installations().isEmpty() != (data.defaultInstallationId() == null)
                || data.defaultInstallationId() != null
                && data.installations().stream().noneMatch(
                record -> record.id().equals(data.defaultInstallationId()))) {
            throw new IOException("invalid default installation");
        }
    }

    private static InstallationRecord find(RegistryData data, String id) throws IOException {
        return data.installations().stream().filter(record -> record.id().equals(id)).findFirst()
                .orElseThrow(() -> new IOException("unknown installation id: " + id));
    }

    private static void requirePresent(RegistryData data, String id) {
        if (data.installations().stream().noneMatch(record -> record.id().equals(id))) {
            throw failure("unknown installation id: " + id);
        }
    }

    private static void rejectRegistryPlacement(Path registry, Path root) throws IOException {
        Path file = registry.toAbsolutePath().normalize();
        if (file.startsWith(root) || root.startsWith(file)) {
            throw new IOException("registry must be separate from application roots");
        }
    }

    private static void rejectLink(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path cursor = absolute.getRoot();
        for (Path component : absolute) {
            cursor = cursor.resolve(component);
            if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) continue;
            var attributes = Files.readAttributes(cursor,
                    java.nio.file.attribute.BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther()) {
                throw new IOException("registry path may not use links/reparse points: " + cursor);
            }
        }
    }

    private static String normalizePin(String pin) throws IOException {
        if (pin == null) return null;
        if (pin.isBlank() || pin.length() > 200 || pin.chars().anyMatch(Character::isISOControl)) {
            throw new IOException("pin must be a nonblank printable value");
        }
        return pin;
    }

    private static MutationFailure failure(String message) {
        return new MutationFailure(message);
    }

    private static final class MutationFailure extends RuntimeException {
        MutationFailure(String message) { super(message); }
    }
}
