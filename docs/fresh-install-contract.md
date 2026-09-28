# Official release and fresh-install contract

## Release metadata and transport

`releases` accepts only `megamek`, `mekhq`, or `lab`, mapped to the fixed public repositories
`MegaMek/megamek`, `MegaMek/mekhq`, and `MegaMek/megameklab`. The API origin is fixed to
`https://api.github.com`; there is no token, arbitrary URL, or custom API option. Pages are
explicitly bounded to 1–1000 and 1–50 entries. A full page says that another page may exist rather
than claiming to list everything.

Install requires an exact syntactically bounded tag and re-fetches `/releases/tags/{tag}`. Unknown
JSON fields are tolerated for API evolution, while duplicate keys, trailing JSON, malformed
required field types, duplicate assets, and metadata over 8 MiB fail closed. Drafts and missing,
ambiguous, non-`tar.gz`, zero/oversized, or malformed-digest selected assets are rejected.
Digest-less assets from this exact official boundary remain eligible. Prerelease is
reported but never interpreted as a version ordering signal. GitHub-generated source archives are
not assets and do not match the product-specific asset names.

The initial download must be an exact HTTPS `github.com/<official-repository>/releases/download/`
asset URL. At most five redirects are followed, only to a small fixed set of HTTPS GitHub asset CDN
hosts, with no userinfo, custom port, fragment, cookies, or credentials. Signed redirect URLs are
never printed. Connection, request/read, and two-hour overall limits apply. Downloads stream to an
operation-owned file, are capped at 2 GiB, report typed byte progress coalesced by the GUI, and must
exactly match metadata size before extraction. A valid published SHA-256 is enforced when present;
otherwise SHA-256 is computed while streaming that one exact body and retained as the attempt and
installed copy's authoritative local package identity. Streams close on all error and
interruption paths.
API HTTP and rate-limit failures are explicit. HTTPS plus GitHub's same-source digest is useful
integrity checking, not an independent signature.

Imported-copy adoption reuses these exact repository, release metadata, asset-name, size,
SHA-256, transport, and safe-extraction boundaries, but it is not a fresh install: it stages the
package outside the registered root only for comparison and never publishes package files into
that root. It performs one package download per prepared attempt. Historical archives without a
published digest use the same computed local identity; archives that otherwise fail this contract
remain unsupported and launch-only.

The GUI's single **Fetch releases** action reads both bounded website
`current_releases.yml` pointers once and calls one bounded history page for only the selected
product repository. It combines the selected channel's known current target with releases whose
historical membership is unknown, excludes a distinct current target known only to the other
channel, and treats a shared stable/dev identity as current in either selection. Page one contains
the selected target exactly once; an exact lookup is added only when needed to obtain its
canonical eligible metadata. Identity is deduplicated by fixed repository/tag, and titles,
versions, ordering, and `prerelease` never infer a channel. Most historical rows currently remain
unknown and therefore appear for either channel. The selected channel becomes the created
installation's fixed update track and does not classify an unknown historical release. No picker
metadata action downloads package bytes.

Empty Home has a separate frame-scoped current snapshot contract. One background attempt resolves
all six fixed quick-install choices while both install segments are disabled and **Use existing
installation** remains enabled. Failure leaves installation disabled until an explicit,
deduplicated Retry. The first successful immutable snapshot is reused for the frame lifetime
across re-render, cancel/close, Change location, and repeated planning; those paths do not reread
`current_releases.yml`.

## Archive and installation boundary

Apache Commons Compress 1.28.0 reads the official gzip/tar format; no shell command or downloaded
code runs. Limits are 100,000 entries, 512 characters per name, 2 GiB per file, and 6 GiB total
expanded bytes. Truncation/checksum errors fail. Only ordinary files and directories beneath one
top-level distribution directory are accepted. A conventional leading `./` is normalized.

Absolute, drive, UNC, traversal, backslash, ADS/colon, Windows device, trailing-dot/space,
case/Unicode aliases, duplicates, and file/parent collisions are rejected, as are links, hardlinks,
devices, FIFOs, sparse/unknown entries, multiple roots, and reserved `.mm-launcher` state. The
single wrapper directory is removed when publishing, so the selected destination itself is the
application root. `InstallationInspector` then verifies the supported static layout and that the
selected application is present. It does not classload or execute artifacts.

