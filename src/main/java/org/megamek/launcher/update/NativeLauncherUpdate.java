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

import org.megamek.launcher.launch.DirectProcessRunner;
import org.megamek.launcher.launch.ProcessRunner;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/** Native package identity and handoff; release selection and download stay in LauncherSelfUpdate. */
final class NativeLauncherUpdate {
    private static final String PACKAGE = "megamek-launcher";
    private static final String MAC_ID = "org.megamek.launcher";
    private static final String LINUX_LAUNCHER = "/opt/megamek-launcher/bin/MegaMek Launcher";
    private static final String MAC_LAUNCHER =
            "/Applications/MegaMek Launcher.app/Contents/MacOS/MegaMek Launcher";
    private static final String DEB_FORMAT = "${Status}\\t${Version}\\t${Architecture}\\n";
    private static final String RPM_FORMAT = "%{NAME}\\t%{VERSION}\\t%{RELEASE}\\t%{ARCH}\\n";
    private final Platform platform;
    private final Path launcher;
    private final ProcessRunner runner;
    private final java.util.function.Predicate<Path> executable;
    private Kind kind;

    enum Platform { MAC_INTEL, MAC_ARM, LINUX }
    enum Kind {
        MAC_INTEL("macos-intel.pkg"), MAC_ARM("macos-apple-silicon.pkg"),
        DEB("linux-x64.deb"), RPM("linux-x64.rpm");
        final String suffix;
        Kind(String suffix) { this.suffix = suffix; }
    }

    NativeLauncherUpdate(Platform platform, Path launcher, ProcessRunner runner) {
        this(platform, launcher, runner, Files::isExecutable);
    }

    NativeLauncherUpdate(Platform platform, Path launcher, ProcessRunner runner,
                         java.util.function.Predicate<Path> executable) {
        this.platform = platform;
        this.launcher = launcher;
        this.runner = runner;
        this.executable = executable;
        this.kind = switch (platform) {
            case MAC_INTEL -> Kind.MAC_INTEL;
            case MAC_ARM -> Kind.MAC_ARM;
            case LINUX -> null;
        };
    }

    static Platform platform(String os, String arch, String entry) {
        String architecture = arch.toLowerCase(Locale.ROOT);
        if (os.startsWith("Mac") && MAC_LAUNCHER.equals(entry)) {
            return switch (architecture) {
                case "x86_64", "amd64" -> Platform.MAC_INTEL;
                case "aarch64", "arm64" -> Platform.MAC_ARM;
                default -> null;
            };
        }
        return os.equals("Linux") && LINUX_LAUNCHER.equals(entry)
                && (architecture.equals("amd64") || architecture.equals("x86_64"))
                ? Platform.LINUX : null;
    }

    static NativeLauncherUpdate system() {
        String entry = System.getProperty("jpackage.app-path", "");
        Platform platform = platform(System.getProperty("os.name", ""),
                System.getProperty("os.arch", ""), entry);
        return platform == null ? null : new NativeLauncherUpdate(platform, Path.of(entry),
                new DirectProcessRunner());
    }

    static boolean available() { return system() != null; }

    String suffix(String runningVersion) throws IOException, InterruptedException {
        if (kind == null) verifyInstalledVersion(runningVersion);
        return kind.suffix;
    }

    private ProcessRunner.Result run(List<String> command) throws IOException, InterruptedException {
        ProcessRunner.Result result = runner.run(command, launcher.getParent(), Duration.ofSeconds(30), false);
        if (result.timedOut()) throw new IOException("Native package inspection timed out: " + command.getFirst());
        return result;
    }

    private String output(List<String> command) throws IOException, InterruptedException {
        ProcessRunner.Result result = run(command);
        if (result.exitCode() != 0) throw new IOException("Native package inspection failed: "
                + command.getFirst() + " (exit " + result.exitCode() + "): " + result.output().strip());
        return result.output().strip();
    }

