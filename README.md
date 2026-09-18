# MegaMek graphical launcher

## Portable archive prototype

MM Launcher is distributed as one versioned all-platform portable archive, not an installer.
Install an external **64-bit Java 21 or newer** runtime, download
`MM-Launcher-0.1.0-SNAPSHOT.tar.gz`, extract it, and keep the fixed `MM Launcher/` tree intact.
Choose the entry point for the current OS:

- Windows: `MM Launcher.exe`
- macOS: `MM Launcher.app`
- Linux: `./mm-launcher`

The one shared application/dependency payload is inside
`MM Launcher.app/Contents/app/lib/`. This keeps the macOS app self-contained; the sibling Windows
and Linux entry points deliberately use that same payload. Do not move the Windows executable or
Linux script away from the sibling app folder.

The macOS and Windows prototypes are unsigned, and the macOS app is not notarized. Do not bypass
Gatekeeper or other OS security controls. Signing, notarization, and a public source-license
decision remain release prerequisites. No Java runtime is bundled or downloaded by a launcher.

The support goal is all three OSes. The archive's Windows x64 entry point and native startup smoke
are validated locally on Windows. Native CI is prepared to download and validate the exact same
archive bytes on Windows x64, Linux x64, macOS Intel, and macOS Apple Silicon. Those CI runs remain
pending and user-owned; this Windows checkpoint does not certify native macOS or Linux execution.

Build the one archive and its checksum, then verify its full structure and current native entry
point with:

```powershell
& 'C:\repos\megamek\mm-launcher\gradlew.bat' `
  -p 'C:\repos\megamek\mm-launcher' buildArchive verifyArchive
