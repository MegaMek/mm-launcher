# Portable archive distribution contract

## Native installer CI

The generic archive described below remains a developer-only prototype;
CI does not build, verify or upload any archives. CI builds five native
installer/checksum pairs, each on its target host:
`MegaMek-Launcher-<version>-windows-x64.msi`,
`MegaMek-Launcher-<version>-linux-x64.deb`,
`MegaMek-Launcher-<version>-linux-x64.rpm`, and
`MegaMek-Launcher-<version>-macos-{intel,apple-silicon}.pkg`.
Each has an exact-name `.sha256` counterpart. MSI uses jpackage's
full-module runtime and WiX 3.14; Linux and macOS use a host-native
`ALL-MODULE-PATH` jlink image passed to jpackage via `--runtime-image`.
These images retain `bin/java` for games, `lib/modules`, and upstream
`legal/` notices. CI only inspects installer contents and checksums;
the native-installer workflow never installs them or publishes a release. A separate manual
release workflow reuses that complete build/test workflow before publication. Optional Windows MSI
signing is prepared but disabled by default; Foundation approval and live verification remain pending.
Linux/macOS signing and notarization remain outside this integration.

Linux's stable package name is `megamek-launcher` under `/opt`; the macOS
bundle identifier is `org.megamek.launcher` under `/Applications`.
Both own only application files. Registry, settings, logs and installed
games live in independent user paths and must not be overwritten by package
upgrades. The Windows MSI retains its stable per-user installation path and
upgrade UUID described below. The archive tasks are still available for
development (including `verifyPortableArchive` and `verifyProvidedArchive`),
but are not part of the CI installer contract.
The MSI keeps the permanent upgrade UUID and per-user fixed binary directory
`%LOCALAPPDATA%\Programs\MegaMek Launcher` (no folder chooser). This is
separate from mutable registry state under `%LOCALAPPDATA%\MegaMek Launcher`,
which MSI removal must not own. Its self-contained WiX 3 UI override shows
`[INSTALLDIR]` on Welcome after MSI directory costing, then a modeless progress
dialog before `ExecuteAction` (a modal dialog there would block the install).
Welcome has a checked-by-default Create a desktop shortcut option. This uses
jpackage's own conditional `DesktopFolder` shortcut component, not a shell
script: unchecking it disables that component. An HKCU installer-owned opt-out
registry component records only the unchecked choice; AppSearch restores it
before jpackage's early `RemoveExistingProducts` during silent `/qn` upgrades.
An interactive upgrade may change that choice on Welcome. Removing the MSI
removes only its owned shortcut and preference component, not unrelated desktop
entries. The Start menu shortcut is independent. The interactive completion
screen displays a checked Launch MegaMek Launcher box on the interactive finish
dialog; only the finish button publishes the launcher custom action. Both
options use one native WiX CheckBox control each, with the label inside its
clickable area. The cropped artwork occupies only the left side of Welcome and
Finish; the right-side controls sit on the native light dialog background, not
a baked cream panel. Silent `/qn` MSI upgrades cannot run that UI action.
The installed EXE and MSI icon share an ICO derivative of MegaMek.png; the
left-strip wizard BMP and progress banner derive from bundled first-launch art.
The MSI artifact keeps the Gradle version in its filename, while Windows
Installer ProductVersion uses the numeric portion (for example, `0.1.0` for
`0.1.0-SNAPSHOT`), as required by jpackage/MSI.
Regenerate the BMPs with Pillow using
`python src/distribution/windows/msi/generate_art.py`; the source artwork stays
in `artwork/`. Static MSI table inspection cannot certify an interactive
installation; this build never runs `msiexec`. These icons
do not change Windows Explorer's `.msi` file association. The internal
`mm-launcher` CLI name remains compatible.
The macOS app bundle includes a MegaMek.png-derived ICNS in `Contents/Resources`.

## Manual stable launcher releases

