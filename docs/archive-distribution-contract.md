# Portable archive distribution contract

## Scope

MM Launcher produces separate Windows, macOS, and Linux downloads. These are portable archives:
the user extracts one archive and starts one obvious desktop entry point. They are not installers,
do not write startup integration, do not elevate, and do not contain or install a Java runtime.
This contract does not infer package counts or platform policy from any current upstream
MegaMek/MekHQ asset inventory.

The Java application JAR and resolved dependency JARs are the shared payload in every archive.
The ordinary Gradle `application`, `run`, and `installDist` behavior continues to use
`org.megamek.launcher.Main` for developer CLI use. Desktop artifacts instead enter through
`DesktopLauncher`, which opens the existing GUI by default. The application's generic `distZip`
and `distTar` tasks are intentionally disabled so they cannot create universal distribution
outputs.

## Declared artifacts and fixed roots

All file names include the project version:

| OS | Archive | Only top-level root | Desktop entry point |
| --- | --- | --- | --- |
| Windows x64 | `MM-Launcher-<version>-Windows-x64.zip` | `MM Launcher/` | `MM Launcher.exe` |
| macOS | `MM-Launcher-<version>-macOS.zip` | `MM Launcher.app/` | app bundle / `Contents/MacOS/MM Launcher` |
| Linux x64 | `MM-Launcher-<version>-Linux-x64.tar.gz` | `MM Launcher/` | `mm-launcher` |

Windows uses Launch4j's classic GUI header and an `asInvoker` application manifest. The small
embedded Java bridge locates only the extracted external `lib` payload; it is not a runtime.
The macOS ZIP contains a normal `APPL` bundle with `Info.plist`, `CFBundleExecutable`, and payload
under `Contents/app/lib`. The macOS archive is architecture-neutral because both the application
and bootstrap are Java/shell content and select an external host JVM. Linux's root script and the
macOS `Contents/MacOS` bootstrap are LF files with executable archive mode `0755`.

Archive tasks disable source-file timestamps and request reproducible file ordering. The Java and
script archives are expected to reproduce from identical inputs. No claim of bit-identical native
EXE output is made. SHA-256 tasks hash the one declared output path for their task and write
`<archive-name>.sha256`; they do not glob the distributions directory.

## External Java and arguments

All entry points require Java 21 or newer and never download or install it.

- Launch4j searches compatible external Java and presents explicit missing/old-runtime messages.
- Linux honors an executable `$JAVA_HOME/bin/java`, otherwise `java` on `PATH`.
- macOS also asks `/usr/libexec/java_home` for Java 21+ before its `PATH` fallback so Finder
  launches do not depend on an interactive-shell environment.

The POSIX launchers quote each path and forward arguments with `"$@"`; they use neither `eval` nor
constructed shell commands. The desktop entry point accepts only the existing optional
`--registry <absolute-path>` GUI override. Diagnostics are:

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

`verifyWindowsArchive`, `verifyMacArchive`, and `verifyLinuxArchive` check the exact fixed root,
expected application/runtime JAR set, OS metadata and modes, path/case safety, absence of bundled
JRE/JDK and source/state/credential paths, and exact SHA-256 file. Matching hosts extract into a
path containing spaces, metacharacters, and non-ASCII text, start from an unrelated working
directory, pass an isolated absolute registry path, and require the report to prove that code came
from the extracted application JAR. POSIX host tests also use missing and Java 17 fixtures without
changing system Java.

The prepared read-only GitHub Actions matrix uses these current runner labels:

| Runner | Tested architecture intent |
| --- | --- |
| `windows-2025` | Windows x64 |
| `ubuntu-24.04` | Linux x64, with Xvfb for Swing tests |
| `macos-15-intel` | macOS Intel |
| `macos-15` | macOS Apple Silicon |

These labels describe test coverage, not a minimum supported OS promise. The workflow uploads each
native archive, checksum, and XML test reports as CI artifacts only. It does not create a Release,
write repository contents, use secrets, sign, notarize, or publish anything. Native Windows has
been exercised locally; native macOS/Linux and both Mac architectures remain pending the user's CI
run. Windows ARM and Linux ARM are not qualified by this matrix.

## Licensing and signing limitations

Dependency JARs stay unmodified and retain embedded `META-INF` legal material. Packaging also
extracts available dependency `LICENSE`, `NOTICE`, and `DEPENDENCIES` files beside the payload for
review. This is not a claim about MM Launcher's own source license: the repository currently has no
tracked root `LICENSE` or `NOTICE`, so a public licensing decision is a future release prerequisite.
No project copyright, trademark, icon, signing identity, or notarization identity is invented by
the packaging.

The current archives are unsigned prototypes. Users must not be instructed to disable or bypass
Gatekeeper, SmartScreen, or another security control. Signing/notarization and release assembly
remain explicit future owner actions.
