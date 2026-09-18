# Graphical launcher checkpoint contract

`gui [--registry <path>]` opens Swing without changing the no-arguments CLI behavior. The default
registry is `%LOCALAPPDATA%\MegaMek\launcher-registry.json` on Windows,
`~/Library/Application Support/MegaMek/launcher-registry.json` on macOS, an
`$XDG_STATE_HOME/MegaMek` registry when configured, or `~/.megamek/launcher-registry.json`.
An absent registry is an empty first run. An existing unreadable or corrupt registry is reported
and is never reset. Its immediate launcher-owned parent is created only when the user consents to
a mutation and its parent is already a real directory.

The empty first-launch screen uses the supplied artwork, a responsive dark action panel, and
suite-inspired vector buttons. Its joined split control keeps **Download & install** as the large
primary action for the fixed current Milestone MekHQ all-three-program suite. A distinct arrow
opens exactly **Latest Development** and **Choose another version or application…**. The former
uses the same verified normal-install workflow for the fixed Development MekHQ target; the latter
directly opens the existing full release picker without fetching until its current Fetch action.
The visible, regular secondary **Use existing installation** action imports MegaMek, MekHQ, or
MegaMekLab. There is no separate advanced action on first Home. Import entry points share
static inspection, exact current-launcher Java validation, confirmation, and atomic registration.
The first locked import supplies Main only when no Main exists; later/concurrent imports do not
replace it. Imported Home launch buttons reflect only actually detected products. Imported records
are launch-only and have no receipt/channel provenance. Logs are in Settings. `GuiScale` keeps
custom metrics in logical coordinates while Java handles per-monitor device DPI. See
[first-launch presentation](first-launch-ui.md). Artwork loading failures are reported without
disabling onboarding or treating a registry error as empty.

The first-launch caption starts as **Latest Milestone**. A separate background metadata-only
request resolves the fixed Milestone MekHQ target and validates its asset before adding the
version in parentheses. It never requests package bytes, disables onboarding, or writes state.
Offline or stale results leave the generic caption; page changes, reload, and disposal cancel or
discard the display-only result.

The normal planner accepts only explicit Milestone or Development and always targets MekHQ. The
default overload and primary segment resolve
`OfficialYamlChannelCatalog.target(MILESTONE, MEKHQ)`; the quick menu route resolves
`target(DEVELOPMENT, MEKHQ)`. Planning is metadata-only and binds the requested channel, full
release/asset identity and source, proposed per-user `installations/Main` destination, registry
snapshot, Settings revision/default, and validated launcher Java. Its confirmation has no release
or channel picker and explicitly names the bound channel. A second same-channel metadata
resolution plus the installer's expected-asset check rejects feed/tag/name/URL/size/digest drift
before binary transfer. Bounded real parents are created only after consent; the target itself
must remain wholly absent. Successful setup persists exact external Java, the exact planned
channel with the captured check-on-open setting, and Main selection, then returns the latest
record. Any post-publication setup failure retains the valid/registered copy and points to
Installations repair rather than offering a download over that path.

Installations contains import, exact-release download, Main selection, record-only removal,
location, Java, channel/check, Preview/Update, and recovery actions for the captured selected UUID.
Selection does not move/copy files or mutate a channel. The advanced exact-release picker requires
an explicit channel and uses the Settings default (initially true) for new-copy checks.

Static inspection, registry writes, package metadata/download work, Java validation, command
previews, and child-process waiting run on a single-flight `SwingWorker`, never the event-dispatch
thread. Ordinary actions are disabled during that work and restored after success or failure.
The active operation dialog is exempt: its authoritative **Cancel**, **View logs**, and explicit
copy controls remain reachable. Swing changes occur only on the event-dispatch thread; network,
hashing, extraction, planning, cleanup, persistent-log I/O, and log listing/reading do not. Typed
progress is coalesced at roughly 150 ms and shows honest byte/file totals or indeterminate work,
never an invented overall percentage. The separate metadata-only channel check described below
does not take that gate.

Fresh install and update preparation are cancellable through metadata, download, verification,
extraction, and planning; prepared Update also permits cancellation while awaiting its second
consent. Fresh cancellation is atomically disabled before destination publication and stays
disabled through registry/receipt/channel finalization. Update cancellation is atomically disabled
before the real-update namespace/transaction is created. Recovery disables cancellation before
replay or cleanup. A stale/late request is denied by the backend context and cannot interrupt a
pooled worker. Accepted cancellation closes only the operation-owned pending response, checkpoints
local loops, performs exact temporary cleanup off the EDT, and reports **Cancelled** without
claiming rollback or 100% completion. Window close requests cancellation only while safe; after
cutoff it explains why the window must remain. Game processes are never killed.

Existing-copy preparation performs no write. It statically inspects the chosen root and directly
runs only the exact canonical `<java.home>/bin/java[.exe] -version` for the JVM running MM Launcher;
it does not consult another record, `JAVA_HOME`, or `PATH`. Confirmation precedes registry parent
creation and mutation. Registration revalidates Java before finalization, then RegistryStore
re-inspects and compares the canonical root/layout while holding the registry lock and writes one
record with Java in one atomic replacement. Duplicate/overlap and changed/moved layouts fail
without a new record. No process is run under the registry lock.

A receipt-backed Home shows a concise channel/check status. Existing records are Unknown until the user
chooses Milestone or Development; no tag, release title, prerelease flag, parity, or ordering is
used to infer the choice. Choosing or changing it is local-only and performs no network request.
Normal and advanced new GUI installs use the Settings default, which is true while initially absent
and preserves a later user override. The normal confirmation displays the captured On/Off choice
and requires a fresh quote if the settings configuration changes or becomes unreadable. Existing
false remains false and Unknown/Unavailable is not inferred. Manual selected-copy checks ignore the
off switch. Eligible startup checks are bounded and serial, use separate cancellable workers, and
do not enter the package gate or disable launch/page controls. Results from a disposed window,
changed Main/record/preference, or refreshed generation are discarded. Failure is “Could not
check,” never “Up to date.” Checks never apply or download packages.

