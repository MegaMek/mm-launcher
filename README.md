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

## Per-application Home and installation management

After onboarding, Home is application-oriented. MegaMek, MekHQ, and MegaMekLab each have an
independent preferred installation. Every application found across the union of registered
static package records gets an equal gold split control whose primary label includes its preferred
version, such as **Launch MekHQ 0.51.0**. The arrow lists the other records containing that
application in deterministic registry order. An alternate row launches only that exact captured
record and never changes a preference.

Registry schema 2 stores those targets by application key and stable installation UUID while
retaining the legacy default field and CLI APIs. Reading schema 1 derives preferences only for
applications actually contained by the old default and performs no write. Missing or stale
targets use the first matching record as a side-effect-free runtime fallback. Registration fills
only still-unset application preferences; removal clears only preferences that pointed to the
removed record. Strict parsing, canonical roots, locked mutation, and atomic replacement remain
required.

Home has no visible global “Main” block or per-copy update buttons. It shows a compact aggregate:
**All installed versions are up to date**, **N installations have updates**, or
**Some versions could not be checked**. Unknown and launch-only copies never produce a false
all-current result. Update, retry, recovery, Java, channel, location, removal, and explicit
**Use as preferred for …** actions live on dark-teal installation cards. There is no bulk update.
A successful install leaves only a low-prominence one-line notice such as
**MekHQ 0.51.0 is ready**; navigation or a launch action clears it.

Settings are immediate. **Check installed versions when the launcher opens** is a launcher-wide
master gate, defaulting on when absent; it does not rewrite any per-installation channel or
check-on-open choice, so explicit Off stays Off. **Change default Java** validates and atomically
stores one external Java 21+ executable for new normal installs and imports. Existing records keep
their own Java. When the setting is absent, the compatible launcher runtime is used lazily without
being persisted. No JRE is downloaded or bundled.

Determinate download bars show only a localized percentage; the detail line uses human-readable
sizes (for example, **Downloaded 282 MB of 690 MB**). Extraction remains indeterminate and reports
processed files below the bar. This source checkpoint updates source, tests, and contracts only:
the executable/archive remains stale, and compilation, tests, runtime launch, and visual approval
are deliberately deferred.

## Developer CLI and graphical review

With no registered copy, the artwork-led Home has an accessible split
**Install latest MekHQ Milestone** control. Its large primary segment remains a one-click route to the
normal confirmation for the current official Milestone MekHQ suite, which contains MegaMek,
MekHQ, and MegaMekLab. Its validated version is added to the button label, for example
**Install latest MekHQ Milestone (0.51.0)**; loading and unavailable states never invent a
version. The separate arrow opens a
styled dark-teal/gold popup containing exactly:

1. **Install latest MegaMek Milestone**
2. **Install latest MegaMekLab Milestone**
3. **Install latest MekHQ Development**
4. **Install latest MegaMek Development**
5. **Install latest MegaMekLab Development**

Each available row includes its independently validated version in parentheses. One background
snapshot reads `stable`/`dev` once, validates exact release assets for all three official
repositories, and shares metadata when both channels name the same repository/tag. Ordinarily
opening the menu starts no request. Loading or unavailable rows are truthfully labeled and
disabled; after viewing unavailable rows, closing and reopening is the documented explicit
snapshot retry. The main choice remains available and retries its own normal plan. A visible secondary
**Use existing installation** action, captioned **MegaMek, MekHQ, or MegaMekLab**, imports a
portable copy without moving it. The first page has no Java prerequisite/download control and no
healthy-first-launch advanced picker. The exact product/release/channel picker remains on
Installations after a copy exists or when repair/recovery navigation is relevant.

The first successful import or normal install initializes only still-unset preferences for the
applications it actually contains; later or concurrent operations never replace explicit
preferences. Imported copies are launch-only and create no receipt/channel provenance. Home uses
the union of products statically detected across registered copies. There is no simple/advanced
mode toggle. The layout adapts to narrow windows and Java's per-monitor HiDPI scaling. See the
[first-launch presentation notes](docs/first-launch-ui.md).

Managed Home keeps that composition: full-width cover artwork on top and a compact, scrollable
dark control deck below, with no duplicate header/footer or left artwork rail. It renders the
per-application split controls and aggregate installation-level update summary described above.
Missing Java, a missing root, or pending recovery is routed to the exact installation card.
Imported/no-receipt copies remain launch-only and are never offered impossible update actions.

The existing `application`/`installDist` entry point remains the CLI. Requires Java 21. From the
launcher checkout, build and run the graphical subcommand in PowerShell:

```powershell
Set-Location C:\repos\megamek\mm-launcher
.\gradlew.bat installDist
if ($LASTEXITCODE -ne 0) { throw "Build failed with exit code $LASTEXITCODE." }
.\build\install\mm-launcher\bin\mm-launcher.bat gui
```

