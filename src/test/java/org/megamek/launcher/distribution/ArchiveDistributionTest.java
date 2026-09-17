package org.megamek.launcher.distribution;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("archive")
class ArchiveDistributionTest {
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(30);
    private static final Set<PosixFilePermission> EXECUTABLE_PERMISSIONS =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE);

    @TempDir
    Path temp;

    @Test
    void archiveHasOneSafeFixedRootAndExactRuntimeJars() throws Exception {
        ArchiveView archive = readArchive();
        String root = expectedRoot();
        assertFalse(archive.entries().isEmpty());

        Set<String> caseFolded = new HashSet<>();
        for (ArchiveEntry entry : archive.entries()) {
            String name = entry.name();
            assertTrue(name.startsWith(root + "/") || name.equals(root + "/"),
                    () -> "entry outside fixed root: " + name);
            assertFalse(name.startsWith("/") || name.startsWith("\\")
                            || name.matches("^[A-Za-z]:.*"),
                    () -> "absolute archive entry: " + name);
            assertFalse(name.contains("\\"),
                    () -> "backslash archive entry: " + name);
            assertFalse(Arrays.asList(name.split("/")).contains(".."),
                    () -> "parent traversal archive entry: " + name);
            assertTrue(caseFolded.add(name.toLowerCase(Locale.ROOT)),
                    () -> "case-colliding or duplicate archive entry: " + name);
            assertFalse(entry.symbolicLink(), () -> "archive link is not allowed: " + name);

            Set<String> components = Arrays.stream(name.split("/"))
                    .map(part -> part.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toSet());
            assertTrue(Collections.disjoint(components, Set.of(
                            ".git", "build", "src", "data", "credentials", ".env",
                            "id_rsa", "jre", "jdk")),
                    () -> "forbidden source, state, credential, or runtime path: " + name);
        }

        String libRoot = switch (kind()) {
            case "mac" -> root + "/Contents/app/lib/";
            case "windows", "linux" -> root + "/lib/";
            default -> throw new IllegalStateException("unknown archive kind");
        };
        List<String> actualJars = archive.entries().stream()
                .map(ArchiveEntry::name)
                .filter(name -> name.startsWith(libRoot) && name.endsWith(".jar"))
                .map(name -> name.substring(libRoot.length()))
                .sorted()
                .toList();
        assertEquals(expectedJars(), actualJars,
                "archive must contain exactly the resolved runtime and application JARs");

        String noticeRoot = switch (kind()) {
            case "mac" -> root + "/Contents/app/";
            default -> root + "/";
        };
        assertTrue(archive.contains(noticeRoot + "README.txt"));
        assertTrue(archive.contains(noticeRoot + "THIRD-PARTY-NOTICES.txt"));
        assertTrue(archive.entries().stream()
                        .anyMatch(entry -> entry.name().startsWith(
                                noticeRoot + "third-party-licenses/") && !entry.directory()),
                "extracted dependency license/notice material must be present");
    }

    @Test
    void archiveContainsOnlyItsDeclaredOsEntrypointAndMetadata() throws Exception {
        ArchiveView archive = readArchive();
        String root = expectedRoot();
        switch (kind()) {
            case "windows" -> {
                assertTrue(archive.contains(root + "/MM Launcher.exe"));
                assertTrue(archive.entries().stream().noneMatch(entry ->
                        entry.name().endsWith("/mm-launcher")
                                || entry.name().contains(".app/Contents/MacOS/")));
            }
            case "linux" -> {
                ArchiveEntry launcher = archive.required(root + "/mm-launcher");
                assertEquals(0755, launcher.unixMode() & 0777);
                assertScript(archive.bytes(launcher));
                assertTrue(archive.entries().stream().noneMatch(entry ->
                        entry.name().toLowerCase(Locale.ROOT).endsWith(".exe")
                                || entry.name().contains(".app/")));
            }
            case "mac" -> {
                ArchiveEntry launcher = archive.required(
                        root + "/Contents/MacOS/MM Launcher");
                assertEquals(0755, launcher.unixMode() & 0777);
                assertScript(archive.bytes(launcher));
                ArchiveEntry plistEntry = archive.required(root + "/Contents/Info.plist");
                String plist = new String(archive.bytes(plistEntry), StandardCharsets.UTF_8);
                assertEquals("APPL", plistValue(plist, "CFBundlePackageType"));
                assertEquals("MM Launcher", plistValue(plist, "CFBundleExecutable"));
                assertEquals("org.megamek.launcher",
                        plistValue(plist, "CFBundleIdentifier"));
                assertFalse(plist.contains("@BUNDLE_VERSION@"));
                assertTrue(archive.entries().stream().noneMatch(entry ->
                        entry.name().toLowerCase(Locale.ROOT).endsWith(".exe")
                                || entry.name().equals(root + "/mm-launcher")));
            }
            default -> throw new IllegalStateException("unknown archive kind");
        }
    }

    @Test
    void checksumNamesAndMatchesOnlyTheDeclaredArchive() throws Exception {
        Path archive = archivePath();
        String checksumLine = Files.readString(checksumPath(), StandardCharsets.US_ASCII).strip();
        Matcher matcher = Pattern.compile("^([0-9a-f]{64})  ([^/\\\\]+)$").matcher(checksumLine);
        assertTrue(matcher.matches(), "checksum must use sha256sum-compatible exact-file format");
        assertEquals(archive.getFileName().toString(), matcher.group(2));
        assertEquals(sha256(archive), matcher.group(1));
    }

    @Test
    void matchingNativeEntrypointRunsFromMovedUnicodePathAndUnrelatedCwd() throws Exception {
        Assumptions.assumeTrue(hostMatchesKind(),
                "native execution intentionally runs only on the matching CI/local host");
        // Launch4j's classic header uses the Windows ANSI APIs. Exercise a
        // non-ASCII Latin path representable on the qualified Windows runner;
        // the POSIX launchers exercise a wider Unicode path.
        String nonAscii = kind().equals("windows") ? "café" : "Ω";
        Path extraction = temp.resolve("moved " + nonAscii + " [space] & package");
        extractArchive(extraction);
        Path root = extraction.resolve(expectedRoot());
        Path registry = temp.resolve("isolated state " + nonAscii + " &")
                .resolve("registry.json").toAbsolutePath();
        Files.createDirectories(registry.getParent());
        Path report = temp.resolve("startup result " + nonAscii + " &.txt").toAbsolutePath();
        Path cwd = temp.resolve("unrelated cwd " + nonAscii + " &");
        Files.createDirectories(cwd);
        Path userHome = temp.resolve("isolated user");
        Files.createDirectories(userHome);

        Path entrypoint = switch (kind()) {
            case "windows" -> root.resolve("MM Launcher.exe");
            case "linux" -> root.resolve("mm-launcher");
            case "mac" -> root.resolve("Contents/MacOS/MM Launcher");
            default -> throw new IllegalStateException("unknown archive kind");
        };
        ProcessResult result = run(
                List.of(entrypoint.toString(), "--startup-check",
                        "--registry", registry.toString(), "--report", report.toString()),
                cwd, Map.of(
                        "JAVA_HOME", System.getProperty("java.home"),
                        "HOME", userHome.toString(),
                        "USERPROFILE", userHome.toString(),
                        "LOCALAPPDATA", userHome.resolve("Local App Data").toString(),
                        "APPDATA", userHome.resolve("App Data").toString()
                ));

        assertEquals(0, result.exitCode(), result.output());
        String startupReport = Files.readString(report, StandardCharsets.UTF_8);
        assertTrue(startupReport.contains("MM-LAUNCHER-STARTUP-OK"));
        assertTrue(startupReport.contains("packaged=true"),
                "startup check must prove the extracted JAR, not checkout classes, was loaded");
        assertTrue(startupReport.contains("codeSource=mm-launcher-"));
        assertTrue(startupReport.contains("registryOverride=validated"));
        assertFalse(Files.exists(registry), "startup check must not create registry state");
    }

    @Test
    void posixBootstrapRejectsMissingAndOldJavaFixturesWithoutTouchingSystemJava()
            throws Exception {
        Assumptions.assumeTrue(hostMatchesKind()
                        && (kind().equals("linux") || kind().equals("mac")),
                "fixture executes only on matching POSIX archive hosts");
        Path extraction = temp.resolve("java fixture package Ω &");
        extractArchive(extraction);
        Path root = extraction.resolve(expectedRoot());
        Path entrypoint = kind().equals("mac")
                ? root.resolve("Contents/MacOS/MM Launcher")
                : root.resolve("mm-launcher");
        Path cwd = temp.resolve("fixture cwd");
        Files.createDirectories(cwd);

        Path missingHome = temp.resolve("missing Java home").toAbsolutePath();
        ProcessResult missing = run(List.of(entrypoint.toString(), "--startup-check"), cwd,
                Map.of("JAVA_HOME", missingHome.toString()));
        assertEquals(2, missing.exitCode(), missing.output());
        assertTrue(missing.output().contains("JAVA_HOME"));

        Path oldHome = temp.resolve("old Java Ω &");
        Path fakeJava = oldHome.resolve("bin/java");
        Files.createDirectories(fakeJava.getParent());
        Files.writeString(fakeJava, "#!/bin/sh\n"
                + "printf '%s\\n' '    java.version = 17.0.12' >&2\n"
                + "exit 0\n", StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(fakeJava, EXECUTABLE_PERMISSIONS);
        ProcessResult old = run(List.of(entrypoint.toString(), "--startup-check"), cwd,
                Map.of("JAVA_HOME", oldHome.toString()));
        assertEquals(2, old.exitCode(), old.output());
        assertTrue(old.output().contains("requires Java 21 or newer; found Java 17"));
    }

    private static void assertScript(byte[] bytes) {
        String script = new String(bytes, StandardCharsets.UTF_8);
        assertTrue(script.startsWith("#!/bin/sh\n"));
        assertFalse(script.contains("\r"), "POSIX bootstrap must use LF line endings");
        assertFalse(script.contains("eval"));
        assertTrue(script.contains("\"$JAVA_COMMAND\""));
        assertTrue(script.contains("\"$@\""));
    }

    private static String plistValue(String plist, String key) {
        Pattern pattern = Pattern.compile("<key>" + Pattern.quote(key)
                + "</key>\\s*<string>([^<]+)</string>");
        Matcher matcher = pattern.matcher(plist);
        assertTrue(matcher.find(), () -> "missing plist value for " + key);
        return matcher.group(1);
    }

    private ArchiveView readArchive() throws IOException {
        return kind().equals("linux") ? readTar(archivePath()) : readZip(archivePath());
    }

    private static ArchiveView readZip(Path path) throws IOException {
        List<ArchiveEntry> entries = new ArrayList<>();
        try (ZipFile zip = ZipFile.builder().setPath(path).get()) {
            var enumeration = zip.getEntries();
            while (enumeration.hasMoreElements()) {
                ZipArchiveEntry entry = enumeration.nextElement();
                byte[] bytes = entry.isDirectory()
                        ? new byte[0] : readAll(zip.getInputStream(entry));
                entries.add(new ArchiveEntry(entry.getName(), entry.isDirectory(),
                        entry.isUnixSymlink(), entry.getUnixMode(), bytes));
            }
        }
        return new ArchiveView(entries);
    }

    private static ArchiveView readTar(Path path) throws IOException {
        List<ArchiveEntry> entries = new ArrayList<>();
        try (InputStream input = Files.newInputStream(path);
             GzipCompressorInputStream gzip = new GzipCompressorInputStream(input);
             TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                byte[] bytes = entry.isDirectory() ? new byte[0] : readAll(tar);
                entries.add(new ArchiveEntry(entry.getName(), entry.isDirectory(),
                        entry.isSymbolicLink() || entry.isLink(), entry.getMode(), bytes));
            }
        }
        return new ArchiveView(entries);
    }

    private void extractArchive(Path destination) throws IOException {
        Files.createDirectories(destination);
        ArchiveView archive = readArchive();
        for (ArchiveEntry entry : archive.entries()) {
            Path output = destination.resolve(entry.name()).normalize();
            assertTrue(output.startsWith(destination));
            if (entry.directory()) {
                Files.createDirectories(output);
            } else {
                Files.createDirectories(output.getParent());
                Files.copy(new ByteArrayInputStream(entry.bytes()), output);
                if (!isWindowsHost() && (entry.unixMode() & 0111) != 0) {
                    Files.setPosixFilePermissions(output, EXECUTABLE_PERMISSIONS);
                }
            }
        }
    }

    private static ProcessResult run(List<String> command, Path cwd, Map<String, String> additions)
            throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(cwd.toFile())
                .redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        environment.remove("JDK_JAVA_OPTIONS");
        environment.remove("JAVA_TOOL_OPTIONS");
        environment.remove("_JAVA_OPTIONS");
        environment.putAll(additions);
        Process process = builder.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = Thread.ofVirtual().start(() -> {
            try {
                process.getInputStream().transferTo(output);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
        if (!process.waitFor(PROCESS_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
            process.destroyForcibly();
            process.waitFor(10, TimeUnit.SECONDS);
            throw new AssertionError("owned fixture process timed out: " + command);
        }
        reader.join(Duration.ofSeconds(10));
        return new ProcessResult(process.exitValue(), output.toString(StandardCharsets.UTF_8));
    }

    private static byte[] readAll(InputStream input) throws IOException {
        return input.readAllBytes();
    }

    private static String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
        }
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    private static String kind() {
        return requiredProperty("archive.kind");
    }

    private static Path archivePath() {
        return Path.of(requiredProperty("archive.path"));
    }

    private static Path checksumPath() {
        return Path.of(requiredProperty("archive.checksum"));
    }

    private static List<String> expectedJars() {
        return Arrays.stream(requiredProperty("archive.expectedJars").split("\\|"))
                .sorted()
                .toList();
    }

    private static String expectedRoot() {
        return kind().equals("mac") ? "MM Launcher.app" : "MM Launcher";
    }

    private static String requiredProperty(String name) {
        return java.util.Objects.requireNonNull(System.getProperty(name),
                () -> "missing test property " + name);
    }

    private static boolean hostMatchesKind() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return switch (kind()) {
            case "windows" -> os.contains("win");
            case "mac" -> os.contains("mac");
            case "linux" -> os.contains("linux");
            default -> false;
        };
    }

    private static boolean isWindowsHost() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private record ArchiveEntry(
            String name,
            boolean directory,
            boolean symbolicLink,
            int unixMode,
            byte[] bytes
    ) {
    }

    private record ProcessResult(int exitCode, String output) {
    }

    private record ArchiveView(List<ArchiveEntry> entries) {
        private ArchiveView {
            entries = List.copyOf(entries);
        }

        boolean contains(String name) {
            return entries.stream().anyMatch(entry -> entry.name().equals(name));
        }

        ArchiveEntry required(String name) {
            ArchiveEntry result = entries.stream()
                    .filter(entry -> entry.name().equals(name))
                    .findFirst()
                    .orElse(null);
            assertNotNull(result, () -> "missing archive entry " + name);
            return result;
        }

        byte[] bytes(ArchiveEntry entry) {
            return entry.bytes();
        }
    }
}
