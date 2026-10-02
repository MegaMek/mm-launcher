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
Every physical record shows its editable launcher-only name, known version, and fixed channel
beside one another on a left-aligned title row with a small gap before applications,
per-application preferred markers, and applicable check/recovery state. Repeated
launch-only/unavailable status is omitted when the provenance line already says it.
Version and channel are omitted when unknown; the channel
is not repeated on the applications row. Existing saved names are preserved, while a matching
generated version suffix is suppressed in the display to avoid duplication. The **Rename…**
action changes only the registry label, not the installation folder or application files; names
must be unique. Imported records show
**Imported copy · Launch only · Updates unavailable** and omit a channel rather than inferring one.
Top-level actions are **Install another version** and **Import existing installation**. Exact actions include **Use as preferred for …**, Check/Retry, Update, update recovery,
**Open location**, and **Remove from launcher…** when applicable. Managed/adopted cards also place
**Uninstall…** at the destructive bottom; imported cards do not. A pending uninstall exposes only
**Recover uninstall** instead of a new remove/uninstall action. There is no standalone GUI Preview
action. Update is primary only when known available.
**Reset preferences…** appears in the More menu for ordinary registered copies, including
imports. The confirmation explains the program scope, asks users to close the suite, and says
that saves, campaigns, and custom files stay. It omits the file list and backup path; the
success dialog identifies the retained backup location. The root coordinator blocks tracked
running games and pending updates. Users must close externally started suite apps as well.
Official receipt source determines package scope;
without a receipt, an unambiguous inspected product inventory is required. MekHQ resets MM,
MHQ and MML local settings; MegaMek and MegaMekLab reset only their own. MML's
`megameklab.properties.bak` is moved with the primary settings file. The operation leaves other
files (including saves and custom files) alone and never touches OS-global Java Preferences.
No matching files means no reset and no backup created. On a partial move failure, already
moved files are rolled back where safe; errors identify the backup for manual recovery.

**Repair installation…** appears in More only for a healthy exact-release managed copy without
pending recovery; imported and adopted copies cannot use it. The pre-download consent identifies
the download size if known, asks users to close all suite apps, and explains in plain language
that missing or changed game files will be replaced, saves and settings stay, and users should
back up changed game files they want to keep. It does not display the installation name, folder,
release tag, or technical file details in the consent. The facade rechecks consented provenance before execution; the
backend enforces the root/live-process gate and transaction. Progress shows restoration counts
and warnings, while a failed repair points to pending update recovery rather than claiming success.
Repair is non-cancellable from the start in both UI and operation context; its progress displays
**Downloading installation files** with actual transferred bytes and package-download percentage
only, then indeterminate **Preparing installation files**, **Repairing installation**, and
**Finishing up**. The visible reminder is **Please keep the launcher open until repair finishes.**
There is no overall repair percentage. With no restored files the completion says
**Repair complete. No missing or changed installation files were found.**; otherwise it says
**Repair complete. Your installation is ready to use.** Extra unowned lib JARs show a separate
plain-language caveat, **Some added files may still affect the game. View logs for details.**,
while their paths and technical warnings remain in logs.
Selection is never a preference, and there is no bulk update.

Each eligible managed card directly shows **Check for updates when the launcher opens** near its
read-only channel/update status. It saves immediately off the EDT using the exact displayed
record/preference binding, stays selected as requested while disabled for saving, and reloads the
authoritative value on failure. Imported, incomplete, corrupt, missing-channel, and otherwise
ineligible records show no checkbox. The action menu has no update-check editor.

The title bar shows the packaged launcher version (0.14.5) or identifies a development build. Settings
uses equal-width, top-aligned columns at wide window sizes: Game Java, Diagnostics, and
Launcher update (Windows MSI) on the left; Community and Latest news on the right.
At narrow sizes these groups stack in that reading order, with the bottom navigation
remaining visible and the sections scrolling vertically as needed. News headline buttons
use the right column's available width with left-aligned text rather than a fixed character limit; at narrow
widths their full titles remain available through tooltips and accessible names. Loading and error
states keep the same layout. Settings
includes Game Java, Diagnostics, Launcher update on Windows MSI, Community, and Latest news;
there is no global automatic-check control. Latest news loads up to three dated headlines from
MegaMek's official Atom feed in the background once per window, links to the full posts, and
always offers the blog archive when offline. Remote article HTML is not rendered in the launcher
and news loading never delays navigation.

