package org.megamek.launcher.sandbox;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.megamek.launcher.manifest.ManifestException;
import org.megamek.launcher.manifest.ManifestReader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

final class StateStore {
    static final String METADATA = ".mm-launcher";
    static final int SCHEMA = 1;
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    SandboxState readState(Path root) throws IOException, ManifestException {
        Path file = metadata(root).resolve("state.json");
        SandboxState state;
        try {
            state = mapper.readValue(Files.readAllBytes(file), SandboxState.class);
        } catch (IOException e) {
            throw new ManifestException("invalid or unreadable sandbox state: " + e.getMessage(), e);
        }
        validateState(root, state);
        return state;
    }

    Journal readJournal(Path root) throws IOException, ManifestException {
        Path file = metadata(root).resolve("journal.json");
        try {
            Journal journal = mapper.readValue(Files.readAllBytes(file), Journal.class);
            if (journal.schemaVersion() != SCHEMA || journal.operations() == null
                    || journal.createdDirectories() == null || journal.previousState() == null
                    || journal.nextState() == null || journal.phase() == null
                    || journal.transactionId() == null
                    || !journal.transactionId().equals(journal.nextState().lastTransactionId())) {
                throw new ManifestException("invalid transaction journal schema");
            }
            validateState(root, journal.previousState());
            validateState(root, journal.nextState());
            new ManifestReader().validatePair(journal.previousState().officialManifest(),
                    journal.nextState().officialManifest());
            if (!journal.previousState().sandboxId().equals(journal.nextState().sandboxId())
                    || !journal.previousState().canonicalRoot().equals(journal.nextState().canonicalRoot())
                    || journal.transactionId().equals(journal.previousState().lastTransactionId())) {
                throw new ManifestException("journal state identity is inconsistent");
            }
            Set<String> operationPaths = new HashSet<>();
            for (Journal.Operation operation : journal.operations()) {
                if (operation == null || (!operation.action().equals("ADD")
                        && !operation.action().equals("REPLACE")
                        && !operation.action().equals("REMOVE"))
                        || operation.beforeHash() != null && !operation.beforeHash().matches("[0-9a-f]{64}")
                        || operation.afterHash() != null && !operation.afterHash().matches("[0-9a-f]{64}")) {
                    throw new ManifestException("invalid journal operation");
                }
                ManifestReader.validatePortablePath(operation.path(), "journal path", "journal");
                rejectMetadataPath(operation.path(), "operation");
                if (!operationPaths.add(operation.path().toLowerCase(Locale.ROOT))) {
                    throw new ManifestException("duplicate journal operation path");
                }
                boolean remove = operation.action().equals("REMOVE");
                if (remove != (operation.afterHash() == null)
                        || operation.action().equals("ADD") != (operation.beforeHash() == null)
                        || operation.beforeHash() != null && operation.backupPath() == null
                        || operation.beforeHash() == null && operation.backupPath() != null) {
                    throw new ManifestException("inconsistent journal operation hashes");
                }
                if (operation.backupPath() != null) {
                    ManifestReader.validatePortablePath(operation.backupPath(), "backup path", "journal");
                    String expectedPrefix = METADATA + "/transactions/" + journal.transactionId()
                            + "/backups/";
                    if (!operation.backupPath().startsWith(expectedPrefix)
                            || !operation.backupPath().substring(expectedPrefix.length())
                            .equals(operation.path())) {
                        throw new ManifestException("journal backup is outside transaction storage");
                    }
                }
            }
            for (String directory : journal.createdDirectories()) {
                ManifestReader.validatePortablePath(directory, "created directory", "journal");
                rejectMetadataPath(directory, "created directory");
                boolean parentOfOperation = journal.operations().stream()
                        .anyMatch(operation -> operation.path().startsWith(directory + "/"));
                if (!parentOfOperation) {
                    throw new ManifestException("journal created directory is unrelated to an operation");
                }
            }
            return journal;
        } catch (ManifestException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new ManifestException("invalid or unreadable transaction journal: " + e.getMessage(), e);
        }
    }

    void validateState(Path root, SandboxState state) throws IOException, ManifestException {
        if (state.schemaVersion() != SCHEMA || state.sandboxId() == null
                || !state.sandboxId().matches("[0-9a-f-]{36}") || state.canonicalRoot() == null
                || state.officialManifest() == null || state.overrides() == null) {
            throw new ManifestException("invalid sandbox state schema");
        }
        String actual = root.toRealPath(LinkOption.NOFOLLOW_LINKS).toString();
        if (!actual.equals(state.canonicalRoot())) {
            throw new ManifestException("sandbox state root binding mismatch");
        }
        new ManifestReader().validateInMemory(state.officialManifest(), "sandbox state officialManifest");
        Set<String> paths = new HashSet<>();
        for (OverrideEntry override : state.overrides()) {
            if (override == null || override.kind() == null || override.officialHashes() == null) {
                throw new ManifestException("invalid override entry");
            }
            ManifestReader.validatePortablePath(override.path(), "override path", "sandbox state");
            rejectMetadataPath(override.path(), "override");
            if (!paths.add(override.path().toLowerCase(Locale.ROOT))) {
                throw new ManifestException("duplicate override path: " + override.path());
            }
            for (String hash : override.officialHashes()) {
                if (!hash.matches("[0-9a-f]{64}")) {
                    throw new ManifestException("invalid official override hash for " + override.path());
                }
            }
        }
    }

    void write(Path destination, Object value) throws IOException {
        byte[] bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(value);
        atomicWrite(destination, bytes);
    }

    private static void atomicWrite(Path destination, byte[] bytes) throws IOException {
        Path temporary = destination.resolveSibling(destination.getFileName() + ".new");
        if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("refusing unexpected state staging path: " + temporary);
        }
        try (FileChannel channel = FileChannel.open(temporary,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            channel.write(ByteBuffer.wrap(bytes));
            channel.force(true);
        }
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.deleteIfExists(temporary);
            throw new IOException("atomic same-volume metadata replacement is unsupported", e);
        }
    }

    static Path metadata(Path root) {
        return root.resolve(METADATA);
    }

    private static void rejectMetadataPath(String path, String kind) throws ManifestException {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.equals(METADATA) || lower.startsWith(METADATA + "/")
                || METADATA.startsWith(lower + "/")) {
            throw new ManifestException(kind + " conflicts with reserved launcher metadata");
        }
    }
}