`Publish launcher release` is a separate, manual-only entry point in
[launcher-release.yml](../.github/workflows/launcher-release.yml). Dispatch it from official
`MegaMek/mm-launcher` **main** with no version input. The workflow reads the version committed
at dispatch and increments only its patch number on a run-specific
`release-candidates/<version>/<run-id>` branch. For example, committed `0.14.5` produces
a `0.14.6` candidate while `main` remains `0.14.5` throughout builds and publication.
Versions are canonical `major.minor.patch` within Windows Installer's numeric limits; a patch
at 65535 fails rather than silently rolling into a different minor version.
It must be unused (with or without a `v` tag prefix) and newer than every published stable
launcher version. Existing tags and releases, including drafts left by failed runs, are not reused.
The workflow serializes publication attempts without cancelling an in-flight attempt.

Preparation first validates the candidate using read-only access. A dedicated GitHub App then
creates a Git tree differing only in the version literal in `build.gradle.kts`, verifies the
tree and single parent, and creates the candidate branch without touching `main`.
At preparation, `main` must still match the dispatched
commit; an intervening change or stale queued dispatch fails instead of overwriting newer work.
The version file's formatting and other content are preserved.

The workflow then calls the existing native-installer workflow, including all four platforms'
required headless tests and offline
release tooling tests. Every checkout and build identifier uses the exact candidate
commit, not the older workflow-triggering SHA. Publication requires that entire called workflow
to succeed. Only the publication job receives workflow-token `contents: write`; ordinary CI
and preparation keep that token read-only. The App's short-lived Contents-write token is
restricted to this repository, used only for candidate creation, revoked at the end of preparation,
and never passed to installer/test jobs or publication.
Artifacts come from the current workflow run, not a previous build or independently selected
latest artifact. A read-only release-assets job validates the complete unsigned build outputs
and optionally signs the Windows MSI. It uploads the final ten-file installer/checksum set as
one immutable artifact. Publication and finalization both download that exact artifact ID,
not the unsigned build outputs or a name selected from the newest run. Artifact IDs are captured
as job outputs, so finalization-only reruns retain the original signed bytes even when the
workflow attempt number changes. No signing credential is passed to native builds.

### Avoiding redundant CI after publication

Candidate commits retain a `Launcher-Release-Run` trailer identifying their original
workflow run. After finalization pushes that commit to main, the ordinary native workflow
first runs its offline tooling tests and a read-only `launcher_release.py ci-plan` check.
Only an official main push without a pinned source input can reuse release verification.
PRs, forks, manual native builds and reusable candidate builds always run all four platforms.

The check does not trust a bot identity, commit title or trailer by itself. Before emitting
`build_installers=false` and `run_desktop_tests=false`, it verifies the pushed checkout,
published stable tag SHA,
exact version-only tree/single base parent, run-specific candidate branch, and official
manual release workflow at that same base. The original prepare, tooling, four native
build/test jobs and publication job must all have succeeded. It checks the latest execution
of each job, including successful jobs retained when only failed finalization is rerun.
Finalization/the overall run may still be in progress when the push arrives; publication
success is the relevant completed prerequisite.

After publication, a read-only step checks the public release against the original local
installer/checksum bytes and writes `launcher-release-provenance.json`. The same publish
job retains it using immutable v4 Actions artifacts, named
`launcher-release-provenance-<run-id>-<publish-attempt>`, without overwrite, for 14 days.
Recording/upload failures are visible but nonblocking: this evidence enables a CI
optimization, not publication safety, and its absence must cause normal installer builds
and desktop tests.
For signed releases, the record also retains Windows signature verification tied to the final
MSI digest, candidate, and approved certificate. CI reuse verifies that binding in addition to
the ordinary asset IDs, sizes and digests. Existing unsigned schema-1 provenance remains valid.

The gate uses the successful publication job's attempt, not the overall run's latest
attempt, so finalization-only reruns can retain valid proof. It checks the artifact's
original run/base identity and archive SHA-256, then reads only one JSON file (16 KiB
uncompressed, 64 KiB archive maximum) without extraction. The record must bind the
repository, version, candidate SHA, run/attempt, release ID and all ten original asset
IDs, sizes and SHA-256 digests. Current public metadata is checked against that independent
record, including exact official URLs and updater-compatible installer sizes. Deleting and
re-uploading assets cannot satisfy the gate merely by keeping their names and URLs.
No installer is downloaded again. Missing/expired provenance, missing publication or
unconfirmed successful jobs means normal installer builds and desktop tests, with an
explicit explanation.
Malformed/mismatched provenance or API failures fail explicitly, never silently skip tests.

