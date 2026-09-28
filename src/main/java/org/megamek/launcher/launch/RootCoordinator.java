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

package org.megamek.launcher.launch;

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
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.HexFormat;

/**
 * Per-OS-user, per-canonical-root coordination shared by CLI/GUI processes and registries.
 * State lives outside installations, so merely launching an imported copy does not modify it.
 */
public final class RootCoordinator {
    public static final String CLOSE_ALL_CONFIRMATION = "CLOSE-ALL-SUITE-APPS-AND-APPLY";
    public static final String UPDATE_NAMESPACE = ".mm-launcher-update";
    private static final int SCHEMA = 1;
    private final Path directory;
    private final boolean persistent;
    private final PathProbe pathProbe;
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    public RootCoordinator() {
        this(Path.of(System.getProperty("mm.launcher.coordination.dir",
                Path.of(System.getProperty("user.home"), ".mm-launcher", "coordination-v1")
                        .toString())), true, RootCoordinator::readAttributesNoFollow);
    }

    public RootCoordinator(Path directory) {
        this(directory, true, RootCoordinator::readAttributesNoFollow);
    }

    RootCoordinator(Path directory, PathProbe pathProbe) {
        this(directory, true, pathProbe);
    }

    private RootCoordinator(Path directory, boolean persistent, PathProbe pathProbe) {
        this.directory = directory.toAbsolutePath().normalize();
        this.persistent = persistent;
        this.pathProbe = pathProbe;
    }

    /** Fake-process test seam: no cross-process claim is made for injected ProcessRunner tests. */
    static RootCoordinator inMemory() {
        return new RootCoordinator(Path.of(System.getProperty("java.io.tmpdir")), false,
                RootCoordinator::readAttributesNoFollow);
    }

    boolean persistent() {
        return persistent;
    }

    public Lease acquire(Path root, boolean closeAllAcknowledged) throws IOException {
        Path canonical = StrictPathSafety.requireDirectory(root, "coordinated application root");
        return acquireCanonical(canonical, closeAllAcknowledged, false);
    }

    /** Launches may coexist with tracked children, but never with an unverified start. */
    public Lease acquireForLaunch(Path root) throws IOException {
        return acquireCanonical(StrictPathSafety.requireDirectory(root,
                "coordinated application root"), false, true);
    }

