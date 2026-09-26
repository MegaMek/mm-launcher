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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RootCoordinatorTest {
    @TempDir Path temp;

    @Test
    void independentInstancesSerializeRootAndPendingNamespaceBlocksLaunch() throws Exception {
        Path root = Files.createDirectory(temp.resolve("app"));
        Path coordination = temp.resolve("coordination");
        RootCoordinator first = new RootCoordinator(coordination);
        RootCoordinator second = new RootCoordinator(coordination);
        try (RootCoordinator.Lease held = first.acquire(root, false)) {
            IOException busy = assertThrows(IOException.class,
                    () -> second.acquire(root, false));
            assertTrue(busy.getMessage().contains("another launch"));
        }

        Files.createDirectory(root.resolve(RootCoordinator.UPDATE_NAMESPACE));
        try (RootCoordinator.Lease lease = second.acquire(root, false)) {
            IOException pending = assertThrows(IOException.class, lease::requireNoPendingUpdate);
            assertTrue(pending.getMessage().contains("recover"));
        }
    }

    @Test
    void crashStyleLiveProcessIdentityRemainsAConservativeBlocker() throws Exception {
        Path root = Files.createDirectory(temp.resolve("live-app"));
        Path coordination = temp.resolve("coordination-live");
        RootCoordinator coordinator = new RootCoordinator(coordination);
        try (RootCoordinator.Lease lease = coordinator.acquire(root, false)) {
            lease.markLaunchStarting();
            lease.markChild(ProcessIdentity.of(ProcessHandle.current()));
            // Deliberately do not clear: this models launcher termination while a tracked child
            // identity is still live. Closing only releases the OS gate.
        }
        IOException live = assertThrows(IOException.class,
                () -> new RootCoordinator(coordination).acquire(root, false));
        assertTrue(live.getMessage().contains("still running"));
        IOException stillLiveWithAcknowledgement = assertThrows(IOException.class,
                () -> new RootCoordinator(coordination).acquire(root, true));
        assertTrue(stillLiveWithAcknowledgement.getMessage().contains("still running"));
    }

    @Test
    void separateJvmsHoldOsLockAndStartingRemainsUnknownUntilExplicitAcknowledgement()
            throws Exception {
        Path root = Files.createDirectory(temp.resolve("process-app"));
        Path coordination = temp.resolve("process-coordination");
        Path lockReady = temp.resolve("lock-ready");
        Path lockStop = temp.resolve("lock-stop");
        Process holder = startFixture("hold-lock", coordination.toString(), root.toString(),
                lockReady.toString(), lockStop.toString());
        try {
            awaitFile(lockReady);
            IOException busy = assertThrows(IOException.class,
                    () -> new RootCoordinator(coordination).acquire(root, false));
            assertTrue(busy.getMessage().contains("another launch"));
        } finally {
            stopFixture(holder, lockStop);
        }
        try (RootCoordinator.Lease ignored =
                     new RootCoordinator(coordination).acquire(root, false)) {
            // Releasing the separate process makes the real OS lock immediately reusable.
        }

        Path childPid = temp.resolve("starting-child-pid");
        Path childReady = temp.resolve("starting-child-ready");
        Path childStop = temp.resolve("starting-child-stop");
        Process parent = startFixture("starting-parent", coordination.toString(), root.toString(),
                childPid.toString(), childReady.toString(), childStop.toString());
        ProcessHandle child = null;
        try {
            assertTrue(parent.waitFor(8, TimeUnit.SECONDS), "launcher proxy did not exit");
            assertTrue(parent.exitValue() == 0, "launcher proxy failed");
            awaitFile(childPid);
            child = ProcessHandle.of(Long.parseLong(Files.readString(childPid))).orElseThrow();
            assertTrue(child.isAlive(), "benign child must outlive its launcher proxy");

            IOException liveUnknown = assertThrows(IOException.class,
                    () -> new RootCoordinator(coordination).acquire(root, false));
            assertTrue(liveUnknown.getMessage().contains("unknown child state"));

            Files.writeString(childStop, "stop");
            awaitExit(child);
            IOException deadStillUnknown = assertThrows(IOException.class,
                    () -> new RootCoordinator(coordination).acquire(root, false));
            assertTrue(deadStillUnknown.getMessage().contains("unknown child state"),
                    "dead STARTING parent/child is not proof that no other child was spawned");

            try (RootCoordinator.Lease ignored =
                         new RootCoordinator(coordination).acquire(root, true)) {
                // Explicit close-all acknowledgement is the only STARTING reset.
            }
            try (RootCoordinator.Lease ignored =
                         new RootCoordinator(coordination).acquire(root, false)) {
                // Marker was reset.
            }
        } finally {
            stopFixture(parent, temp.resolve("unused-parent-stop"));
            if (child == null && Files.exists(childPid)) {
                child = ProcessHandle.of(Long.parseLong(Files.readString(childPid))).orElse(null);
            }
            if (child != null && child.isAlive()) {
                Files.writeString(childStop, "stop");
                try {
                    child.onExit().get(5, TimeUnit.SECONDS);
                } catch (java.util.concurrent.TimeoutException e) {
                    child.destroyForcibly();
                    child.onExit().get(5, TimeUnit.SECONDS);
                }
            }
        }
    }

    @Test
    void runningChildBlocksEvenAcknowledgementAndDeadChildIsReclaimed() throws Exception {
        Path root = Files.createDirectory(temp.resolve("running-app"));
        Path coordination = temp.resolve("running-coordination");
        Path ready = temp.resolve("running-ready");
        Path stop = temp.resolve("running-stop");
        Process childProcess = startFixture("wait", ready.toString(), stop.toString());
        try {
            awaitFile(ready);
            ProcessHandle child = childProcess.toHandle();
            ProcessIdentity identity = ProcessIdentity.of(child);
            assertTrue(identity.startedAt() != null, "fixture child start identity is required");
            try (RootCoordinator.Lease lease =
                         new RootCoordinator(coordination).acquire(root, false)) {
                lease.markLaunchStarting();
                lease.markChild(identity);
            }
            IOException blocked = assertThrows(IOException.class,
                    () -> new RootCoordinator(coordination).acquire(root, true));
            assertTrue(blocked.getMessage().contains("still running"));

            Files.writeString(stop, "stop");
            assertTrue(childProcess.waitFor(8, TimeUnit.SECONDS), "child did not stop");
            try (RootCoordinator.Lease ignored =
                         new RootCoordinator(coordination).acquire(root, false)) {
                // A dead, verified RUNNING identity is safely reclaimed.
            }
        } finally {
            stopFixture(childProcess, stop);
        }
    }

    @Test
    void genuineAbsenceIsAllowedButUnexpectedPendingAndMarkerTypesFailClosed()
            throws Exception {
        Path root = Files.createDirectory(temp.resolve("types-app"));
        Path coordination = temp.resolve("types-coordination");
        RootCoordinator coordinator = new RootCoordinator(coordination);
        assertFalse(coordinator.pending(root));
        try (RootCoordinator.Lease lease = coordinator.acquire(root, false)) {
            lease.requireNoPendingUpdate();
        }

        Path namespace = root.resolve(RootCoordinator.UPDATE_NAMESPACE);
        Files.writeString(namespace, "not a directory");
        assertThrows(IOException.class, () -> coordinator.pending(root));
        try (RootCoordinator.Lease lease = coordinator.acquire(root, false)) {
            assertThrows(IOException.class, lease::requireNoPendingUpdate);
        }
        Files.delete(namespace);

        Path outside = Files.createDirectory(temp.resolve("outside-pending"));
        createDirectoryLink(namespace, outside);
        assertThrows(IOException.class, () -> coordinator.pending(root));
        Files.delete(namespace);

        // Create the root key's lock file, then substitute an invalid marker object.
        try (RootCoordinator.Lease ignored = coordinator.acquire(root, false)) {
        }
        Path lock;
        try (var entries = Files.list(coordination)) {
            lock = entries.filter(path -> path.getFileName().toString().endsWith(".lock"))
                    .findFirst().orElseThrow();
        }
        Path marker = lock.resolveSibling(lock.getFileName().toString()
                .replace(".lock", ".launch.json"));
        Files.createDirectory(marker);
        assertThrows(IOException.class, () -> coordinator.acquire(root, true));
    }

    @Test
    void indeterminatePendingAndMarkerAttributesAreNeverTreatedAsAbsent() throws Exception {
        Path root = Files.createDirectory(temp.resolve("acl-app"));
        Path coordination = temp.resolve("acl-coordination");
        RootCoordinator coordinator = new RootCoordinator(coordination);
        Path namespace = Files.createDirectory(root.resolve(RootCoordinator.UPDATE_NAMESPACE));
        RootCoordinator deniedPending = coordinatorWithDeniedPath(coordination, namespace);
        assertThrows(AccessDeniedException.class, () -> deniedPending.pending(root));
        try (RootCoordinator.Lease lease = deniedPending.acquire(root, false)) {
            assertThrows(AccessDeniedException.class, lease::requireNoPendingUpdate);
        }
        Files.delete(namespace);

        Path marker;
        try (RootCoordinator.Lease lease = coordinator.acquire(root, false)) {
            lease.markLaunchStarting();
        }
        try (var entries = Files.list(coordination)) {
            marker = entries.filter(path -> path.getFileName().toString()
                            .endsWith(".launch.json"))
                    .findFirst().orElseThrow();
        }
        RootCoordinator deniedMarker = coordinatorWithDeniedPath(coordination, marker);
        assertThrows(AccessDeniedException.class, () -> deniedMarker.acquire(root, true));
        assertTrue(Files.exists(marker, LinkOption.NOFOLLOW_LINKS));
        try (RootCoordinator.Lease ignored = coordinator.acquire(root, true)) {
            // Restore and explicitly clear the conservative STARTING marker.
        }
    }

    private static Process startFixture(String... args) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                        ? "java.exe" : "java").toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(RootCoordinatorProcessFixture.class.getName());
        command.addAll(List.of(args));
        return new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
    }

    private static void awaitFile(Path path) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(path)) return;
            Thread.sleep(20);
        }
        throw new AssertionError("timed out waiting for fixture signal " + path);
    }

    private static void awaitExit(ProcessHandle process) throws Exception {
        process.onExit().get(8, TimeUnit.SECONDS);
        assertFalse(process.isAlive());
    }

    private static void stopFixture(Process process, Path stop) throws Exception {
        if (!process.isAlive()) return;
        Files.writeString(stop, "stop");
        if (!process.waitFor(5, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "fixture process did not terminate");
        }
    }

    private static void createDirectoryLink(Path link, Path target) throws Exception {
        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            Process process = new ProcessBuilder(System.getenv("ComSpec"), "/d", "/c", "mklink",
                    "/J", link.toString(), target.toString()).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes());
            if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IOException("could not create test junction: " + output);
            }
        } else {
            Files.createSymbolicLink(link, target);
        }
    }

    private static RootCoordinator coordinatorWithDeniedPath(Path coordination, Path denied) {
        return new RootCoordinator(coordination, path -> {
            if (path.equals(denied)) throw new AccessDeniedException(path.toString());
            return Files.readAttributes(path, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
        });
    }
}
