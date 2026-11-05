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

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * How a launched application tells the launcher its first window is on screen (see
 * {@code docs/launch-ready-contract.md}). An application whose jar manifest carries
 * {@link #READY_MANIFEST_ATTRIBUTE}{@code : 1} is started with the system property
 * {@link #READY_FILE_PROPERTY} naming a file that does not exist yet; it creates that file, empty,
 * once its first window shows. The launcher watches for it, deletes it as soon as it appears or
 * the launch ends, and removes any file a crash left behind once it is a day old. All signal files
 * live in one launcher-owned folder.
 */
public final class LaunchSignals {
    /** The system property naming the file to create; its presence also means "started by the launcher". */
    public static final String READY_FILE_PROPERTY = "megamek.launcher.readyFile";
    /** The jar manifest attribute that opts an application in, with the value {@code 1}. */
    public static final String READY_MANIFEST_ATTRIBUTE = "MegaMek-Launcher-Ready";
    /** How long the launcher waits for the file before treating the application as on screen anyway. */
    public static final Duration READY_TIMEOUT = Duration.ofSeconds(30);
    static final Duration STALE_AFTER = Duration.ofDays(1);
    static final Duration POLL_INTERVAL = Duration.ofMillis(100);
    private static final String SUFFIX = ".ready";

    private final Path directory;
    private final Clock clock;
    private final Duration timeout;

    public LaunchSignals() {
        this(Path.of(System.getProperty("mm.launcher.signals.dir",
                Path.of(System.getProperty("user.home"), ".mm-launcher", "launch-signals")
                        .toString())));
    }

    public LaunchSignals(Path directory) {
        this(directory, Clock.systemUTC(), READY_TIMEOUT);
    }

    LaunchSignals(Path directory, Clock clock, Duration timeout) {
        this.directory = directory.toAbsolutePath().normalize();
        this.clock = clock;
        this.timeout = timeout;
    }

    Path directory() {
        return directory;
    }

    /** A fresh signal file path for one launch, after removing stale files. The file is not created. */
    Path allocate() throws IOException {
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory)) {
            throw new IOException("launch signal folder must not be a link: " + directory);
        }
        removeStale();
        return directory.resolve(UUID.randomUUID() + SUFFIX);
    }

    /** Deletes signal files older than {@link #STALE_AFTER}; newer ones may belong to another launcher's launch. */
    void removeStale() {
        Instant cutoff = clock.instant().minus(STALE_AFTER);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*" + SUFFIX)) {
            for (Path file : files) {
                try {
                    BasicFileAttributes attributes = Files.readAttributes(file,
                            BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (attributes.lastModifiedTime().toInstant().isBefore(cutoff)) {
                        Files.deleteIfExists(file);
                    }
                } catch (IOException ignored) {
                    // Another launcher may be deleting the same file; the next launch retries.
                }
            }
        } catch (IOException ignored) {
            // Cleanup is best effort and never blocks a launch.
        }
    }

    /** {@code command} with the ready-file property added before the class path and main class. */
    static List<String> withReadyFile(List<String> command, Path readyFile) {
        int classPath = command.size() - 3;
        if (classPath < 1 || !command.get(classPath).equals("-cp")) {
            throw new IllegalArgumentException("launch command does not end in -cp <path> <main class>");
        }
        List<String> result = new ArrayList<>(command);
        result.add(classPath, "-D" + READY_FILE_PROPERTY + "=" + readyFile);
        return List.copyOf(result);
    }

    /**
     * Starts watching for {@code readyFile} on a virtual thread, reporting it to {@code observer} once,
     * or after the timeout. Closing the watch stops it and deletes the file.
     */
    Watch watch(Path readyFile, LaunchObserver observer) {
        return new Watch(readyFile, observer, timeout);
    }

    /** One launch's wait for its ready file. */
    static final class Watch implements AutoCloseable {
        private final Path file;
        private final LaunchObserver observer;
        private final AtomicBoolean settled = new AtomicBoolean();
        private final Thread thread;

        private Watch(Path file, LaunchObserver observer, Duration timeout) {
            this.file = file;
            this.observer = observer;
            long deadline = System.nanoTime() + timeout.toNanos();
            thread = Thread.ofVirtual().name("launch-ready-watch").start(() -> {
                try {
                    while (!settled.get()) {
                        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                            deleteQuietly();
                            report();
                            return;
                        }
                        if (System.nanoTime() - deadline >= 0) {
                            report();
                            return;
                        }
                        Thread.sleep(POLL_INTERVAL);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        private void report() {
            if (settled.compareAndSet(false, true)) {
                LaunchSignals.report(observer);
            }
        }

        private void deleteQuietly() {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // A file left behind is removed as stale by a later launch.
            }
        }

        @Override
        public void close() {
            settled.set(true);
            thread.interrupt();
            try {
                thread.join(POLL_INTERVAL.toMillis() * 5);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            deleteQuietly();
        }
    }

    /** Tells {@code observer} without letting its failure disturb the launch. */
    static void report(LaunchObserver observer) {
        try {
            observer.ready();
        } catch (RuntimeException ignored) {
            // The observer only drives presentation; the launch carries on regardless.
        }
    }
}
