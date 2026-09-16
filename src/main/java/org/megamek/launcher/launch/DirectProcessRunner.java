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
        ProcessBuilder builder = new ProcessBuilder(command).directory(workingDirectory.toFile());
        Process process;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Thread stdout = null;
        Thread stderr = null;
        if (inheritIo) {
            builder.inheritIO();
            process = builder.start();
        } else {
            process = builder.start();
            stdout = Thread.ofVirtual().start(() -> copyBounded(process.getInputStream(), captured));
            stderr = Thread.ofVirtual().start(() -> copyBounded(process.getErrorStream(), captured));
        }
        boolean completed = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (!completed) {
            process.destroyForcibly();
            process.waitFor();
        }
        if (stdout != null) stdout.join();
        if (stderr != null) stderr.join();
        return new Result(completed ? process.exitValue() : -1,
                captured.toString(java.nio.charset.StandardCharsets.UTF_8), !completed);
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
