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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class DirectProcessRunnerTest {
    @TempDir Path temp;

    private List<String> command(Class<?> fixture, String... arguments) {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")
                        ? "java.exe" : "java").toString());
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(fixture.getName());
        command.addAll(List.of(arguments));
        return command;
    }

    private List<String> waitingCommand() {
        return command(RootCoordinatorProcessFixture.class, "wait",
                temp.resolve("ready.txt").toString(), temp.resolve("stop.txt").toString());
    }

    private static void assertStopped(ProcessIdentity child) {
        assertNotNull(child);
        assertFalse(ProcessHandle.of(child.pid()).filter(handle -> child.equals(ProcessIdentity.of(handle)))
                        .map(ProcessHandle::isAlive).orElse(false),
                "the runner returned with its child still alive");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void interruptionTerminatesAndReapsTheChildBeforeReturning(boolean inheritIo) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<ProcessIdentity> child = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        Thread worker = Thread.ofPlatform().unstarted(() -> {
            try {
                new DirectProcessRunner().runTracked(waitingCommand(), temp, Duration.ofMinutes(1),
                        inheritIo, identity -> {
                            child.set(identity);
                            started.countDown();
                        });
                failure.set(new IllegalStateException("interrupted execution returned normally"));
            } catch (IOException | InterruptedException | RuntimeException error) {
                failure.set(error);
            }
        });
        worker.start();
        try {
            assertTrue(started.await(10, TimeUnit.SECONDS));
            worker.interrupt();
            worker.join(10_000);
            assertFalse(worker.isAlive(), "interrupted execution did not finish cleanup");
            assertInstanceOf(InterruptedException.class, failure.get());
            assertStopped(child.get());
        } finally {
            worker.interrupt();
            if (child.get() != null) ProcessHandle.of(child.get().pid())
                    .filter(handle -> child.get().equals(ProcessIdentity.of(handle)))
                    .ifPresent(ProcessHandle::destroyForcibly);
            worker.join(10_000);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void timeoutTerminatesAndReapsTheChildAndKeepsItsResultContract(boolean inheritIo) throws Exception {
        AtomicReference<ProcessIdentity> child = new AtomicReference<>();
        var result = new DirectProcessRunner().runTracked(waitingCommand(), temp, Duration.ofMillis(250),
                inheritIo, child::set);
        assertTrue(result.timedOut());
        assertEquals(-1, result.exitCode());
        assertStopped(child.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedStartCallbackAlsoCleansUpItsChildAndPreservesInterruption(boolean interrupt) {
        AtomicReference<ProcessIdentity> child = new AtomicReference<>();
        IllegalStateException expected = new IllegalStateException("callback failed");
        boolean previouslyInterrupted = Thread.interrupted();
        try {
            var failure = assertThrows(IllegalStateException.class, () -> new DirectProcessRunner()
                    .runTracked(waitingCommand(), temp, Duration.ofMinutes(1), false, identity -> {
                        child.set(identity);
                        if (interrupt) Thread.currentThread().interrupt();
                        throw expected;
                    }));
            assertSame(expected, failure);
            assertEquals(interrupt, Thread.currentThread().isInterrupted());
            assertStopped(child.get());
        } finally {
            Thread.interrupted();
            if (previouslyInterrupted) Thread.currentThread().interrupt();
        }
    }

    @Test void normalCompletionCapturesBothPipesAndPreservesTheExitCode() throws Exception {
        var result = new DirectProcessRunner().run(command(OutputFixture.class, "small"),
                temp, Duration.ofSeconds(10), false);
        assertFalse(result.timedOut());
        assertEquals(7, result.exitCode());
        assertTrue(result.output().contains("stdout"));
        assertTrue(result.output().contains("stderr"));
    }

    @Test void outputBeyondTheLimitIsDrainedWithoutBlockingTheChild() throws Exception {
        var result = new DirectProcessRunner().run(command(OutputFixture.class, "large"),
                temp, Duration.ofSeconds(10), false);
        assertFalse(result.timedOut());
        assertEquals(7, result.exitCode());
        assertEquals(64 * 1024, result.output().length());
    }

    public static final class OutputFixture {
        public static void main(String[] args) {
            if (args[0].equals("large")) {
                System.out.print("o".repeat(128 * 1024));
                System.err.print("e".repeat(128 * 1024));
            } else {
                System.out.println("stdout");
                System.err.println("stderr");
            }
            System.exit(7);
        }
    }
}