    void verifyInstalledVersion(String version) throws IOException, InterruptedException {
        LauncherSelfUpdate.compare(version, version);
        if (!Files.isRegularFile(launcher, LinkOption.NOFOLLOW_LINKS)
                || !launcher.equals(launcher.toRealPath()))
            throw new IOException("The installed launcher path is missing or was replaced");
        if (platform != Platform.LINUX) {
            String receipt = output(List.of("/usr/sbin/pkgutil", "--pkg-info", MAC_ID));
            if (receipt.lines().filter(("package-id: " + MAC_ID)::equals).count() != 1)
                throw new IOException("Installed macOS launcher package identity could not be confirmed");
            Path plist = launcher.getParent().getParent().resolve("Info.plist");
            if (!macVersion(version).equals(output(List.of("/usr/libexec/PlistBuddy", "-c",
                    "Print :CFBundleShortVersionString", plist.toString())))
                    || !MAC_ID.equals(output(List.of("/usr/libexec/PlistBuddy", "-c",
                    "Print :CFBundleIdentifier", plist.toString()))))
                throw new IOException("Installed macOS launcher identity does not match its package receipt");
            return;
        }
        boolean deb = false;
        boolean rpm = false;
        if (executable.test(Path.of("/usr/bin/dpkg-query"))) {
            ProcessRunner.Result owner = run(List.of("/usr/bin/dpkg-query", "--search", launcher.toString()));
            deb = owner.exitCode() == 0 && owner.output().strip().equals(PACKAGE + ": " + launcher);
            if (deb && !("install ok installed\t" + version + "\tamd64").equals(output(
                    List.of("/usr/bin/dpkg-query", "--show", "--showformat=" + DEB_FORMAT, PACKAGE))))
                throw new IOException("Running launcher version does not match its installed Debian package");
        }
        if (executable.test(Path.of("/usr/bin/rpm"))) {
            ProcessRunner.Result owner = run(List.of("/usr/bin/rpm", "-q", "--queryformat", RPM_FORMAT,
                    "-f", launcher.toString()));
            rpm = owner.exitCode() == 0 && owner.output().strip().equals(
                    PACKAGE + "\t" + version + "\t1\tx86_64");
            if (owner.exitCode() == 0 && !rpm)
                throw new IOException("Running launcher version does not match its installed RPM package");
        }
        if (deb == rpm) throw new IOException("Cannot identify one installed launcher package");
        kind = deb ? Kind.DEB : Kind.RPM;
    }

    static String macVersion(String version) throws IOException {
        LauncherSelfUpdate.compare(version, version);
        String[] parts = version.split("\\.");
        try {
            return Math.addExact(Long.parseLong(parts[0]), 1) + "." + parts[1] + "." + parts[2];
        } catch (ArithmeticException | NumberFormatException error) {
            throw new IOException("macOS launcher package version exceeds numeric range", error);
        }
    }

    void verifyPackage(Path file, String version) throws IOException, InterruptedException {
        if (kind == null) throw new IOException("Installed launcher package has not been identified");
        if (kind == Kind.DEB) {
            String metadata = output(List.of("/usr/bin/dpkg-deb", "--show",
                    "--showformat=${Package}\\t${Version}\\t${Architecture}\\n", file.toString()));
            if (!(PACKAGE + "\t" + version + "\tamd64").equals(metadata))
                throw new IOException("Debian launcher package name, version or architecture does not match release");
        } else if (kind == Kind.RPM) {
            String metadata = output(List.of("/usr/bin/rpm", "-qp", "--queryformat", RPM_FORMAT, file.toString()));
            if (!(PACKAGE + "\t" + version + "\t1\tx86_64").equals(metadata))
                throw new IOException("RPM launcher package name, version or architecture does not match release");
        } else {
            inspectMacPackage(file, version);
            output(List.of("/usr/bin/xattr", "-w", "com.apple.quarantine",
                    "0083;" + Long.toHexString(java.time.Instant.now().getEpochSecond())
                            + ";MegaMek Launcher;" + UUID.randomUUID(), file.toString()));
        }
    }

    private void inspectMacPackage(Path file, String version) throws IOException, InterruptedException {
        Path inspection = Files.createTempDirectory("mm-launcher-pkg-identity-").toRealPath();
        Path distribution = inspection.resolve("Distribution");
        Exception failure = null;
        try {
            output(List.of("/usr/bin/xar", "-x", "-f", file.toString(), "-C", inspection.toString(),
                    "Distribution"));
            if (!Files.isRegularFile(distribution, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("macOS launcher package Distribution is not a regular file");
            try (var input = Files.newInputStream(distribution, LinkOption.NOFOLLOW_LINKS)) {
                byte[] bytes = input.readNBytes(65537);
                if (bytes.length > 65536) throw new IOException("macOS launcher package Distribution exceeds limit");
                verifyDistribution(new String(bytes, StandardCharsets.UTF_8), version);
            }
        } catch (IOException | InterruptedException | RuntimeException error) {
            failure = error;
            throw error;
        } finally {
            try {
                Files.deleteIfExists(distribution);
                Files.delete(inspection);
            } catch (IOException cleanup) {
                if (failure == null) throw cleanup;
                failure.addSuppressed(cleanup);
            }
        }
    }

    static void verifyDistribution(String xml, String version) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
            if (!"installer-gui-script".equals(document.getDocumentElement().getTagName()))
                throw new IOException("Unexpected macOS launcher package distribution");
            var refs = document.getElementsByTagName("pkg-ref");
            int declarations = 0;
            for (int i = 0; i < refs.getLength(); i++) {
                var attributes = refs.item(i).getAttributes();
                var pkgVersion = attributes.getNamedItem("version");
                if (pkgVersion == null) continue;
                var id = attributes.getNamedItem("id");
                if (id == null || !MAC_ID.equals(id.getNodeValue())
                        || !macVersion(version).equals(pkgVersion.getNodeValue()))
                    throw new IOException("macOS launcher package identifier or version does not match release");
                declarations++;
            }
            if (declarations != 1) throw new IOException("Expected one macOS launcher application package");
        } catch (javax.xml.parsers.ParserConfigurationException | SAXException error) {
            throw new IOException("Cannot read macOS launcher package identity", error);
        }
    }