An available result displays verified installed tag, followed target, status, release-notes URL,
and full size. **Update to recommended release…** goes directly to that captured target. Before any
package request the backend rechecks the record, channel sidecar, fixed source, repository, tag,
asset name, size, and digest. Changed metadata is rejected for fresh consent; it cannot silently
retarget. The existing advanced exact-release Preview/Update picker remains available and does not
change the channel.

Home determines update/preview availability from local registry, immutable receipt, and latest
current-provenance validation without startup network access. Missing, corrupt, stale, or
unsupported receipts disable **Preview update** and **Update…**.
A receipt-backed copy with a changed registered JAR can still preview (and report `SKIP`) while
launch remains disabled. Preview uses the receipt's fixed repository and explicit paged Fetch/Next
actions. Before downloading it confirms source, exact target, full byte size, temporary static
inspection, and read-only scope. Work runs off the EDT. Results show all decisions, counts,
protected paths, and excluded official paths; the Preview report itself has no Apply control.

**Update…** is a distinct, attempt-scoped workflow. After the first consent it downloads the exact
selected full package once, verifies/extracts it in a random owned workspace, and builds the same
read-only report. It then presents a separate confirmation naming the captured installation and
path, exact target size/digest, destructive managed actions, retained `SKIP` count,
backups/recovery, instruction to close every suite application (including manually started copies),
and the fact that the package is already downloaded and will not be downloaded again. After
consent it atomically claims that one-use handle, acquires the captured root's cross-process gate,
re-reads the captured record/receipt/current source, refreshes exact release metadata, re-verifies
the retained compressed size/SHA-256/file identity, safely re-extracts from those bytes, validates
the result against the pristine captured target inventory/inspection, replans fresh local files,
and applies only through `RealUpdateService`. The recommended route additionally refreshes the
channel source and preference under the gate. Drift fails with instructions to restart; it never
redownloads or retargets.

One background worker owns the prepared handle through the EDT-only second confirmation and
Apply/discard. Package transfer, hashing, extraction, planning, Apply, and potentially large cleanup
stay off the EDT. Cancellation after preparation and a late result after frame disposal discard the
exact workspace. Concurrent close/Apply, reuse, another facade, changed source/root binding, a live
launch gate, or pending recovery fail closed. Success distinguishes pristine and preserved-override
results. If installation commit succeeds but temporary-package cleanup fails, the UI truthfully
reports **Update complete — cleanup warning** and says not to retry Apply. Apply failure retains the
transaction journal/backups needed by recovery and does not let package-cleanup failure replace the
original cause.

Prepared data is process-local and valid for only the active attempt. It is not serialized, a
persistent/offline cache, resume state, or arbitrary path authority. A crash can leave its randomly
named temporary directory, but startup performs no broad deletion and no later attempt adopts it.
Standalone GUI Preview and CLI `preview-update` remain separate read-only workspaces and never offer
Apply; an unrelated CLI `apply-update` retains its own independent package transfer.

Every functional operation records a terminal structured local log in
`<registry-name>.launcher-logs/`. Generic displayed GUI failures, including Java/registration and
channel-check failures, are queued for the same storage without blocking error presentation on the
EDT. **View logs** is available from empty, unavailable, corrupt-registry, and managed homes and
after restart. Listing and reading are asynchronous and use a bounded plain `JTextArea`; there is no
arbitrary path field, automatic file opening, issue/project integration, upload, or telemetry.
Copying is explicit and uses the same sanitizer as persistence and viewing.

The default store retains at most 20 proven-owned closed JSON logs, each at most 1 MiB. A log binds
schema, operation UUID/time/type/phase/outcome, build version, bounded progress, and a readable
sanitized error/stack chain to the canonical registry path. Progress truncation is explicit and
never removes the terminal error/outcome. URL queries/fragments/userinfo, bearer/password/secret/
token values, command/environment dumps, and Jackson configuration-source snippets are removed
before persistence and copy. Home paths use `~`; other local absolute paths may remain and the
viewer instructs review before sharing.

The sidecar must be outside every registered or potential fresh root and receipt metadata. Existing
ancestors/directories/files are checked without following links/reparse points. Retention deletes
only strict-name files whose schema/header/registry binding matches; unknown files and links remain.
POSIX files are owner-only where supported; Windows uses inherited ACLs in the normal per-user
location and never elevates. Corrupt/unreadable registry placement or log I/O fails visibly without
creating an unsafe directory. Logging failure remains a warning and cannot replace an original
error, turn committed Apply into failure/cancellation, or affect root journals/backups.

**Check update recovery…** is rendered independently of successful application inspection and
launch preparation, so a half-updated preferred root remains recoverable. Update, recovery,
registry I/O, downloads, hashing, and child waiting remain on the single-flight worker, not the EDT.
Current launcher-started games hold the same root gate for their entire lifetime. The confirmation
accurately states that externally/older-started applications cannot be universally detected.

Tests should use a disposable `--registry`, fixture installation, fake `ReleaseTransport`, and fake
`ProcessRunner`; they must not download or launch a real game. A separate non-headless smoke test
may show `LauncherFrame`, wait for its empty home, exercise the Manage dialog, then dispose it on
the EDT. Skip that smoke test only when `GraphicsEnvironment.isHeadless()` is true.
