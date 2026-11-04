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

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaunchSignalsTest {
    private static final Instant NOW = Instant.parse("2026-05-01T12:00:00Z");

    @TempDir
    Path temp;

    private LaunchSignals signals(Duration timeout) {
        return new LaunchSignals(temp.resolve("signals"), Clock.fixed(NOW, ZoneOffset.UTC), timeout);
    }

    @Test
    void allocateNamesAFreshFileInTheSignalFolderWithoutCreatingIt() throws Exception {
        LaunchSignals signals = signals(Duration.ofSeconds(5));

        Path first = signals.allocate();
        Path second = signals.allocate();

        assertEquals(signals.directory(), first.getParent());
        assertTrue(Files.isDirectory(signals.directory()));
        assertTrue(first.getFileName().toString().endsWith(".ready"));
        assertFalse(Files.exists(first));
        assertNotEquals(first, second);
    }

    @Test
    void readyFilePropertyGoesBeforeTheClassPathAndMainClass() {
        List<String> command = List.of("java", "-Xmx2048m", "-cp", "MegaMek.jar", "megamek.MegaMek");
        Path file = Path.of("signals", "a.ready").toAbsolutePath();

        assertEquals(List.of("java", "-Xmx2048m", "-D" + LaunchSignals.READY_FILE_PROPERTY + "=" + file,
                "-cp", "MegaMek.jar", "megamek.MegaMek"), LaunchSignals.withReadyFile(command, file));
        assertThrows(IllegalArgumentException.class,
                () -> LaunchSignals.withReadyFile(List.of("java", "megamek.MegaMek"), file));
    }

    @Test
    void cleanupRemovesOnlySignalFilesOlderThanADay() throws Exception {
        LaunchSignals signals = signals(Duration.ofSeconds(5));
        Path directory = Files.createDirectories(signals.directory());
        Path stale = Files.createFile(directory.resolve("stale.ready"));
        Files.setLastModifiedTime(stale, FileTime.from(NOW.minus(Duration.ofDays(2))));
        Path live = Files.createFile(directory.resolve("live.ready"));
        Files.setLastModifiedTime(live, FileTime.from(NOW.minus(Duration.ofHours(1))));
        Path unrelated = Files.createFile(directory.resolve("notes.txt"));
        Files.setLastModifiedTime(unrelated, FileTime.from(NOW.minus(Duration.ofDays(9))));

        signals.allocate();

        assertFalse(Files.exists(stale));
        assertTrue(Files.exists(live), "another launch may still be waiting for a recent file");
        assertTrue(Files.exists(unrelated));
    }

    @Test
    void watchReportsOnceWhenTheFileAppearsAndDeletesIt() throws Exception {
        LaunchSignals signals = signals(Duration.ofSeconds(30));
        Path file = signals.allocate();
        CountDownLatch ready = new CountDownLatch(1);
        AtomicInteger reports = new AtomicInteger();

        try (LaunchSignals.Watch ignored = signals.watch(file, () -> {
            reports.incrementAndGet();
            ready.countDown();
        })) {
            Files.createFile(file);
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            awaitDeleted(file);
        }

        assertEquals(1, reports.get());
    }

    @Test
    void watchReportsAfterTheTimeoutWhenTheFileNeverAppears() throws Exception {
        LaunchSignals signals = signals(Duration.ofMillis(200));
        CountDownLatch ready = new CountDownLatch(1);

        try (LaunchSignals.Watch ignored = signals.watch(signals.allocate(), ready::countDown)) {
            assertTrue(ready.await(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void closingTheWatchStopsReportingAndDeletesALateFile() throws Exception {
        LaunchSignals signals = signals(Duration.ofMillis(300));
        Path file = signals.allocate();
        AtomicInteger reports = new AtomicInteger();

        LaunchSignals.Watch watch = signals.watch(file, reports::incrementAndGet);
        watch.close();
        Files.createFile(file);
        watch.close();
        Thread.sleep(600);

        assertEquals(0, reports.get());
        assertFalse(Files.exists(file));
    }

    @Test
    void aFailingObserverDoesNotEscape() {
        LaunchSignals.report(() -> {
            throw new IllegalStateException("presentation failed");
        });
    }

    private static void awaitDeleted(Path file) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (Files.exists(file) && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertFalse(Files.exists(file));
    }
}
