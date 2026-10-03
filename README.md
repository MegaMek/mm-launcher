# MegaMek graphical launcher

## CI distributions

The official source repository is [MegaMek/mm-launcher](https://github.com/MegaMek/mm-launcher).
The `Launcher native installers` workflow builds on pushes to `main`, pull requests,
and manual dispatch. PR branch pushes run once through the pull-request event rather
than also starting a duplicate push matrix. CI produces **only** five unsigned native installer/checksum
pairs: Windows x64 `.msi`, Linux x64 `.deb` and `.rpm`, and macOS `.pkg` on both
Intel and Apple Silicon. It does not build, verify, or upload portable archives,
install packages on CI runners, create a release, sign, or notarize. The separate
manual `Publish launcher release` workflow reuses these same builds and tests before publication. Each
installer contains a host-native Java 21 image with all JDK modules and `bin/java`
for games launched by the application; no external Java is required.
Run `./gradlew test buildDebInstaller buildRpmInstaller` on Linux,
`./gradlew test buildPkgInstaller` on macOS, or
`.\gradlew.bat test buildWindowsInstaller` on Windows. Windows packaging requires
WiX 3.14 in `.tools/wix314`; CI verifies the downloaded binaries.
The former archive tasks remain available for development but are not CI outputs.

After a release updates `main`, the native-installer workflow still appears, but its
small read-only tooling job can skip the four duplicate installer builds. It verifies
the exact published tag, version-only candidate and candidate branch, plus successful
build/test/publication jobs from the original release run. Current release and asset IDs,
sizes and SHA-256 digests must also match the small immutable provenance artifact from
that run's successful publication attempt. The gate downloads only that bounded record,
not installers. Missing or expired evidence means normal builds; replaced assets fail
explicitly. Recording/uploading this optional evidence does not block publication.
A release-run trailer is only
a lookup hint, not permission to skip CI. PRs, ordinary code changes, manual builds and
the release workflow's pinned candidate builds still build all platforms. The decision
and original run link appear in the workflow summary. The advisory workflow uses the same
read-only proof to skip its four desktop jobs only for these verified version-only pushes.
PRs, ordinary main changes, manual desktop runs and weekly runs still execute desktop tests.
Missing evidence keeps normal validation; invalid evidence fails visibly. Neither workflow
publishes anything.

Actions run titles explicitly say **Launcher build checks** or **Desktop UI checks**,
with the event and branch, rather than reusing the release candidate's commit message.
After publication, two short workflow entries can still appear: installer verification
and desktop-check selection. Verified version-only pushes skip both expensive matrices;
the Actions entries themselves are not hidden. Ordinary PRs add one lightweight desktop
selection job before their four desktop jobs.

`test` is the required headless gate: backend/file-safety tests, deterministic UI
components and state, the production automatic-check worker, and safe Windows helper
execution. Tests that open native windows are tagged `native-gui` and run separately:
`.\gradlew.bat nativeGuiTest` on Windows, `./gradlew nativeGuiTest` on macOS,
or `xvfb-run -a ./gradlew nativeGuiTest` on Linux. The independent
**Launcher desktop smoke tests (advisory)** workflow reports real failures on all four
platforms, but does not block release publication. Do not make its jobs required branch
checks. It must execute tests, not report an all-skipped run as success.

The Windows MSI is an additional per-user installation (bundled Java, fixed
install folder, persistent upgrade UUID). Installed Apps, Start Menu, the
desktop shortcut, and executable are named **MegaMek Launcher**; the icon on
the installed application and installer is derived from MegaMek.png. The macOS
app bundle uses an ICNS derivative of that same source. The
interactive WiX wizard uses composed crops of the launcher first-launch artwork,
shows the costed per-user binary path on Welcome, advances through a modeless
installation progress dialog with a steady status label and live progress bar,
and offers a checked **Launch MegaMek Launcher** option on its completion screen. This
launch is an interactive finish-button action only: `msiexec /qn` self-upgrades
never launch the app. It does not change the `.msi` file association or prompt
for an install directory. `mm-launcher` remains the compatible internal CLI
and Linux script name.
Version 0.14.5 is a newer MSI for upgrading existing 0.14.4 (or earlier) installations;
rebuilding the same version does not allow a same-version MSI reinstall. The upgrade
keeps the fixed upgrade UUID and install location and leaves user data intact.

The Linux installers use the stable `megamek-launcher` package identity under
`/opt`, while macOS uses bundle identifier `org.megamek.launcher` under
`/Applications`. macOS's internal package version offsets the numeric major
by one (for example, launcher 0.14.5 uses package version 1.14.5) because
Apple does not accept a zero major; the downloadable filename retains the
launcher version. They contain only program files. Neither installer owns the
per-user registry, logs, settings, or any registered game installation;
upgrades must leave those separate locations untouched.

Supported native installations check the official MegaMek/mm-launcher
GitHub latest stable release for a newer numeric version. One themed
**Update now** confirmation authorizes download, verification, closing the launcher,
and installation. Its text states the available version and asks users to close running games.
A cancellable progress dialog shows download and verification;
cancellation stops being available when installer handoff begins. It requires an exact release
installer asset with a published SHA-256. On Windows it checks the staged MSI's upgrade code,
product name and product version using Windows Installer, then exits before
the per-user upgrade begins with visible Windows Installer progress (`/passive`,
no extra wizard and no forced computer restart). After successful completion,
the helper records its result before reopening the launcher; the next launch confirms
the installed version and quietly saves clean success in local diagnostics, without a
success popup. Failures, restart-required notices and staged installer cleanup warnings remain
visible. Settings offers a manual check.
macOS uses the matching Intel or Apple Silicon package and opens Apple's Installer,
including its normal authorization and security checks. The helper waits for Installer to
close and confirms the package receipt and app version before reopening.
Linux selects `.deb` or `.rpm` from the installed package, not whichever tool happens to
be present. PolicyKit supplies the administrator prompt; `apt-get` or `dnf` performs the
upgrade using a verified private copy. Linux requires a desktop authorization agent.
Portable copies and unsupported package-manager setups remain manual.
Existing macOS/Linux launchers need one manual upgrade to a release containing this support.
Active updates are shown as still finishing, not as failed release lookups. Completed
failures are acknowledged so a later manual check can retry. Incomplete reports are
retained as diagnostic evidence: identified targets are confirmed as updated only
with a matching installed version and an exited helper. If the old version is still
confirmed installed, the failed attempt is acknowledged so a later check can retry.
Safely reconciled incomplete reports do not show a popup or get appended to an update
confirmation or release-lookup error. Their original evidence is archived and recovery
details are saved in local operation logs. Old reports without a target remain an unknown
previous result, not a claimed successful update, after checking that the old
helper is absent and the system confirms the current installation. A successful installation
with staged installer cleanup failure is reported as a cleanup warning, not as an
installation failure. Pending or unrecognized results remain for review; a
completed result is acknowledged before the release lookup, even when offline.
Helper output and installer diagnostics are retained beside the registry
as `msi-update-helper.log` and `msi-update-installer.log` on Windows, or
`launcher-update-helper.log` and `launcher-update-installer.log` on macOS/Linux; update operation logs include
both locations. Windows PowerShell's atomic completion write uses `[NullString]::Value`,
not `$null`, which is converted to an invalid empty backup path by its .NET method binding.
If no compatible
official release exists, no upgrade is attempted. Portable archives do not self-update.
Native upgrades replace installer-owned binaries, not launcher registrations, settings,
logs or installed games. Downloads retain macOS quarantine; self-update does not bypass
Gatekeeper, package-manager trust policy, or administrator authorization.

### Publishing a launcher release

The manually dispatched `Publish launcher release` workflow is independent of the game-suite
coordinator. Select **main** and click **Run workflow**, with no version field. It increments the
committed patch version (for example `0.14.5` to `0.14.6`), prepares a version-only commit on
`release-candidates/<version>/<run-id>` using a dedicated release bot, and captures that
exact candidate. **Main's version does not change during preparation or builds.** It refuses forks, other
branches, stale dispatches, existing candidate tags/releases (including drafts), and a version
not newer than every published stable launcher release. It never runs automatically on pushes
or pull requests. Major/minor version changes remain explicit source changes.

The workflow calls the ordinary native-installer CI at the captured candidate commit and
waits for all four platforms' builds, package inspections, required headless tests, and release tooling
tests to pass. Only then does one write-enabled
job download this run's five installers and five checksum files, check the exact filenames and
hashes, create `v<version>` at the tested commit, and upload the complete asset set. GitHub must
report the expected uploaded asset IDs, sizes, URLs, and SHA-256 digests, including the digest used
by native self-update. All installers must fit the updater's 300 MiB limit.

Publication goes straight to a stable release in that same run, with no draft-review approval
stage. The upload operation briefly uses GitHub's draft flag so an incomplete asset set cannot
become the update target; it is cleared automatically only after verification. The workflow then
verifies the official latest-stable endpoint. Installers remain unsigned and macOS packages are
not notarized. A separate finalization job rechecks the published release, latest endpoint,
tested tag, and version-only candidate, then fast-forwards `main` without force. Main must
still match the captured base commit; concurrent work is never overwritten.
During upload, GitHub may give draft assets a temporary `untagged-...` download address.
The publisher accepts it only when it matches that draft's official release-page address and
the exact asset name. Once published, every download address must use the final `v<version>`
tag, including on the latest-stable endpoint. Asset IDs, sizes, states and SHA-256 checks remain
mandatory throughout.

The release bot needs one-time installation, repository-scoped Contents write permission, an
explicit protected-main ruleset bypass, the `LAUNCHER_RELEASE_APP_ID` Actions variable, and
the `LAUNCHER_RELEASE_APP_PRIVATE_KEY` Actions secret. Preparation and finalization each use
a fresh, repository-scoped App token, revoked at the end of their job; neither token is
passed to builds or publication. See
[release bot setup](docs/archive-distribution-contract.md#release-bot-setup).

Writes are not automatically retried, existing assets/tags are never overwritten, and failure
does not delete a candidate branch, tag, draft, or published release.
A failed build or unpublished draft leaves `main` unchanged. Inspect any partial state before
another attempt: publication and the main update cannot be one atomic transaction.
If publication succeeded but finalization failed, resume only finalization after inspection,
using the same candidate and artifacts; do not publish or increment again. An already-synchronized
candidate is confirmed without another write. If `main` has advanced independently, an owner
must review a manual version synchronization that preserves that newer work.
Full Windows self-update still requires an older installed MSI and a newer published
version. Publishing the same version as the installed launcher does not trigger an upgrade.
See [the distribution contract](docs/archive-distribution-contract.md) for validation commands.

The desktop default registry is `%LOCALAPPDATA%\MegaMek Launcher\launcher-registry.json`
on Windows, `~/Library/Application Support/MegaMek Launcher/launcher-registry.json`
on macOS, and `$XDG_STATE_HOME/MegaMek Launcher/launcher-registry.json` on Linux
(fallback `~/.megamek-launcher/launcher-registry.json`). On first default GUI
launch, a valid schema-3 registry from the previous `MegaMek` location is
copied along with its launcher settings, receipt/metadata directory, and logs
to a new directory via atomic rename. The old state remains intact as a
backup; registered game files are never moved or removed. An unsupported old
schema (including schema 1) is left untouched, so it cannot block a new
install. Unknown future schemas, corrupt schema-3 data, symlinks, pending writes or conflicting new
state stop migration visibly without resetting either location. Custom
`--registry` paths do not migrate. Future versions should continue reading
schema 3 where compatible; any eventual schema change needs an explicit,
version-aware and backed-up migration, never a speculative unknown-schema
conversion.

These unsigned, non-notarized CI artifacts are not a release; signing and
notarization remain outstanding. MegaMek Launcher source code is GPL
version 3 or later (see [LICENSE.code](LICENSE.code)); distributions carry
the same license text alongside dependency notices. Archive builds and their
read-only verification remain developer-only.

## Developer-only portable archive prototype

The developer-only archive tasks can build a versioned all-platform portable archive.
Install an external **64-bit Java 21 or newer** runtime, download
`MegaMek-Launcher-0.1.0-SNAPSHOT.tar.gz`, extract it, and keep the fixed `MegaMek Launcher/` tree intact.
Choose the entry point for the current OS:

- Windows: `MegaMek Launcher.exe`
- macOS: `MegaMek Launcher.app`
- Linux: `./mm-launcher`

The one shared application/dependency payload is inside
`MegaMek Launcher.app/Contents/app/lib/`. This keeps the macOS app self-contained; the sibling Windows
and Linux entry points deliberately use that same payload. Do not move the Windows executable or
Linux script away from the sibling app folder.

The macOS and Windows prototypes are unsigned, and the macOS app is not notarized. Do not bypass
Gatekeeper or other OS security controls. Signing, notarization, and a public source-license
decision remain release prerequisites. The generic archive is Java-free; the
native installers bundle Java, but the launcher does not download a JRE.

The support goal is all three OSes. The archive's Windows x64 entry point and native startup smoke
were validated locally on Windows. CI now builds and inspects native installers instead
of running portable archive verification across runners.

Build the one archive and its checksum, then verify its full structure and current native entry
point with:

```powershell
& 'C:\repos\megamek\mm-launcher\gradlew.bat' `
  -p 'C:\repos\megamek\mm-launcher' buildArchive verifyArchive
```

The generic archive outputs are `MegaMek-Launcher-<version>.tar.gz` and its exact-name `.sha256` file under
`build/distributions/`. `verifyArchive` checks all three entry points, the single shared payload,
and checksum, then runs the current host's native entry point. `verifyProvidedArchive` is the
separate read-only CI mode and requires explicit `-PprovidedArchive=<path>` and
`-PprovidedChecksum=<path>` inputs; it never depends on packaging. It is not
used by installer CI. `--version` and
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

Registry schema 3 stores those targets by application key and stable installation UUID. Older
shapes, including records containing per-copy Java, remain rejected; only the same schema 3
is relocated between default launcher data directories. Missing or stale
targets use the first matching record as a side-effect-free runtime fallback. Registration fills
only still-unset application preferences; removal safely retargets affected preferences to the
first remaining compatible copy. Strict parsing, canonical roots, locked mutation, and atomic
replacement remain required.

Home has no visible global “Main” block, per-copy update buttons, or a second navigation control
for update status. Its single **Installations** button gains a known count such as
**Installations (2 updates)** when checked installations have updates; unknown and launch-only
copies never create a false count. Update, retry, recovery, channel, location, removal, and explicit
**Use as preferred for …** actions live on dark-teal installation cards. A managed card shows its
read-only **Channel: Milestone/Development/Weekly** identity and directly shows
**Check for updates when the launcher opens**. This per-card checkbox is the sole automatic-check
setting and saves immediately without changing the fixed channel. An imported card says
**Imported copy · Launch only · Updates unavailable** and has
no channel, check, preview, update, or recovery action; only its separate opt-in
**Enable managed updates…** action is available. There is no bulk update.
Managed Home has one top-left information area for useful aggregate update state:
**Checking for updates…**, **All installations are up to date**,
**N installations have updates**, or **Some installations could not be checked**. It does not
repeat an obvious post-install “ready” message or place status text above navigation.

The title bar shows the packaged MegaMek Launcher version (or "development build" outside a
packaged release). Settings also includes **Launcher update** on supported native installations, **Community**,
and **Latest news**. Latest news reads the official MegaMek Atom feed once per launcher window,
independently of installation checks, and shows up to three dated headlines linking to official
posts. **All news** opens the blog archive even if the feed cannot be loaded; news never blocks
the launcher. At wide window sizes Settings places Game Java, Diagnostics, and
Launcher update in one column beside Community and Latest news in an equally wide
column; narrower windows stack the groups in reading order above the fixed bottom
navigation. **Change default Java** validates and
atomically stores the sole external Java 21+ executable for every installation. Launch and
explicit launch preview resolve and revalidate this setting; installation records contain no Java
path or feature. With no saved default, the exact Java runtime executing MegaMek Launcher is the
automatic effective runtime and is not persisted. An invalid or corrupt explicit setting fails
visibly instead of silently falling back. The
pre-release settings schema contains no global update
fields and deliberately does not migrate older settings schemas. Settings presents unboxed,
left-aligned sections; the Java version and regular-font path appear above
the left-aligned change action, and **View logs** opens a styled local viewer.
Settings displays the effective runtime whether it is automatic or explicitly selected. The
selector uses the same dark-teal/gold controls. No JRE is
downloaded or installed by the application; installed/native launcher
distributions already bundle a runtime.

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
6. **Install latest MekHQ Weekly**
7. **Install latest MegaMek Weekly**
8. **Install latest MegaMekLab Weekly**

Each available row includes its independently validated version in parentheses. One background
snapshot per launcher-window session discovers complete suite records for Milestone, Development,
and Weekly, validates all three exact product references before offering any product, and caches
reused release IDs. The record's mandatory SHA-256 remains authoritative when GitHub omits its digest.
There is no website YAML or independent-latest-release fallback. While it loads, both install segments are visibly disabled and removed from
keyboard focus, while **Use existing installation** remains available. Failure keeps installation
unavailable and exposes one explicit **Retry version check** command; concurrent retries are
deduplicated. The first successful immutable snapshot is retained for the frame lifetime, so Home
reloads, confirmation cancellation, location changes, and repeated quote opens preserve the same
labels without another current-channel request. Missing channels are disabled with explicit reasons;
Weekly-only records remain usable without changing the Milestone default. Explicit Retry can refresh
partial availability. A visible secondary
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
[first-launch presentation notes](docs/first-launch-ui.md). New windows request a
1180-by-820 logical-pixel size, capped to the current monitor's usable work area; users can
resize them, but the size is not yet saved across launcher restarts.

Managed Home keeps that composition: full-width cover artwork on top and a compact, scrollable
dark control deck below, with no duplicate header/footer or left artwork rail. It renders the
per-application split controls and aggregate installation-level update summary described above.
A missing root or pending recovery is routed to the exact installation card. An absent saved Java
default is healthy and never blocks Home; Java is resolved by the launch worker.
Imported/no-receipt copies remain launch-only and are never offered impossible update actions.
Their explicit **Enable managed updates…** flow presents one compact program/version and future
channel choice. **Continue** searches bounded official release-list metadata automatically; it
never guesses a tag URL. Exactly one eligible normalized version match proceeds to the existing
one-package verification. Otherwise the copy remains launch-only and the user may explicitly
choose a different official version from the bounded chooser. Existing application and personal
files are not changed by matching or verification.

The existing `application`/`installDist` entry point remains the CLI. Requires Java 21. From the
launcher checkout, build and run the graphical subcommand in PowerShell:

```powershell
Set-Location C:\repos\megamek\mm-launcher
.\gradlew.bat installDist
if ($LASTEXITCODE -ne 0) { throw "Build failed with exit code $LASTEXITCODE." }
.\build\install\mm-launcher\bin\mm-launcher.bat gui
```

GUI tests use the package-local `SwingTestSupport` harness. Probe Swing state on the EDT,
wait for the actual rendered result rather than service-entry counters or fixed sleeps,
and reacquire controls after a render. Assertions and component counts also belong on
the EDT: finding a component does not make a later off-EDT tree read safe. The shared
counting helpers traverse the entire tree in one EDT turn.
`click` looks up a showing, enabled control and
clicks it in the same EDT turn. Use `startClick` or `startClickText` for actions that can
open a synchronous modal dialog, then await the owned dialog and dismiss it; a synchronous
click cannot finish while its modal dialog is open. Asynchronous listener failures are
reported to the test, and waits have bounded, named timeout failures. Installation menus
must belong to the exact clicked invoker; dialog lookup and recursive teardown must stay
within the tested window's ownership tree.

`SwingTestSupportTest` exercises hidden/disabled and replaced controls, modal interaction,
exception propagation, timeout diagnostics, and unrelated-window isolation. The existing
GUI classes use the same harness. They require a display (Xvfb on Linux); a headless skip
is not GUI qualification. Two package-local testability hooks expose metadata-worker
completion and let progress tests explicitly flush pending rendering, without changing
the production worker or timer behavior.

Review Home, Installations, Settings, the split **Install latest MekHQ Milestone** control,
**Use existing installation**, **Install another version**, Update, recovery, Game Java
selection, and direct launch behavior. The primary normal first install uses the greatest complete
Milestone suite record's MekHQ target. Each popup item binds its displayed official repository and
record membership; no selection can substitute a different product, channel, title, or
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
current-record lookup. Local planning still freshly captures destination and
registry/default state off the EDT. Before package bytes or parent
creation, install revalidates the captured record and its exact references, requiring the same release/asset
IDs, name, size, SHA-256, and URL. A newer record cannot silently retarget the quoted install;
exact metadata drift requires fresh consent.

Static folder inspection recognizes independent program versions, preferring each root jar's
`Implementation-Version` and retaining the legacy shared-MegaMek metadata fallback when absent.
The bundle display uses MekHQ's version, otherwise Lab's, otherwise MegaMek's. This does not prove
compatibility or ownership: imported copies remain launch-only until exact official archive
verification succeeds. Conflicting versions of duplicated MegaMek jars remain unsupported.

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
path is managed payload/support data, not content inside `MegaMek Launcher.app`.
Confirmation is required before any package byte or destination/state write. Metadata drift, an
appeared destination, or registry/Main drift rejects the attempt for fresh consent; no choice
falls back to another repository/channel, a different tag, or “latest.” MekHQ requires the exact
three-program suite; standalone MegaMek and MegaMekLab packages must inspect as only their actual
product, so managed Home never gains launch buttons from a mixed or title-inferred bundle.

The Installations page's dark **Install another version** dialog defaults to MekHQ and Milestone.
Its Product/Channel/**Fetch releases** row discovers bounded complete records and shows one page
of product targets with the selected record membership. Page one contains the current product
exactly once; reused tags retain their newest referencing record as the captured source.
All three product references must validate for each displayed target. Titles, publication dates,
and `prerelease` flags never supply classification, and unclassified repository history is not
a fallback. The selected Channel becomes the new installation's immutable update track.
No picker metadata action fetches package bytes.

Previous/next use the exact loaded product/channel snapshot and a bounded record-history page. Changing
either combo clears the rows, selection, and page controls and invalidates late results. Eligible
rows use **Program Channel (Version) — human size**; unavailable rows contain only
**— Unavailable** after the identity. A separate **Page N** indicator remains in the footer, while
the reserved status line is blank after success. The controls use the same styled combo/button
treatment and high-contrast fallback as the rest of the launcher.

Before consent, the picker sends the exact choice and captured record source through the
normal-install planner. Current and historical choices revalidate that record and its exact
references, never a newer current record. Before transfer the planner repeats registry/destination
checks and requires the same source, release/asset IDs, tag, asset URL, name, size, and mandatory
record SHA-256. The package must match that digest even when GitHub publishes none.
Every successfully published graphical managed installation
starts with its per-install check-on-open value enabled. See
[`ci_update.md`](ci_update.md) for the complete-suite record and producer release contract.
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
The installation **More… → Reset preferences…** action asks you to close all suite apps
(including those started outside the launcher) and confirms the exact files and backup location.
For a MekHQ package it moves the existing `mmconf/clientsettings.xml`, `mm.preferences`,
`mhq.preferences`, `mml.preferences`, `megameklab.properties` and
`megameklab.properties.bak`; MegaMek-only and MegaMekLab-only packages move only their
respective files. The `.bak` is included because MML can restore settings from it.
The files are moved, not deleted, into a unique backup under
`<installation>/.mm-launcher-preferences-backups/`. Unrecognized or ambiguous imported
packages are refused; campaigns, saves, custom files, and all other `mmconf` contents remain.
OS-global Java Preferences cannot be reset per installation and are not touched.
Managed/adopted copies with complete current provenance and a fixed channel additionally offer
**Uninstall…**. Uninstall moves only byte-exact current official files into an external,
same-filesystem recovery area, preserves modified/custom/protected files, removes only empty known
package directories, and removes registration/metadata last. A durable strict journal makes every
pre-commit failure recoverable; post-commit cleanup failures are successful uninstalls with a
cleanup warning. The complete move intent is recorded before changes, with phase checkpoints
instead of rewriting the whole journal for every file. Pending work blocks launch/update and
exposes **Recover uninstall** while the folder exists.
After confirmation, the GUI scans once under the same root/process gate used for removal,
with stages labelled **Checking installation files** and **Removing official files**.
Progress refreshes show the latest state and may skip a stage that has already finished.
Every targeted file is still verified immediately before its move to recovery.

If a user manually deletes an installation folder, **More…** offers a styled
**Installation not found** confirmation to remove its launcher registration, including a
validated interrupted-uninstall record. This does not recreate the folder or touch other
installations. Inaccessible locations and unavailable drives are not mistaken for deleted
folders. Reinstalling the launcher intentionally preserves the per-user registry and settings;
use this registration-removal flow rather than reinstalling to clear a missing game copy.

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
(Milestone by default, or Development or Weekly). It treats that information only as a candidate. Before
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
product/build identity mismatch with the selected archive, or a linked/special runtime path fail closed. Ordinary Update replaces
damaged or missing official runtime files and removes obsolete official runtime, retaining a
verified rollback backup until commit. Extra unowned JARs under lib are preserved (they may affect
the game). Modified non-runtime official files
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
