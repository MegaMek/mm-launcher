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
static package records gets an equal gold split control whose primary label uses the shared
**Program Channel (Version)** format, such as **Launch MekHQ Milestone (0.51.0)**. The arrow lists the other records containing that
application in deterministic registry order. An alternate row launches only that exact captured
record and never changes a preference.

Primary and alternate launch actions are direct: there is no command/runtime confirmation dump.
The existing backend still re-reads the captured registry record, statically reinspects the root
and product layout, validates external Java, and acquires the root/process coordinator. Only after
the BusyGate accepts the action does the launcher iconify into the normal taskbar/Dock while it
keeps running and holding coordination for the child's full lifetime. Exit zero leaves the
launcher minimized without a completion dialog or foreground request. A start exception or
nonzero exit restores the window and shows the explicit sanitized error. A busy action does not
minimize and retains the **Please wait** response. This is normal window minimization, not tray
integration, disposal, detachment, or launcher exit.

Registry schema 3 stores those targets by application key and stable installation UUID. It is a
clean pre-release schema: older shapes, including records containing per-copy Java, are rejected
without migration. Missing or stale
targets use the first matching record as a side-effect-free runtime fallback. Registration fills
only still-unset application preferences; removal safely retargets affected preferences to the
first remaining compatible copy. Strict parsing, canonical roots, locked mutation, and atomic
replacement remain required.

Home has no visible global “Main” block, per-copy update buttons, or a second navigation control
for update status. Its single **Installations** button gains a known count such as
**Installations (2 updates)** when checked installations have updates; unknown and launch-only
copies never create a false count. Update, retry, recovery, channel, location, removal, and explicit
**Use as preferred for …** actions live on dark-teal installation cards. A managed card shows its
read-only **Channel: Milestone/Development** identity and directly shows
**Check for updates when the launcher opens**. This per-card checkbox is the sole automatic-check
setting and saves immediately without changing the fixed channel. An imported card says
**Imported copy · Launch only · Updates unavailable** and has
no channel, check, preview, update, or recovery action; only its separate opt-in
**Enable managed updates…** action is available. There is no bulk update.
Managed Home has one top-left information area for useful aggregate update state:
**Checking for updates…**, **All installations are up to date**,
**N installations have updates**, or **Some installations could not be checked**. It does not
repeat an obvious post-install “ready” message or place status text above navigation.

Settings contains only **Game Java** and **Diagnostics**. **Change default Java** validates and
atomically stores the sole external Java 21+ executable for every installation. Launch and
explicit launch preview resolve and revalidate this setting; installation records contain no Java
path or feature. With no saved default, the exact Java runtime executing MM Launcher is the
automatic effective runtime and is not persisted. An invalid or corrupt explicit setting fails
visibly instead of silently falling back. The
pre-release settings schema contains no global update
fields and deliberately does not migrate older settings schemas. Settings presents unboxed,
left-aligned sections; the Java version and
regular-font path share a row with the change action, and **View logs** opens a styled local viewer.
Settings displays the effective runtime whether it is automatic or explicitly selected. The
selector uses the same dark-teal/gold controls. No JRE is
downloaded, installed, or bundled; Java acquisition remains deferred.

Determinate download bars show only a localized percentage; the detail line uses human-readable
sizes (for example, **Downloaded 282 MB of 690 MB**). Extraction remains indeterminate and reports
processed files below the bar.

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
snapshot per launcher-window session reads `stable`/`dev` once, validates exact release assets for
all three official repositories, and shares metadata when both channels name the same
repository/tag. While it loads, both install segments are visibly disabled and removed from
keyboard focus, while **Use existing installation** remains available. Failure keeps installation
unavailable and exposes one explicit **Retry version check** command; concurrent retries are
deduplicated. The first successful immutable snapshot is retained for the frame lifetime, so Home
reloads, confirmation cancellation, location changes, and repeated quote opens preserve the same
labels without another current-channel request. A visible secondary
**Use existing installation** action, captioned **MegaMek, MekHQ, or MegaMekLab**, imports a
portable copy without moving it. After the folder chooser, static inspection and atomic
registration run in one styled progress window; there is no separate name,
confirmation, or completion dialog. The first page has no Java prerequisite/download control and no
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
A missing root or pending recovery is routed to the exact installation card. An absent saved Java
default is healthy and never blocks Home; Java is resolved by the launch worker.
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
**Use existing installation**, **Install another version**, Update, recovery, Game Java
selection, and direct launch behavior. The primary normal first install resolves only the fixed website `stable`
value for the MekHQ repository. Each popup item binds its displayed official repository and
`stable` or `dev` channel; no selection can substitute a different product, channel, title, or
bundle.
Before package transfer it shows a dedicated dark-teal/gold confirmation headed with the selected
product and channel. Its concise summary gives the validated version, actual included programs,
binary download size, and full selectable destination.
**Change location** chooses an existing parent and safe new subfolder, then produces a fresh
immutable quote for that same product/channel pair. Java is not part of installation planning or
confirmation. There is no update-behavior row or technical-details toggle in this
simple confirmation. The backend still binds and revalidates the exact repository, source, tag,
asset identity/digest, destination, registry, and Main snapshot; operation logs retain
failures.

