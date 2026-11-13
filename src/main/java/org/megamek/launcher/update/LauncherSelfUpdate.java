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

package org.megamek.launcher.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.megamek.launcher.internationalization.LocalizedText;
import org.megamek.launcher.release.GitHubRateLimitException;
import org.megamek.launcher.release.JavaReleaseTransport;
import org.megamek.launcher.release.ReleaseTransport;
import org.megamek.launcher.operation.OperationContext;
import org.megamek.launcher.operation.OperationPhase;
import org.megamek.launcher.operation.OperationType;
import org.megamek.launcher.operation.ProgressUnit;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Shared release discovery, verified downloads, and user-authorized native installer handoff. */
public final class LauncherSelfUpdate {
    private static final String BUNDLE = "org.megamek.launcher.services";
    // UpgradeCode of the MSI chained by the Windows setup .exe (WiX Burn bundle).
    private static final String UPGRADE = "{616FFD64-0D7F-4FBE-9BB9-63FD6D9D32FA}";
    static final String WINDOWS_SUFFIX = "windows-x64.exe";
    private static final URI LATEST = URI.create(
            "https://api.github.com/repos/MegaMek/mm-launcher/releases/latest");
    private static final Pattern VERSION = Pattern.compile("[0-9]+\\.[0-9]+\\.[0-9]+");
    private static final Pattern TAG = Pattern.compile("v?[0-9]+\\.[0-9]+\\.[0-9]+");
    private static final long LIMIT = 300L * 1024 * 1024;
    private final ReleaseTransport transport;
    private final java.util.Map<Path, String> staged = new ConcurrentHashMap<>();
    private final ObjectMapper json = JsonMapper.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private final NativeLauncherUpdate nativeUpdater = NativeLauncherUpdate.system();

    public LauncherSelfUpdate() { this(new JavaReleaseTransport()); }
    public LauncherSelfUpdate(ReleaseTransport transport) { this.transport = transport; }

    public record Candidate(String tag, String version, String name, URI url, long size, String sha256) {}

    /** A newer official release that a notify-only installation can only point the player to. */
    public record Release(String version, URI page) {}

    /**
     * A verified native installation whose package tools can't apply updates here (for example
     * an RPM install without dnf). It can still be pointed at the newer release's page.
     */
    public static final class InstallerToolsUnavailableException extends IOException {
        public InstallerToolsUnavailableException(String message) { super(message); }
    }

    public static boolean available() {
        return windowsAvailable() || NativeLauncherUpdate.available();
    }

    /** Linux package updates need an administrator password, which players should expect. */
    public static boolean asksForAdministratorPassword() {
        return NativeLauncherUpdate.asksForAdministratorPassword();
    }

    /**
     * Released builds outside a native installer (portable or generic archives) cannot apply
     * an update, but can still tell the player that one exists. Development builds have no
     * released version and never check.
     */
    public static boolean notifyOnly() {
        return !available() && VERSION.matcher(currentVersion()).matches();
    }

    private static boolean windowsAvailable() {
        return System.getProperty("os.name", "").startsWith("Windows")
                && windowsInstallMatches(System.getProperty("jpackage.app-path", ""),
                        System.getenv("LOCALAPPDATA"), recordedInstallDir());
    }

    /**
     * Portable entry points do not carry jpackage.app-path; an MSI install runs
     * from either the per-user default folder or the folder the installer recorded.
     */
    static boolean windowsInstallMatches(String appPath, String localAppData, String recordedInstallDir) {
        if (appPath == null || appPath.isBlank()) return false;
        try {
            String location = Path.of(appPath).toAbsolutePath().normalize().toString();
            if (localAppData != null && !localAppData.isBlank() && location.equalsIgnoreCase(
                    launcherExe(Path.of(localAppData, "Programs", "MegaMek Launcher"))))
                return true;
            return recordedInstallDir != null && !recordedInstallDir.isBlank()
                    && Path.of(recordedInstallDir).isAbsolute()
                    && location.equalsIgnoreCase(launcherExe(Path.of(recordedInstallDir)));
        } catch (java.nio.file.InvalidPathException invalid) {
            return false;
        }
    }

    private static String launcherExe(Path folder) {
        return folder.resolve("MegaMek Launcher.exe").toAbsolutePath().normalize().toString();
    }

    /** Parses the InstallDir string value from {@code reg.exe export} text. */
    static String parseRecordedInstallDir(String regExport) {
        var match = Pattern.compile("(?m)^\"InstallDir\"=\"((?:[^\"\\\\]|\\\\.)*)\"\\s*$")
                .matcher(regExport);
        return match.find() ? match.group(1).replaceAll("\\\\(.)", "$1") : null;
    }

    private static boolean recordedInstallDirRead;
    private static String recordedInstallDir;

    /*
     * Cached in a plain synchronized method rather than a holder class's static initializer:
     * any helper thread used by the read must never wait on a class that is still
     * initializing, or it deadlocks with the caller (the Swing event thread at startup).
     */
    static synchronized String recordedInstallDir() {
        if (!recordedInstallDirRead) {
            recordedInstallDir = readRecordedInstallDir();
            recordedInstallDirRead = true;
        }
        return recordedInstallDir;
    }