Review Home, Installations, Settings, the split **Install latest MekHQ Milestone** control,
**Import existing copy…**, **Add exact release…**, Preview, Update, recovery, Java selection, and
launch confirmation. The primary normal first install resolves only the fixed website `stable`
value for the MekHQ repository. Each popup item binds its displayed official repository and
`stable` or `dev` channel; no selection can substitute a different product, channel, title, or
bundle.
Before package transfer it shows a dedicated dark-teal/gold confirmation headed with the selected
product and channel. Its concise summary gives the validated version, actual included programs,
binary download size, and full selectable destination.
**Change location** chooses an existing parent and safe new subfolder, then produces a fresh
immutable quote for that same product/channel pair. The already-validated launcher Java is not a
happy-path choice here. There is no update-behavior row or technical-details toggle in this
simple confirmation. The backend still binds and revalidates the exact repository, source, tag,
asset identity/digest, Java, Settings revision, registry, and Main snapshot; operation logs retain
failures.

With the ordinary GUI registry, launcher-managed application payload/support data defaults to
`%LOCALAPPDATA%\MegaMek\<product channel>` on Windows,
`~/Library/Application Support/MegaMek/<product channel>` on macOS, and
`${XDG_DATA_HOME:-~/.local/share}/MegaMek/<product channel>` on Linux. A relative
`XDG_DATA_HOME` is ignored. The product/channel child, such as `MekHQ Milestone`, is required
because registry and receipt metadata may use files in the same support root; the payload is never
installed directly as the `MegaMek` root. An explicit
`gui --registry` override, and custom registries supplied to `LauncherServices`, intentionally
retain `<registry parent>/installations/<product channel>` for disposable isolation. The macOS
path is managed payload/support data, not content inside `MM Launcher.app`.
Confirmation is required before any package byte or destination/state write. Metadata drift, an
appeared destination, or registry/Main drift rejects the attempt for fresh consent; no choice
falls back to another repository/channel, a different tag, or “latest.” MekHQ requires the exact
three-program suite; standalone MegaMek and MegaMekLab packages must inspect as only their actual
product, so managed Home never gains launch buttons from a mixed or title-inferred bundle.

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
dialog with a compact dark-teal/gold presentation and typed phases: metadata, download,
verification, extraction, planning, consent, installation preparation, Apply/recovery, and
cleanup. Byte totals are shown for package transfer and retained-package verification. File totals
are shown only when known; extraction remains indeterminate when the archive has no trustworthy
total while its typed event supplies a localized **N files processed** detail under
**Extracting application files**. Safe user-facing typed details are preferred; paths, URLs, and
digests fall back to phase text. Updates are coalesced before reaching Swing. Phase, bar, and
detail sit directly on the dialog background without an inner framed backplate. Backend text
remains in a hidden bounded legacy capture and structured
local logs; it does not create a visible log pane or blank space. While work is cancellable, the
only bottom action is the same vector-painted dark secondary **Cancel** used by launcher controls,
including its whole-control custom focus outline. Contextual repair actions remain absent until a failure needs
them. After the atomic cutoff, Cancel disappears and the concise status asks the user to keep the
launcher open.

Cancellation is safe only before publication or transaction/recovery finalization. A fresh install
stops accepting cancellation immediately before its extracted destination is published; registry,
receipt, and channel finalization then run without interruption. Update stops accepting it
immediately before `RealUpdateService` creates `.mm-launcher-update` and its pending transaction.
Recovery is non-cancellable before any replay or cleanup. A request that loses one of those atomic
cutoff races is denied with the reason and never interrupts the worker. Accepted cancellation
closes only the operation-owned pending response, checkpoints hashing/extraction/planning, removes
only owned temporary data, closes the progress window, and returns to Home or the current page
with a cancelled status, not success or a rollback claim. It never kills a game process.

A successful normal install closes progress immediately, returns directly to managed Home, and
shows the process-local one-line **product version is ready** notice. The registered copy
initializes only still-unset included-application preferences; existing explicit preferences are
preserved.
There is no install-complete modal and nothing is launched automatically. Failure stays in the
same compact progress surface with a concise sanitized summary, **Close**, and failure-only
**View details**; the generic error dialog is not also opened. A retained published copy adds
**Open Installations** only after that repair condition is known.

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

The primary normal GUI install follows Milestone. Normal and advanced new GUI installs use the
Settings **check on open** default, initially enabled until the user explicitly changes it for
future installs. That immutable choice remains bound to the backend plan but is not a passive row
in the simple confirmation. A changed or unreadable setting requires a fresh confirmation before
transfer or destination creation. Existing explicit false choices remain false, and
legacy/unknown/corrupt
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
always create a new subfolder. The normal destination can create at most four individually quoted
real parent directories after consent; the product/channel target itself must be wholly absent.

See the [real-update contract](docs/real-update-contract.md), [GUI contract](docs/gui-contract.md),
the [channel/check contract](docs/channel-update-contract.md), and
[read-only preview contract](docs/update-preview-contract.md).
The archived command-line fresh-install walkthrough remains in
[docs/cli-fresh-install.md](docs/cli-fresh-install.md).
