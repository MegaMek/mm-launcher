# Portable archive distribution contract

## Native installer CI

The generic archive described below remains a developer-only prototype;
CI does not build, verify or upload any archives. CI builds five native
installer/checksum pairs, each on its target host:
`MegaMek-Launcher-<version>-windows-x64.msi`,
`MegaMek-Launcher-<version>-linux-x64.deb`,
`MegaMek-Launcher-<version>-linux-x64.rpm`, and
`MegaMek-Launcher-<version>-macos-{intel,apple-silicon}.pkg`.
Each has an exact-name `.sha256` counterpart. MSI uses jpackage's
full-module runtime and WiX 3.14; Linux and macOS use a host-native
`ALL-MODULE-PATH` jlink image passed to jpackage via `--runtime-image`.
These images retain `bin/java` for games, `lib/modules`, and upstream
`legal/` notices. CI only inspects installer contents and checksums;
it never installs them or publishes a release. Signing and notarization
remain pending.

Linux's stable package name is `megamek-launcher` under `/opt`; the macOS
bundle identifier is `org.megamek.launcher` under `/Applications`.
Both own only application files. Registry, settings, logs and installed
games live in independent user paths and must not be overwritten by package
upgrades. The Windows MSI retains its stable per-user installation path and
upgrade UUID described below. The archive tasks are still available for
development (including `verifyPortableArchive` and `verifyProvidedArchive`),
but are not part of the CI installer contract.
The MSI keeps the permanent upgrade UUID and per-user fixed binary directory
`%LOCALAPPDATA%\Programs\MegaMek Launcher` (no folder chooser). This is
separate from mutable registry state under `%LOCALAPPDATA%\MegaMek Launcher`,
which MSI removal must not own. Its self-contained WiX 3 UI override shows
`[INSTALLDIR]` on Welcome after MSI directory costing, then a modeless progress
dialog before `ExecuteAction` (a modal dialog there would block the install).
Welcome has a checked-by-default Create a desktop shortcut option. This uses
jpackage's own conditional `DesktopFolder` shortcut component, not a shell
script: unchecking it disables that component. An HKCU installer-owned opt-out
registry component records only the unchecked choice; AppSearch restores it
before jpackage's early `RemoveExistingProducts` during silent `/qn` upgrades.
An interactive upgrade may change that choice on Welcome. Removing the MSI
removes only its owned shortcut and preference component, not unrelated desktop
entries. The Start menu shortcut is independent. The interactive completion
screen displays a checked Launch MegaMek Launcher box on the interactive finish
dialog; only the finish button publishes the launcher custom action. Both
options use one native WiX CheckBox control each, with the label inside its
clickable area. The cropped artwork occupies only the left side of Welcome and
Finish; the right-side controls sit on the native light dialog background, not
a baked cream panel. Silent `/qn` MSI upgrades cannot run that UI action.
The installed EXE and MSI icon share an ICO derivative of MegaMek.png; the
left-strip wizard BMP and progress banner derive from bundled first-launch art.
The MSI artifact keeps the Gradle version in its filename, while Windows
Installer ProductVersion uses the numeric portion (for example, `0.1.0` for
`0.1.0-SNAPSHOT`), as required by jpackage/MSI.
Regenerate the BMPs with Pillow using
`python src/distribution/windows/msi/generate_art.py`; the source artwork stays
in `artwork/`. Static MSI table inspection cannot certify an interactive
installation; this build never runs `msiexec`. These icons
do not change Windows Explorer's `.msi` file association. The internal
`mm-launcher` CLI name remains compatible.
The macOS app bundle includes a MegaMek.png-derived ICNS in `Contents/Resources`.

## Scope

MegaMek Launcher produces one versioned all-platform `tar.gz`. The user extracts the archive and starts
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
MegaMek-Launcher-<version>.tar.gz
MegaMek-Launcher-<version>.tar.gz.sha256
```

The archive has one top-level `MegaMek Launcher/` root:

```text
MegaMek Launcher/
├── MegaMek Launcher.exe
├── MegaMek Launcher.app/
│   └── Contents/
│       ├── Info.plist
│       ├── MacOS/MegaMek Launcher
│       └── app/lib/*.jar
├── mm-launcher
├── README.txt
├── THIRD-PARTY-NOTICES.txt
└── third-party-licenses/
```

`MegaMek Launcher.app/Contents/app/lib/` is the one shared application/dependency payload. Owning the
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
MegaMek Launcher --version [--report <new-absolute-file>]
MegaMek Launcher --startup-check [--registry <absolute-path>] [--report <new-absolute-file>]
```

Diagnostics do not open Swing, access the network, or create registry state. `--report` exists so
automation can read a result from the Windows GUI-subsystem EXE; it requires an explicit absolute
new file and will not overwrite an existing file. Diagnostic output includes only version/build,
Java feature, packaged-code status, the code-source file name, and whether a registry override was
validated—never an absolute machine path, environment dump, or secret.

Java selected here starts MegaMek Launcher itself. It does not alter the independent per-game Java
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

Archive verification is developer-only; the GitHub Actions workflow no longer
builds or verifies archives. It builds native installers on the following runners
and inspects checksums, metadata and runtime payload without installing anything:

| Runner | Tested architecture intent |
| --- | --- |
| `windows-2025` | Windows x64 |
| `ubuntu-24.04` with Xvfb | Linux x64 |
| `macos-15-intel` | macOS Intel |
| `macos-15` | macOS Apple Silicon |

Runner labels describe build coverage, not a minimum supported OS promise. The workflow does not create a Release, write
repository contents, use secrets, sign, notarize, or publish anything. Native Windows has been
exercised locally; native macOS/Linux and both Mac architectures remain pending the user's CI run.
Windows ARM and Linux ARM are not qualified by this matrix.

## Licensing and signing limitations

Dependency JARs stay unmodified and retain embedded `META-INF` legal material. Packaging also
extracts available dependency `LICENSE`, `NOTICE`, and `DEPENDENCIES` files into the one common
`third-party-licenses/` location. This is not a claim about MegaMek Launcher's own source license: the
repository currently has no tracked root `LICENSE` or `NOTICE`, so a public licensing decision is
a future release prerequisite. No project copyright, trademark, icon, signing identity, or
notarization identity is invented by the packaging.

The current archive is an unsigned prototype and the app is not notarized. Users must not be
instructed to disable or bypass Gatekeeper, SmartScreen, or another security control.
Signing/notarization and release assembly remain explicit future owner actions.
