# Portable archive distribution contract

## Scope

MM Launcher produces one versioned all-platform `tar.gz`. The user extracts the archive and starts
the entry point for Windows, macOS, or Linux from one fixed root. It is not an installer, does not
write startup integration, does not elevate, and does not contain or install a Java runtime. This
matches the current MegaMek/MekHQ single-archive distribution model; it does not infer any broader
platform or package policy from upstream assets.

The Java application JAR and resolved dependency JARs occur once. The ordinary Gradle
`application`, `run`, and `installDist` behavior continues to use `org.megamek.launcher.Main` for
developer CLI use. Desktop artifacts instead enter through `DesktopLauncher`, which opens the
existing GUI by default. The application's generic `distZip` and `distTar` tasks remain
intentionally disabled; the dedicated archive task owns the portable desktop distribution.

## Declared artifact and fixed root

The two declared outputs include the dynamic project version:

```text
MM-Launcher-<version>.tar.gz
MM-Launcher-<version>.tar.gz.sha256
```

The archive has one top-level `MM Launcher/` root:

```text
MM Launcher/
├── MM Launcher.exe
├── MM Launcher.app/
│   └── Contents/
│       ├── Info.plist
│       ├── MacOS/MM Launcher
│       └── app/lib/*.jar
├── mm-launcher
├── README.txt
├── THIRD-PARTY-NOTICES.txt
└── third-party-licenses/
```

`MM Launcher.app/Contents/app/lib/` is the one shared application/dependency payload. Owning the
payload inside the app bundle keeps the macOS app self-contained. The sibling Windows executable
and Linux script resolve that exact same directory. They use no cross-root symbolic links or hard
links, and the app does not depend on sibling mutable files. Windows and Linux users must keep the
extracted tree intact rather than moving an entry point away from the app folder.

Windows uses Launch4j's classic GUI header and an `asInvoker` application manifest. Its small
embedded Java bridge resolves the app-owned payload relative to the executable directory; it is
not a runtime. The macOS app is architecture-neutral Java/shell content with a normal `APPL`
bundle, `Info.plist`, and `CFBundleExecutable`. Linux's root script and the macOS
`Contents/MacOS` bootstrap are LF files with executable archive mode `0755`. Other regular files
are mode `0644`, directories are `0755`, and the archive contains no links.

Archive tasks disable source-file timestamps and request reproducible file ordering. The Java and
script content is expected to reproduce from identical inputs. No claim of bit-identical native
EXE output from independent rebuilds is made. The checksum task hashes only the one declared
archive path and writes a sha256sum-compatible exact-name line; it does not glob the distributions
directory.

## External Java and arguments

All entry points require Java 21 or newer and never download or install it.

- Launch4j searches compatible external Java and presents explicit missing/old-runtime messages.
- Linux honors an executable `$JAVA_HOME/bin/java`, otherwise `java` on `PATH`.
- macOS also asks `/usr/libexec/java_home` for Java 21+ before its `PATH` fallback so Finder
  launches do not depend on an interactive-shell environment.

The POSIX launchers quote the shared classpath and each path, forward arguments with `"$@"`, and
use neither `eval` nor constructed shell commands. The desktop entry point accepts only the
existing optional `--registry <absolute-path>` GUI override. Diagnostics are:

```text
MM Launcher --version [--report <new-absolute-file>]
MM Launcher --startup-check [--registry <absolute-path>] [--report <new-absolute-file>]
```

Diagnostics do not open Swing, access the network, or create registry state. `--report` exists so
automation can read a result from the Windows GUI-subsystem EXE; it requires an explicit absolute
new file and will not overwrite an existing file. Diagnostic output includes only version/build,
Java feature, packaged-code status, the code-source file name, and whether a registry override was
validated—never an absolute machine path, environment dump, or secret.

Java selected here starts MM Launcher itself. It does not alter the independent per-game Java
selection, persistence, update/apply gates, recovery, or other launcher security behavior.

## Program files, user data, and integration

Everything in the extracted fixed root is program material and is treated as read-only by the
desktop bootstrap. In particular, the macOS launcher never stores mutable configuration inside
the app bundle. Existing launcher state remains outside the program root:

- Windows: `%LOCALAPPDATA%\MegaMek\launcher-registry.json`
- macOS: `~/Library/Application Support/MegaMek/launcher-registry.json`
- Linux: `$XDG_STATE_HOME/MegaMek/launcher-registry.json`, or
  `~/.megamek/launcher-registry.json` when `XDG_STATE_HOME` is unset

Linux desktop-file integration is not installed automatically. A future documented desktop-file
step may be offered as an explicit user action, not startup behavior.

## Verification and current qualification

`buildArchive` selects the canonical archive and checksum chain. `verifyArchive` checks the one
fixed root, all three entry points, exact application/runtime JAR set with no duplicate payload,
OS metadata and modes, path/case safety, absence of bundled JRE/JDK and source/state/credential
paths, and exact SHA-256 file. It extracts into a path containing spaces, metacharacters, and
non-ASCII text, starts from an unrelated working directory, passes an isolated absolute registry
path, and requires the report to prove that code came from the extracted application JAR. On a
matching host it natively starts that host's entry point. POSIX host tests also use missing and
Java 17 fixtures without changing system Java; structural tests check both POSIX scripts on every
host and run `sh -n` when `sh` is available.

`verifyProvidedArchive` is a separate, read-only mode requiring explicit `providedArchive` and
`providedChecksum` Gradle properties. It validates the dynamic expected names, existence,
structure, and integrity of those exact files. Its task graph includes test compilation and the
configuration guard but no staging, Launch4j, archive, or checksum producer, so a missing or wrong
checksum fails instead of being regenerated.

The prepared read-only GitHub Actions workflow builds the archive once on Windows, uploads one
named CI artifact containing the archive and checksum, and makes every verifier download those
same bytes. The workflow reports the archive SHA on each job and uses unique platform report
artifact names:

| Runner | Tested architecture intent |
| --- | --- |
| `windows-2025` | Windows x64 |
| `ubuntu-24.04` with Xvfb | Linux x64 |
| `macos-15-intel` | macOS Intel |
| `macos-15` | macOS Apple Silicon |

The macOS and Linux verifiers do not run Launch4j or any packaging task. Runner labels describe
test coverage, not a minimum supported OS promise. The workflow does not create a Release, write
repository contents, use secrets, sign, notarize, or publish anything. Native Windows has been
exercised locally; native macOS/Linux and both Mac architectures remain pending the user's CI run.
Windows ARM and Linux ARM are not qualified by this matrix.

## Licensing and signing limitations

Dependency JARs stay unmodified and retain embedded `META-INF` legal material. Packaging also
extracts available dependency `LICENSE`, `NOTICE`, and `DEPENDENCIES` files into the one common
`third-party-licenses/` location. This is not a claim about MM Launcher's own source license: the
repository currently has no tracked root `LICENSE` or `NOTICE`, so a public licensing decision is
a future release prerequisite. No project copyright, trademark, icon, signing identity, or
notarization identity is invented by the packaging.

The current archive is an unsigned prototype and the app is not notarized. Users must not be
instructed to disable or bypass Gatekeeper, SmartScreen, or another security control.
Signing/notarization and release assembly remain explicit future owner actions.