The Windows launcher-update confirmation says **Version \<version\> is available.** and
**Close any running games before updating.**, with **Cancel** and **Update now** actions.
Clean, confirmed update completion is diagnostic-only: no success popup, confirmation
preamble, or extra detail in a release-lookup error. The title bar identifies the current version.
Failures, required computer restarts, staged MSI cleanup warnings and unresolved pending
updates retain their existing presentation. Diagnostic-write failures are surfaced explicitly.

The effective game Java
section shows the feature and canonical selectable/tooltipped executable without exposing
persistence implementation details. Its heading and contents are left aligned, the path uses the
regular UI font, and the change action sits below the path on the left. A successful **Change default Java** validates Java 21+ off the EDT
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
**Install latest MegaMek Development**, **Install latest MegaMekLab Development**,
**Install latest MekHQ Weekly**, **Install latest MegaMek Weekly**, and
**Install latest MegaMekLab Weekly**, in that order. Each
available row shows its validated version. The popup contains neither a redundant MekHQ Milestone
row nor the full exact-release picker. It preserves ordinary menu actions, keyboard opening,
Escape, accessible names/descriptions, opaque selection contrast, and a system high-contrast
fallback rather than inheriting plain white OS rows.
Successful rows need no validation tooltip; unavailable rows retain concise diagnostic details.
On initial empty Home, one frame-scoped background snapshot loads all nine choices. Until it
succeeds, both install segments are disabled and unfocusable while **Use existing installation**
stays enabled. Failure or partial availability exposes one explicit Retry command; retry
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

If the selected folder cannot be inspected as a MegaMek suite installation, the operation surface
shows one plain instruction to choose the main MegaMek, MekHQ, or MegaMekLab installation folder.
This expected validation state offers only **Close**: it does not expose raw layout/path details,
developer diagnostics, or a secondary logging warning. Local diagnostics remain available through
Settings when recording succeeds.

Ready quick-install actions pass the selected cached `QuickInstallOption` to an off-EDT planner.
That planner freshly captures destination and registry/default state but does not read Settings or
perform a current-record lookup. Confirmation opens with the shared Home
status cleared. Cancel, Escape, window close, local retry, and Change location preserve the cached
labels and readiness. Before transfer or parent creation, the backend revalidates the captured
record and its references, including release/asset IDs, tag, name, size, SHA-256, and URL.
It never follows a newer current record for that quote; exact-target drift fails for fresh consent.

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
**Install latest MekHQ Milestone (0.51.00)**. There is no separate caption. A single background
metadata-only snapshot discovers bounded complete records and validates all nine
repository/channel targets. Repeated product release IDs are cached within the attempt.
The main label and eight labels update atomically only on the current panel.
It never requests package bytes or writes state. Loading disables both install segments but not
other onboarding. Offline results show **Version unavailable** and eight disabled
**(Unavailable)** rows plus the explicit Retry command. Partially available channels remain usable:
a Weekly-only bootstrap leaves the Milestone primary disabled and Weekly rows enabled. Each disabled
choice carries a reason; explicit Retry can refresh partial snapshots. Stale components are never rebound.

The normal planner accepts only one explicit official `MEKHQ`, `MEGAMEK`, or `LAB` repository and
one explicit Milestone, Development, or Weekly channel. The default overload remains available to the
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
current-target lookup; captured record/release revalidation plus the installer's expected-asset check still
reject release/asset ID, tag/name/URL/size/digest drift before binary transfer without retargeting. Java,
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
launcher-managed application payload/support-data directories, not `MegaMek Launcher.app` resources.
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
One fetch performs bounded complete-record discovery and resolves the current record plus the
records needed for one selected-membership product-history page. Every resolved record requires
all three product references to validate. It never requests package bytes.

Results include the selected channel's current product and only history with explicit record membership.
Reused product tags retain their newest referencing record as the captured install source.
No title, `prerelease`, version, or publication-date ordering
inference is allowed. Page one contains the selected current identity exactly once, with stable
repository/tag deduplication. **Previous page**, a non-button **Page N**, **Next page**, and
**Install** share a stable footer. Previous is disabled on page one; Next follows the
record history's bounded may-have-next signal. Product or channel changes clear selection/results/page
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
The proposed complete-suite records and the current workaround are documented
in [the CI update proposal](../ci_update.md).

