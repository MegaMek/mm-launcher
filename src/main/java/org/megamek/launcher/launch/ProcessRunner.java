package org.megamek.launcher.launch;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.Consumer;

public interface ProcessRunner {
    Result run(List<String> command, Path workingDirectory, Duration timeout, boolean inheritIo)
            throws IOException, InterruptedException;

    default Result runTracked(List<String> command, Path workingDirectory, Duration timeout,
                              boolean inheritIo, Consumer<ProcessIdentity> started)
            throws IOException, InterruptedException {
        return run(command, workingDirectory, timeout, inheritIo);
    }

    record Result(int exitCode, String output, boolean timedOut) {}
}
