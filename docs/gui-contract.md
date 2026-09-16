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

Static inspection, registry writes, metadata/download work, Java validation, command previews, and
child-process waiting run on a single-flight `SwingWorker`, never the event-dispatch thread. All
actions are disabled during work and restored after success or failure. Closing is blocked during
an install or while a game is running because neither is advertised as safely cancellable. Errors
include selectable cause details. Download logs are bounded and show only backend-reported stages.

Home determines preview availability from local registry/receipt validation without startup
network access. Missing, corrupt, stale, or unsupported receipts disable only **Preview update**.
A receipt-backed copy with a changed registered JAR can still preview (and report `SKIP`) while
launch remains disabled. Preview uses the receipt's fixed repository and explicit paged Fetch/Next
actions. Before downloading it confirms source, exact target, full byte size, temporary static
inspection, and read-only scope. Work runs off the EDT. Results show all decisions, counts,
protected paths, and excluded official paths; there is no Apply control.

Tests should use a disposable `--registry`, fixture installation, fake `ReleaseTransport`, and fake
`ProcessRunner`; they must not download or launch a real game. A separate non-headless smoke test
may show `LauncherFrame`, wait for its empty home, exercise the Manage dialog, then dispose it on
the EDT. Skip that smoke test only when `GraphicsEnvironment.isHeadless()` is true.