```

The only current outputs are `MM-Launcher-<version>.tar.gz` and its exact-name `.sha256` file under
`build/distributions/`. `verifyArchive` checks all three entry points, the single shared payload,
and checksum, then runs the current host's native entry point. `verifyProvidedArchive` is the
separate read-only CI mode and requires explicit `-PprovidedArchive=<path>` and
`-PprovidedChecksum=<path>` inputs; it never depends on packaging. `--version` and
`--startup-check` are side-effect-free packaged diagnostics. The desktop launchers also accept
`--registry <absolute-path>` as an isolated test/development override.

See the [archive distribution contract](docs/archive-distribution-contract.md) for layouts,
program-files/user-data separation, native runner coverage, and release limitations.

## Developer CLI and graphical review

With no registered copy, the artwork-led Home has one primary **Download & install** action. It
clearly offers the current official **Milestone MekHQ suite**, which contains MegaMek, MekHQ, and
MegaMekLab. The screen presents **Download & install** followed by its **Milestone** caption,
without repeating installer details. The smaller **Advanced Options** button opens the Installations
page for importing an existing portable copy
or adding an advanced exact release; Settings contains diagnostics. There is no simple/advanced
mode toggle. The layout adapts to narrow windows and Java's per-monitor HiDPI scaling. See the
[first-launch presentation notes](docs/first-launch-ui.md).

The existing `application`/`installDist` entry point remains the CLI. Requires Java 21. From the
launcher checkout, build and run the graphical subcommand in PowerShell:

```powershell
Set-Location C:\repos\megamek\mm-launcher
.\gradlew.bat installDist
if ($LASTEXITCODE -ne 0) { throw "Build failed with exit code $LASTEXITCODE." }
.\build\install\mm-launcher\bin\mm-launcher.bat gui
```

Review Home, Installations, Settings, **Download & install**, **Import existing copy…**,
**Add exact release…**, Preview, Update, recovery, Java selection, and launch confirmation.
The normal first install resolves only the fixed website `stable` value for the MekHQ repository.
Before package transfer it shows an immutable quote containing repository, tag, asset name/URL,
size, digest, proposed per-user destination, and validated external Java. **Change location** and
**Choose Java** produce a fresh quote. Confirmation is required before any package byte or
destination/state write. Metadata drift, an appeared destination, or registry/Main drift rejects
the attempt for fresh consent; it never falls back to Development, a different tag, or “latest.”

The Installations page's advanced exact-release flow retains the explicit independent
**Milestone** or **Development** choice. Its exact selected release does not imply a channel.
Advanced graphical installs use the Settings default for background checks, initially on; changing
that default never rewrites an existing copy's explicit true/false/unknown preference.
Preview asks to fetch official releases and shows the exact package and full size before download.
Preview itself remains read-only and has no Apply control. **Update…** is a separate workflow:
it previews first, requires a second confirmation that names the destructive intent and exact
size/digest and the captured installation name/path, then acquires the update/launch gate,
re-reads provenance, refreshes small release metadata, re-verifies the retained archive, safely
re-extracts it, and replans immediately before changing managed files. One GUI Update attempt
downloads the full package exactly once; the second confirmation states that it is already
downloaded and no second package transfer occurs. It is available only to copies freshly
downloaded by this launcher with a valid immutable ownership receipt. Existing, imported, and
pre-receipt copies remain launch-only; local hashes are never converted into provenance.

For an existing receipt-backed copy, open **Installations > Channel & checks…**, choose
**Milestone** (the website's `stable` label) or **Development** (the website's `dev` label), then
select **Check selected**. Old records intentionally show an unknown channel until this explicit
choice. A check reads the fixed official website data file and exact GitHub release metadata only;
it downloads no package and changes no installed files. If an update is recommended, **Update to
recommended release…** binds both consent steps to that exact installation, preference, repository,
tag, asset name, size, and digest and also uses one full-package transfer for the attempt. The
advanced exact-release Preview/Update picker remains available and never changes the followed
channel.

Prepared package data is process-local and attempt-scoped, not a persistent/offline cache or
filesystem token. Cancel, success, and ordinary failure remove only that attempt's random temporary
workspace; no later GUI or CLI operation resumes or adopts it. A process crash can leave that exact
temporary directory for manual diagnosis, and startup does not broadly delete such directories.
Standalone **Preview update** and `preview-update` remain separate read-only operations that dispose
their own workspaces. Standalone CLI `apply-update` keeps its independent one-download contract and
does not share packages with an unrelated preview or arbitrary path.

Fresh download, standalone Preview, prepared Update, and recovery use one functional operation
dialog with typed phases: metadata, download, verification, extraction, planning, consent,
installation preparation, Apply/recovery, cleanup, and final outcome. Byte totals are shown for
package transfer and retained-package verification. File totals are shown only when known;
otherwise the progress bar remains indeterminate. Updates are coalesced before reaching Swing.
The dialog's **Cancel** and **View logs** actions remain usable while ordinary launcher actions are
disabled.

Cancellation is safe only before publication or transaction/recovery finalization. A fresh install
stops accepting cancellation immediately before its extracted destination is published; registry,
receipt, and channel finalization then run without interruption. Update stops accepting it
immediately before `RealUpdateService` creates `.mm-launcher-update` and its pending transaction.
Recovery is non-cancellable before any replay or cleanup. A request that loses one of those atomic
cutoff races is denied with the reason and never interrupts the worker. Accepted cancellation
closes only the operation-owned pending response, checkpoints hashing/extraction/planning, removes
only owned temporary data, and reports **Cancelled**, not success or a rollback claim. It never
kills a game process.

Structured local operation/error logs are stored beside the registry in
`<registry-name>.launcher-logs/`, never inside an installation, fresh destination, or profile data.
The default retention is 20 closed logs of at most 1 MiB each. Matching files are deleted only
after their schema, registry binding, and operation UUID prove launcher ownership; unknown files
and links are retained. **View logs** is available in Settings and reads logs
asynchronously into bounded plain text. **Copy sanitized details/log** is explicit; there is no
automatic clipboard use, upload, telemetry, browser launch, or file-browser path input.

Persisted and copied details remove URL user information/query/fragment data, bearer values,
password/secret/token fields, and configuration-source snippets from Jackson parse chains before
storage or display. Home-directory prefixes are shortened to `~`; review local diagnostics before
sharing. The logs can still contain other absolute local paths needed to diagnose placement.
Unsafe/corrupt registry placement, links/reparse points, permissions, or log I/O fail explicitly.
Logging failure never changes the operation result, masks an original failure, or removes update
journals/backups. The current checkpoint adds these functional surfaces only; broader empty,
imported, managed, and recovery-state visual polish remains a separate UI/UX checkpoint.

Normal new GUI installs follow Milestone. Normal and advanced new GUI installs use the Settings
**check on open** default, initially enabled until the user changes it. The normal confirmation
shows On or Off; a changed or unreadable setting requires a fresh confirmation before transfer or
destination creation. Existing explicit false choices remain false, and legacy/unknown/corrupt
channel state is never inferred or migrated. The CLI still uses false when its legacy option is
omitted. Only explicitly enabled copies are checked, serially and in the background; slow or
unavailable metadata does not disable healthy launch or page navigation. No check downloads or
applies a package. A failed check is shown as “Could not check,” never as “Up to date.”

Before Apply or recovery, close **all** MegaMek, MekHQ, and MegaMekLab processes, including ones
started manually. Launcher-started games are coordinated across launcher GUI/CLI processes for
their full lifetime. The launcher cannot universally detect old or externally started processes
and does not claim that Windows file locks prove closure. It never kills a process.

There are no automatic Applies, launcher self-updates, Nightly channel, bundled Java, elevation,
or administrator requirement. A channel change only changes what a copy watches; it never
downgrades or writes installed files. Advanced downloads need an existing writable parent and
always create a new subfolder. The normal destination can create only its bounded launcher-owned
state and `installations` parents after consent; the final `Main` target must be wholly absent.

See the [real-update contract](docs/real-update-contract.md), [GUI contract](docs/gui-contract.md),
the [channel/check contract](docs/channel-update-contract.md), and
[read-only preview contract](docs/update-preview-contract.md).
The archived command-line fresh-install walkthrough remains in
[docs/cli-fresh-install.md](docs/cli-fresh-install.md).