    /**
     * Recovery-only gate for a transaction which may have removed an empty root. The exact
     * canonical path is derived from its real parent; the directory is not created here.
     */
    public Lease acquireForRecovery(Path root, boolean closeAllAcknowledged) throws IOException {
        Path absolute = root.toAbsolutePath().normalize();
        if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            return acquireCanonical(StrictPathSafety.requireDirectory(
                    absolute, "coordinated recovery root"), closeAllAcknowledged, false);
        }
        Path parent = StrictPathSafety.requireDirectory(absolute.getParent(),
                "coordinated application parent");
        Path canonical = parent.resolve(absolute.getFileName());
        if (!canonical.equals(absolute)) {
            throw new IOException("recovery root is not canonical");
        }
        return acquireCanonical(canonical, closeAllAcknowledged, false);
    }

    private Lease acquireCanonical(Path canonical, boolean closeAllAcknowledged, boolean launch)
            throws IOException {
        if (!persistent) return new Lease(canonical, null, null, null, false);
        ensureDirectory();
        String key = hash(canonical.toString());
        Path lockPath = directory.resolve(key + ".lock");
        rejectUnexpected(lockPath, true);
        FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE,
                StandardOpenOption.WRITE);
        FileLock lock;
        try {
            lock = channel.tryLock();
            if (lock == null) throw new IOException("another launch, update, or recovery is active");
        } catch (OverlappingFileLockException e) {
            channel.close();
            throw new IOException("another launch, update, or recovery is active", e);
        } catch (IOException e) {
            channel.close();
            throw e;
        }
        Lease lease = new Lease(canonical, channel, lock, directory.resolve(key + ".launch.json"),
                true);
        try {
            lease.resolvePriorMarker(closeAllAcknowledged, launch);
            return lease;
        } catch (IOException e) {
            lease.close();
            throw e;
        }
    }

    public boolean pending(Path root) throws IOException {
        Path canonical = StrictPathSafety.requireDirectory(root, "application root");
        Path namespace = canonical.resolve(UPDATE_NAMESPACE);
        if (!existsNoFollow(namespace)) return false;
        StrictPathSafety.requireDirectory(namespace, "real update namespace");
        return true;
    }

    private void ensureDirectory() throws IOException {
        Path parent = directory.getParent();
        if (parent == null) throw new IOException("coordination directory has no parent");
        ensureOwnedDirectory(parent);
        if (Files.notExists(directory, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(directory);
        StrictPathSafety.requireDirectory(directory, "coordination directory");
    }

    private static void ensureOwnedDirectory(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            StrictPathSafety.requireDirectory(path, "coordination parent");
            return;
        }
        Path parent = path.getParent();
        if (parent == null) throw new IOException("coordination parent does not exist");
        ensureOwnedDirectory(parent);
        Files.createDirectory(path);
        StrictPathSafety.requireDirectory(path, "coordination parent");
    }

    private void rejectUnexpected(Path path, boolean allowMissing) throws IOException {
        if (!existsNoFollow(path)) {
            if (allowMissing) return;
            throw new IOException("coordination state is absent: " + path);
        }
        StrictPathSafety.requireFile(path, "coordination state");
    }

    /**
     * Returns false only for a positively identified missing entry. Access, I/O, and malformed
     * path failures remain blockers instead of being converted to success-shaped absence.
     */
    private boolean existsNoFollow(Path path) throws IOException {
        try {
            pathProbe.read(path);
            return true;
        } catch (NoSuchFileException e) {
            return false;
        }
    }

    private static BasicFileAttributes readAttributesNoFollow(Path path) throws IOException {
        return Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public final class Lease implements AutoCloseable {
        private final Path root;
        private final FileChannel channel;
        private final FileLock lock;
        private final Path marker;
        private final boolean writesMarkers;
        private boolean markerOwned;
        private String launchId;
        private List<LaunchEntry> entries = new ArrayList<>();

        private Lease(Path root, FileChannel channel, FileLock lock, Path marker,
                      boolean writesMarkers) {
            this.root = root;
            this.channel = channel;
            this.lock = lock;
            this.marker = marker;
            this.writesMarkers = writesMarkers;
        }

        public void requireNoPendingUpdate() throws IOException {
            Path namespace = root.resolve(UPDATE_NAMESPACE);
            if (existsNoFollow(namespace)) {
                StrictPathSafety.requireDirectory(namespace, "real update namespace");
                throw new IOException("an interrupted update is pending; recover it before launch");
            }
        }

        public void markLaunchStarting() throws IOException {
            if (!writesMarkers) return;
            launchId = UUID.randomUUID().toString();
            List<LaunchEntry> next = new ArrayList<>(entries);
            next.add(new LaunchEntry(launchId, ProcessIdentity.of(ProcessHandle.current()),
                    "STARTING"));
            writeEntries(next);
            markerOwned = true;
        }

        public void markChild(ProcessIdentity child) throws IOException {
            if (!writesMarkers || child == null) return;
            if (!markerOwned) throw new IOException("no launch start owns this marker");
            List<LaunchEntry> next = new ArrayList<>(entries);
            next.replaceAll(entry -> entry.id().equals(launchId)
                    ? new LaunchEntry(launchId, child, "RUNNING") : entry);
            writeEntries(next);
        }

        public void clearLaunchMarker() throws IOException {
            if (markerOwned) clearLaunchMarker(launchId);
        }

        public String launchId() {
            return launchId;
        }

        /** Remove only the completed child, under a newly acquired root gate if necessary. */
        public void clearLaunchMarker(String id) throws IOException {
            if (!writesMarkers) return;
            if (id == null) throw new IOException("launch entry is missing");
            // A concurrent lease may already have reclaimed the verified dead child.
            if (entries.stream().noneMatch(entry -> entry.id().equals(id))) return;
            List<LaunchEntry> next = new ArrayList<>(entries);
            next.removeIf(entry -> entry.id().equals(id));
            writeEntries(next);
            if (id.equals(launchId)) markerOwned = false;
        }

        private void resolvePriorMarker(boolean closeAllAcknowledged, boolean launch) throws IOException {
            if (!writesMarkers) return;
            Path staging = marker.resolveSibling(marker.getFileName() + ".new");
            if (existsNoFollow(staging)) {
                rejectUnexpected(staging, false);
                if (!closeAllAcknowledged) {
                    throw new IOException("interrupted launch-marker publication requires "
                            + "explicit close-all recovery/unblock confirmation");
                }
                Files.delete(staging);
            }
            if (!existsNoFollow(marker)) return;
            rejectUnexpected(marker, false);
            final List<LaunchEntry> prior;
            try {
                var tree = mapper.readTree(Files.readAllBytes(marker));
                if (tree.path("schemaVersion").asInt(-1) == SCHEMA) {
                    LaunchMarker legacy = mapper.treeToValue(tree, LaunchMarker.class);
                    if (!root.toString().equals(legacy.canonicalRoot())) {
                        throw new IOException("marker root mismatch");
                    }
                    prior = List.of(new LaunchEntry("legacy", legacy.process(), legacy.phase()));
                } else {
                    LaunchLedger ledger = mapper.treeToValue(tree, LaunchLedger.class);
                    if (ledger.schemaVersion() != 2
                            || !root.toString().equals(ledger.canonicalRoot())
                            || ledger.entries() == null || ledger.entries().isEmpty()) {
                        throw new IOException("invalid launch ledger");
                    }
                    prior = ledger.entries();
                }
            } catch (IOException | RuntimeException e) {
                if (!closeAllAcknowledged) {
                    throw new IOException("unreadable launch marker blocks this root; close all "
                            + "suite applications and use explicit recovery/unblock confirmation", e);
                }
                Files.delete(marker);
                return;
            }
            if (prior.stream().anyMatch(entry -> entry == null || entry.id() == null
                    || entry.id().isBlank() || entry.process() == null
                    || entry.process().pid() <= 0 || entry.phase() == null
                    || !java.util.Set.of("STARTING", "RUNNING").contains(entry.phase()))
                    || prior.stream().map(LaunchEntry::id).distinct().count() != prior.size()) {
                if (!closeAllAcknowledged) {
                    throw new IOException("unknown launch marker blocks this root; close all suite "
                            + "applications and use explicit recovery/unblock confirmation");
                }
                Files.delete(marker);
                return;
            }
            // Validate all entries before pruning anything: acknowledgement cannot mask a
            // verified live child elsewhere in the same ledger.
            List<LaunchEntry> retained = new ArrayList<>();
            for (LaunchEntry entry : prior) {
            if ("STARTING".equals(entry.phase())) {
                if (!closeAllAcknowledged) {
                    throw new IOException("an interrupted launch start has unknown child state; "
                            + "close all suite applications and use explicit recovery/unblock "
                            + "confirmation");
                }
                continue;
            }
            ProcessHandle process = ProcessHandle.of(entry.process().pid()).orElse(null);
            if (process == null || !process.isAlive()) {
                continue;
            }
            String actualStart = process.info().startInstant().map(Instant::toString).orElse(null);
            if (entry.process().startedAt() != null && actualStart != null
                    && !entry.process().startedAt().equals(actualStart)) {
                continue; // verified PID reuse
            }
            if (entry.process().startedAt() == null || actualStart == null) {
                if (closeAllAcknowledged) {
                    continue;
                }
                throw new IOException("tracked process identity cannot be verified; close all suite "
                        + "applications, then use explicit recovery/unblock confirmation");
            }
            if (!launch) throw new IOException("a launcher-started suite application is still running (PID "
                    + entry.process().pid() + "); close it before update or recovery");
            retained.add(entry);
            }
            if (!retained.equals(prior)) writeEntries(retained);
            else entries = retained;
        }

        private void writeEntries(List<LaunchEntry> next) throws IOException {
            if (next.isEmpty()) {
                Files.deleteIfExists(marker);
                entries = next;
                return;
            }
            writeMarker(new LaunchLedger(2, root.toString(), next));
            entries = next;
        }

        private void writeMarker(Object value) throws IOException {
            Path staging = marker.resolveSibling(marker.getFileName() + ".new");
            if (existsNoFollow(staging)) {
                rejectUnexpected(staging, false);
                throw new IOException("interrupted launch-marker publication requires unblock");
            }
            byte[] bytes = mapper.writeValueAsBytes(value);
            try (FileChannel output = FileChannel.open(staging, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE)) {
                output.write(ByteBuffer.wrap(bytes));
                output.force(true);
            }
            try {
                Files.move(staging, marker, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.deleteIfExists(staging);
                throw new IOException("atomic launch-marker publication is unsupported", e);
            }
        }

        @Override
        public void close() throws IOException {
            if (!writesMarkers) return;
            IOException failure = null;
            try {
                if (lock != null && lock.isValid()) lock.release();
            } catch (IOException e) {
                failure = e;
            }
            try {
                if (channel != null) channel.close();
            } catch (IOException e) {
                if (failure == null) failure = e;
                else failure.addSuppressed(e);
            }
            if (failure != null) throw failure;
        }
    }

    private record LaunchMarker(int schemaVersion, String canonicalRoot,
                                ProcessIdentity process, String phase) {
    }
    private record LaunchEntry(String id, ProcessIdentity process, String phase) {}
    private record LaunchLedger(int schemaVersion, String canonicalRoot,
                                List<LaunchEntry> entries) {}

    @FunctionalInterface
    interface PathProbe {
        BasicFileAttributes read(Path path) throws IOException;
    }
}
