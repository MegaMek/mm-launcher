package org.megamek.launcher.launch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JavaRuntime {
    private static final Pattern VERSION = Pattern.compile("version\\s+\"(?:1\\.)?(\\d+)");
    private final ProcessRunner runner;

    public JavaRuntime() {
        this(new DirectProcessRunner());
    }

    public JavaRuntime(ProcessRunner runner) {
        this.runner = runner;
    }

    public List<Path> candidates() {
        Set<Path> candidates = new LinkedHashSet<>();
        addCandidate(candidates, System.getProperty("java.home"));
        addCandidate(candidates, System.getenv("JAVA_HOME"));
        return List.copyOf(candidates);
    }

    /**
     * Resolves only the Java runtime which is executing this launcher.  The normal first-install
     * path intentionally does not fall through to JAVA_HOME or to a runtime inherited from a
     * different registered copy.
     */
    public Path launcherJava() throws IOException {
        String home = System.getProperty("java.home");
        if (home == null || home.isBlank()) {
            throw new IOException("the Java runtime running this launcher could not be located");
        }
        return resolve(home);
    }

    public Path resolve(String selected) throws IOException {
        Path path = Path.of(selected).toAbsolutePath().normalize();
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            path = path.resolve("bin").resolve(isWindows() ? "java.exe" : "java");
        }
        String fileName = path.getFileName() == null ? "" : path.getFileName().toString();
        if (!(fileName.equals("java") || fileName.equalsIgnoreCase("java.exe"))) {
            throw new IOException("selected executable must be named java or java.exe: " + path);
        }
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IOException("selected Java is not a regular executable: " + path);
        }
        return path.toRealPath();
    }

    public int validate(Path executable, Path workingDirectory)
            throws IOException, InterruptedException {
        ProcessRunner.Result result = runner.run(List.of(executable.toString(), "-version"),
                workingDirectory, Duration.ofSeconds(10), false);
        if (result.timedOut()) throw new IOException("selected Java -version timed out");
        if (result.exitCode() != 0) {
            throw new IOException("selected Java -version failed with exit " + result.exitCode());
        }
        Matcher matcher = VERSION.matcher(result.output());
        if (!matcher.find()) {
            throw new IOException("selected Java returned an unrecognized version");
        }
        int feature = Integer.parseInt(matcher.group(1));
        if (feature < 21) {
            throw new IOException("selected Java " + feature + " is incompatible; Java 21+ required");
        }
        return feature;
    }

    private static void addCandidate(Set<Path> candidates, String home) {
        if (home == null || home.isBlank()) return;
        Path executable = Path.of(home).resolve("bin").resolve(isWindows() ? "java.exe" : "java")
                .toAbsolutePath().normalize();
        if (Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(executable)) candidates.add(executable);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