The shared legacy/CLI installer requires the destination parent to already exist, be real,
canonical, and writable. The normal GUI planner may create at most four individually quoted real
parents beneath an existing writable ancestor after consent. With the ordinary GUI registry, its
managed payload/support-data root is `%LOCALAPPDATA%\MegaMek` on Windows,
`~/Library/Application Support/MegaMek` on macOS, and
`${XDG_DATA_HOME:-~/.local/share}/MegaMek` on Linux; a relative `XDG_DATA_HOME` is ignored.
Explicit `gui --registry` overrides and custom `LauncherServices` registries retain
`<registry parent>/installations` for disposable isolation. This placement does not move existing
records, install a payload directly as the shared `MegaMek` support root, or place payloads inside
`MegaMek Launcher.app`. Registry/receipt files remain outside each product/channel child. Leaf names
include the selected product and channel, such as
`MekHQ Milestone`, `MegaMek Development`, or `MegaMekLab Milestone`, so different quick choices
do not collide or misdescribe their contents.
After the native parent chooser, a styled folder-name dialog preselects the friendly
`Program Channel (Version)` name and keeps invalid input in place with an inline error. Cancel or
Escape changes no state. There is no separate installation-name prompt: the same friendly value
is registered automatically. The destination must be wholly
nonexistent—even an empty directory is refused. Registry/destination/registered-root
overlaps, duplicate names, and linked or special ancestors fail preflight. Extraction occurs in a
random create-new sibling staging directory. Final move has no replace option and refuses a target
that appears concurrently.

Metadata, transfer, verification, extraction, static inspection, package inventory, and other
pre-publication planning are cancellable through typed checkpoints. A request closes only the
operation's active response and interrupts only its registered worker; archive and inventory loops
observe the request without executing downloaded content. The atomic cutoff occurs immediately
before `Files.move` publishes the extracted destination. If cancellation wins, the destination,
registry record, receipt, and channel setting are absent and only the random staging tree is
removed. If publication wins, cancellation is denied and final validation, registration, receipt,
channel finalization, and exact temporary cleanup run without interruption. A later failure keeps
the existing **NOT REGISTERED**, **REGISTERED**, and channel-salvage wording; it is never mislabeled
as cancellation and publication is not rolled back.

Only after final static validation does `RegistryStore.register` run. Existing legacy default
selection is preserved by registration; the first record supplies that compatibility field for an
empty registry. Independently, registration initializes only still-unset per-application
preferences for products actually in the new static record and never overwrites an explicit
preference. The normal GUI setup does not inspect Java. It initializes the exact
planned Milestone or Development channel once with check-on-open enabled, rechecks that no
unrelated registry/default drift occurred, and updates the compatibility default only for the
still-empty registry captured by the quote. The primary first-run route defaults to
`(MEKHQ, MILESTONE)`; the five popup routes bind their displayed fixed repository/channel pairs and
cannot silently alter the primary default or an existing installation's track. The same installer
requires exact product matching for
normal quotes: MekHQ is the three-program suite, while MegaMek and MegaMekLab are standalone.
Repository titles and extra/missing products cannot substitute for that binding. Normal, exact historical, and CLI managed installs always initialize check-on-open to true; there
is no global or new-install default. The normal backend does not read launcher Settings while
planning or installing. The simple
summary keeps the exact planned destination visible but has no passive update-behavior row and no
technical-details toggle or pane. Raw source, asset, digest, Java, and registry bindings
remain validated backend state, and operation logs retain errors. Existing per-copy channels are never rewritten. The CLI requires an explicit channel for each
fresh install and initializes check-on-open to true; its former channel mutation command is
rejected without writing.
Every record remains `updateEligible=false`; no Java is bundled and no game is automatically
launched.

A quick-install plan is explicitly marked as captured-current rather than historical. Its local
quote preparation accepts only the exact validated snapshot option and freshly captures
destination and registry/default state without another network
lookup. Before creating parents or requesting package bytes, installation re-fetches the exact
official repository/tag release metadata and requires release/tag plus asset
name/size/SHA-256/URL identity to match the quote. It does not re-read the channel pointer: pointer
advancement alone cannot retarget the install, while exact asset removal or identity drift fails
before transfer and requires fresh consent. The package body remains a single download.

