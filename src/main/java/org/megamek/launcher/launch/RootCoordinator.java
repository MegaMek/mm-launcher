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

    public Lease acquire(Path root, boolean closeAllAcknowledged) throws IOException {
        Path canonical = StrictPathSafety.requireDirectory(root, "coordinated application root");
        return acquireCanonical(canonical, closeAllAcknowledged);
    }

    /**
     * Recovery-only gate for a transaction which may have removed an empty root. The exact
     * canonical path is derived from its real parent; the directory is not created here.
     */
    public Lease acquireForRecovery(Path root, boolean closeAllAcknowledged) throws IOException {
        Path absolute = root.toAbsolutePath().normalize();
        if (Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            return acquireCanonical(StrictPathSafety.requireDirectory(
                    absolute, "coordinated recovery root"), closeAllAcknowledged);
        }
        Path parent = StrictPathSafety.requireDirectory(absolute.getParent(),
                "coordinated application parent");
        Path canonical = parent.resolve(absolute.getFileName());
        if (!canonical.equals(absolute)) {
            throw new IOException("recovery root is not canonical");
        }
        return acquireCanonical(canonical, closeAllAcknowledged);
    }

    private Lease acquireCanonical(Path canonical, boolean closeAllAcknowledged)
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
            lease.resolvePriorMarker(closeAllAcknowledged);
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
            writeMarker(new LaunchMarker(SCHEMA, root.toString(),
                    ProcessIdentity.of(ProcessHandle.current()), "STARTING"));
            markerOwned = true;
        }

        public void markChild(ProcessIdentity child) throws IOException {
            if (!writesMarkers || child == null) return;
            writeMarker(new LaunchMarker(SCHEMA, root.toString(), child, "RUNNING"));
        }

        public void clearLaunchMarker() throws IOException {
            if (writesMarkers && markerOwned) {
                Files.deleteIfExists(marker);
                markerOwned = false;
            }
        }

        private void resolvePriorMarker(boolean closeAllAcknowledged) throws IOException {
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
            final LaunchMarker prior;
            try {
                prior = mapper.readValue(Files.readAllBytes(marker), LaunchMarker.class);
            } catch (IOException | RuntimeException e) {
                if (!closeAllAcknowledged) {
                    throw new IOException("unreadable launch marker blocks this root; close all "
                            + "suite applications and use explicit recovery/unblock confirmation", e);
                }
                Files.delete(marker);
                return;
            }
            if (prior.schemaVersion() != SCHEMA || !root.toString().equals(prior.canonicalRoot())
                    || prior.process() == null || prior.process().pid() <= 0
                    || prior.phase() == null
                    || !java.util.Set.of("STARTING", "RUNNING").contains(prior.phase())) {
                if (!closeAllAcknowledged) {
                    throw new IOException("unknown launch marker blocks this root; close all suite "
                            + "applications and use explicit recovery/unblock confirmation");
                }
                Files.delete(marker);
                return;
            }
            // STARTING records this launcher, not a child identity. Once publication can have
            // raced a launcher crash, neither a dead/reused parent PID nor the absence of a
            // visible child proves that no suite process was started.
            if ("STARTING".equals(prior.phase())) {
                if (!closeAllAcknowledged) {
                    throw new IOException("an interrupted launch start has unknown child state; "
                            + "close all suite applications and use explicit recovery/unblock "
                            + "confirmation");
                }
                Files.delete(marker);
                return;
            }
            ProcessHandle process = ProcessHandle.of(prior.process().pid()).orElse(null);
            if (process == null || !process.isAlive()) {
                Files.delete(marker);
                return;
            }
            String actualStart = process.info().startInstant().map(Instant::toString).orElse(null);
            if (prior.process().startedAt() != null && actualStart != null
                    && !prior.process().startedAt().equals(actualStart)) {
                Files.delete(marker); // PID was reused; this is demonstrably not the tracked child.
                return;
            }
            if (prior.process().startedAt() == null || actualStart == null) {
                if (closeAllAcknowledged) {
                    Files.delete(marker);
                    return;
                }
                throw new IOException("tracked process identity cannot be verified; close all suite "
                        + "applications, then use explicit recovery/unblock confirmation");
            }
            throw new IOException("a launcher-started suite application is still running (PID "
                    + prior.process().pid() + "); close it before launch, update, or recovery");
        }

        private void writeMarker(LaunchMarker value) throws IOException {
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

    @FunctionalInterface
    interface PathProbe {
        BasicFileAttributes read(Path path) throws IOException;
    }
}
