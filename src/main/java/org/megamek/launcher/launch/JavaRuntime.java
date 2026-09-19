package org.megamek.launcher.launch;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class JavaRuntime {
    private static final Pattern VERSION = Pattern.compile("version\\s+\"(?:1\\.)?(\\d+)");
    private final ProcessRunner runner;
    private final Supplier<String> javaHome;
    private final Supplier<String> osName;

    public JavaRuntime() {
        this(new DirectProcessRunner());
    }

    public JavaRuntime(ProcessRunner runner) {
        this(runner, () -> System.getProperty("java.home"),
                () -> System.getProperty("os.name", ""));
    }

    JavaRuntime(ProcessRunner runner, Supplier<String> javaHome, Supplier<String> osName) {
        this.runner = Objects.requireNonNull(runner);
        this.javaHome = Objects.requireNonNull(javaHome);
        this.osName = Objects.requireNonNull(osName);
    }

    public List<Path> candidates() {
        Set<Path> candidates = new LinkedHashSet<>();
        addCandidate(candidates, javaHome.get());
        addCandidate(candidates, System.getenv("JAVA_HOME"));
        return List.copyOf(candidates);
    }

    /**
     * Resolves only the Java runtime which is executing this launcher.  The normal first-install
     * path intentionally does not fall through to JAVA_HOME or to a runtime inherited from a
     * different registered copy.
     */
    public Path launcherJava() throws IOException {
        return currentExecutable();
    }

    /** Returns the canonical executable from this process's exact {@code java.home}. */
    public Path currentExecutable() throws IOException {
        String home = javaHome.get();
        if (home == null || home.isBlank()) {
            throw new IOException("the Java runtime running this launcher could not be located");
        }
        Path requested;
        try {
            requested = Path.of(home).toAbsolutePath().normalize()
                    .resolve("bin").resolve(isWindows() ? "java.exe" : "java");
        } catch (RuntimeException error) {
            throw new IOException("the Java runtime running this launcher has an invalid "
                    + "java.home", error);
        }
        return resolveExecutable(requested, "launcher Java");
    }

    /** Validates only the runtime executing MM Launcher and returns its feature version. */
    public int currentFeature() throws IOException, InterruptedException {
        Path executable = currentExecutable();
        return validate(executable, executable.getParent());
    }

    /**
     * Validates the exact launcher JVM for an imported copy and captures its file identity.
     * No environment or registry candidate is considered.
     */
    public CurrentJava validateCurrentExternal(Path applicationRoot)
            throws IOException, InterruptedException {
        return validateExternal(currentExecutable(), applicationRoot);
    }

    /**
     * Validates and captures an explicitly selected Java executable (or Java home) for a new
     * installation/import.  The captured file identity is rechecked by RegistryStore at publish
     * time.
     */
    public CurrentJava validateExternal(Path selected, Path applicationRoot)
            throws IOException, InterruptedException {
        Path root = applicationRoot.toAbsolutePath().normalize().toRealPath();
        Path executable = resolve(selected.toString());
        if (executable.startsWith(root)) {
            throw new IOException("selected Java is inside the application folder; choose an "
                    + "external Java 21+ runtime");
        }
        int feature = validate(executable, executable.getParent());
        return CurrentJava.capture(executable, feature);
    }

    public Path resolve(String selected) throws IOException {
        final Path selectedPath;
        try {
            selectedPath = Path.of(selected).toAbsolutePath().normalize();
        } catch (RuntimeException error) {
            throw new IOException("selected Java path is invalid", error);
        }
        Path path = selectedPath;
        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            path = path.resolve("bin").resolve(isWindows() ? "java.exe" : "java");
        }
        return resolveExecutable(path, "selected Java");
    }

    private Path resolveExecutable(Path requested, String label) throws IOException {
        Path path = requested.toAbsolutePath().normalize();
        String fileName = path.getFileName() == null ? "" : path.getFileName().toString();
        if (!(fileName.equals("java") || fileName.equalsIgnoreCase("java.exe"))) {
            throw new IOException("selected executable must be named java or java.exe: " + path);
        }
        rejectLinks(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IOException(label + " is not a regular executable: " + path);
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

    private void addCandidate(Set<Path> candidates, String home) {
        if (home == null || home.isBlank()) return;
        Path executable = Path.of(home).resolve("bin")
                .resolve(isWindows() ? "java.exe" : "java")
                .toAbsolutePath().normalize();
        if (Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)
                && !Files.isSymbolicLink(executable)) candidates.add(executable);
    }

    private boolean isWindows() {
        return osName.get().toLowerCase().contains("win");
    }

    private static void rejectLinks(Path path) throws IOException {
        Path current = path.getRoot();
        for (Path part : path) {
            current = current == null ? part : current.resolve(part);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) continue;
            BasicFileAttributes attributes = Files.readAttributes(current,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther()
                    || isWindowsReparsePoint(current)) {
                throw new IOException("Java path may not use links, reparse points, or special "
                        + "files: " + current);
            }
        }
    }

    private static boolean isWindowsReparsePoint(Path path) throws IOException {
        try {
            Object value = Files.getAttribute(path, "dos:attributes",
                    LinkOption.NOFOLLOW_LINKS);
            return value instanceof Integer attributes && (attributes & 0x400) != 0;
        } catch (UnsupportedOperationException | IllegalArgumentException ignored) {
            return false;
        }
    }

    /**
     * Unforgeable-by-callers evidence that a specific current-JVM executable passed Java 21+
     * validation. RegistryStore rechecks the file identity immediately before publication.
     */
    public static final class CurrentJava {
        private final Path executable;
        private final int feature;
        private final Object fileKey;
        private final long size;
        private final long modifiedMillis;

        private CurrentJava(Path executable, int feature, Object fileKey, long size,
                            long modifiedMillis) {
            this.executable = executable;
            this.feature = feature;
            this.fileKey = fileKey;
            this.size = size;
            this.modifiedMillis = modifiedMillis;
        }

        private static CurrentJava capture(Path executable, int feature) throws IOException {
            BasicFileAttributes attributes = Files.readAttributes(executable,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            return new CurrentJava(executable, feature, attributes.fileKey(), attributes.size(),
                    attributes.lastModifiedTime().toMillis());
        }

        public Path executable() {
            return executable;
        }

        public int feature() {
            return feature;
        }

        public boolean sameRuntime(CurrentJava other) {
            return other != null && executable.equals(other.executable)
                    && feature == other.feature
                    && sameIdentity(other.fileKey, other.size, other.modifiedMillis);
        }

        public Path requireUnchanged() throws IOException {
            Path canonical = executable.toAbsolutePath().normalize().toRealPath();
            if (!canonical.equals(executable) || feature < 21) {
                throw new IOException("validated launcher Java is no longer canonical or "
                        + "compatible");
            }
            BasicFileAttributes attributes = Files.readAttributes(canonical,
                    BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther()
                    || !attributes.isRegularFile()
                    || !sameIdentity(attributes.fileKey(), attributes.size(),
                    attributes.lastModifiedTime().toMillis())) {
                throw new IOException("validated launcher Java changed before registration");
            }
            return canonical;
        }

        private boolean sameIdentity(Object otherKey, long otherSize, long otherModified) {
            return Objects.equals(fileKey, otherKey) && size == otherSize
                    && modifiedMillis == otherModified;
        }
    }
}