Static inspection, registry writes, package metadata/download work, launch-time Java validation,
and child-process waiting run on background `SwingWorker`s, never the event-dispatch
thread. Ordinary actions are disabled during single-flight operations and restored after success
or failure. Launch waiting is an exception: after dispatch, manually restoring the minimized
window exposes launch controls for additional games. The per-root OS gate serializes each start
and mutation; a durable per-root ledger tracks every started child independently. Updates,
uninstalls, and recovery remain blocked while any tracked child is live, including children of
other launcher processes. Ambiguous starts require explicit close-all recovery.
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
and full size. **Update to recommended release…** goes directly to that captured target and opens a
styled update consent showing the application name, current version, new version, and rounded
human-readable MiB download size. It states that the operation will download, verify, plan, and
apply the update and reminds the user to close suite applications. Its right-aligned actions are
**Cancel** then **Update**, with Update as the default. This one consent authorizes the complete
attempt; Cancel, Escape, and window close fetch no package body. Before any package request the
backend rechecks the record, channel sidecar, fixed source, repository, tag, asset name, size, and
digest. Changed metadata is rejected for fresh consent; it cannot silently retarget. There is no
standalone GUI preview picker. Update computes the same read-only plan internally and proceeds to
Apply without another UI confirmation; it cannot change the channel.

Home determines update/preview availability from local registry, immutable receipt, and latest
current-provenance validation without startup network access. Missing, corrupt, stale, or
unsupported receipts disable **Update…** and **Uninstall…**.
A receipt-backed copy with a changed registered JAR can still preview (and report `SKIP`) while
launch remains disabled. Preview uses the receipt's fixed repository and explicit paged Fetch/Next
actions. Before downloading it confirms source, exact target, full byte size, temporary static
inspection, and read-only scope. Work runs off the EDT. Results show all decisions, counts,
protected paths, and excluded official paths; the Preview report itself has no Apply control.

**Update…** is a distinct, attempt-scoped workflow. After its one consent it downloads the exact
selected full package once, verifies/extracts it in a random owned workspace, and builds the same
read-only plan internally. Preparation completion does not interrupt progress with another consent
or expose the plan as a decision point. The worker immediately supplies the existing explicit
backend confirmation token, atomically claims that one-use handle, acquires the captured root's cross-process gate,
re-reads the captured record/receipt/current source, refreshes exact release metadata, re-verifies
the retained compressed size/SHA-256/file identity, safely re-extracts from those bytes, validates
the result against the pristine captured target inventory/inspection, replans fresh local files,
and applies only through `RealUpdateService`. The recommended route additionally refreshes the
channel source and preference under the gate. Drift fails with instructions to restart; it never
redownloads or retargets.

One background worker owns the prepared handle from the initial consent through Apply/discard.
Package transfer, hashing, extraction, planning, Apply, and potentially large cleanup stay off the
EDT. Cancellation and a late result after frame disposal discard the exact workspace until the
existing transaction cutoff makes cancellation unsafe. Concurrent close/Apply, reuse, another
facade, changed source/root binding, a live launch gate, or pending recovery fail closed. Clean
success disposes progress and silently reloads the current Installations page; pristine versus
preserved-override decisions do not create a completion dialog. If installation commit succeeds
but temporary-package cleanup fails, the existing styled progress surface remains visible with one
concise **Update complete — cleanup warning**, **Close**, and logs access, and says not to retry
Update. Apply failure retains the transaction journal/backups needed by recovery and does not let
package-cleanup failure replace the original cause.

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
styled, keyboard-accessible **Enable Updates** action. It appears only when receipt,
current state, channel, adoption staging, and pending transaction are all absent. Managed,
managed-incomplete/corrupt, and recovery-pending cards never show it.

The compact first screen shows one **Program (Version)** line and a styled update-channel selector
with Milestone selected by default and Development and Weekly as alternatives. **Cancel** is fixed at
the far left and **Continue** at the far right; Escape cancels and Enter continues. No network
request occurs until Continue. Continue searches bounded GitHub release-list metadata for the
detected official repository and automatically prepares the copy only when one safe immutable tag
matches. Numeric dotted versions compare numerically (`0.50.7`, `0.50.07`, and `v0.50.07` are
equivalent), while component count and prerelease/build suffix remain identity-bearing. It first
compares the primary product's observed version with the available channels' current complete-record
targets. A selected-current identity is allowed, a shared identity is valid for its matching channels,
and a distinct identity current only in another channel is rejected before package download.
An identity matching no current target remains historical/unknown and may use the selected fixed
channel in this legacy-ancestor adoption route, not in the normal record-only install browser.

