# Graphical launcher checkpoint contract

`gui [--registry <path>]` opens Swing without changing the no-arguments CLI behavior. The default
registry is `%LOCALAPPDATA%\MegaMek\launcher-registry.json` on Windows,
`~/Library/Application Support/MegaMek/launcher-registry.json` on macOS, an
`$XDG_STATE_HOME/MegaMek` registry when configured, or `~/.megamek/launcher-registry.json`.
An absent registry is an empty first run. An existing unreadable or corrupt registry is reported
and is never reset. Its immediate launcher-owned parent is created only when the user consents to
a mutation and its parent is already a real directory. State/receipt files remain outside every
normal product/channel payload child; Linux keeps state and managed application data in their
respective XDG/fallback locations.

## Managed application and settings model

Managed Home is application-oriented, not legacy-default-oriented. It has no visible Main
heading/caption. The union of statically recorded products determines whether MegaMek, MekHQ, and
MegaMekLab controls exist. Each application's primary segment captures its independently resolved
preferred `InstallationRecord`, displays **Program Channel (Version)**, and uses the existing
static reinspection, Java, coordinator, and launch validation without a GUI preview or confirmation.
Alternate rows are deterministic,
launch-only exact-record actions and never mutate a preference or fall back at click time.

Registry schema 3 contains a strict `preferredInstallationIds`
application-key/installation-UUID map and no per-installation Java. Older pre-release schemas are
rejected without migration or aliases. Valid-but-stale references fall back
at runtime to the first matching registry record and remain stale until explicit user action.
Malformed/unknown keys or values fail closed. Registration/import fills only absent keys; removal
clears only values pointing at the removed UUID. Every mutation revalidates captured record
identity under the registry lock and uses flushed atomic replacement.

Installations has a centered title without explanatory subtitle and a scrollable dark-teal/gold
card surface. Cards use their content height rather than filling fixed vertical blocks; the
automatic-check checkbox aligns with summary text and compact actions sit at the top-right.
Every physical record shows name,
version, applications, per-application preferred markers, and applicable check/recovery state.
Managed records show **Channel: Milestone/Development** read-only. Imported records show
**Imported copy · Launch only · Updates unavailable** and omit a channel rather than inferring one.
Top-level actions are **Install another version** and **Import existing installation**. Exact actions include **Use as preferred for …**, Check/Retry, Update, update recovery,
**Open location**, and **Remove from launcher…** when applicable. Managed/adopted cards also place
**Uninstall…** at the destructive bottom; imported cards do not. A pending uninstall exposes only
**Recover uninstall** instead of a new remove/uninstall action. There is no standalone GUI Preview
action. Update is primary only when known available.
Selection is never a preference, and there is no bulk update.

Each eligible managed card directly shows **Check for updates when the launcher opens** near its
read-only channel/update status. It saves immediately off the EDT using the exact displayed
record/preference binding, stays selected as requested while disabled for saving, and reloads the
authoritative value on failure. Imported, incomplete, corrupt, missing-channel, and otherwise
ineligible records show no checkbox. The action menu has no update-check editor.

Settings has only Game Java and Diagnostics; it has no Updates section or automatic-check
control. The effective game Java
section shows the feature and canonical selectable/tooltipped executable without exposing
persistence implementation details. Its heading and contents are left aligned, the path uses the
regular UI font, and the change action shares the same row. A successful **Change default Java** validates Java 21+ off the EDT
before atomic publication. It is the sole explicit runtime for every registered copy and changes
take effect on the next launch. If no default is saved, the exact Java runtime executing MM
Launcher is displayed and used automatically without persistence. Invalid or corrupt explicit
settings fail visibly; absence is healthy. Settings sections use spacing rather than framed backplates. **View logs** opens a
dark-teal/gold local viewer with selectable text and styled Copy/Close controls. The Game Java
selector uses the same launcher styling. No Java download, installation,
bundle, or generic Save settings action exists; Java acquisition remains deferred.

Home's Installations label is derived from installation-level in-memory results. It remains
**Installations** unless one or more checked records have a known update, when it becomes
**Installations (N updates)**. Unknown is never counted as an update. Late workers are
generation-bound and update that button on the EDT without replacing Home. Home offers no normal
Check/Retry/Update buttons or redundant status navigation. Pending recovery and launch
blockers remain exact-record actions in Installations. One top-left Home information area displays
only useful aggregate update state: **Checking for updates…**,
**All installations are up to date**, **N installations have updates**, or
**Some installations could not be checked**. It makes no all-current claim when imported or
unchecked installations prevent that conclusion. There is no separate middle status line and no
post-install “ready” notice.

