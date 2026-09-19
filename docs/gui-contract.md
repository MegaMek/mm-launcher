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
preferred `InstallationRecord`, displays that record's version, and uses the existing preview,
confirmation, Java, coordinator, and launch validation. Alternate rows are deterministic,
launch-only exact-record actions and never mutate a preference or fall back at click time.

Registry schema 2 adds a strict `preferredInstallationIds` application-key/installation-UUID map
and retains `defaultInstallationId` for CLI/API compatibility. Schema-1 reads derive only keys
actually present in the old default record without writing. Valid-but-stale references fall back
at runtime to the first matching registry record and remain stale until explicit user action.
Malformed/unknown keys or values fail closed. Registration/import fills only absent keys; removal
clears only values pointing at the removed UUID. Every mutation revalidates captured record
identity under the registry lock and uses flushed atomic replacement.

Installations is a scrollable dark-teal/gold card surface. Every physical record shows name,
version, channel, applications, per-application preferred markers, and check/recovery state.
Top-level actions are **Install another version** and **Import existing installation**. Exact
record actions include **Use as preferred for …**, Change Java, Channel & checks, Check/Retry,
Preview, Update, Recover, Show location, and Remove record only when applicable. Update is primary
only when known available; imported records are marked launch-only. Selection is never a
preference, and there is no bulk update.

Settings writes each choice immediately. The launcher-wide
**Check installed versions when the launcher opens** gate defaults enabled when absent and gates
all automatic checks without changing per-record Off/channel values. The default game Java
section shows feature, canonical selectable/tooltipped executable, and whether the fallback
launcher runtime is unsaved. A successful **Change default Java** validates Java 21+ off the EDT
before atomic publication. New normal installs/imports use it; existing per-record Java is
untouched. No Java download/bundle or generic Save settings action exists.

Home's update line is derived from installation-level in-memory results. It says exactly all
current, a known update count, or that some versions could not be checked; unknown is never
success-shaped. Late workers are generation-bound and update that component on the EDT without
replacing Home. Home offers no normal Check/Retry/Update buttons. Pending recovery and launch
blockers remain exact-record actions in Installations. A successful install notice is one line,
for example `MekHQ 0.51.0 is ready`, and clears on navigation or a launch action.

Download bars paint percentage only, with localized human-readable byte detail below. Extraction
is indeterminate with processed-file detail and no bar string. Source validation for this change
is static only; tests, compilation, execution, screenshots, and archive regeneration are deferred.

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
First-launch artwork uses a centered cover treatment without side bars. The bottom control deck
has no repeated application title; equal-width actions are horizontally centered and top-aligned.
Compact layouts stack those actions and remain top-aligned and scrollable. Focus on either split
segment outlines the complete joined control, not only the arrow segment.

The visible, regular secondary **Use existing installation** action imports MegaMek, MekHQ, or
MegaMekLab and retains that caption. There is no Java prerequisite/download control or advanced
action on healthy first Home. The exact product/release/channel picker remains on Installations
after a copy exists and for relevant repair/recovery navigation. Import entry points share
static inspection, configured-default/current-launcher Java validation, confirmation, and atomic
registration. The locked import fills only still-unset preferences for products it contains;
later/concurrent imports do not replace explicit choices. Imported records contribute their
statically detected products to Home's union. Imported records
are launch-only and have no receipt/channel provenance. Logs are in Settings. `GuiScale` keeps
custom metrics in logical coordinates while Java handles per-monitor device DPI. See
[first-launch presentation](first-launch-ui.md). Artwork loading failures are reported without
disabling onboarding or treating a registry error as empty.

Managed Home uses the same top-art/bottom-deck composition, centered cover crop, dark palette, and
compact vertical scrolling as first launch. It has no duplicate launcher header/footer, left-side
art rail, raw registry path, or permanently reserved empty region. The deck contains the optional
process-local notice, application launch row, aggregate update state, and compact
Installations/Settings navigation. Three product controls share equal widths when they fit and
wrap/stack as the viewport narrows. Each primary passes its preferred exact record/product to the
existing launch backend. Its arrow lists the other matching records in registry order as
**name · observed version** plus already-held status. Menu construction performs no filesystem or
network read on the EDT. A row never persists a temporary target or falls back at click time.
Missing Java/layout/recovery disables the affected primary. With no alternate, the arrow is absent
and unfocusable.