Ready first-launch actions plan from the exact cached option, not from another
`current_releases.yml` lookup. Local planning still freshly captures destination and
registry/default state off the EDT. Before package bytes or parent
creation, install re-fetches the quoted exact repository/tag metadata and requires the same asset
name, size, SHA-256, and URL. A moved channel pointer cannot silently retarget the quoted install;
exact metadata drift requires fresh consent.

Routine source and documentation iteration does not rebuild the portable archive. Archive
creation and verification remain explicit release-validation work.

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

The Installations page's dark **Install another version** dialog defaults to MekHQ and Milestone.
Its one Product/Channel/**Fetch releases** row reads both current pointers from the fixed website
YAML once and requests one page from only the selected product's GitHub history. Page one contains
the selected channel's authoritative current target exactly once plus rows whose historical
channel membership is unknown. A distinct current target known only to the other channel is
excluded; if both pointers name the same identity, that release is valid for either selection.
Titles, versions, ordering, and `prerelease` flags never supply classification. Most historical
rows therefore appear for both channels today. The selected Channel becomes the new
installation's immutable update track; it is not a claim about an unknown row's historical
origin. No picker metadata action fetches package bytes.

Previous/next use the exact loaded product/channel snapshot and a bounded GitHub page. Changing
either combo clears the rows, selection, and page controls and invalidates late results. Eligible
rows use **Program Channel (Version) — human size**; unavailable rows contain only
**— Unavailable** after the identity. A separate **Page N** indicator remains in the footer, while
the reserved status line is blank after success. The controls use the same styled combo/button
treatment and high-contrast fallback as the rest of the launcher.

Before consent, the picker sends the exact choice through the normal-install planner. Current
and unknown choices both re-fetch the selected exact repository/tag rather than retargeting from a
current pointer. Before transfer the planner repeats registry/destination checks and revalidates
the same source, tag, asset URL, name, size, and published-digest state. A digest quoted by the
plan may not disappear or change. If the plan quoted no digest, a valid digest newly present at
that exact refresh is adopted; otherwise the one bounded official body is hashed while streaming
and that computed SHA-256 becomes the installed copy's local package identity. Unknown
classification is accepted only
because successful installation creates a new fixed local track. Every successfully published graphical managed installation
starts with its per-install check-on-open value enabled. See
[`ci.md`](ci.md) for the canonical release index needed to classify old releases safely.
Preview asks to fetch official releases and shows the exact package and full size before download.
Preview itself remains read-only and has no Apply control. **Update…** is a separate workflow:
it previews first, requires a second confirmation that names the destructive intent and exact
size/digest and the captured installation name/path, then acquires the update/launch gate,
re-reads provenance, refreshes small release metadata, re-verifies the retained archive, safely
re-extracts it, and replans immediately before changing managed files. One GUI Update attempt
downloads the full package exactly once; the second confirmation states that it is already
downloaded and no second package transfer occurs. It is available only to copies freshly
downloaded by this launcher or successfully adopted from one exact official ancestor, with a
valid immutable ownership receipt. Unadopted, pre-receipt, and corrupt/incomplete copies remain
launch-only; local hashes alone are never converted into provenance.

For a managed receipt-backed copy, the installation card displays its fixed channel and a direct
**Check for updates when the launcher opens** checkbox. Toggling it immediately writes only that
copy's boolean using the exact displayed record/preference binding.
Use **Install another version** to create a separate root on another channel. Existing valid
channel sidecars are read without migration writes; missing, corrupt, stale, or unavailable
channel state remains launch-only and cannot be assigned through channel settings. Imported
folders receive no channel sidecar or ownership receipt unless the separate exact-ancestor
adoption succeeds. A check reads canonical channel and exact release metadata
only; it downloads no package and changes no installed files. If an update is recommended,
**Update** binds both consent steps to that exact installation, fixed channel, repository, tag,
asset name, size, URL, and optional published digest and uses one full-package transfer for the
attempt. Older official assets without a published digest remain eligible: the exact bounded body
is hashed during that transfer and the computed value is retained in receipt/current state.
Update continues to use the same read-only plan internally before its separate Apply consent.
There is no standalone GUI preview picker; the CLI diagnostic preview remains available.

Prepared package data is process-local and attempt-scoped, not a persistent/offline cache or
filesystem token. Cancel, success, and ordinary failure remove only that attempt's random temporary
workspace; no later GUI or CLI operation resumes or adopts it. A process crash can leave that exact
temporary directory for manual diagnosis, and startup does not broadly delete such directories.
CLI `preview-update` remains a separate read-only diagnostic that disposes its workspace.
Standalone CLI `apply-update` keeps its independent one-download contract and
does not share packages with an unrelated preview or arbitrary path.

Every installation offers **Open location** and **Remove from launcher…**. Open location
revalidates the exact root and asks the platform file manager to open it off the UI thread.
Removal deletes only the exact registration and launcher-owned sidecars; application files stay.
Managed/adopted copies with complete current provenance and a fixed channel additionally offer
**Uninstall…**. Uninstall moves only byte-exact current official files into an external,
same-filesystem recovery area, preserves modified/custom/protected files, removes only empty known
package directories, and removes registration/metadata last. A durable strict journal makes every
pre-commit failure recoverable; post-commit cleanup failures are successful uninstalls with a
cleanup warning. Pending work blocks launch/update and exposes **Recover uninstall**.

Fresh download, standalone Preview, prepared Update, and recovery use one functional operation
dialog with a compact dark-teal/gold presentation and typed phases: metadata, download,
verification, extraction, planning, consent, installation preparation, Apply/uninstall/recovery, and
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

A successful normal install closes progress immediately and returns directly to managed Home. The
registered copy
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

The primary normal GUI install creates a fixed Milestone copy. Current-channel, exact-history,
adopted, and CLI managed installations always initialize check-on-open to true. That value is not
part of the install quote and there is no configurable new-install default. Existing explicit
false choices remain false, and
legacy/unknown/corrupt channel state is never inferred, assigned, or rewritten. The CLI requires a
fixed channel for installation and initializes check-on-open to true. Only explicitly enabled
copies are checked, serially and in the background; slow or
unavailable metadata does not disable healthy launch or page navigation. No check downloads or
applies a package. A failed check is shown as “Could not check,” never as “Up to date.”

Before Apply or recovery, close **all** MegaMek, MekHQ, and MegaMekLab processes, including ones
started manually. Launcher-started games are coordinated across launcher GUI/CLI processes for
their full lifetime. The launcher cannot universally detect old or externally started processes
and does not claim that Windows file locks prove closure. It never kills a process.

There are no automatic Applies, launcher self-updates, Nightly channel, bundled Java, elevation,
or administrator requirement. A channel cannot be changed: another channel always means another
managed copy/root, so there is no cross-channel in-place downgrade or retarget path. Advanced
downloads need an existing writable parent and always create a new subfolder. A styled
dark-teal/gold folder-name dialog validates the preselected friendly
`Program Channel (Version)` suggestion inline; Cancel or Escape changes no state. The same dialog
is used by **Change location**, and the registered name is derived automatically from that
friendly label. The normal
destination can create at most four individually quoted real parent directories after consent;
the product/channel target itself must be wholly absent.

### Opt-in managed updates for an imported copy

Import remains immediate, dialog-free after folder choice, and launch-only. A genuine imported
record with no receipt, current provenance, fixed channel, or pending transaction is labeled
**Imported copy · Launch only · Updates unavailable** and has a separate
**Enable managed updates…** action. Incomplete or corrupt managed metadata never gets that action.

The opt-in flow shows the statically detected applications/version and a fixed future channel
(Milestone by default, or Development). It treats that information only as a candidate. Before
offering Enable, the launcher resolves one exact release in the matching fixed official
repository, requires its bounded asset name/size and validated official URL, downloads that
package once, verifies a valid published SHA-256 when present (or records the SHA-256 computed
from that one exact body when absent),
safely extracts it outside the application root, and compares its static product/build identity
and ownership inventory with the imported copy. If automatic version lookup is unavailable, the
user may explicitly choose an exact version from the bounded official release browser. Release
titles and GitHub prerelease flags do not establish identity or channel history.

All official application executables, application JARs, dependency JARs, and launch metadata must
match byte-for-byte. Missing managed files, case/Unicode aliases, links, structural conflicts, a
mixed product/version, or a changed runtime file fail closed. Modified non-runtime official files
are recorded as pre-existing overrides; unknown custom files and protected saves, campaigns,
userdata, configuration, and logs remain outside launcher ownership. The current architecture
cannot durably represent intentional missing managed files, so adoption rejects them rather than
allowing a later update to restore them silently.

Preparation never writes beneath the application root. Confirmation re-enters the shared root
gate, rechecks the exact registry binding and absence of all provenance, compares a complete
immutable local snapshot, revalidates the retained package and exact release metadata, and then
publishes receipt, current state/override history, adoption binding, and fixed channel outside the
root. The channel is the final visibility barrier; failed publication rolls back only
attempt-owned metadata and leaves the copy launch-only. Success reports **Managed updates
enabled**, does not launch or update anything, and makes the normal Check/Update path
available for later explicit use. There is no second package download and no Nightly adoption.

The official source hierarchy is singular: the canonical release index is the long-term release
and channel authority; an embedded package identity is a signed/build-produced projection that
assists exact candidate selection; the launcher receipt and fixed track are local policy.
Adoption never trusts an embedded marker, local version string, or registry field by itself.

See the [real-update contract](docs/real-update-contract.md), [GUI contract](docs/gui-contract.md),
the [channel/check contract](docs/channel-update-contract.md), and
[read-only CLI preview contract](docs/update-preview-contract.md), plus the
[safe uninstall contract](docs/uninstall-contract.md).
The archived command-line fresh-install walkthrough remains in
[docs/cli-fresh-install.md](docs/cli-fresh-install.md).