The native tooling job and advisory desktop-selection job use repository-scoped `actions: read`
alongside `contents: read` for provenance lookup; the release caller permits that read-only
scope. No App credentials or write permissions are introduced. Both matrices depend on
their respective outputs from the same shared verification command.
The workflow summary links to the original verified release run when duplicate builds are
skipped. The independent advisory workflow skips actual window tests only for verified
release-version-only main pushes, not because the publisher ran native desktop tests.
PRs, ordinary main changes, manual dispatch and weekly runs keep all four desktop jobs.
Changes to the shared scripts also trigger the advisory workflow. Neither workflow can
publish a release. Verification errors stay visible; they are not silently accepted.

Explicit Actions run names identify **Launcher build checks** and **Desktop UI checks**,
including event and branch. They no longer inherit misleading candidate-preparation
commit titles. Two quick post-publication workflow entries still appear even when both
matrices are skipped; ordinary PRs gain one lightweight desktop-selection job.

The default `test` task excludes `archive` and `native-gui` tags and forces headless mode.
It still compiles all tests and runs backend/material safety checks, safe Windows helper
execution, deterministic UI components/state, and the actual automatic-check worker.
Mixed classes retain their headless-safe methods in this gate. `nativeGuiTest` selects only
display-dependent methods, requires actual execution rather than an all-skipped success, and
runs in the independent [advisory desktop workflow](../.github/workflows/launcher-gui-smoke.yml)
on PRs, ordinary main pushes, manual dispatch, and a weekly schedule across all four
platforms (Linux uses Xvfb). Failures stay visibly red with retained reports; there are no automatic retries,
`continue-on-error`, or release dependencies on that workflow. Its jobs must not become
required branch checks.

Before any release/tag write, [launcher_release.py](../scripts/launcher_release.py) requires exactly
the five expected installer/checksum pairs, nonempty regular files, matching SHA-256 values, and
installers no larger than the updater's 300 MiB limit. It creates a new lightweight `v<version>` tag
at the tested commit, uploads all ten files, and requires GitHub's uploaded state, immutable IDs,
sizes, exact download URLs, and SHA-256 digests to match. A temporary GitHub draft is only an
upload implementation detail: no human draft-review gate is added. After all checks, that same
job publishes a normal stable release and verifies the latest-stable endpoint consumed by the
native updater.

Only then does a separate finalization job obtain a fresh, repository-scoped App token.
It re-verifies the candidate's exact parent, base blob and version-only tree; the public
release's complete assets/checksums; the tested tag SHA; and the latest stable endpoint.
If `main` still matches the captured base, it advances `main` to the tested candidate with
`force: false`. An already-synchronized candidate is acknowledged without a write.
A changed main or denied/ambiguous write fails explicitly as **version synchronization pending**;
the workflow never overwrites parallel work.

While the release is a draft, GitHub can return an `untagged-...` download URL.
Staging accepts either the final versioned URL or the temporary URL derived from
that same draft's official `html_url`, with the exact asset filename. A different
host, repository, temporary release, version, filename, query or fragment is rejected.
After publication, only the final `v<version>` URL is accepted, both in the
publication response and the latest-stable response. The asset IDs, uploaded
state, sizes and SHA-256 digests are still checked at every stage.

No write is retried automatically, no existing asset/tag is replaced, and no automatic rollback
deletes release evidence or reverts a version commit. A failed build or unpublished draft leaves
`main` unchanged; the candidate branch remains available as evidence. Failed installer jobs can
be rerun using the successful preparation outputs and exact SHA; their same-run CI artifacts
are replaceable to prevent upload-name collisions. This never permits overwriting public
release assets. Rerunning all jobs does not reuse an existing candidate branch.
A failed publisher can leave a tag, draft, or public release: inspect remote state before
any retry, and never blindly rerun publication after an ambiguous result.
Publication and a branch update are separate GitHub operations, not an atomic transaction.
After successful publication with failed finalization, rerun **only finalization**, retaining
the same candidate/base outputs and installer artifacts; do not rebuild, republish, or
dispatch another patch. If main has advanced independently, the guard keeps rejecting the
candidate: an authorized owner must review manual version synchronization that preserves
newer source changes, rather than forcing the old candidate onto main.
The workflow never installs packages, notarizes, assembles a game suite, or updates an
installed launcher on a runner. A real self-update test requires separate explicit authorization
to publish and to upgrade an older Windows MSI installation.