Download bars paint percentage only, with localized human-readable byte detail below. Extraction
is indeterminate with processed-file detail and no bar string.

The empty first-launch screen uses the supplied artwork, a responsive dark action panel, and
suite-inspired vector buttons. Its joined split control keeps
**Install latest MekHQ Milestone** as the
large primary action for the fixed current Milestone MekHQ all-three-program suite. A distinct
arrow opens a dark-teal/gold popup with exactly **Install latest MegaMek Milestone**,
**Install latest MegaMekLab Milestone**, **Install latest MekHQ Development**,
**Install latest MegaMek Development**, and **Install latest MegaMekLab Development**, in that order. Each
available row shows its validated version. The popup contains neither a redundant MekHQ Milestone
row nor the full exact-release picker. It preserves ordinary menu actions, keyboard opening,
Escape, accessible names/descriptions, opaque selection contrast, and a system high-contrast
fallback rather than inheriting plain white OS rows.
Successful rows need no validation tooltip; unavailable rows retain concise diagnostic details.
On initial empty Home, one frame-scoped background snapshot loads all six choices. Until it
succeeds, both install segments are disabled and unfocusable while **Use existing installation**
stays enabled. Failure keeps install unavailable and exposes one explicit Retry command; retry
activation is deduplicated. READY labels and exact options survive Home reloads, page transitions,
confirmation cancellation/close, Change location, and repeated quote opens without another
current-channel request or loading flicker.
First-launch artwork uses a centered cover treatment without side bars. The bottom control deck
has no repeated application title; equal-width actions are horizontally centered and top-aligned.
Compact layouts stack those actions and remain top-aligned and scrollable. Focus on either split
segment outlines the complete joined control, not only the arrow segment.

The visible, regular secondary **Use existing installation** action imports MegaMek, MekHQ, or
MegaMekLab and retains that caption. There is no Java prerequisite/download control or advanced
action on healthy first Home. The exact product/release/channel picker remains on Installations
after a copy exists and for relevant repair/recovery navigation. Import entry points share
one styled operation that performs static inspection, configured-default/current-launcher Java
validation, automatic product/version naming, and atomic registration. Only the folder chooser
precedes it; no separate name, confirmation, or completion dialog is shown. The locked import
fills only still-unset preferences for products it contains;
later/concurrent imports do not replace explicit choices. Imported records contribute their
statically detected products to Home's union. Imported records
are launch-only and have no receipt/channel provenance. Logs are in Settings. `GuiScale` keeps
custom metrics in logical coordinates while Java handles per-monitor device DPI. See
[first-launch presentation](first-launch-ui.md). Artwork loading failures are reported without
disabling onboarding or treating a registry error as empty.

Ready quick-install actions pass the selected cached `QuickInstallOption` to an off-EDT planner.
That planner freshly captures destination and registry/default state but does not read Settings or
perform a YAML/current-target lookup. Confirmation opens with the shared Home
status cleared. Cancel, Escape, window close, local retry, and Change location preserve the cached
labels and readiness. Before transfer or parent creation, the backend re-fetches and validates the
quoted exact repository/tag and asset name, size, SHA-256, and URL. It never follows an advanced
channel pointer for that quote; exact-target drift fails for fresh consent.

Managed Home uses the same top-art/bottom-deck composition, centered cover crop, dark palette, and
compact vertical scrolling as first launch. It has no duplicate launcher header/footer, left-side
art rail, raw registry path, or permanently reserved empty region. The deck contains the optional
process-local notice, application launch row, aggregate update state, and compact
Installations/Settings navigation. Three product controls share equal widths when they fit and
wrap/stack as the viewport narrows. Each primary passes its preferred exact record/product to the
existing launch backend. Its arrow lists the other matching records in registry order as
**name · observed version** plus already-held status. Menu construction performs no filesystem or
network read on the EDT. A row never persists a temporary target or falls back at click time.
Layout/recovery failures disable the affected primary. Missing saved Java does not. With no alternate, the arrow is absent
and unfocusable.

Primary and alternate clicks directly invoke launch for their captured record/product; the GUI
has no command/runtime confirmation and no finished dialog. `LauncherServices` still re-reads the
exact record and `ApplicationLauncher` still reinspects root/build/product, validates Java, and
acquires the root coordinator. After (and only after) BusyGate admission, the frame is iconified
on the EDT before the worker can start the child. It stays alive for the full child lifetime so
the coordinator lease remains held. Exit zero reloads internal state but leaves the frame
iconified with no foreground request. Nonzero exit or launch/start exception deiconifies, makes
the frame visible/front, and shows the existing explicit error. BusyGate refusal does not
iconify and retains **Please wait**. No path disposes, exits, detaches, or uses a system tray.

