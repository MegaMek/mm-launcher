package org.megamek.launcher.launch;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public interface ProcessRunner {
    Result run(List<String> command, Path workingDirectory, Duration timeout, boolean inheritIo)
            throws IOException, InterruptedException;

    record Result(int exitCode, String output, boolean timedOut) {}
}
