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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

/**
 * Separate-JVM fixture for proving operating-system locking and crash-window marker behavior.
 * It launches only copies of itself and communicates only through disposable files.
 */
public final class RootCoordinatorProcessFixture {
    private RootCoordinatorProcessFixture() {
    }

    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "hold-lock" -> holdLock(Path.of(args[1]), Path.of(args[2]),
                    Path.of(args[3]), Path.of(args[4]));
            case "starting-parent" -> startingParent(Path.of(args[1]), Path.of(args[2]),
                    Path.of(args[3]), Path.of(args[4]), Path.of(args[5]));
            case "wait" -> waitForStop(Path.of(args[1]), Path.of(args[2]));
            default -> throw new IllegalArgumentException("unknown fixture mode");
        }
    }

    private static void holdLock(Path coordination, Path root, Path ready, Path stop)
            throws Exception {
        try (RootCoordinator.Lease ignored =
                     new RootCoordinator(coordination).acquire(root, false)) {
            signal(ready, Long.toString(ProcessHandle.current().pid()));
            await(stop);
        }
    }

    private static void startingParent(Path coordination, Path root, Path childPid,
                                       Path childReady, Path childStop) throws Exception {
        try (RootCoordinator.Lease lease =
                     new RootCoordinator(coordination).acquire(root, false)) {
            lease.markLaunchStarting();
            Process child = new ProcessBuilder(javaCommand("wait", childReady.toString(),
                    childStop.toString()))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            signal(childPid, Long.toString(child.pid()));
            awaitFile(childReady, Duration.ofSeconds(8));
            // Deliberately exit without markChild/clearLaunchMarker: this is the exact crash
            // window after child creation but before durable RUNNING publication.
        }
    }

    private static void waitForStop(Path ready, Path stop) throws Exception {
        signal(ready, Long.toString(ProcessHandle.current().pid()));
        await(stop);
    }

    private static void signal(Path path, String value) throws Exception {
        Files.writeString(path, value);
    }

    private static void await(Path stop) throws Exception {
        while (!Files.exists(stop)) Thread.sleep(25);
    }

    private static void awaitFile(Path path, Duration timeout) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (Files.exists(path)) return;
            Thread.sleep(20);
        }
        throw new IllegalStateException("timed out waiting for child fixture");
    }

    private static String[] javaCommand(String... args) {
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(Locale.ROOT)
                        .contains("win") ? "java.exe" : "java").toString();
        String[] command = new String[4 + args.length];
        command[0] = java;
        command[1] = "-cp";
        command[2] = System.getProperty("java.class.path");
        command[3] = RootCoordinatorProcessFixture.class.getName();
        System.arraycopy(args, 0, command, 4, args.length);
        return command;
    }
}