### Optional Windows signing

This is preparation for SignPath Foundation, not confirmation of approval or a signed release.
The initial scope is the **Windows launcher MSI only**, not game archives, Linux/macOS installers
or notarization. Ordinary PR, main-push and manual native CI builds remain unsigned and do not
read SignPath secrets. Only the official manually dispatched release workflow can submit signing.

Leave `LAUNCHER_SIGNING_ENABLED` unset or exactly `false` until the account is approved and
configured. The disabled path preserves all five installer/checksum pairs byte-for-byte and
needs no SignPath account or token. Any other nonempty value except exactly `true` is an error,
not permission to fall back to unsigned publication.

After approval, configure these repository Actions settings:

| Setting | Kind | Value |
| --- | --- | --- |
| `LAUNCHER_SIGNING_ENABLED` | Variable | `true` only when explicitly activating production signing |
| `SIGNPATH_ORGANIZATION_ID` | Variable | Organization UUID provided by SignPath |
| `SIGNPATH_PROJECT_SLUG` | Variable | Approved launcher project slug |
| `SIGNPATH_SIGNING_POLICY_SLUG` | Variable | Approved production signing policy slug |
| `SIGNPATH_ARTIFACT_CONFIGURATION_SLUG` | Variable | Approved Windows MSI artifact configuration slug |
| `SIGNPATH_CERTIFICATE_SHA256` | Variable | 64 hexadecimal digits: SHA-256 of the approved signing certificate's DER bytes, **not** its usual SHA-1 thumbprint or an MSI checksum |
| `SIGNPATH_API_TOKEN` | Secret | Submitter token scoped to the approved project/policy, not an approver/admin token |

Slugs must contain 1-100 ASCII letters/digits, underscores, dots or hyphens and start with a
letter/digit. Preparation validates all required values and captures public configuration before
creating a candidate. The token is required only for enabled signing and is never exported in
job outputs. Configuration changes during a run do not change its captured policy or certificate
pin. Coordinate certificate rotation with the configured policy and pin before the next release;
an unexpected certificate blocks publication.

The SignPath project must trust only this official release workflow and its GitHub-hosted builds.
Review the origin rules with SignPath: the workflow is dispatched from main, but all installer
checkouts use the separately verified version-only candidate, not the triggering `github.sha`.
The submitted v4 Actions artifact is a ZIP containing exactly the original tested Windows MSI;
configure that ZIP/MSI shape and permitted product/version metadata, not arbitrary executable
signing. Configure trusted signing approvers and the required manual authorization according to
the Foundation policy. This adds no separate GitHub draft-review stage or requirement for a
different person to approve. Do not configure an unapproved/test certificate for public releases.

After all native tests pass, the signing action submits that immutable unsigned artifact ID,
waits up to one hour for completion, and downloads the result. A Windows runner requires a
trusted `Valid` Authenticode signature, a timestamp, and the captured certificate SHA-256.
Both original and signed MSI databases are opened **read-only** and must retain
`MegaMek Launcher`, the exact candidate ProductVersion, and the permanent upgrade UUID.
The returned directory must contain exactly one correctly named MSI within 300 MiB.
No MSI is installed or executed.

A bounded verification record binds the original digest, final signed digest/size, source
candidate and certificate. The final Windows checksum is regenerated **after signing**;
Linux/macOS pairs remain unchanged. Publisher, provenance recorder and finalizer all validate
the signed-byte binding and use the same immutable final artifacts. Signed release notes
identify only the Windows MSI as signed and give SignPath/Foundation credit; disabled release
notes continue to say installers are unsigned.