Current-channel picker results go through the same normal planner, which refreshes the website
pointer and exact tag before displaying consent. A Browse-all selection uses the planner's exact
historical mode: it re-fetches only that repository/tag, applies the same asset/digest/product,
destination and registry checks, and records the chosen channel solely as that
new installation's immutable track. Install repeats the matching current-pointer or exact-tag lookup before any
binary transfer. Product/channel/source-mode changes invalidate stale picker objects rather than
retargeting them. The canonical historical-channel metadata needed to replace the unclassified
workaround is discussed in [the CI update proposal](../ci_update.md).

Normal installation and existing-copy import do not read, resolve, execute, or validate Java.
Missing or corrupt launcher settings therefore cannot invalidate an installation quote or prevent
package publication. Java is resolved only for launch or explicit launch preview. With no saved
default, the exact Java runtime executing MegaMek Launcher is validated and used automatically without
being persisted; an explicit default remains launcher-wide and has no per-copy override.

Before publication, policy-version-1 hashes are made only from the pristine verified extracted
package. After registration generates its UUID, a receipt bound to that UUID, canonical root,
registration time, fixed repository/tag/asset identity, size, GitHub SHA-256, protected-path
contract, complete managed manifest, and excluded-file inventory is created beside the registry
in `<registry-name>.metadata/<uuid>.json`. The fixed-channel sidecar is then initialized only when
that exact receipt and registry binding revalidate. Same-channel transaction retries are
idempotent; another channel is rejected. Archives are not retained.

The first-launch display snapshot is separate from planning. It reads stable/dev once and resolves
the six validated repository/channel choices, deduplicating exact release metadata for identical
repository/tag pairs. It performs no package transfer, Java validation, destination/state write,
or state write. Its two install segments are disabled while loading; direct existing-copy import
remains active. Opening its styled menu when READY performs no request. A failed attempt exposes
an explicit Retry command, while a successful snapshot and its labels remain cached for the frame
session. Selecting an available choice starts a fresh local quote from that exact cached option.
Before binary transfer, the exact release/asset is revalidated without rereading or following the
current channel pointer.

## Failure and recovery

Ordinary pre-publication failures remove only this operation's staging tree. Abrupt process or
machine termination can leave a sibling named `.mm-launcher-install-<uuid>`; it is private staging,
not a supported installation, and this milestone intentionally has no general cleanup command.
Do not launch it. Inspect it manually before removing only that specifically identified directory.

After publication, registration can still lose a race or fail. The valid final installation is
retained and the error explicitly says **NOT REGISTERED** and directs the user to the existing-copy
registration flow. It is never deleted because a user may already have observed it. This is a
bounded two-phase recovery story, not a distributed filesystem/registry transaction. The installer
does not touch existing game copies, migrate saves, update installations, select Java, or launch.

Receipt publication is a later bounded step. If it fails after registration, the valid
installation and registry record are retained and the error says **REGISTERED** and
**PREVIEW UNAVAILABLE**, never **NOT REGISTERED**. **Remove from launcher…** removes its receipt and other launcher-owned sidecars while retaining
every application-root file. The stale UUID/root/time binding cannot authorize a later
registration.

Fixed-channel publication follows receipt publication. If it fails, the valid registered copy is
also retained with explicit incomplete-setup/repair wording and remains launch-only; it is never
reported as a successful managed copy and cannot be assigned a channel later through GUI or CLI.

The GUI writes a terminal sanitized operation log outside both the destination and receipt metadata
in `<registry-name>.launcher-logs/`. A corrupt/unreadable registry or unsafe overlap prevents log
directory creation and produces a separate logging warning without hiding the original install
outcome. Standalone CLI arguments, output/exit behavior, package model, and lack of cache/resume
semantics are unchanged.

The GUI operation window is a compact dark-teal/gold phase/progress surface, not a live technical
log viewer. While cancellation is safe its only bottom action is **Cancel**; after publication
wins, Cancel disappears and the window says that installation is finishing and must remain open.
Accepted pre-publication cancellation disposes progress and returns to the current page with
**Installation cancelled**. Failure remains in progress as one concise sanitized summary with
**Close** and failure-only **View details**; a retained published normal copy alone reveals
**Open Installations**. The generic error dialog is not duplicated.

On normal-install success the authoritative result reports whether the new record actually became
Main under the locked registry outcome. Progress closes immediately and managed Home reloads
without an install-complete modal or launch. Home shows a process-local gold/green notice naming
the installed product and observed version. It says **installed and ready** only when that result
became Main; otherwise it says to find the new copy in Installations while Home continues showing
the existing Main.
