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

package org.megamek.launcher.diagnostics;

import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.megamek.launcher.operation.OperationOutcome;
import org.megamek.launcher.operation.OperationProgress;
import org.megamek.launcher.operation.OperationProgressListener;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryData;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.update.StrictPathSafety;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class OperationLogStore {
    public static final int SCHEMA = 1;
    public static final int MAX_LOGS = 20;
    public static final int MAX_FILE_BYTES = 1024 * 1024;
    private static final int MAX_PROGRESS = 512;
    private static final Pattern NAME = Pattern.compile(
            "operation-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\\.json");
    private static final Set<PosixFilePermission> OWNER_DIRECTORY = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
    private static final Set<PosixFilePermission> OWNER_FILE = Set.of(
            PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    private final Path registry;
    private final Path injectedDirectory;
    private final RegistryStore registries;
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public OperationLogStore(Path registry) {
        this(registry, null, new RegistryStore());
    }

    public OperationLogStore(Path registry, Path injectedDirectory) {
        this(registry, injectedDirectory, new RegistryStore());
    }

    OperationLogStore(Path registry, Path injectedDirectory, RegistryStore registries) {
        this.registry = registry.toAbsolutePath().normalize();
        this.injectedDirectory = injectedDirectory == null
                ? null : injectedDirectory.toAbsolutePath().normalize();
        this.registries = registries;
    }

    public Collector collector(List<Path> potentialInstallationRoots) {
        return new Collector(potentialInstallationRoots == null
                ? List.of()
                : potentialInstallationRoots.stream()
                .map(path -> path.toAbsolutePath().normalize()).toList());
    }

    public List<StoredLog> readAll() throws IOException {
        Path directory = requireDirectory(List.of(), false);
        if (directory == null) return List.of();
        List<StoredLog> logs = new ArrayList<>();
        for (Path file : entries(directory)) {
            StoredLog log = readOwned(file);
            if (log != null) logs.add(log);
        }
        logs.sort(Comparator.comparing((StoredLog log) -> log.record().endedAt()).reversed());
        return List.copyOf(logs);
    }

    public String renderForViewer() throws IOException {
        List<StoredLog> logs = readAll();
        if (logs.isEmpty()) {
            return "No local operation logs have been recorded.\n\nLog location: "
                    + defaultDirectory();
        }
        StringBuilder text = new StringBuilder("Local diagnostics only. Review before sharing.\n"
                + "Home-directory prefixes are shown as ~; URL queries/fragments and secret "
                + "fields are redacted.\n\n");
        for (StoredLog log : logs) {
            if (text.length() >= MAX_FILE_BYTES) {
                text.append("\n[viewer output truncated]\n");
                break;
            }
            text.append(mapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(log.record())).append("\n\n");
        }
        return text.length() <= MAX_FILE_BYTES ? text.toString()
                : text.substring(0, MAX_FILE_BYTES) + "\n[viewer output truncated]\n";
    }

    public Path defaultDirectory() {
        if (injectedDirectory != null) return injectedDirectory;
        return registry.resolveSibling(registry.getFileName() + ".launcher-logs");
    }

    public final class Collector implements OperationProgressListener {
        private final List<Path> potentialRoots;
        private final List<ProgressEntry> progress = new ArrayList<>();
        private OperationProgress first;
        private OperationProgress latest;
        private boolean progressTruncated;

        private Collector(List<Path> potentialRoots) {
            this.potentialRoots = potentialRoots;
        }

        @Override
        public synchronized void onProgress(OperationProgress event) {
            if (first == null) first = event;
            latest = event;
            ProgressEntry entry = new ProgressEntry(event.timestamp().toString(),
                    event.phase().name(), event.completed(), event.total(), event.unit().name(),
                    SanitizedErrors.text(event.detail()), event.outcome().name(),
                    event.cancellationAllowed(), event.cancellationReason() == null ? null
                    : SanitizedErrors.text(event.cancellationReason()));
            if (progress.size() == MAX_PROGRESS) {
                progress.removeFirst();
                progressTruncated = true;
            }
            progress.add(entry);
        }

        public synchronized Path persist(Throwable error) throws IOException {
            if (first == null || latest == null || latest.outcome() == OperationOutcome.RUNNING) {
                throw new IOException("operation log cannot be saved before a terminal event");
            }
            List<ProgressEntry> retained = new ArrayList<>(progress);
            StoredRecord record;
            byte[] bytes;
            do {
                record = record(retained, error);
                bytes = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(record);
                if (bytes.length <= MAX_FILE_BYTES) break;
                if (retained.size() <= 1) {
                    throw new IOException("terminal operation log exceeds the 1 MiB safety limit");
                }
                retained.removeFirst();
                progressTruncated = true;
            } while (true);
            Path directory = requireDirectory(potentialRoots, true);
            Path target = directory.resolve("operation-" + first.operationId() + ".json");
            Path staging = directory.resolve(".new-" + first.operationId());
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(staging, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("operation log path already exists");
            }
            boolean stagingOwned = false;
            try {
                Files.write(staging, bytes, StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
                stagingOwned = true;
                restrictFile(staging);
                try {
                    Files.move(staging, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException e) {
                    throw new IOException("atomic operation-log publication is unsupported", e);
                }
                stagingOwned = false;
                retain(directory);
                return target;
            } finally {
                if (stagingOwned) Files.deleteIfExists(staging);
            }
        }

        private StoredRecord record(List<ProgressEntry> retained, Throwable error) {
            return new StoredRecord(SCHEMA, binding(), first.operationId().toString(),
                    first.timestamp().toString(), latest.timestamp().toString(),
                    first.operationType().name(), latest.phase().name(), latest.outcome().name(),
                    buildVersion(), progressTruncated, List.copyOf(retained),
                    SanitizedErrors.persistent(error));
        }
    }

    private Path requireDirectory(List<Path> potentialRoots, boolean create) throws IOException {
        RegistryData data = readRegistryForPlacement();
        Path directory = defaultDirectory();
        Path parent = directory.getParent();
        if (parent == null) throw new IOException("operation log directory has no parent");
        for (InstallationRecord record : data.installations()) {
            rejectOverlap(directory, Path.of(record.canonicalRoot()),
                    "operation log directory overlaps a registered installation");
        }
        for (Path potential : potentialRoots) {
            rejectOverlap(directory, potential,
                    "operation log directory overlaps the potential installation destination");
            Path metadata = registry.resolveSibling(registry.getFileName() + ".metadata");
            rejectOverlap(potential, metadata,
                    "potential installation destination overlaps launcher sidecar metadata");
            validatePotentialRoot(potential);
        }
        try {
            StrictPathSafety.requireDirectory(parent, "operation log parent");
        } catch (NoSuchFileException missing) {
            if (!create) return null;
            Path grandparent = parent.getParent();
            if (grandparent == null) {
                throw new IOException("operation log parent cannot be created safely", missing);
            }
            StrictPathSafety.requireDirectory(grandparent, "operation log parent ancestor");
            Files.createDirectory(parent);
            StrictPathSafety.requireDirectory(parent, "operation log parent");
            restrictDirectory(parent);
        }
        try {
            StrictPathSafety.requireDirectory(directory, "operation log directory");
            return directory;
        } catch (NoSuchFileException missing) {
            if (!create) return null;
        }
        try {
            Files.createDirectory(directory);
        } catch (FileAlreadyExistsException ignored) {
            // Validate a racing object below.
        }
        StrictPathSafety.requireDirectory(directory, "operation log directory");
        restrictDirectory(directory);
        return directory;
    }

    private RegistryData readRegistryForPlacement() throws IOException {
        try {
            BasicFileAttributes attrs = Files.readAttributes(registry, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isRegularFile() || attrs.isSymbolicLink() || attrs.isOther()) {
                throw new IOException("registry has an unexpected type; operation logs were not used");
            }
            return registries.read(registry);
        } catch (NoSuchFileException missing) {
            return new RegistryData(RegistryStore.SCHEMA, null, List.of());
        }
    }

    private void retain(Path directory) throws IOException {
        List<StoredLog> logs = new ArrayList<>();
        for (Path file : entries(directory)) {
            StoredLog log = readOwned(file);
            if (log != null) logs.add(log);
        }
        logs.sort(Comparator.comparing(log -> log.record().endedAt()));
        int remove = logs.size() - MAX_LOGS;
        for (int index = 0; index < remove; index++) {
            Files.delete(logs.get(index).path());
        }
    }

    private List<Path> entries(Path directory) throws IOException {
        try (var stream = Files.list(directory)) {
            return stream.toList();
        }
    }

    private StoredLog readOwned(Path file) throws IOException {
        var matcher = NAME.matcher(file.getFileName().toString());
        if (!matcher.matches()) return null;
        BasicFileAttributes attrs;
        try {
            attrs = Files.readAttributes(file, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException missing) {
            return null;
        }
        if (!attrs.isRegularFile() || attrs.isSymbolicLink() || attrs.isOther()
                || attrs.size() <= 0 || attrs.size() > MAX_FILE_BYTES) return null;
        StoredRecord record;
        try {
            BasicFileAttributes before = attrs;
            byte[] bytes;
            try (var input = Files.newInputStream(file, StandardOpenOption.READ,
                    LinkOption.NOFOLLOW_LINKS)) {
                bytes = input.readNBytes(MAX_FILE_BYTES + 1);
            }
            BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (bytes.length > MAX_FILE_BYTES || before.size() != after.size()
                    || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || !java.util.Objects.equals(before.fileKey(), after.fileKey())) {
                return null;
            }
            record = mapper.readValue(bytes, StoredRecord.class);
        } catch (IOException | RuntimeException invalid) {
            return null;
        }
        if (record.schemaVersion() != SCHEMA || !binding().equals(record.registryBinding())
                || !matcher.group(1).equals(record.operationId())
                || record.startedAt() == null || record.endedAt() == null
                || record.operationType() == null || record.phase() == null
                || record.outcome() == null || record.buildVersion() == null
                || record.progress() == null
                || !Set.of("SUCCEEDED", "CANCELLED", "FAILED").contains(record.outcome())) {
            return null;
        }
        try {
            UUID.fromString(record.operationId());
            Instant.parse(record.startedAt());
            Instant.parse(record.endedAt());
        } catch (IllegalArgumentException invalid) {
            return null;
        }
        return new StoredLog(file, record);
    }

    private String binding() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(registry.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java runtime lacks SHA-256", impossible);
        }
    }

    private static void rejectOverlap(Path first, Path second, String message)
            throws IOException {
        Path normalizedFirst = first.toAbsolutePath().normalize();
        Path normalizedSecond = second.toAbsolutePath().normalize();
        if (normalizedFirst.equals(normalizedSecond)
                || normalizedFirst.startsWith(normalizedSecond)
                || normalizedSecond.startsWith(normalizedFirst)) {
            throw new IOException(message + ": " + normalizedSecond);
        }
    }

    private static void validatePotentialRoot(Path potential) throws IOException {
        Path normalized = potential.toAbsolutePath().normalize();
        try {
            BasicFileAttributes attrs = Files.readAttributes(normalized,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isDirectory() || attrs.isSymbolicLink() || attrs.isOther()) {
                throw new IOException("potential installation root has an unsafe existing type: "
                        + normalized);
            }
            StrictPathSafety.requireDirectory(normalized, "potential installation root");
        } catch (NoSuchFileException missing) {
            Path parent = normalized.getParent();
            if (parent == null) {
                throw new IOException("potential installation root has no safe parent", missing);
            }
            StrictPathSafety.requireDirectory(parent, "potential installation parent");
        }
    }

    private static void restrictDirectory(Path directory) throws IOException {
        if (Files.getFileStore(directory)
                .supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(directory, OWNER_DIRECTORY);
        }
    }

    private static void restrictFile(Path file) throws IOException {
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(file, OWNER_FILE);
        }
    }

    private static String buildVersion() {
        String version = OperationLogStore.class.getPackage().getImplementationVersion();
        return version == null || version.isBlank() ? "development-build" : version;
    }

    public record StoredLog(Path path, StoredRecord record) {
    }

    public record StoredRecord(
            int schemaVersion,
            String registryBinding,
            String operationId,
            String startedAt,
            String endedAt,
            String operationType,
            String phase,
            String outcome,
            String buildVersion,
            boolean progressTruncated,
            List<ProgressEntry> progress,
            String errorDetails) {
    }

    public record ProgressEntry(
            String time,
            String phase,
            long completed,
            long total,
            String unit,
            String detail,
            String outcome,
            boolean cancellationAllowed,
            String cancellationReason) {
    }
}