    private static String readRecordedInstallDir() {
        if (!System.getProperty("os.name", "").startsWith("Windows")
                || System.getProperty("jpackage.app-path", "").isBlank()) return null;
        Path export = null;
        try {
            // reg.exe query prints in the console code page; export always writes UTF-16LE.
            export = Files.createTempFile("megamek-launcher-installdir", ".reg");
            Process process = new ProcessBuilder("reg.exe", "export",
                    "HKCU\\Software\\MegaMek\\MegaMek Launcher\\Installer", export.toString(), "/y")
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return null;
            }
            if (process.exitValue() != 0 || Files.size(export) > 64 * 1024) return null;
            String text = new String(Files.readAllBytes(export), StandardCharsets.UTF_16LE);
            return parseRecordedInstallDir(text);
        } catch (IOException | RuntimeException error) {
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            if (export != null) {
                try {
                    Files.deleteIfExists(export);
                } catch (IOException ignored) {
                    // A leftover temp file is harmless.
                }
            }
        }
    }

    static synchronized void resetRecordedInstallDirForTest() {
        recordedInstallDirRead = false;
        recordedInstallDir = null;
    }

    public static String currentVersion() {
        String version = LauncherSelfUpdate.class.getPackage().getImplementationVersion();
        return version == null ? "" : version.replaceFirst("-.*$", "");
    }

    public Candidate check() throws IOException, InterruptedException {
        if (!available() || !VERSION.matcher(currentVersion()).matches()) {
            throw new IOException("Launcher self-update requires a supported native installation");
        }
        if (nativeUpdater == null) verifyInstalledVersion();
        else {
            nativeUpdater.verifyInstalledVersion(currentVersion());
            nativeUpdater.verifyInstallerTools();
        }
        return parseCandidate(latestReleaseMetadata(), currentVersion(), nativeUpdater == null
                ? WINDOWS_SUFFIX : nativeUpdater.suffix(currentVersion()));
    }

    /**
     * Finds a newer official release for a copy that can't update in place ({@link #notifyOnly()}
     * or {@link InstallerToolsUnavailableException}); null when current.
     */
    public Release checkRelease() throws IOException, InterruptedException {
        if (!VERSION.matcher(currentVersion()).matches()) {
            throw new IOException("Launcher update checks require a released launcher version");
        }
        return parseRelease(latestReleaseMetadata(), currentVersion());
    }

    private byte[] latestReleaseMetadata() throws IOException, InterruptedException {
        try (var response = transport.get(LATEST, "application/vnd.github+json")) {
            if (response.status() != 200) {
                GitHubRateLimitException limited = GitHubRateLimitException.from(response);
                if (limited != null) throw limited;
                throw new IOException("Official launcher release lookup returned HTTP " + response.status());
            }
            byte[] metadata = response.body().readNBytes(1024 * 1024 + 1);
            if (metadata.length > 1024 * 1024) throw new IOException("Release metadata exceeds limit");
            return metadata;
        }
    }

    Release parseRelease(byte[] metadata, String installedVersion) throws IOException {
        String tag = stableTag(json.readTree(metadata));
        String version = tag.replaceFirst("^v", "");
        if (compare(version, installedVersion) <= 0) return null;
        return new Release(version, URI.create("https://github.com/MegaMek/mm-launcher/releases/tag/" + tag));
    }

    private static String stableTag(JsonNode release) throws IOException {
        if (release == null || !release.isObject() || release.path("draft").asBoolean(true)
                || release.path("prerelease").asBoolean(true)) {
            throw new IOException("No stable official launcher release is available");
        }
        String tag = release.path("tag_name").asText("");
        if (!TAG.matcher(tag).matches()) throw new IOException("Unsupported launcher release tag");
        return tag;
    }

    Candidate parseCandidate(byte[] metadata, String installedVersion) throws IOException {
        return parseCandidate(metadata, installedVersion, WINDOWS_SUFFIX);
    }

    Candidate parseCandidate(byte[] metadata, String installedVersion, String suffix) throws IOException {
            JsonNode release = json.readTree(metadata);
            String tag = stableTag(release);
            String version = tag.replaceFirst("^v", "");
            if (compare(version, installedVersion) <= 0) return null;
            String name = "MegaMek-Launcher-" + version + "-" + suffix;
            JsonNode assets = release.path("assets");
            if (!assets.isArray() || assets.size() > 100) throw new IOException("Invalid release assets");
            Candidate found = null;
            for (JsonNode asset : assets) {
                if (!name.equals(asset.path("name").asText())) continue;
                if (found != null) throw new IOException("Ambiguous launcher installer release assets");
                long size = asset.path("size").asLong(-1);
                String digest = asset.path("digest").asText("");
                if (size <= 0 || size > LIMIT || !digest.matches("sha256:[0-9a-fA-F]{64}"))
                    throw new IOException("Official launcher installer requires a published SHA-256 and valid size");
                URI url;
                try {
                    url = URI.create(asset.path("browser_download_url").asText(""));
                } catch (IllegalArgumentException error) {
                    throw new IOException("Malformed official launcher installer asset URL", error);
                }
                if (!url.equals(URI.create("https://github.com/MegaMek/mm-launcher/releases/download/"
                        + tag + "/" + name))) throw new IOException("Unexpected launcher installer asset URL");
                found = new Candidate(tag, version, name, url, size, digest.substring(7));
            }
            if (found == null) throw new IOException("Official release has no matching launcher installer");
            return found;
    }

    static int compare(String a, String b) throws IOException {
        if (!VERSION.matcher(a).matches() || !VERSION.matcher(b).matches())
            throw new IOException("Invalid numeric launcher version");
        String[] left = a.split("\\.");
        String[] right = b.split("\\.");
        try {
            for (int i = 0; i < 3; i++) {
                int result = Long.compare(Long.parseLong(left[i]), Long.parseLong(right[i]));
                if (result != 0) return result;
            }
        } catch (NumberFormatException error) {
            throw new IOException("Launcher version component exceeds numeric range", error);
        }
        return 0;
    }

    public Path stage(Candidate candidate) throws IOException, InterruptedException {
        return stage(candidate, OperationContext.none(OperationType.LAUNCHER_UPDATE));
    }

    public Path stage(Candidate candidate, OperationContext context) throws IOException, InterruptedException {
        context.checkpoint();
        if (!available()) throw new IOException("Not a supported native launcher installation");
        return nativeUpdater == null
                ? stage(candidate, currentVersion(), context, LauncherSelfUpdate::verifyIdentity)
                : stage(candidate, currentVersion(), context, nativeUpdater::verifyPackage,
                        nativeUpdater.suffix(currentVersion()));
    }

    @FunctionalInterface
    interface InstallerIdentityCheck {
        void verify(Path file, String version) throws IOException, InterruptedException;
    }

    Path stage(Candidate candidate, String installedVersion, OperationContext context,
               InstallerIdentityCheck identityCheck) throws IOException, InterruptedException {
        return stage(candidate, installedVersion, context, identityCheck, WINDOWS_SUFFIX);
    }

    Path stage(Candidate candidate, String installedVersion, OperationContext context,
               InstallerIdentityCheck identityCheck, String suffix) throws IOException, InterruptedException {
        context.checkpoint();
        if (candidate == null || compare(candidate.version(), installedVersion) <= 0)
            throw new IOException("Not a newer launcher update");
        String tag = candidate.tag();
        if (!TAG.matcher(tag).matches() || !tag.replaceFirst("^v", "").equals(candidate.version())
                || !candidate.name().equals("MegaMek-Launcher-" + candidate.version() + "-" + suffix)
                || candidate.size() <= 0 || candidate.size() > LIMIT
                || !candidate.sha256().matches("[0-9a-fA-F]{64}")
                || !candidate.url().equals(URI.create("https://github.com/MegaMek/mm-launcher/"
                        + "releases/download/" + tag + "/" + candidate.name())))
            throw new IOException("Invalid official launcher installer asset identity");
        Path directory = Files.createTempDirectory(suffix.equals(WINDOWS_SUFFIX)
                ? "mm-launcher-setup-" : "mm-launcher-update-").toRealPath();
        Path file = directory.resolve(candidate.name());
        try {
            context.phase(OperationPhase.DOWNLOAD,
                    LocalizedText.of(BUNDLE, "LauncherSelfUpdate.downloadingInstaller"));
            URI uri = candidate.url();
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            try (var output = Files.newOutputStream(file)) {
                long count = 0;
                for (int redirects = 0; ; redirects++) {
                    if (!safeDownloadUri(uri, redirects == 0)) throw new IOException("Unsafe launcher installer download URL");
                    context.checkpoint();
                    try (var response = transport.get(uri, "application/octet-stream");
                         var ignored = context.interruptible(response)) {
                        if (response.status() >= 300 && response.status() < 400) {
                            if (redirects >= 4) throw new IOException("Too many launcher installer download redirects");
                            String location = response.firstHeader("location");
                            if (location == null) throw new IOException("Missing launcher installer redirect location");
                            try {
                                uri = uri.resolve(URI.create(location));
                            } catch (IllegalArgumentException error) {
                                throw new IOException("Invalid launcher installer redirect", error);
                            }
                            continue;
                        }
                        if (response.status() != 200) throw new IOException("Launcher installer download HTTP " + response.status());
                        byte[] buffer = new byte[65536];
                        int n;
                        while ((n = response.body().read(buffer)) != -1) {
                            context.checkpoint();
                            if (count > candidate.size() - n) throw new IOException("Launcher installer exceeds published size");
                            sha.update(buffer, 0, n);
                            output.write(buffer, 0, n);
                            count += n;
                            context.progress(OperationPhase.DOWNLOAD, count, candidate.size(),
                                    ProgressUnit.BYTES, LocalizedText.of(BUNDLE,
                                            "LauncherSelfUpdate.downloadingLauncher", candidate.version()));
                        }
                        break;
                    }
                }
                if (count != candidate.size() || !HexFormat.of().formatHex(sha.digest())
                        .equalsIgnoreCase(candidate.sha256())) throw new IOException("Launcher installer size or SHA-256 mismatch");
            }
            context.phase(OperationPhase.VERIFY,
                    LocalizedText.of(BUNDLE, "LauncherSelfUpdate.checkingInstaller"));
            identityCheck.verify(file, candidate.version());
            context.checkpoint();
            staged.put(file, candidate.sha256());
            return file;
        } catch (IOException | InterruptedException | RuntimeException | NoSuchAlgorithmException error) {
            try {
                context.cleanupPreservingCancellation(() -> {
                    Files.deleteIfExists(file);
                    Files.deleteIfExists(directory);
                });
            } catch (Exception cleanup) {
                error.addSuppressed(cleanup);
            }
            if (context.cancellationRequested()) {
                try {
                    context.checkpoint();
                } catch (org.megamek.launcher.operation.OperationCancelledException cancelled) {
                    cancelled.addSuppressed(error);
                    throw cancelled;
                }
            }
            if (error instanceof InterruptedException interrupted) throw interrupted;
            if (error instanceof IOException io) throw io;
            throw new IOException("Could not stage the launcher update", error);
        }
    }

    private static boolean safeDownloadUri(URI uri, boolean initial) {
        if (!"https".equals(uri.getScheme()) || uri.getHost() == null || uri.getPort() != -1
                || uri.getRawUserInfo() != null || uri.getFragment() != null) return false;
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return initial ? host.equals("github.com") :
                host.equals("release-assets.githubusercontent.com")
                || host.equals("objects.githubusercontent.com")
                || host.equals("github-releases.githubusercontent.com");
    }

    private static String literal(String value) { return "'" + value.replace("'", "''") + "'"; }

    static String decodeBase64Utf8(String encoded) throws IOException {
        try {
            return new String(java.util.Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Windows Installer returned an unreadable install folder");
        }
    }

    public static void verifyInstalledVersion() throws IOException, InterruptedException {
        NativeLauncherUpdate nativeUpdate = NativeLauncherUpdate.system();
        if (nativeUpdate != null) {
            nativeUpdate.verifyInstalledVersion(currentVersion());
            return;
        }
        String script = "$ErrorActionPreference='Stop';"
                + "$i=New-Object -ComObject WindowsInstaller.Installer;"
                + "$codes=@($i.RelatedProducts(" + literal(UPGRADE) + "));"
                + "if($codes.Count -ne 1){throw 'Expected one installed MSI product'};"
                + "$i.ProductInfo($codes[0],'VersionString');"
                // Base64 keeps a non-ASCII folder intact whatever the console code page is.
                + "[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("
                + "[string]$i.ProductInfo($codes[0],'InstallLocation')))";
        String[] lines = powershell(script).trim().split("\\R", 2);
        if (!currentVersion().equals(lines[0].trim()))
            throw new IOException("Running launcher version is not the installed MSI version");
        String location = lines.length > 1 ? decodeBase64Utf8(lines[1].trim()) : "";
        if (!location.isEmpty() && !windowsInstallMatches(
                System.getProperty("jpackage.app-path", ""), null, location))
            throw new IOException("Running launcher is not in the installed MSI folder");
    }

    private static String powershell(String script) throws IOException, InterruptedException {
        String encoded = java.util.Base64.getEncoder().encodeToString(
                ("$ProgressPreference='SilentlyContinue';" + script).getBytes(StandardCharsets.UTF_16LE));
        Process process = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive",
                "-EncodedCommand", encoded).start();
        var stdout = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> drain(process.getInputStream()));
        var stderr = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> drain(process.getErrorStream()));
        try {
            if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("Windows Installer identity check timed out");
            }
            String output = stdout.join().trim();
            String diagnostics = stderr.join().trim();
            if (process.exitValue() != 0)
                throw new IOException("Windows Installer identity check failed: " + diagnostics);
            return output;
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }

    private static String drain(java.io.InputStream stream) {
        try (stream) {
            byte[] bytes = new byte[4096];
            java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
            int n;
            while ((n = stream.read(bytes)) != -1) {
                captured.write(bytes, 0, Math.min(n, Math.max(0, 4096 - captured.size())));
            }
            return captured.toString(StandardCharsets.UTF_8);
        } catch (IOException error) {
            return "Could not read PowerShell output: " + error.getMessage();
        }
    }

    // Sanity check of the setup .exe version resource; the published SHA-256 is the trust anchor.
    private static void verifyIdentity(Path file, String version) throws IOException, InterruptedException {
        String script = "$ErrorActionPreference='Stop';"
                + "$v=(Get-Item -LiteralPath " + literal(file.toString()) + ").VersionInfo;"
                + "$v.ProductName;$v.CompanyName;$v.ProductVersion";
        String[] values = powershell(script).lines().toArray(String[]::new);
        if (values.length != 3 || !"MegaMek Launcher".equals(values[0].trim())
                || !"MegaMek".equals(values[1].trim())
                || !version.equals(values[2].trim()))
            throw new IOException("Setup product name, company or version does not match release");
    }

    /** Only the exact file returned by this instance's stage operation is eligible for deletion. */
    public void discard(Path file) throws IOException {
        String digest = staged.get(file);
        if (digest == null) throw new IOException("Launcher installer staging directory is not owned by this update");
        Path directory = file.getParent();
        if (Files.isSymbolicLink(file) || Files.isSymbolicLink(directory))
            throw new IOException("Launcher installer staging path was replaced");
        if (!digest.equalsIgnoreCase(hash(file)))
            throw new IOException("Staged launcher installer changed; refusing to delete replaced file");
        Files.deleteIfExists(file);
        Files.delete(directory); // never recurse into a directory containing unrelated files
        staged.remove(file);
    }

    private static String hash(Path file) throws IOException {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = input.read(buffer)) != -1) sha.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 unavailable", error);
        }
    }

    static String installerArguments() {
        // Burn's /passive shows only the themed progress window; no prompts.
        // PowerShell single quotes here delimit a literal double-quote, not a backslash.
        return "@('/passive','/norestart','/log',('\"'+$installerLog+'\"'))";
    }

    public enum ReportState { INSTALLED, INSTALLED_WARNING, FAILED, PENDING, RECOVERED, INSTALLED_RECOVERED }

    /** {@link #message()} is in the player's language; {@link #englishMessage()} is for logs. */
    public record ReportResult(ReportState state, LocalizedText text) {
        public ReportResult(ReportState state, String message) {
            this(state, LocalizedText.literal(message));
        }
        public ReportResult(boolean installed, LocalizedText text) {
            this(installed ? ReportState.INSTALLED : ReportState.FAILED, text);
        }
        public String message() {
            return text == null ? null : text.text();
        }
        public String englishMessage() {
            return text == null ? null : text.english();
        }
        public boolean installed() {
            return state == ReportState.INSTALLED || state == ReportState.INSTALLED_WARNING
                    || state == ReportState.INSTALLED_RECOVERED;
        }
        public boolean pending() { return state == ReportState.PENDING; }
        public boolean recovered() {
            return state == ReportState.RECOVERED || state == ReportState.INSTALLED_RECOVERED;
        }
    }

    @FunctionalInterface
    interface InstalledVersionCheck {
        void verify() throws IOException, InterruptedException;
    }

    @FunctionalInterface
    interface HelperCheck {
        boolean active(Path report, long pid, long started) throws IOException, InterruptedException;
    }

    private static boolean helperActive(Path report, long pid, long started)
            throws IOException, InterruptedException {
        if (pid > 0) {
            var process = ProcessHandle.of(pid);
            if (process.isEmpty() || !process.get().isAlive()) return false;
            var actualStart = process.get().info().startInstant();
            if (actualStart.isEmpty()) throw new IOException("Cannot confirm launcher update helper identity");
            return actualStart.get().toEpochMilli() == started;
        }
        // Old launchers recorded only "pending", so identify their helper by its
        // encoded script and exact report path, not by every PowerShell process.
        String needle = "$report=" + literal(report.toString()) + ";$msi=";
        String script = "$ErrorActionPreference='Stop';$needle=" + literal(needle) + ";"
                + "foreach($p in @(Get-CimInstance Win32_Process -Filter \"Name='powershell.exe'\")){"
                + "if($p.ProcessId -eq $PID){continue};"
                + "$m=[regex]::Match([string]$p.CommandLine,"
                + "'(?i)(?:^|\\s)-EncodedCommand\\s+([A-Za-z0-9+/=]+)(?:\\s|$)');"
                + "if(!$m.Success){continue};"
                + "$code=[Text.Encoding]::Unicode.GetString([Convert]::FromBase64String($m.Groups[1].Value));"
                + "if($code.Contains($needle) -and $code.Contains('Get-FileHash -LiteralPath $msi'))"
                + "{[Console]::Out.Write('active');exit}};[Console]::Out.Write('absent')";
        String result = powershell(script);
        if (!result.equals("active") && !result.equals("absent"))
            throw new IOException("Cannot determine whether the previous launcher updater is still running");
        return result.equals("active");
    }

    /**
     * Acknowledge completed results before network lookup. Active helpers retain their report.
     * Incomplete results can be archived only after helper and installed-version checks.
     */
    public static ReportResult consumeReport(Path report, String runningVersion)
            throws IOException, InterruptedException {
        NativeLauncherUpdate nativeUpdate = NativeLauncherUpdate.system();
        return nativeUpdate == null
                ? consumeReport(report, runningVersion, LauncherSelfUpdate::verifyInstalledVersion)
                : consumeReport(report, runningVersion, () -> nativeUpdate.verifyInstalledVersion(runningVersion),
                    LauncherSelfUpdate::helperActive);
    }

    static synchronized ReportResult consumeReport(Path report, String runningVersion,
                                                    InstalledVersionCheck installedVersionCheck)
            throws IOException, InterruptedException {
        return consumeReport(report, runningVersion, installedVersionCheck, LauncherSelfUpdate::helperActive);
    }

    static synchronized ReportResult consumeReport(Path report, String runningVersion,
                                                    InstalledVersionCheck installedVersionCheck,
                                                    HelperCheck helperCheck)
            throws IOException, InterruptedException {
        byte[] original = reportBytes(report);
        if (original == null) return null;
        String outcome = new String(original, StandardCharsets.UTF_8);
        if (outcome.startsWith("starting:") && VERSION.matcher(outcome.substring(9)).matches()) {
            return new ReportResult(ReportState.PENDING, LocalizedText.of(BUNDLE,
                    "LauncherSelfUpdate.handoffIdentityMissing", helperLog(report)));
        }
        if ("pending".equals(outcome)) {
            if (helperCheck.active(report, 0, 0)) return pendingResult(report);
            if (!VERSION.matcher(runningVersion).matches())
                throw new IOException("Cannot reconcile an incomplete report without a valid launcher version");
            installedVersionCheck.verify();
            Path saved = acknowledgeReport(report, original, true);
            return new ReportResult(ReportState.RECOVERED, LocalizedText.of(BUNDLE,
                    "LauncherSelfUpdate.completionUnrecorded", runningVersion, saved));
        }
        if (outcome.startsWith("pending:")) {
            String[] fields = outcome.split(":", -1);
            if (fields.length != 4 || !VERSION.matcher(fields[1]).matches())
                throw new IOException("Unrecognized pending launcher update report; retained at " + report);
            long pid;
            long started;
            try {
                pid = Long.parseLong(fields[2]);
                started = Long.parseLong(fields[3]);
                if (pid <= 0 || started <= 0) throw new NumberFormatException("nonpositive helper identity");
            } catch (NumberFormatException error) {
                throw new IOException("Invalid launcher update helper identity; report retained at " + report, error);
            }
            if (helperCheck.active(report, pid, started)) return pendingResult(report);
            if (!fields[1].equals(runningVersion)) {
                installedVersionCheck.verify();
                Path saved = acknowledgeReport(report, original, true);
                return new ReportResult(false, LocalizedText.of(BUNDLE,
                        "LauncherSelfUpdate.updaterStopped", fields[1], runningVersion, saved,
                        helperLog(report)));
            }
            installedVersionCheck.verify();
            Path saved = acknowledgeReport(report, original, true);
            return new ReportResult(ReportState.INSTALLED_RECOVERED, LocalizedText.of(BUNDLE,
                    "LauncherSelfUpdate.finalResultUnsaved", runningVersion, saved, helperLog(report)));
        }
        ReportResult result;
        String[] completed = outcome.split(":", -1);
        if (completed.length == 2 && java.util.Set.of("installed", "installed-reboot-required",
                "installed-cleanup-warning", "installed-reboot-required-cleanup-warning",
                "installed-reopen-warning", "installed-cleanup-reopen-warning").contains(completed[0])
                && VERSION.matcher(completed[1]).matches()) {
            if (!completed[1].equals(runningVersion)) {
                result = new ReportResult(false, LocalizedText.of(BUNDLE,
                        "LauncherSelfUpdate.versionMismatch", completed[1], runningVersion));
            } else {
                try {
                    installedVersionCheck.verify();
                    result = new ReportResult("installed".equals(completed[0])
                            ? ReportState.INSTALLED : ReportState.INSTALLED_WARNING,
                            LocalizedText.concat(
                                    LocalizedText.of(BUNDLE, "LauncherSelfUpdate.updated", runningVersion),
                                    completed[0].contains("-cleanup")
                                            ? LocalizedText.of(BUNDLE, "LauncherSelfUpdate.updatedCleanupFailed",
                                            helperLog(report)) : null,
                                    completed[0].contains("-reboot-required")
                                            ? LocalizedText.of(BUNDLE, "LauncherSelfUpdate.updatedRestartRequired")
                                            : null,
                                    completed[0].contains("-reopen")
                                            ? LocalizedText.of(BUNDLE, "LauncherSelfUpdate.updatedReopen",
                                            helperLog(report)) : null));
                } catch (IOException error) {
                    result = new ReportResult(false, LocalizedText.of(BUNDLE,
                            "LauncherSelfUpdate.updatedUnconfirmed", error.getMessage()));
                }
            }
        } else if ("installation succeeded; staged MSI cleanup failed".equals(outcome)
                || "installation succeeded; staged MSI cleanup failed; restart required".equals(outcome)) {
            try {
                installedVersionCheck.verify();
                result = new ReportResult(ReportState.INSTALLED_WARNING,
                        LocalizedText.concat(
                                LocalizedText.of(BUNDLE, "LauncherSelfUpdate.msiCleanupFailed"),
                                outcome.endsWith("; restart required")
                                        ? LocalizedText.of(BUNDLE, "LauncherSelfUpdate.msiRestartRequired")
                                        : null));
            } catch (IOException error) {
                result = new ReportResult(false, LocalizedText.of(BUNDLE,
                        "LauncherSelfUpdate.msiUnconfirmed", error.getMessage()));
            }
        } else if (outcome.matches("[0-9]{1,10}")
                || outcome.startsWith("handoff failed: ")
                && outcome.length() > "handoff failed: ".length()) {
            result = new ReportResult(false, LocalizedText.of(BUNDLE,
                    "LauncherSelfUpdate.previousIncomplete", outcome.replaceAll("\\p{Cntrl}", " "),
                    helperLog(report), failedOrInstallerLog(report, outcome)));
        } else {
            throw new IOException("Unrecognized launcher update result. The report was retained at "
                    + report + " for inspection; do not retry until it is resolved.");
        }
        // The helper replaces pending atomically only after installation and cleanup are finished.
        // Refuse to acknowledge a file that changed during validation (including a new link).
        acknowledgeReport(report, original, false);
        return result;
    }

    private static ReportResult pendingResult(Path report) {
        return new ReportResult(ReportState.PENDING, LocalizedText.of(BUNDLE,
                "LauncherSelfUpdate.stillFinishing", helperLog(report)));
    }

    private static Path acknowledgeReport(Path report, byte[] original, boolean retain) throws IOException {
        if (!java.util.Arrays.equals(original, reportBytes(report))) {
            throw new IOException("Launcher update result changed during review; retry after the helper finishes.");
        }
        if (retain) {
            Path saved = report.resolveSibling(report.getFileName() + ".incomplete-" + UUID.randomUUID() + ".txt");
            Files.move(report, saved, StandardCopyOption.ATOMIC_MOVE);
            return saved;
        }
        Files.delete(report);
        return report;
    }

    private static byte[] reportBytes(Path report) throws IOException {
        if (!Files.exists(report, LinkOption.NOFOLLOW_LINKS)) return null;
        BasicFileAttributes attributes = Files.readAttributes(report, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.size() > 4096) {
            throw new IOException("Launcher update result is not a small regular file; report retained at " + report);
        }
        try (var channel = Files.newByteChannel(report, java.util.Set.of(StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS))) {
            var buffer = java.nio.ByteBuffer.allocate(4097);
            while (buffer.hasRemaining() && channel.read(buffer) != -1) { /* bounded read */ }
            if (buffer.position() > 4096) throw new IOException("Launcher update result exceeds size limit");
            return java.util.Arrays.copyOf(buffer.array(), buffer.position());
        }
    }

    static String handoffScript(Path file, String version, String sha256, Path report, long parentPid) {
        return handoffScript(file, version, sha256, report, parentPid,
                Path.of(System.getProperty("jpackage.app-path", "MegaMek Launcher.exe")),
                file);
    }

    public static Path helperLog(Path report) {
        return report.resolveSibling(report.getFileName().toString().equals("launcher-update-result.txt")
                ? "launcher-update-helper.log" : "msi-update-helper.log");
    }

    public static Path installerLog(Path report) {
        return report.resolveSibling(report.getFileName().toString().equals("launcher-update-result.txt")
                ? "launcher-update-installer.log" : "msi-update-installer.log");
    }

    public static Path failedInstallerLog(Path report) {
        String name = installerLog(report).getFileName().toString();
        return report.resolveSibling(name.substring(0, name.length() - ".log".length()) + "-failed.log");
    }

    private static Path failedOrInstallerLog(Path report, String outcome) {
        Path failed = failedInstallerLog(report);
        return outcome.matches("[0-9]+") && Files.isRegularFile(failed, LinkOption.NOFOLLOW_LINKS)
                ? failed : installerLog(report);
    }

    static String handoffScript(Path file, String version, String sha256, Path report, long parentPid,
                                Path launcher, Path installer) {
        Path directory = file.getParent();
        return "$ErrorActionPreference='Stop'; $ProgressPreference='SilentlyContinue';"
                + "$report=" + literal(report.toString()) + ";$msi=" + literal(file.toString()) + ";"
                + "$dir=" + literal(directory.toString()) + ";"
                + "$installerLog=" + literal(installerLog(report).toString()) + ";"
                + "$outcome='';try {"
                + "Import-Module ([IO.Path]::Combine($PSHOME,'Modules','Microsoft.PowerShell.Utility',"
                + "'Microsoft.PowerShell.Utility.psd1'));"
                + "Write-Output 'Waiting for launcher shutdown';while(Get-Process -Id " + parentPid
                + " -ErrorAction SilentlyContinue){Start-Sleep -Milliseconds 500};"
                + "if((Get-FileHash -LiteralPath $msi -Algorithm SHA256).Hash -ine "
                + literal(sha256) + "){throw 'Staged installer digest changed'};"
                + "Write-Output 'Starting launcher setup';$p=Start-Process -FilePath " + literal(installer.toString())
                + " -ArgumentList " + installerArguments()
                + " -Wait -PassThru; if($p.ExitCode -eq 0)"
                + " { $outcome=" + literal("installed:" + version) + " }"
                + " elseif($p.ExitCode -eq 3010)"
                + " { $outcome=" + literal("installed-reboot-required:" + version) + " }"
                + " else { $outcome=[string]$p.ExitCode } }"
                + "catch { $outcome='handoff failed: '+$_.Exception.Message;Write-Error $_ -ErrorAction Continue }"
                + "finally { try {"
                + "if(([IO.File]::GetAttributes($dir) -band [IO.FileAttributes]::ReparsePoint) -eq 0"
                + " -and [IO.File]::Exists($msi)"
                + " -and ([IO.File]::GetAttributes($msi) -band [IO.FileAttributes]::ReparsePoint) -eq 0"
                + " -and (Get-FileHash -LiteralPath $msi -Algorithm SHA256).Hash -ieq "
                + literal(sha256) + ") {"
                + "[IO.File]::Delete($msi); [IO.Directory]::Delete($dir) }"
                + "else { throw 'Staged installer path changed; cleanup refused' }"
                + "} catch { Write-Error $_ -ErrorAction Continue;"
                + "if($outcome -eq " + literal("installed:" + version) + ")"
                + " { $outcome=" + literal("installed-cleanup-warning:" + version) + " }"
                + "elseif($outcome -eq " + literal("installed-reboot-required:" + version) + ")"
                + " { $outcome=" + literal("installed-reboot-required-cleanup-warning:" + version) + " } };"
                // The next update attempt overwrites the installer log; keep the failed one to share.
                + "if($outcome -match '^[0-9]+$') { try { if([IO.File]::Exists($installerLog))"
                + " { [IO.File]::Copy($installerLog," + literal(failedInstallerLog(report).toString()) + ",$true) } }"
                + " catch { Write-Error $_ -ErrorAction Continue } };"
                + "$tmp=[IO.Path]::Combine([IO.Path]::GetDirectoryName($report),"
                + "[IO.Path]::GetRandomFileName());"
                + "try { $bytes=[Text.Encoding]::UTF8.GetBytes($outcome);"
                + "$stream=[IO.File]::Open($tmp,[IO.FileMode]::CreateNew);"
                + "try { $stream.Write($bytes,0,$bytes.Length); $stream.Flush($true) }"
                + "finally { $stream.Dispose() };"
                + "[IO.File]::Replace($tmp,$report,[NullString]::Value) }"
                + "finally { if([IO.File]::Exists($tmp)){[IO.File]::Delete($tmp)} } };"
                + "Write-Output $outcome;"
                + "if($outcome -eq " + literal("installed:" + version)
                + " -or $outcome -eq " + literal("installed-reboot-required:" + version)
                + " -or $outcome -eq " + literal("installed-cleanup-warning:" + version)
                + " -or $outcome -eq " + literal("installed-reboot-required-cleanup-warning:" + version) + ")"
                + " { Start-Process -FilePath " + literal(launcher.toString()) + " }"
                // Windows Installer rolls a failed update back to the previous launcher; reopen it
                // so the player sees the failure and where its diagnostics are.
                + " elseif([IO.File]::Exists(" + literal(launcher.toString()) + "))"
                + " { try { Start-Process -FilePath " + literal(launcher.toString()) + " }"
                + " catch { Write-Error $_ -ErrorAction Continue } }";
    }

    /** The helper waits for this JVM to exit; exit status is retained for the next launch. */
    public void handoff(Path file, String version, String sha256, Path report)
            throws IOException, InterruptedException {
        if (sha256 == null || !sha256.matches("[0-9a-fA-F]{64}")
                || !sha256.equalsIgnoreCase(staged.get(file)))
            throw new IOException("Launcher installer staging path is not owned or its digest differs");
        java.util.List<String> command;
        if (nativeUpdater == null) {
            verifyInstalledVersion();
            if (!windowsAvailable())
                throw new IOException("Launcher self-update requires the installed Windows entry point");
            verifyIdentity(file, version);
            String script = handoffScript(file, version, sha256, report, ProcessHandle.current().pid());
            String encoded = java.util.Base64.getEncoder().encodeToString(
                    script.getBytes(StandardCharsets.UTF_16LE));
            command = java.util.List.of("powershell.exe", "-NoProfile", "-NonInteractive",
                    "-EncodedCommand", encoded);
        } else {
            command = nativeUpdater.handoffCommand(file, version, sha256, report, currentVersion());
        }
        Files.createDirectories(report.toAbsolutePath().getParent());
        if (!report.getParent().toAbsolutePath().equals(report.getParent().toRealPath()))
            throw new IOException("Launcher update diagnostics directory was replaced");
        if (Files.exists(report, java.nio.file.LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Previous launcher update result has not been reviewed");
        Path log = helperLog(report);
        if (Files.exists(log, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(log, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Launcher helper log is not a regular file: " + log);
        Path installerLog = installerLog(report);
        if (Files.exists(installerLog, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(installerLog, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Launcher installer log is not a regular file: " + installerLog);
        Files.writeString(report, "starting:" + version, java.nio.file.StandardOpenOption.CREATE_NEW);
        Process helper = null;
        String pending = null;
        try {
            helper = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
            long started = helper.info().startInstant().orElseThrow(
                    () -> new IOException("Cannot record launcher update helper identity")).toEpochMilli();
            pending = "pending:" + version + ":" + helper.pid() + ":" + started;
            Path temporary = Files.createTempFile(report.toAbsolutePath().getParent(), "launcher-update-", ".tmp");
            try {
                Files.writeString(temporary, pending);
                byte[] current = reportBytes(report);
                if (current == null || !("starting:" + version).equals(new String(current, StandardCharsets.UTF_8)))
                    throw new IOException("Launcher update report changed while starting the helper");
                Files.move(temporary, report, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(temporary);
            }
            if (!helper.isAlive()) throw new IOException("Launcher update helper stopped before handoff");
        } catch (IOException error) {
            if (helper != null && helper.isAlive()) helper.destroyForcibly();
            try {
                clearFailedHandoff(report, version, pending);
            } catch (IOException cleanup) {
                error.addSuppressed(cleanup);
            }
            throw error;
        }

    }

    public static String reportFileName() {
        return windowsAvailable() ? "msi-update-result.txt" : "launcher-update-result.txt";
    }

    static void clearFailedHandoff(Path report, String version, String pending) throws IOException {
        byte[] current = reportBytes(report);
        if (current == null) return;
        String text = new String(current, StandardCharsets.UTF_8);
        if (!text.equals("starting:" + version) && !(pending != null && pending.equals(text)))
            throw new IOException("Launcher update report changed during handoff; retained at " + report);
        acknowledgeReport(report, current, false);
    }
}