The primary label starts without a version and gains the validated MekHQ Milestone version, for
example **Install latest MekHQ Milestone (0.51.0)**. There is no separate caption. A single
background metadata-only snapshot reads the bounded
official stable/dev YAML once and validates all six repository/channel targets. Exact release
metadata is deduplicated per repository/tag when stable equals development. The main label and
five labels update atomically only for the current panel. It never requests package bytes,
disables onboarding, or writes state. Offline results show **Version unavailable** and five
disabled **(Unavailable)** rows; stale page generations are discarded. Opening while loading or
available starts no request. Once unavailable rows have been viewed, closing and reopening is the
documented explicit snapshot retry. The main action remains clickable and retries its own normal
plan.

The normal planner accepts only one explicit official `MEKHQ`, `MEGAMEK`, or `LAB` repository and
one explicit Milestone or Development channel. The default overload and primary segment resolve
`(MEKHQ, MILESTONE)`; each quick-menu row passes its fixed repository/channel pair to the same
backend. Planning is metadata-only and binds the requested repository/product set/channel, source
version/tag, full release/asset identity, product/channel-specific per-user destination, registry
and compatibility-default snapshot, Settings revision/default, and validated game Java. Its dedicated
dark-teal/gold confirmation has no release, channel, Java, or editable update-setting picker. The
summary displays only the product/channel, validated version, actual programs, binary download
size, full selectable destination with **Change location**, and Cancel/Install actions. There is no
passive update-behavior row or technical-details toggle/pane. A second same-pair metadata
resolution plus the installer's expected-asset check still rejects
feed/tag/name/URL/size/digest drift before binary transfer; Java, registry/default, Settings, receipt,
and state validation also remain backend requirements, with failures retained in operation logs.
At most four individually quoted real parents are created only after consent; the target itself
must remain wholly absent. Successful setup persists exact external Java, the exact planned
channel with the captured check-on-open setting, and the compatibility default only if empty, then returns the latest
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
record-only removal, location, Java, channel/check, Preview/Update, and recovery actions on exact
cards. Merely viewing/focusing a card mutates nothing. The exact-release picker requires an
explicit channel and uses the unchanged per-new-copy check default.

Static inspection, registry writes, package metadata/download work, Java validation, command
previews, and child-process waiting run on a single-flight `SwingWorker`, never the event-dispatch
thread. Ordinary actions are disabled during that work and restored after success or failure.
The active operation dialog is exempt so its authoritative **Cancel** remains reachable. It uses a
compact dark-teal/gold skin, logical `GuiScale` metrics, a clear typed phase, an honest accent
progress bar, and one concise nontechnical detail. Backend stream text is retained only in a
hidden bounded legacy capture; there is no visible log area or reserved blank space, and active
progress has no log/copy/Open Installations clutter. Contextual actions are invisible while
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
success returns directly to Home with a one-line `installationReadyNotice`, such as
**MekHQ 0.51.0 is ready**. It survives in-place status updates but clears on navigation or
meaningful launch action and is not persisted across launcher restart. There
is no normal-install completion dialog and no automatic launch. Failure transforms progress into
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

A receipt-backed Home shows a concise channel/check status. Existing records are Unknown until the user
chooses Milestone or Development; no tag, release title, prerelease flag, parity, or ordering is
used to infer the choice. Choosing or changing it is local-only and performs no network request.
Normal and advanced new GUI installs use the Settings default, which is true while initially absent
and preserves a later user override. The normal backend binds that captured choice without adding
an update-behavior row to the simple confirmation, and requires a fresh quote if the Settings
configuration changes or becomes unreadable.
Existing false remains false and Unknown/Unavailable is not inferred. Manual selected-copy checks
ignore the off switch. Eligible startup checks are bounded and serial, use separate cancellable
workers, and do not enter the package gate or disable launch/page controls. Results from a
disposed window, changed record/preference, or refreshed generation are discarded. Failure
is “Could not check,” never “Up to date.” Checks never apply or download packages.

Home has no per-copy update controls. It aggregates exact-current/installed-ahead only when every
installation was truthfully checked, otherwise reports known update count or that some versions
could not be checked. Per-card Update, Retry, channel repair, and pending recovery remain available
where applicable. Completion updates the summary in place and never replaces managed Home.

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
only when local state reports a pending update, so a half-updated preferred root remains
recoverable without a receipt-based healthy-Home recovery control. Update, recovery,
registry I/O, downloads, hashing, and child waiting remain on the single-flight worker, not the EDT.
Current launcher-started games hold the same root gate for their entire lifetime. The confirmation
accurately states that externally/older-started applications cannot be universally detected.

Tests should use a disposable `--registry`, fixture installation, fake `ReleaseTransport`, and fake
`ProcessRunner`; they must not download or launch a real game. A separate non-headless smoke test
may show `LauncherFrame`, wait for its empty home, exercise the Manage dialog, then dispose it on
the EDT. Skip that smoke test only when `GraphicsEnvironment.isHeadless()` is true.
