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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class DirectProcessRunner implements ProcessRunner {
    private static final int OUTPUT_LIMIT = 64 * 1024;

    @Override
    public Result run(List<String> command, Path workingDirectory, Duration timeout,
                      boolean inheritIo) throws IOException, InterruptedException {
        return runTracked(command, workingDirectory, timeout, inheritIo, ignored -> {});
    }

    @Override
    public Result runTracked(List<String> command, Path workingDirectory, Duration timeout,
                             boolean inheritIo,
                             java.util.function.Consumer<ProcessIdentity> started)
            throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(command).directory(workingDirectory.toFile());
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        if (inheritIo) builder.inheritIO();
        Process process = builder.start();
        try (ProcessResources resources = new ProcessResources(process)) {
            if (!inheritIo) {
                resources.stdout = Thread.ofVirtual().start(() -> copyBounded(process.getInputStream(), captured));
                resources.stderr = Thread.ofVirtual().start(() -> copyBounded(process.getErrorStream(), captured));
            }
            started.accept(ProcessIdentity.of(process.toHandle()));
            boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                process.waitFor();
            }
            if (resources.stdout != null) resources.stdout.join();
            if (resources.stderr != null) resources.stderr.join();
            return new Result(completed ? process.exitValue() : -1,
                    captured.toString(java.nio.charset.StandardCharsets.UTF_8), !completed);
        }
    }

    private static final class ProcessResources implements AutoCloseable {
        private final Process process;
        private Thread stdout;
        private Thread stderr;

        private ProcessResources(Process process) {
            this.process = process;
        }

        @Override
        public void close() throws IOException {
            boolean interrupted = Thread.interrupted();
            try {
                if (process.isAlive()) process.destroyForcibly();
                while (process.isAlive()) {
                    try {
                        process.waitFor();
                    } catch (InterruptedException error) {
                        interrupted = true;
                    }
                }
                try (var input = process.getInputStream();
                     var error = process.getErrorStream();
                     var output = process.getOutputStream()) {
                    // Close the pipes before joining collectors on an interrupted or failed run.
                } finally {
                    interrupted |= join(stdout);
                    interrupted |= join(stderr);
                }
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        private static boolean join(Thread thread) {
            boolean interrupted = false;
            if (thread != null) {
                while (thread.isAlive()) {
                    try {
                        thread.join();
                    } catch (InterruptedException error) {
                        interrupted = true;
                    }
                }
            }
            return interrupted;
        }
    }

    private static void copyBounded(InputStream input, ByteArrayOutputStream output) {
        try (input) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                synchronized (output) {
                    int remaining = OUTPUT_LIMIT - output.size();
                    if (remaining > 0) output.write(buffer, 0, Math.min(count, remaining));
                }
            }
        } catch (IOException ignored) {
            // Process termination may close its streams. Exit/timeout remains authoritative.
        }
    }
}