The primary label gains the validated MekHQ Milestone version, for example
**Install latest MekHQ Milestone (0.51.0)**. There is no separate caption. A single background
metadata-only snapshot reads the bounded official stable/dev YAML once and validates all six
repository/channel targets. Exact release metadata is deduplicated per repository/tag when stable
equals development. The main label and five labels update atomically only on the current panel.
It never requests package bytes or writes state. Loading disables both install segments but not
other onboarding. Offline results show **Version unavailable** and five disabled
**(Unavailable)** rows plus the explicit Retry command. Stale components are never rebound.

The normal planner accepts only one explicit official `MEKHQ`, `MEGAMEK`, or `LAB` repository and
one explicit Milestone or Development channel. The default overload remains available to the
managed picker. First-launch primary/menu actions instead pass their fixed cached option to the
same backend. Planning is metadata-only and binds the requested repository/product set/channel, source
version/tag, full release/asset identity, product/channel-specific per-user destination, registry
and compatibility-default snapshot, and validated game Java. Its dedicated
dark-teal/gold confirmation has no release, channel, Java, or editable update-setting picker. The
summary displays only the product/channel, validated version, actual programs, binary download
size, full selectable destination with **Change location**, and Cancel/Install actions. Parent
selection is followed by a styled folder-name dialog with a friendly preselected
`Program Channel (Version)` value, inline validation, Cancel/Escape behavior, and no separate
installation-name prompt. The friendly value is also the automatic registered name. There is no
passive update-behavior row or technical-details toggle/pane. Captured-current plans skip a second
channel-pointer lookup; exact release revalidation plus the installer's expected-asset check still
reject tag/name/URL/size/digest drift before binary transfer without retargeting. Java,
registry/default, receipt,
and state validation also remain backend requirements, with failures retained in operation logs.
At most four individually quoted real parents are created only after consent; the target itself
must remain wholly absent. Successful setup persists exact external Java, the exact planned
channel with check-on-open enabled, and the compatibility default only if empty, then returns the latest
record. The MekHQ package must contain exactly MegaMek, MekHQ, and MegaMekLab; standalone
repositories must contain only their actual product. No title fallback or mixed bundle can expose
extra Home launch actions. Any post-publication setup failure retains the valid/registered copy
and points to Installations repair rather than offering a download over that path.

For the exact ordinary GUI registry, normal destinations are
`%LOCALAPPDATA%\MegaMek\<product channel>` on Windows,
`~/Library/Application Support/MegaMek/<product channel>` on macOS, and
`${XDG_DATA_HOME:-~/.local/share}/MegaMek/<product channel>` on Linux. Missing or relative
`LOCALAPPDATA` is an explicit Windows placement error; macOS requires an absolute nonblank home;
Linux ignores relative `XDG_DATA_HOME` and uses the absolute-home fallback. These are
launcher-managed application payload/support-data directories, not `MM Launcher.app` resources.
An explicit `gui --registry` path, even when supplied to an otherwise ordinary GUI launch, and
custom `LauncherServices` registries use
`<registry parent>/installations/<product channel>` for isolation. Destination selection performs
no migration or filesystem write.

Installations contains import, exact-release download, independent per-application preference,
launcher removal, safe uninstall, location, channel/check, Update, and recovery actions on exact
cards. Merely viewing/focusing a card mutates nothing. **Install another version** defaults to
MekHQ/Milestone and uses a dark-teal/gold content, list, viewport, labels, vector buttons, and
product/channel combos. The same combo class is used by the Game Java selector;
it retains native Swing popup, keyboard, selection, and accessibility behavior while painting the
field and arrow segment locally, with a system high-contrast palette fallback.

One top row contains, in order, styled **Product**, styled **Channel**, and styled
**Fetch releases** controls. It defaults to MekHQ/Milestone; there is no separate Browse mode.
One fetch reads both fixed `current_releases.yml` pointers, requests one bounded page from only the
selected product repository, and performs an exact selected-target lookup only when the history
page cannot provide its canonical eligible metadata. It never requests package bytes.