Drafts and releases without exactly one eligible supported asset are ignored. Search is capped at
ten 50-entry pages and refuses a result if more history may exist at that bound. It does not infer
a historical channel from title or prerelease metadata, does not probe a guessed exact-tag URL,
and does not request package bytes. Preparation then re-fetches the selected exact tag and uses the
existing one-package verified path. If there are zero or multiple safe candidates, the bound is
reached, or metadata fails, the normal UI says only **We couldn’t find the matching official
version. This copy will remain launch-only.** and offers **Close** /
**Choose a different version…**. The latter alone opens the existing bounded paged
official-history chooser; rows use understandable versions and do not expose digest or repository
terminology. Technical causes are retained only in sanitized local diagnostics. All
metadata/package work runs in a worker under `BusyGate`, with exact record, selected channel,
dialog generation, and cancellation guards.

The typed opposite-current result replaces the progress surface before package download. Its gold
heading names **Use Milestone**, **Use Development**, or **Use Weekly for this installation**,
plain copy identifies the version's current canonical channel and confirms that no files changed,
and its only actions are a direct styled **Try Milestone** / **Try Development** / **Try Weekly** retry and
**Close**. Retry uses the same record, suggestion, and dialog generation with the canonical
channel. It neither exposes logs/technical details nor mislabels a file-comparison failure as a
channel mismatch.
Both the channel-mismatch and ineligible result dialogs are fixed-size (not resizable),
retain their centered dark styling, default action and Escape-to-close behavior, and
offer no technical-details action. A disposed progress surface must not be updated again
after either result takes over.

Verification uses one styled progress surface and supports safe cancellation until publication.
Its three filesystem scans are distinguished as **Checking existing installation**,
**Checking official package**, and **Confirming installation has not changed**, with file counts.
The official scan hashes each file once and shares that immutable evidence between ownership and
full-tree comparison. The visible summary never lists paths, digests, or comparison counts. An
eligible result immediately proceeds to atomic metadata publication for the already-selected
channel without a second confirmation. This includes read-only reconstruction of sticky
case-only managed data/docs/licenses prefixes (with any modified-file history); excluded
`bin/*.bat` scripts remain outside managed ownership even when local bytes differ.
Case-only runtime paths and structural conflicts still block adoption. An ineligible result uses
the standard gold heading **Updates can't be enabled** and explains the
actual blocking category in plain language: version mismatch, a file/folder
conflict, or modified core application files. It confirms that no files changed, directs the user
toward a separate managed installation, and offers only **Close**. Detailed
exact/modified/missing/protected/unknown/conflict/case-prefix information remains bounded to local logs.
An ineligible verification stays in this result even if discarding its owned temporary
workspace reports a cleanup warning; only local diagnostics include that warning.

Final revalidation and publication remain in the same cancellable operation as verification and
reuse its progress surface and prepared immutable official evidence. Final revalidation is local:
under the root gate it rechecks registry, true-import, and pending state, then compares one fresh
local root snapshot with the prepared snapshot. It does not re-fetch metadata, re-hash the archive,
extract again, or rebuild official evidence. Cancellation remains available through that local
check, then becomes unavailable at the atomic metadata-publication barrier. Publication runs off
the event-dispatch thread without re-downloading or modifying the root.
Success refreshes the Installations page in place and automatically checks the newly managed copy,
so its current update availability is visible there. Failure keeps
launch available, displays one concise launch-only message, and links to logs. Closing or
cancelling before publication closes the opaque prepared handle and removes its owned workspace.
The fixed channel cannot be edited after success.

Tests should use a disposable `--registry`, fixture installation, fake `ReleaseTransport`, and fake
`ProcessRunner`; they must not download or launch a real game. The default `test` task runs
headless, retaining component/state assertions and the production automatic-check worker's
opt-in, eligibility, bounded selection, deduplication, cancellation, binding and EDT behavior.
Tests that need actual windows are tagged `native-gui` at method level in mixed classes and
run with `nativeGuiTest` in the independent advisory desktop workflow, not the release gate.
Missing display/all-skipped execution is not a successful advisory run.
Native smoke tests must dispose their own windows on the EDT.
Tests that trigger background diagnostics must release and join their fixture's writer
before JUnit deletes the disposable registry directory. The preference-save failure
regression deliberately holds the real logger, checks that UI recovery remains responsive,
then verifies saved diagnostics and the terminal logging status before disposing its windows.
`SwingTestSupport.createWindow`
also disposes a fixture that finishes construction after its caller times out; ordinary EDT
read probes must never acquire that disposal behavior. Timeout failures retain thread stacks,
including the event-dispatch thread, for diagnosis instead of relying on larger time budgets.
