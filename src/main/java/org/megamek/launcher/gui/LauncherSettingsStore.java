package org.megamek.launcher.gui;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
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
import java.util.function.UnaryOperator;

/**
 * Launcher-level settings which are unrelated to a managed installation's fixed channel.
 * Per-copy automatic-check choices live solely in ChannelPreferenceStore.
 */
final class LauncherSettingsStore {
    static final int SCHEMA = 3;
    private static final int MAX_BYTES = 4096;
    private final Path registry;
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    LauncherSettingsStore(Path registry) {
        this.registry = registry.toAbsolutePath().normalize();
        this.mapper.findAndRegisterModules();
    }

    Settings read() throws IOException {
        Path file = path();
        try {
            StrictPathSafety.requireFile(file, "launcher settings");
        } catch (NoSuchFileException missing) {
            verifyAbsent(file);
            return Settings.DEFAULT;
        }
        long size = Files.size(file);
        if (size <= 0 || size > MAX_BYTES) {
            throw new IOException("launcher settings size is invalid (not reset)");
        }
        byte[] bytes = Files.readAllBytes(file);
        if (bytes.length != size) {
            throw new IOException("launcher settings changed while being read (not reset)");
        }
        try {
            com.fasterxml.jackson.databind.JsonNode tree = mapper.readTree(bytes);
            com.fasterxml.jackson.databind.JsonNode version = tree.get("schemaVersion");
            if (version == null || !version.canConvertToInt()) {
                throw new IOException("unsupported launcher settings schema");
            }
            if (version.intValue() != SCHEMA) {
                throw new IOException("unsupported launcher settings schema");
            }
            com.fasterxml.jackson.databind.JsonNode java =
                    tree.get("defaultJavaExecutable");
            com.fasterxml.jackson.databind.JsonNode feature =
                    tree.get("defaultJavaFeature");
            if (java == null || feature == null
                    || !(java.isNull() || java.isTextual())
                    || !(feature.isNull() || feature.isIntegralNumber())) {
                throw new IOException("default Java settings have invalid types");
            }
            Settings settings = mapper.treeToValue(tree, Settings.class);
            validate(settings);
            return settings;
        } catch (IOException error) {
            throw new IOException("launcher settings are invalid or unreadable (not reset): "
                    + error.getMessage(), error);
        }
    }

    Settings writeDefaultJava(Path executable, int feature) throws IOException {
        if (executable == null || feature < 21) {
            throw new IOException("validated Java 21+ is required");
        }
        Path canonical = executable.toAbsolutePath().normalize();
        if (!canonical.toString().equals(executable.toString())) {
            throw new IOException("default Java executable must be canonical");
        }
        return update(settings -> new Settings(SCHEMA, canonical.toString(), feature));
    }

    private Settings update(UnaryOperator<Settings> change) throws IOException {
        Path file = path();
        Path parent = file.getParent();
        StrictPathSafety.requireDirectory(parent, "launcher settings parent");
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) read();
        Path lockPath = file.resolveSibling(file.getFileName() + ".lock");
        rejectUnexpected(lockPath, "launcher settings lock");
        try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE); FileLock ignored = lock(channel, lockPath)) {
            Settings current = Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                    ? read() : Settings.DEFAULT;
            Settings settings = change.apply(current);
            validate(settings);
            byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(settings);
            if (bytes.length <= 0 || bytes.length > MAX_BYTES) {
                throw new IOException("launcher settings exceed size limit");
            }
            Path staging = file.resolveSibling(file.getFileName() + ".new");
            if (Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("interrupted launcher settings publication requires review");
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
                throw new IOException("atomic launcher settings publication is unsupported",
                        error);
            } catch (IOException error) {
                Files.deleteIfExists(staging);
                throw error;
            }
            return settings;
        }
    }

    private static void validate(Settings settings) throws IOException {
        if (settings == null || settings.schemaVersion() != SCHEMA
                || (settings.defaultJavaExecutable() == null)
                != (settings.defaultJavaFeature() == null)) {
            throw new IOException("unsupported launcher settings schema");
        }

        if (settings.defaultJavaExecutable() == null) return;
        if (settings.defaultJavaExecutable().isBlank()
                || settings.defaultJavaExecutable().length() > 2048
                || settings.defaultJavaExecutable().chars().anyMatch(Character::isISOControl)
                || settings.defaultJavaFeature() < 21) {
            throw new IOException("invalid default Java setting");
        }
        try {
            Path path = Path.of(settings.defaultJavaExecutable());
            if (!path.isAbsolute()
                    || !path.normalize().toString().equals(settings.defaultJavaExecutable())) {
                throw new IOException("default Java setting is not canonical");
            }
        } catch (RuntimeException error) {
            throw new IOException("invalid default Java setting", error);
        }
    }

    Path path() {
        return registry.resolveSibling(registry.getFileName() + ".settings.json");
    }

    private static FileLock lock(FileChannel channel, Path file) throws IOException {
        try {
            FileLock lock = channel.tryLock();
            if (lock == null) throw new IOException("launcher settings are locked: " + file);
            return lock;
        } catch (OverlappingFileLockException error) {
            throw new IOException("launcher settings are locked by this process: " + file, error);
        }
    }

    private static void rejectUnexpected(Path file, String label) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(file)) {
            throw new IOException(label + " is not an ordinary file: " + file);
        }
    }

    private static void verifyAbsent(Path file) throws IOException {
        Path parent = file.getParent();
        if (parent == null) throw new IOException("launcher settings have no parent");
        Path cursor = parent.getRoot();
        for (Path part : parent) {
            cursor = cursor == null ? part : cursor.resolve(part);
            if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) return;
            var attributes = Files.readAttributes(cursor,
                    java.nio.file.attribute.BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther()
                    || !attributes.isDirectory()) {
                throw new IOException("launcher settings path traverses an unsafe object: "
                        + cursor);
            }
        }
    }

    record Settings(int schemaVersion, String defaultJavaExecutable,
                    Integer defaultJavaFeature) {
        static final Settings DEFAULT = new Settings(SCHEMA, null, null);
    }
}
