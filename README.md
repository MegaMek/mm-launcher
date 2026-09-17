# MegaMek graphical launcher

## Portable archive prototype

MM Launcher is distributed as three separate portable downloads, not an installer and not one
combined archive. Install an external **64-bit Java 21 or newer** runtime, download the archive for
your OS, extract it, and use its one desktop entry point:

- Windows: `MM Launcher.exe` in
  `MM-Launcher-0.1.0-SNAPSHOT-Windows-x64.zip`
- macOS: `MM Launcher.app` in
  `MM-Launcher-0.1.0-SNAPSHOT-macOS.zip`
- Linux: `./mm-launcher` in
  `MM-Launcher-0.1.0-SNAPSHOT-Linux-x64.tar.gz`

The macOS and Windows prototypes are unsigned, and the macOS app is not notarized. Do not bypass
Gatekeeper or other OS security controls. Signing, notarization, and a public source-license
decision remain release prerequisites. No Java runtime is bundled or downloaded by a launcher.

The support goal is all three OSes. The Windows x64 package and native startup smoke are validated
locally on Windows. Native CI is prepared for Windows x64, Linux x64, macOS Intel, and macOS Apple
Silicon, but those CI runs remain pending and user-owned; this Windows checkpoint does not certify
native macOS or Linux execution.

Build one package and its checksum with `windowsArchiveChecksum`, `macArchiveChecksum`, or
`linuxArchiveChecksum`. Build all three locally with:

```powershell
& 'C:\repos\megamek\mm-launcher\gradlew.bat' `
  -p 'C:\repos\megamek\mm-launcher' buildAllArchives verifyArchives
```

Outputs are under `build/distributions/`. `verifyArchives` checks all archive contents and runs a
native entry-point smoke only when the archive matches the current host. `--version` and
`--startup-check` are side-effect-free packaged diagnostics. The desktop launchers also accept
`--registry <absolute-path>` as an isolated test/development override.

See the [archive distribution contract](docs/archive-distribution-contract.md) for layouts,
program-files/user-data separation, native runner coverage, and release limitations.

## Developer CLI and graphical review

The existing `application`/`installDist` entry point remains the CLI. Requires Java 21. From the
launcher checkout, build and run the graphical subcommand in PowerShell:

```powershell
Set-Location C:\repos\megamek\mm-launcher
.\gradlew.bat installDist
if ($LASTEXITCODE -ne 0) { throw "Build failed with exit code $LASTEXITCODE." }
.\build\install\mm-launcher\bin\mm-launcher.bat gui
```

Review **Download MegaMek**, **Use existing copy**, **Preview update**, **Update…**,
**Check for updates**, **Check update recovery…**, **Manage installations**, Java selection, and
launch confirmation.
If a copy is already registered, use
**Manage installations > Download MegaMek** to download another copy without removing the
existing one. With no registered copies, **Download MegaMek** also appears on the home screen.
Every graphical download requires a separate **Milestone** or **Development** choice. The exact
release selected for that first download does not imply a channel.
Preview asks to fetch official releases and shows the exact package and full size before download.
Preview itself remains read-only and has no Apply control. **Update…** is a separate workflow:
it previews first, requires a second confirmation that names the destructive intent and exact
size/digest, acquires the update/launch gate, re-reads provenance, and downloads/replans again
immediately before changing managed files. It is available only to copies freshly downloaded by
this launcher with a valid immutable ownership receipt. Existing, imported, and pre-receipt copies
remain launch-only; local hashes are never converted into provenance.

For an existing receipt-backed copy, select **Choose channel…** on its home screen, choose
**Milestone** (the website's `stable` label) or **Development** (the website's `dev` label), then
select **Check for updates**. Old records intentionally show an unknown channel until this explicit
choice. A check reads the fixed official website data file and exact GitHub release metadata only;
it downloads no package and changes no installed files. If an update is recommended, **Preview
recommended update…** binds both consent steps to that exact repository, tag, asset name, size,
and digest. The advanced exact-release Preview/Update picker remains available and never changes
the followed channel.

The optional per-copy **check on open** setting is off by default. Therefore startup performs no
network access unless the user explicitly opts that copy in. Opt-in checks run in the background;
slow or unavailable metadata does not disable a healthy copy's Launch or Manage actions. A failed
check is shown as unavailable, never as “up to date.”

Before Apply or recovery, close **all** MegaMek, MekHQ, and MegaMekLab processes, including ones
started manually. Launcher-started games are coordinated across launcher GUI/CLI processes for
their full lifetime. The launcher cannot universally detect old or externally started processes
and does not claim that Windows file locks prove closure. It never kills a process.

There are no automatic Applies, launcher self-updates, Nightly channel, bundled Java, elevation,
or administrator requirement. A channel change only changes what a copy watches; it never
downgrades or writes installed files. Downloads need an existing writable parent folder and fresh
installs always create a new subfolder.

See the [real-update contract](docs/real-update-contract.md), [GUI contract](docs/gui-contract.md),
the [channel/check contract](docs/channel-update-contract.md), and
[read-only preview contract](docs/update-preview-contract.md).
The archived command-line fresh-install walkthrough remains in
[docs/cli-fresh-install.md](docs/cli-fresh-install.md).
