package org.megamek.launcher.distribution;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
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
    private static final String ROOT = "MegaMek Launcher";
    private static final String APP = ROOT + "/MegaMek Launcher.app";
    private static final String LIB_ROOT = APP + "/Contents/app/lib/";
    private static final String LINUX_ENTRYPOINT = ROOT + "/mm-launcher";
    private static final String MAC_ENTRYPOINT =
            APP + "/Contents/MacOS/MegaMek Launcher";
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(30);
    private static final Set<PosixFilePermission> EXECUTABLE_PERMISSIONS =
            EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE, PosixFilePermission.GROUP_READ,
                    PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ,
                    PosixFilePermission.OTHERS_EXECUTE);

    @TempDir
    Path temp;

    @Test
    void archiveHasOneSafeFixedRootAndExactlyOneSharedRuntimePayload() throws Exception {
        ArchiveView archive = readArchive();
        assertFalse(archive.entries().isEmpty());

        Set<String> caseFolded = new HashSet<>();
        for (ArchiveEntry entry : archive.entries()) {
            String name = entry.name();
            assertTrue(name.startsWith(ROOT + "/") || name.equals(ROOT + "/"),
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
            assertFalse(entry.link(), () -> "archive link is not allowed: " + name);

            Set<String> components = Arrays.stream(name.split("/"))
                    .map(part -> part.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toSet());
            assertTrue(Collections.disjoint(components, Set.of(
                            ".git", "build", "src", "data", "credentials", ".env",
                            "id_rsa", "jre", "jdk")),
                    () -> "forbidden source, state, credential, or runtime path: " + name);
        }

        List<String> actualJarPaths = archive.entries().stream()
                .map(ArchiveEntry::name)
                .filter(name -> name.toLowerCase(Locale.ROOT).endsWith(".jar"))
                .sorted()
                .toList();
        List<String> expectedJarPaths = expectedJars().stream()
                .map(name -> LIB_ROOT + name)
                .sorted()
                .toList();
        assertEquals(expectedJarPaths, actualJarPaths,
                "every application/runtime JAR must occur once in the app-owned shared lib");
        assertTrue(archive.entries().stream().noneMatch(entry ->
                        entry.name().startsWith(ROOT + "/lib/")),
                "a duplicate root lib payload is forbidden");

        assertTrue(archive.contains(ROOT + "/README.txt"));
        assertTrue(archive.contains(ROOT + "/THIRD-PARTY-NOTICES.txt"));
        assertTrue(archive.entries().stream()
                        .anyMatch(entry -> entry.name().startsWith(
                                ROOT + "/third-party-licenses/") && !entry.directory()),
                "extracted dependency license/notice material must occur in one common place");
        assertFalse(archive.contains(APP + "/Contents/app/README.txt"),
                "common legal/readme material must not be duplicated in the app payload");
    }

    @Test
    void archiveContainsAllThreeEntrypointsWithPortableMetadataAndModes() throws Exception {
        ArchiveView archive = readArchive();

        ArchiveEntry windows = archive.required(ROOT + "/MegaMek Launcher.exe");
        byte[] windowsBytes = archive.bytes(windows);
        assertTrue(windowsBytes.length > 2
                        && windowsBytes[0] == 'M' && windowsBytes[1] == 'Z',
                "Windows entry point must be the Launch4j executable");

        ArchiveEntry linux = archive.required(LINUX_ENTRYPOINT);
        assertEquals(0755, linux.unixMode() & 0777);
        assertScript(archive.bytes(linux));
        String linuxScript = new String(archive.bytes(linux), StandardCharsets.UTF_8);
        assertTrue(linuxScript.contains(
                "SHARED_LIB=\"$APP_ROOT/MegaMek Launcher.app/Contents/app/lib\""));
        assertTrue(linuxScript.contains("-cp \"$SHARED_LIB/*\""));

        ArchiveEntry mac = archive.required(MAC_ENTRYPOINT);
        assertEquals(0755, mac.unixMode() & 0777);
        assertScript(archive.bytes(mac));
        String macScript = new String(archive.bytes(mac), StandardCharsets.UTF_8);
        assertTrue(macScript.contains("-cp \"$CONTENTS/app/lib/*\""));

        ArchiveEntry plistEntry = archive.required(APP + "/Contents/Info.plist");
        String plist = new String(archive.bytes(plistEntry), StandardCharsets.UTF_8);
        assertEquals("APPL", plistValue(plist, "CFBundlePackageType"));
        assertEquals("MegaMek Launcher", plistValue(plist, "CFBundleExecutable"));
        assertEquals("MegaMekLauncher.icns", plistValue(plist, "CFBundleIconFile"));
        archive.required(APP + "/Contents/Resources/MegaMekLauncher.icns");
        assertEquals("org.megamek.launcher", plistValue(plist, "CFBundleIdentifier"));
        assertFalse(plist.contains("@BUNDLE_VERSION@"));

        for (ArchiveEntry entry : archive.entries()) {
            int expectedMode = entry.directory()
                    || entry.name().equals(LINUX_ENTRYPOINT)
                    || entry.name().equals(MAC_ENTRYPOINT) ? 0755 : 0644;
            assertEquals(expectedMode, entry.unixMode() & 0777,
                    () -> "unexpected archive mode for " + entry.name());
        }
    }

    @Test
    void archiveAndChecksumNamesAreExactAndChecksumMatches() throws Exception {
        Path archive = archivePath();
        assertEquals(expectedArchiveName(), archive.getFileName().toString());
        assertEquals(expectedArchiveName() + ".sha256",
                checksumPath().getFileName().toString());

        String checksumLine = Files.readString(
                checksumPath(), StandardCharsets.US_ASCII).strip();
        Matcher matcher = Pattern.compile("^([0-9a-f]{64})  ([^/\\\\]+)$")
                .matcher(checksumLine);
        assertTrue(matcher.matches(),
                "checksum must use sha256sum-compatible exact-file format");
        assertEquals(archive.getFileName().toString(), matcher.group(2));
        assertEquals(sha256(archive), matcher.group(1));
    }

    @Test
    void bothPosixEntrypointsPassAvailableShSyntaxCheck() throws Exception {
        String shell = shellCommand();
        Assumptions.assumeTrue(commandIsAvailable(shell),
                "sh is unavailable; content and LF checks still ran");
        ArchiveView archive = readArchive();
        Path syntaxRoot = temp.resolve("syntax Ω & files");
        Files.createDirectories(syntaxRoot);
        Path linux = syntaxRoot.resolve("linux launcher");
        Path mac = syntaxRoot.resolve("mac launcher");
        Files.write(linux, archive.bytes(archive.required(LINUX_ENTRYPOINT)));
        Files.write(mac, archive.bytes(archive.required(MAC_ENTRYPOINT)));

        ProcessResult result = run(List.of(shell, "-n", linux.toString(), mac.toString()),
                temp, Map.of());
        assertEquals(0, result.exitCode(), result.output());
    }

    @Test
    void matchingNativeEntrypointRunsFromMovedUnicodePathAndUnrelatedCwd() throws Exception {
        Assumptions.assumeTrue(hostMatchesPlatform(),
                "native execution intentionally runs only on the selected host platform");
        // Launch4j's classic header uses the Windows ANSI APIs. Exercise a
        // non-ASCII Latin path representable on the qualified Windows runner;
        // the POSIX launchers exercise a wider Unicode path.
        String nonAscii = platform().equals("windows") ? "café" : "Ω";
        Path extraction = temp.resolve("moved " + nonAscii + " [space] & package");
        extractArchive(extraction);
        Path root = extraction.resolve(ROOT);
        Path registry = temp.resolve("isolated state " + nonAscii + " &")
                .resolve("registry.json").toAbsolutePath();
        Files.createDirectories(registry.getParent());
        Path report = temp.resolve("startup result " + nonAscii + " &.txt").toAbsolutePath();
        Path cwd = temp.resolve("unrelated cwd " + nonAscii + " &");
        Files.createDirectories(cwd);
        Path userHome = temp.resolve("isolated user");
        Files.createDirectories(userHome);

        Path entrypoint = switch (platform()) {
            case "windows" -> root.resolve("MegaMek Launcher.exe");
            case "linux" -> root.resolve("mm-launcher");
            case "mac" -> root.resolve("MegaMek Launcher.app/Contents/MacOS/MegaMek Launcher");
            default -> throw new IllegalStateException("unknown verification platform");
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
        assertTrue(startupReport.contains("build=" + expectedBuildIdentifier()));
        assertTrue(startupReport.contains("packaged=true"),
                "startup check must prove the extracted JAR, not checkout classes, was loaded");
        assertTrue(startupReport.contains("codeSource=mm-launcher-"));
        assertTrue(startupReport.contains("registryOverride=validated"));
        assertFalse(Files.exists(registry), "startup check must not create registry state");
    }

    @Test
    void posixBootstrapRejectsMissingAndOldJavaFixturesWithoutTouchingSystemJava()
            throws Exception {
        Assumptions.assumeTrue(hostMatchesPlatform()
                        && (platform().equals("linux") || platform().equals("mac")),
                "fixture executes only on a selected matching POSIX archive host");
        Path extraction = temp.resolve("java fixture package Ω &");
        extractArchive(extraction);
        Path root = extraction.resolve(ROOT);
        Path entrypoint = platform().equals("mac")
                ? root.resolve("MegaMek Launcher.app/Contents/MacOS/MegaMek Launcher")
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
        assertTrue(script.contains("[ -n \"$SCRIPT_DIR\" ] || SCRIPT_DIR=/"),
                "a root-level /mm-launcher must resolve SCRIPT_DIR to /");
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
        List<ArchiveEntry> entries = new ArrayList<>();
        try (InputStream input = Files.newInputStream(archivePath());
             GzipCompressorInputStream gzip = new GzipCompressorInputStream(input);
             TarArchiveInputStream tar = new TarArchiveInputStream(gzip)) {
            TarArchiveEntry entry;
            while ((entry = tar.getNextEntry()) != null) {
                byte[] bytes = entry.isDirectory() ? new byte[0] : tar.readAllBytes();
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

    private boolean commandIsAvailable(String command) {
        try {
            return run(List.of(command, "--version"), temp, Map.of()).exitCode() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static String shellCommand() {
        String configured = System.getProperty("archive.shell", "");
        if (!configured.isBlank()) {
            return configured;
        }
        if (isWindowsHost()) {
            for (String environmentName : List.of("ProgramFiles", "ProgramFiles(x86)")) {
                String programFiles = System.getenv(environmentName);
                if (programFiles != null) {
                    Path gitShell = Path.of(programFiles, "Git", "bin", "sh.exe");
                    if (Files.isRegularFile(gitShell)) {
                        return gitShell.toString();
                    }
                }
            }
        }
        return "sh";
    }

    private static ProcessResult run(
            List<String> command, Path cwd, Map<String, String> additions) throws Exception {
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

    private static Path archivePath() {
        return Path.of(requiredProperty("archive.path"));
    }

    private static Path checksumPath() {
        return Path.of(requiredProperty("archive.checksum"));
    }

    private static String expectedArchiveName() {
        return requiredProperty("archive.expectedName");
    }

    private static String expectedBuildIdentifier() {
        return requiredProperty("archive.expectedBuildIdentifier");
    }

    private static List<String> expectedJars() {
        return Arrays.stream(requiredProperty("archive.expectedJars").split("\\|"))
                .sorted()
                .toList();
    }

    private static String platform() {
        return requiredProperty("archive.platform");
    }

    private static String requiredProperty(String name) {
        return java.util.Objects.requireNonNull(System.getProperty(name),
                () -> "missing test property " + name);
    }

    private static boolean hostMatchesPlatform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        return switch (platform()) {
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
            boolean link,
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