    List<String> handoffCommand(Path file, String version, String sha256, Path report, String runningVersion)
            throws IOException, InterruptedException {
        verifyInstalledVersion(runningVersion);
        if (LauncherSelfUpdate.compare(version, runningVersion) <= 0 || sha256 == null
                || !sha256.matches("[0-9a-fA-F]{64}"))
            throw new IOException("Invalid native launcher update");
        if (!file.getFileName().toString().equals("MegaMek-Launcher-" + version + "-" + kind.suffix)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !file.equals(file.toRealPath()))
            throw new IOException("Staged launcher installer path was replaced");
        String manager = kind == Kind.DEB ? "/usr/bin/apt-get" : "/usr/bin/dnf";
        verifyInstallerTools();
        Commands commands = commands(kind, launcher, manager);
        String script = handoffScript(file, version, sha256, report, ProcessHandle.current().pid(), commands);
        return List.of("/bin/sh", "-c", script, "mm-launcher-updater");
    }

    void verifyInstallerTools() throws IOException {
        if (platform != Platform.LINUX) return;
        if (kind == null) throw new IOException("Installed launcher package has not been identified");
        String manager = kind == Kind.DEB ? "/usr/bin/apt-get" : "/usr/bin/dnf";
        if (!executable.test(Path.of("/usr/bin/pkexec")) || !executable.test(Path.of(manager)))
            throw new IOException("Linux launcher updates require PolicyKit and "
                    + (kind == Kind.DEB ? "apt-get" : "dnf") + ".");
    }

    record Commands(String hash, String install, String verify, String reopen, boolean rootCopy) {}

    static Commands commands(Kind kind, Path launcher, String manager) {
        if (kind == Kind.MAC_INTEL || kind == Kind.MAC_ARM) {
            Path bundle = launcher.getParent().getParent().getParent();
            String verify = "test -x " + quote(launcher) + " && test ! -L "
                    + quote(launcher) + " && receipt=$(/usr/sbin/pkgutil --pkg-info "
                    + MAC_ID + ") && [ \"$(printf '%s\\n' \"$receipt\" | "
                    + "/usr/bin/sed -n 's/^package-id: //p')\" = " + quote(MAC_ID)
                    + " ] && version=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleShortVersionString' "
                    + quote(bundle.resolve("Contents/Info.plist"))
                    + ") && identity=$(/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' "
                    + quote(bundle.resolve("Contents/Info.plist")) + ") && [ \"$identity\" = "
                    + quote(MAC_ID) + " ] && printf '%s' \"$version\"";
            return new Commands("/usr/bin/shasum -a 256",
                    "/usr/bin/open -W -n -b com.apple.installer \"$pkg\"",
                    verify,
                    "/usr/bin/open -n " + quote(bundle), false);
        }
        String root = rootInstallScript(kind, manager);
        String verify = kind == Kind.DEB
                ? "/usr/bin/dpkg-query --show --showformat='${Status}\\t${Version}\\t${Architecture}\\n' "
                    + PACKAGE
                : "/usr/bin/rpm -q --queryformat=" + quote(RPM_FORMAT) + " " + PACKAGE;
        return new Commands("/usr/bin/sha256sum",
                "/usr/bin/pkexec /bin/sh -c " + quote(root) + " mm-launcher-installer \"$pkg\" \"$expected\"",
                "test -x " + quote(launcher) + " && test ! -L " + quote(launcher) + " && " + verify,
                "test -x " + quote(launcher) + " || exit 1\n" + quote(launcher) + " >&2 &\n", true);
    }