Missing configuration, rejected/pending/timed-out signing, invalid trust/timestamp, a wrong
certificate, changed MSI identity, malformed output, stale checksums or mismatched verification
stop publication. There is no unsigned fallback and no nonblocking signing step.
A failed signing run leaves the candidate as evidence; inspect the service request before
rerunning to avoid duplicating an approval request. Published assets/tags are never overwritten.
This code does not install the SignPath GitHub App, create/configure service accounts, set secrets,
enable signing or publish a release. Those remain explicit owner actions after the response.

Offline release/signing contracts, including mocked PowerShell signature/identity guards:

```powershell
python -B -m unittest discover -s scripts -p 'test_launcher_*.py'
```

These tests do not prove certificate issuance, live SignPath origin acceptance, actual MSI
Authenticode trust, signing approval, or Windows installation. Those need the approved account
and a separately authorized end-to-end release/upgrade test.
See the [official GitHub integration](https://docs.signpath.io/trusted-build-systems/github).

### Installed launcher self-update

Startup and Settings use the same update check. A single themed **Update now**
consent covers the exact offered version's download, checksum and native package identity
verification, launcher shutdown, and installation. Download/verification use the
shared cancellable operation progress and persistent diagnostics; handoff is
non-cancellable. No second launcher confirmation is shown; native OS authorization and
macOS's Installer wizard remain visible. Portable and development entry points are excluded.

#### Windows

The helper waits for the original launcher process to exit, checks the staged
checksum again, and launches Windows Installer with `/passive /norestart` and a
verbose diagnostic log. It imports its own Windows PowerShell utility module
explicitly, avoiding incompatible PowerShell 7 module search paths inherited by
Java processes. Progress is visible without a setup wizard. Installer
exit 0 is successful; 3010 is successful with a restart-required notice, never
a forced restart. Failed or cancelled installations are not reported as success.
Only a successful installation reopens the fixed installed launcher entry point,
after the completed report has been written atomically.

The helper's Windows PowerShell completion writer passes `[NullString]::Value`
to `File.Replace`; passing `$null` binds an empty backup path and leaves the
report pending. Both helper output and installer logs are retained beside the
registry. New pending reports bind the target version and helper PID/start time.
An active helper keeps its report and produces an informational finishing state.
An exited helper with an incomplete report requires Windows to confirm the exact
target's running and installed version before acknowledgement; the original
report is archived and reconciliation is recorded in local operation logs without
an interrupting popup. If Windows instead confirms the
old running version is still installed, archive the incomplete failed attempt
and allow a later check to retry, without claiming an upgrade. Reservations
without a recorded helper identity remain for review. New completed reports
retain the exact target even when cleanup warnings or reboot notices apply.
An exact, Windows-confirmed clean completion is recorded as a successful launcher-update
operation in local diagnostics, without a result popup or a notice attached to a newer update
confirmation or release-lookup error. Completed reports distinguish clean success from
successful installation with an actionable warning; reboot and cleanup warnings stay visible.

Legacy plain `pending` reports contain no target. Their helper is identified
by the encoded script's exact report path. Only when that helper is absent and
Windows confirms the current installation may the report be archived and checks
resume. The unknown previous result is recorded in local operation diagnostics, not
shown as a popup or claimed successful upgrade. These safely reconciled details are
also omitted from update confirmations and release-lookup errors; genuine failures,
reboot-required success, cleanup warnings and unresolved pending states keep their
existing presentation. Failure to save completion or recovery diagnostics is surfaced explicitly.
Changed, oversized, linked, malformed, or unverifiable reports remain
for review.

Material Windows regressions execute the real helper script with harmless
installer/launcher substitutes; they never run `msiexec` or install software.
They cover parent shutdown, real Windows PowerShell report replacement,
installer failure/cancellation, reboot-required success, checksum drift, cleanup
warnings, report-write errors, and relaunch ordering. GUI tests exercise the
themed consent, and download tests cover byte progress and cancellation.

Run the self-update regressions on Windows; only the second command needs a display:

```powershell
.\gradlew.bat test --tests "*WindowsMsi*Test" --no-daemon --console=plain
.\gradlew.bat nativeGuiTest --tests "*LauncherUpdateDialogTest" --no-daemon --console=plain
```

#### macOS and Linux

The shared updater selects the exact published native asset, performs one streaming
size/SHA-256 verification, and stages only the owned installer. All five native installers
must fit the 300 MiB self-update limit before publication.
Existing native package inspection also checks the updater's exact entry point,
package version/release and architecture; macOS's receipt, Distribution and app
identities must agree, and its Distribution and app versions must agree.
The jpackage receipt version is `0`, not the app version. It reuses the existing package
expansion/listing, without a second package build or full-payload scan.

macOS qualifies only `/Applications/MegaMek Launcher.app/Contents/MacOS/MegaMek Launcher`
with matching `org.megamek.launcher` receipt and app identity. JVM architecture selects
Intel or Apple Silicon. The package's Distribution must declare exactly that application
identity at the mapped app version (launcher major plus one). The installed receipt
proves package identity; the app bundle proves the installed version. Download quarantine
is set, never removed. The helper uses `open -W -n -b com.apple.installer`, waits for the native
wizard to close, and confirms the receipt and app version before reopening the app.
Opening Installer alone is not success. Cancellation, Gatekeeper refusal, or an unchanged
version is a failed attempt; no security or permission controls are bypassed.

Linux qualifies the package-owned `/opt/megamek-launcher/bin/MegaMek Launcher` on x64.
An installed Debian package selects `.deb`; an installed RPM selects `.rpm`.
Debian uses the numeric launcher version without a revision suffix; RPM uses release `1`.
Package name, version/release and architecture must match both the running installation
and the downloaded package. Missing, mismatched or ambiguous ownership fails explicitly.
PolicyKit (`pkexec`, with a desktop authorization agent) authorizes a bounded installer
script. It copies the download into a private root-owned directory, checks its SHA-256,
then uses `apt-get -y install` or `dnf -y upgrade`. It never enables downgrades,
disables trust checks, or feeds a mutable user-owned package directly to a privileged
package manager. Other Linux package-manager setups remain manual.

The helper waits for launcher shutdown, checks the installed target, and atomically
records completion before reopening. Failure or denied authorization does not reopen.
Owned staging cleanup never recurses; unrelated or changed files are preserved and
cleanup warnings remain visible, including when reopening also fails. Interrupted
package inspections terminate and reap their process and finish output collectors before
staging cleanup. Native reports use `launcher-update-result.txt`,
`launcher-update-helper.log` and `launcher-update-installer.log` beside the registry.
PID/start-time tracking, exact-version reconciliation, quiet clean success and explicit
failure/pending/warning handling use the same report lifecycle as Windows.
Registry, settings, logs and game installations are outside package ownership.

Existing macOS/Linux installations need one manual upgrade to a version with this support.
These installers remain unsigned and macOS packages remain unnotarized; normal OS trust
policy may require user action or refuse installation.

```powershell
.\gradlew.bat test --tests "*NativeLauncher*Test" --tests "*WindowsMsi*Test" --tests "*LauncherServicesTest" --console=plain
```

Native helper tests use harmless substitutes; Linux private-copy tests run without
elevation and never invoke a real package manager. A full native installation/reopen
smoke test requires a macOS/Linux host and separate explicit installation authorization.

### Release bot setup

An authorized administrator must complete this one-time setup before the automatic workflow
can commit a version:

1. Create a dedicated GitHub App for launcher releases. Grant **Repository permissions:
   Contents: Read and write**; Metadata read access is implicit. Webhooks and user OAuth
   authorization are not needed. Install it on the MegaMek organization with **Only select
   repositories: mm-launcher**. Organization approval may be required.
2. In the repository's **Settings > Rules > Rulesets > Protect Main Branch**, add that App
   to the bypass list with **Always allow**. A pull-request-only bypass cannot perform this
   direct version commit. Keep code-owner review and the other protections for everyone else.
   If additional active rulesets restrict main writes, the App must be explicitly authorized
   there too; the workflow must not weaken or disable protections.
3. In **Settings > Secrets and variables > Actions > Variables**, add
   `LAUNCHER_RELEASE_APP_ID` with the App's numeric **App ID**, not its client ID.
4. Generate an App private key and store the entire PEM content as the repository Actions
   secret `LAUNCHER_RELEASE_APP_PRIVATE_KEY`. Never commit it or paste it into issues,
   pull requests, logs, or chat.

The bypass grants the App real repository write authority, not a server-enforced restriction
to one file. The workflow narrows its installation token to this repository and verifies that
the generated commit changes only the version file. Protect the App key and workflow changes.
GitHub App-authored main pushes trigger ordinary read-only CI. Proven release-finalization
pushes skip duplicate installer builds and post-release desktop tests through the guard
above; advisory desktop checks remain independent. These push runs cannot publish and
are never substituted for the release run's required installer/test matrix.
Missing configuration, denied bypass, or GitHub write failures fail explicitly; there is
no personal-token fallback.

Focused local checks (no native installer builds or network publication):

```powershell
python -B -m unittest discover -s scripts -p test_launcher_release.py
.\gradlew.bat test --tests org.megamek.launcher.distribution.WorkflowStructureTest --tests org.megamek.launcher.update.WindowsMsiUpdateTest --no-daemon
```

## Scope

MegaMek Launcher produces one versioned all-platform `tar.gz`. The user extracts the archive and starts
the entry point for Windows, macOS, or Linux from one fixed root. It is not an installer, does not
write startup integration, does not elevate, and does not contain or install a Java runtime. This
matches the current MegaMek/MekHQ single-archive distribution model; it does not infer any broader
platform or package policy from upstream assets.

The Java application JAR and resolved dependency JARs occur once. The ordinary Gradle
`application`, `run`, and `installDist` behavior continues to use `org.megamek.launcher.Main` for
developer CLI use. Desktop artifacts instead enter through `DesktopLauncher`, which opens the
existing GUI by default. The application's generic `distZip` and `distTar` tasks remain
intentionally disabled; the dedicated archive task owns the portable desktop distribution.

## Declared artifact and fixed root

The two declared outputs include the dynamic project version:

```text
MegaMek-Launcher-<version>.tar.gz
MegaMek-Launcher-<version>.tar.gz.sha256
```

The archive has one top-level `MegaMek Launcher/` root:

```text
MegaMek Launcher/
├── MegaMek Launcher.exe
├── MegaMek Launcher.app/
│   └── Contents/
│       ├── Info.plist
│       ├── MacOS/MegaMek Launcher
│       └── app/lib/*.jar
├── mm-launcher
├── README.txt
├── THIRD-PARTY-NOTICES.txt
└── third-party-licenses/
```

`MegaMek Launcher.app/Contents/app/lib/` is the one shared application/dependency payload. Owning the
payload inside the app bundle keeps the macOS app self-contained. The sibling Windows executable
and Linux script resolve that exact same directory. They use no cross-root symbolic links or hard
links, and the app does not depend on sibling mutable files. Windows and Linux users must keep the
extracted tree intact rather than moving an entry point away from the app folder.

Windows uses Launch4j's classic GUI header and an `asInvoker` application manifest. Its small
embedded Java bridge resolves the app-owned payload relative to the executable directory; it is
not a runtime. The macOS app is architecture-neutral Java/shell content with a normal `APPL`
bundle, `Info.plist`, and `CFBundleExecutable`. Linux's root script and the macOS
`Contents/MacOS` bootstrap are LF files with executable archive mode `0755`. Other regular files
are mode `0644`, directories are `0755`, and the archive contains no links.

Archive tasks disable source-file timestamps and request reproducible file ordering. The Java and
script content is expected to reproduce from identical inputs. No claim of bit-identical native
EXE output from independent rebuilds is made. The checksum task hashes only the one declared
archive path and writes a sha256sum-compatible exact-name line; it does not glob the distributions
directory.

## External Java and arguments

All entry points require Java 21 or newer and never download or install it.

- Launch4j searches compatible external Java and presents explicit missing/old-runtime messages.
- Linux honors an executable `$JAVA_HOME/bin/java`, otherwise `java` on `PATH`.
- macOS also asks `/usr/libexec/java_home` for Java 21+ before its `PATH` fallback so Finder
  launches do not depend on an interactive-shell environment.

The POSIX launchers quote the shared classpath and each path, forward arguments with `"$@"`, and
use neither `eval` nor constructed shell commands. The desktop entry point accepts only the
existing optional `--registry <absolute-path>` GUI override. Diagnostics are:

```text
MegaMek Launcher --version [--report <new-absolute-file>]
MegaMek Launcher --startup-check [--registry <absolute-path>] [--report <new-absolute-file>]
```

Diagnostics do not open Swing, access the network, or create registry state. `--report` exists so
automation can read a result from the Windows GUI-subsystem EXE; it requires an explicit absolute
new file and will not overwrite an existing file. Diagnostic output includes only version/build,
Java feature, packaged-code status, the code-source file name, and whether a registry override was
validated—never an absolute machine path, environment dump, or secret.

Java selected here starts MegaMek Launcher itself. It does not alter the independent per-game Java
selection, persistence, update/apply gates, recovery, or other launcher security behavior.

## Program files, user data, and integration

Everything in the extracted fixed root is program material and is treated as read-only by the
desktop bootstrap. In particular, the macOS launcher never stores mutable configuration inside
the app bundle. Existing launcher state remains outside the program root:

- Windows: `%LOCALAPPDATA%\MegaMek\launcher-registry.json`
- macOS: `~/Library/Application Support/MegaMek/launcher-registry.json`
- Linux: `$XDG_STATE_HOME/MegaMek/launcher-registry.json`, or
  `~/.megamek/launcher-registry.json` when `XDG_STATE_HOME` is unset

Linux desktop-file integration is not installed automatically. A future documented desktop-file
step may be offered as an explicit user action, not startup behavior.

## Verification and current qualification

`buildArchive` selects the canonical archive and checksum chain. `verifyArchive` checks the one
fixed root, all three entry points, exact application/runtime JAR set with no duplicate payload,
OS metadata and modes, path/case safety, absence of bundled JRE/JDK and source/state/credential
paths, and exact SHA-256 file. It extracts into a path containing spaces, metacharacters, and
non-ASCII text, starts from an unrelated working directory, passes an isolated absolute registry
path, and requires the report to prove that code came from the extracted application JAR. On a
matching host it natively starts that host's entry point. POSIX host tests also use missing and
Java 17 fixtures without changing system Java; structural tests check both POSIX scripts on every
host and run `sh -n` when `sh` is available.

`verifyProvidedArchive` is a separate, read-only mode requiring explicit `providedArchive` and
`providedChecksum` Gradle properties. It validates the dynamic expected names, existence,
structure, and integrity of those exact files. Its task graph includes test compilation and the
configuration guard but no staging, Launch4j, archive, or checksum producer, so a missing or wrong
checksum fails instead of being regenerated.

Archive verification is developer-only; the GitHub Actions workflow no longer
builds or verifies archives. It builds native installers on the following runners
and inspects checksums, metadata and runtime payload without installing anything:

| Runner | Tested architecture intent |
| --- | --- |
| `windows-2025` | Windows x64 |
| `ubuntu-24.04` with Xvfb | Linux x64 |
| `macos-15-intel` | macOS Intel |
| `macos-15` | macOS Apple Silicon |

Runner labels describe build coverage, not a minimum supported OS promise. The native-installer workflow does not create a Release, write
repository contents, use secrets, sign, notarize, or publish anything. The hosted CI run has
built, inspected, and uploaded all five installers on Windows, Linux, macOS Intel, and macOS
Apple Silicon; source tests also pass on all four runners. This does not certify installation,
upgrades, signing, or notarization on those hosts.
Windows ARM and Linux ARM are not qualified by this matrix.

## Licensing and signing limitations

Dependency JARs stay unmodified and retain embedded `META-INF` legal material. Packaging also
extracts available dependency `LICENSE`, `NOTICE`, and `DEPENDENCIES` files into the one common
`third-party-licenses/` location. This is not a claim about MegaMek Launcher's own source license: the
repository currently has no tracked root `LICENSE` or `NOTICE`, so a public licensing decision is
a future release prerequisite. No project copyright, trademark, icon, signing identity, or
notarization identity is invented by the packaging.

The current archive is an unsigned prototype and the app is not notarized. Users must not be
instructed to disable or bypass Gatekeeper, SmartScreen, or another security control.
Activating Windows signing, notarization and publishing releases remain explicit owner actions.
