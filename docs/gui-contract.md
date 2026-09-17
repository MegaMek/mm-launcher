# Graphical launcher checkpoint contract

`gui [--registry <path>]` opens Swing without changing the no-arguments CLI behavior. The default
registry is `%LOCALAPPDATA%\MegaMek\launcher-registry.json` on Windows,
`~/Library/Application Support/MegaMek/launcher-registry.json` on macOS, an
`$XDG_STATE_HOME/MegaMek` registry when configured, or `~/.megamek/launcher-registry.json`.
An absent registry is an empty first run. An existing unreadable or corrupt registry is reported
and is never reset. Its immediate launcher-owned parent is created only when the user consents to
a mutation and its parent is already a real directory.

**Download MegaMek** is available on the empty home and through **Manage installations**,
including when the preferred copy is unavailable. Both actions open the same release picker;
opening or closing it does not fetch releases or change any registered copy.
The fresh-download picker requires an explicit Milestone or Development choice independently of
the exact release row. A successful installation saves that choice in external metadata. If the
copy was published and registered but the setting cannot be saved, the failure says that the
registered copy was retained and that the channel must be chosen again; it never reports the copy
as unregistered.

Static inspection, registry writes, package metadata/download work, Java validation, command
previews, and child-process waiting run on a single-flight `SwingWorker`, never the event-dispatch
thread. All actions are disabled during that single-flight work and restored after success or
failure. The separate metadata-only channel check described below does not take that gate. Closing
is blocked during an install or while a game is running because neither is advertised as safely
cancellable. Errors include selectable cause details. Download logs are bounded and show only
backend-reported stages.

A receipt-backed home shows **Following channel**. Existing records are Unknown until the user
chooses Milestone or Development; no tag, release title, prerelease flag, parity, or ordering is
used to infer the choice. Choosing or changing it is local-only and performs no network request.
Manual checks are explicit. Per-copy check-on-open is off by default; when enabled it is a
separate cancellable background worker that does not enter the single-flight gate or disable
healthy Launch/Manage controls. Results from a disposed window, changed default record, changed
preference, or refreshed home generation are discarded. Failure is “Unable to check,” never “up
to date.”

An available result displays verified installed tag, followed target, status, release-notes URL,
and full size. **Preview recommended update…** goes directly to that captured target. Before any
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

**Update…** is a distinct workflow. It first performs the same read-only preview, then presents a
separate confirmation with the exact second full-package download size/digest, destructive managed
actions, retained `SKIP` count, backups/recovery, and instruction to close every suite application,
including manually started copies. After consent it acquires the cross-process root gate, re-reads
the captured record/receipt/current source, fetches the exact package again, rejects changed
size/digest, replans fresh files, and applies only through `RealUpdateService`. Success distinguishes
a pristine result from preserved overrides. Failure says recovery may be required.

**Check update recovery…** is rendered independently of successful application inspection and
launch preparation, so a half-updated preferred root remains recoverable. Update, recovery,
registry I/O, downloads, hashing, and child waiting remain on the single-flight worker, not the EDT.
Current launcher-started games hold the same root gate for their entire lifetime. The confirmation
accurately states that externally/older-started applications cannot be universally detected.

Tests should use a disposable `--registry`, fixture installation, fake `ReleaseTransport`, and fake
`ProcessRunner`; they must not download or launch a real game. A separate non-headless smoke test
may show `LauncherFrame`, wait for its empty home, exercise the Manage dialog, then dispose it on
the EDT. Skip that smoke test only when `GraphicsEnvironment.isHeadless()` is true.