Results include the selected channel's known current identity and history whose membership is
unknown. A distinct identity known only as the other channel's current target is excluded; a
shared current pointer is included for either channel. No title, `prerelease`, version, or ordering
inference is allowed. Page one contains the selected current identity exactly once, with stable
repository/tag deduplication. **Previous page**, a non-button **Page N**, **Next page**, and
**Install** share a stable footer. Previous is disabled on page one; Next follows the
API's bounded may-have-next signal. Product or channel changes clear selection/results/page
controls and invalidate in-flight work. Page requests retain their exact product/channel snapshot
and discard stale or closed-dialog results. The reserved status line is blank after success and
shows only loading/errors.

Eligible rows, including older official assets without a published digest, read
**Program Channel (Version) — human binary size** with no Available or checksum suffix.
Ineligible rows are disabled and end only in **— Unavailable**; a sanitized accessible
description may retain the reason. Destination is enabled only for an eligible exact row. Before
consent, every row is re-fetched by exact repository/tag through the normal planner with the
selected channel as the new copy's immutable track. It binds destination, registry/default,
product set, source, release, asset URL/name/size/optional published digest, and fixed channel,
then repeats its exact metadata check before transfer. A quoted digest cannot disappear or change;
quoted absence may become a valid digest at that refresh, or remain absent and use the SHA-256
computed from the one exact retained body. Unknown historical membership is not channel evidence.
The long-term immutable cross-repository history requirement and current workaround are documented
in `../ci.md`.

Static inspection, registry writes, package metadata/download work, launch-time Java validation,
and child-process waiting run on a single-flight `SwingWorker`, never the event-dispatch
thread. Ordinary actions are disabled during that work and restored after success or failure.
The active operation dialog is exempt so its authoritative **Cancel** remains reachable. It uses a
compact dark-teal/gold skin, logical `GuiScale` metrics, a clear typed phase, an honest accent
progress bar, and one concise nontechnical detail. Backend stream text is retained only in a
hidden bounded legacy capture; there is no visible log area or reserved blank space, and active
progress has no log/copy/Open Installations clutter. While that dialog is open, the underlying
Home/page shows no duplicate active-operation status text. Contextual actions are invisible while
disabled and can appear only in the failure state. Swing changes occur only on the event-dispatch
thread; network, hashing, extraction, planning, cleanup, persistent-log I/O, and log
listing/reading do not. Typed progress is coalesced at roughly 150 ms. Downloads paint percentage
only and put localized human-readable transferred/total sizes in the detail. Extraction is titled
**Extracting application files** and uses its typed processed-entry count as a localized
**N files processed** detail while its unknown total remains indeterminate. Other safe typed
details may be shown, but path/URL/digest-like values use a nontechnical phase fallback. The
phase, bar, and detail have only logical padding on the dialog background, not an inner framed
backplate. Cancel is a vector-painted dark secondary button with a custom whole-plate focus outline,
not an OS-white/focus artifact. The separate metadata-only
channel check described below does not take that gate.

Fresh install and update preparation are cancellable through metadata, download, verification,
extraction, and planning; prepared Update also permits cancellation while awaiting its second
consent. Fresh cancellation is atomically disabled before destination publication and stays
disabled through registry/receipt/channel finalization. Update cancellation is atomically disabled
before the real-update namespace/transaction is created. Recovery disables cancellation before
replay or cleanup. A stale/late request is denied by the backend context and cannot interrupt a
pooled worker. Accepted cancellation closes only the operation-owned pending response, checkpoints
local loops, performs exact temporary cleanup off the EDT, and reports **Cancelled** without
claiming rollback or 100% completion. The progress window then closes and exposes Home or the
current page with a concise cancelled status. Window close requests cancellation only while safe;
after cutoff Cancel is hidden and the same window explains why it must remain, without another
message dialog. Game processes are never killed.

Terminal typed events continue to reach persistent operation logs but are not rendered as a
transient **Finished / 100%** state. Success handlers first dispose progress. Normal-install
success returns directly to Home without a redundant readiness notice. Aggregate update status
uses the single top-left Home information area. There is no normal-install completion dialog and
no automatic launch. Failure transforms progress into
one compact surface with a concise sanitized summary, **Close**, and failure-only **View details**.
It does not also open the generic error dialog. A `PublishedInstallationException` additionally
reveals **Open Installations**; ordinary failures do not expose a generic Retry. Logging-storage
failure is a concise secondary warning and never masks or replaces the original outcome.

Existing-copy preparation performs no write. It statically inspects the chosen root and validates
the configured default game Java, or the exact launcher runtime when no default is saved. It does
not consult another record or `PATH`. Confirmation precedes registry parent
creation and mutation. Registration revalidates Java before finalization, then RegistryStore
re-inspects and compares the canonical root/layout while holding the registry lock and writes one
record with Java in one atomic replacement. Duplicate/overlap and changed/moved layouts fail
without a new record. No process is run under the registry lock.