    static String rootInstallScript(Kind kind, String manager) {
        String install = quote(manager) + (kind == Kind.DEB ? " -y install \"$safe\""
                : " -y upgrade \"$safe\"");
        // Only a root-owned copy whose hash matches the release is passed to the package manager.
        return "set -eu; umask 077; pkg=$1; expected=$2; "
                + "work=$(/usr/bin/mktemp -d /var/tmp/mm-launcher-update.XXXXXX); "
                + "safe=\"$work/$(/usr/bin/basename \"$pkg\")\"; "
                + "trap '/usr/bin/rm -f \"$safe\"; /usr/bin/rmdir \"$work\"' EXIT; "
                + "/usr/bin/cp \"$pkg\" \"$safe\"; "
                + "actual=$(/usr/bin/sha256sum \"$safe\"); actual=${actual%% *}; "
                + "[ \"$actual\" = \"$expected\" ] || { echo 'Staged package digest changed' >&2; exit 1; }; "
                + install;
    }

    static String quote(String value) { return "'" + value.replace("'", "'\"'\"'") + "'"; }

    static String quote(Path path) {
        return quote(path.toString().replace(java.io.File.separatorChar, '/'));
    }

    static String handoffScript(Path file, String version, String sha256, Path report, long parent,
                                Commands commands) throws IOException {
        String nativeVersion = commands.rootCopy()
                ? file.toString().endsWith(".deb") ? "install ok installed\t" + version + "\tamd64"
                    : PACKAGE + "\t" + version + "\t1\tx86_64"
                : macVersion(version);
        return """
                set -eu
                umask 077
                """ + "pkg=" + quote(file) + "\n"
                + "dir=" + quote(file.getParent()) + "\n"
                + "report=" + quote(report) + "\n"
                + "expected=" + quote(sha256.toLowerCase(Locale.ROOT)) + "\n"
                + "installerLog=" + quote(LauncherSelfUpdate.installerLog(report)) + "\n"
                + """
                hash_package() {
                    [ -f "$pkg" ] && [ ! -L "$pkg" ] && [ ! -L "$dir" ] || return 1
                """ + "    actual=$(" + commands.hash() + " \"$pkg\") || return 1\n"
                + """
                    actual=${actual%% *}
                    [ "$actual" = "$expected" ]
                }
                publish() {
                    tmp=$(mktemp "${report}.new.XXXXXX")
                    printf '%s' "$outcome" > "$tmp"
                    mv -f "$tmp" "$report"
                }
                echo 'Waiting for launcher shutdown'
                """ + "while kill -0 " + parent + " 2>/dev/null; do sleep 0.5; done\n"
                + "outcome='handoff failed: installer did not complete'\n"
                + (commands.rootCopy() ? "" : """
                if ! hash_package; then
                    outcome='handoff failed: staged package digest changed'
                    publish
                    exit 1
                fi
                """)
                + "echo 'Starting native installer'\n"
                + "if " + commands.install() + " >\"$installerLog\" 2>&1; then\n"
                + "    if installed=$(" + commands.verify() + ") && [ \"$installed\" = "
                    + quote(nativeVersion) + " ]; then\n"
                + "        outcome=" + quote("installed:" + version) + "\n"
                + """
                    else
                        outcome='handoff failed: requested installed version could not be confirmed'
                    fi
                else
                    code=$?
                    outcome="handoff failed: installer exited with $code"
                fi
                if hash_package; then
                    if ! rm -f "$pkg" || ! rmdir "$dir"; then
                        echo 'Staged installer cleanup failed' >&2
                """ + "        case \"$outcome\" in installed:*) outcome="
                    + quote("installed-cleanup-warning:" + version) + ";; esac\n"
                + """
                    fi
                else
                    echo 'Staged installer changed; cleanup refused' >&2
                """ + "    case \"$outcome\" in installed:*) outcome="
                    + quote("installed-cleanup-warning:" + version) + ";; esac\n"
                + """
                fi
                publish
                echo "$outcome"
                case "$outcome" in
                    installed:*|installed-cleanup-warning:*)
                        if ! (
                """ + commands.reopen() + "\n"
                + """
                        ); then
                            echo 'Launcher could not be reopened' >&2
                """ + "            case \"$outcome\" in\n"
                + "                installed-cleanup-warning:*) outcome="
                    + quote("installed-cleanup-reopen-warning:" + version) + ";;\n"
                + "                *) outcome=" + quote("installed-reopen-warning:" + version) + ";;\n"
                + "            esac\n"
                + """
                            publish
                        fi
                        ;;
                esac
                """;
    }
}