A managed Home shows a concise fixed-channel/check status. The installer initializes that channel
once as part of verified fresh publication. Existing valid sidecars preserve their channel without
a read-time migration. Missing, corrupt, stale, or unavailable sidecars remain unconfigured and
launch-only; no tag, release title, prerelease flag, parity, or ordering is used to infer or assign
one. The direct checkbox writes only check-on-open after revalidating the exact record and sidecar
binding. A different channel always requires **Install another version** and a separate root.

Current-channel and exact-history new GUI installs, CLI installs, and successful adoptions
initialize check-on-open to true. There is no global gate or configurable new-install default,
and normal plans carry no Settings revision/check choice. Existing false remains false. Manual
selected-copy checks ignore the off switch. Eligible startup checks are bounded and serial in one
cancellable worker and do not enter the package gate or disable launch/page controls.
Results from a disposed window, changed record/fixed-track setting, or refreshed generation are
discarded. Failure is “Could not check,” never “Up to date.” Checks never apply or download
packages.

Home has no per-copy update controls or separate aggregate-status button. Per-card Update, Retry,
check preference, and managed pending recovery remain available where applicable. The Home Installations
button carries only the known update count and otherwise keeps its default label. Completion
updates that label in place and never replaces managed Home.

An available result displays verified installed tag, followed target, status, release-notes URL,
and full size. **Update to recommended release…** goes directly to that captured target. Before any
package request the backend rechecks the record, channel sidecar, fixed source, repository, tag,
asset name, size, and digest. Changed metadata is rejected for fresh consent; it cannot silently
retarget. There is no standalone GUI preview picker. Update still computes and displays the same read-only
plan before the separate Apply consent and cannot change the channel.

Home determines update/preview availability from local registry, immutable receipt, and latest
current-provenance validation without startup network access. Missing, corrupt, stale, or
unsupported receipts disable **Update…** and **Uninstall…**.
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
EDT. **View logs** is available from corrupt-registry recovery and Settings, including after
restart. Listing and reading are asynchronous and use a bounded plain `JTextArea`; there is no
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

**Recover interrupted update…** is rendered independently of successful application inspection
only when launcher-owned receipt provenance and local state report a pending update, so a
half-updated managed root remains recoverable without exposing recovery on an imported card.
Update, recovery,
registry I/O, downloads, hashing, and child waiting remain on the single-flight worker, not the EDT.
Current launcher-started games hold the same root gate for their entire lifetime. The confirmation
accurately states that externally/older-started applications cannot be universally detected.

## Imported-copy adoption

A true imported card says **Imported copy · Launch only · Updates unavailable** and includes one
styled, keyboard-accessible **Enable managed updates…** action. It appears only when receipt,
current state, channel, adoption staging, and pending transaction are all absent. Managed,
managed-incomplete/corrupt, and recovery-pending cards never show it.

The separate first screen shows detected application/version and a styled future-channel selector
with Milestone selected by default and Development as the only alternative. **Verify copy** and
**Cancel** are the primary actions. If the detected version cannot resolve one exact eligible
release, **Choose official release…** opens the existing bounded paged official-history pattern;
rows use understandable versions and do not expose digest or repository terminology. All
metadata/package work runs in a worker under `BusyGate`.

Verification uses one styled progress surface and supports safe cancellation until publication.
Its visible summary never lists paths, digests, or comparison counts. An eligible result says
**This copy can be managed safely. Existing files will not be changed.** and offers
**Enable** / **Cancel**. An ineligible result says
**This copy could not be verified and will remain launch-only.** and offers **Close** /
**View details/logs**. Detailed exact/modified/missing/protected/unknown/conflict information is
bounded to the hidden operation capture and local logs.

Enable runs off the event-dispatch thread and atomically publishes metadata without re-downloading
or modifying the root. Success returns to Home with **Managed updates enabled**. Failure keeps
launch available, displays one concise launch-only message, and links to logs. Closing or
cancelling before publication closes the opaque prepared handle and removes its owned workspace.
The fixed channel cannot be edited after success.

Tests should use a disposable `--registry`, fixture installation, fake `ReleaseTransport`, and fake
`ProcessRunner`; they must not download or launch a real game. A separate non-headless smoke test
may show `LauncherFrame`, wait for its empty home, exercise the Manage dialog, then dispose it on
the EDT. Skip that smoke test only when `GraphicsEnvironment.isHeadless()` is true.
