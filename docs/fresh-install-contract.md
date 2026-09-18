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
ambiguous, non-`tar.gz`, zero/oversized, or digest-less selected assets are rejected. Prerelease is
reported but never interpreted as a version ordering signal. GitHub-generated source archives are
not assets and do not match the product-specific asset names.

The initial download must be an exact HTTPS `github.com/<official-repository>/releases/download/`
asset URL. At most five redirects are followed, only to a small fixed set of HTTPS GitHub asset CDN
hosts, with no userinfo, custom port, fragment, cookies, or credentials. Signed redirect URLs are
never printed. Connection, request/read, and two-hour overall limits apply. Downloads stream to an
operation-owned file, are capped at 2 GiB, report typed byte progress coalesced by the GUI, and must
exactly match both metadata size and SHA-256 before extraction. Streams close on all error and
interruption paths.
API HTTP and rate-limit failures are explicit. HTTPS plus GitHub's same-source digest is useful
integrity checking, not an independent signature.

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
canonical, and writable. The normal GUI planner may create at most two explicitly quoted real
parents beneath an existing writable ancestor after consent, covering the launcher state parent
and its `installations` directory on a new machine. The destination must be wholly
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

Only after final static validation does `RegistryStore.register` run. Existing default selection
is preserved by registration; the first record is the default for an empty registry. The normal
GUI setup then revalidates and stores the exact external Java from its quote, persists the exact
planned Milestone or Development channel with the quoted Settings check default, rechecks that no
unrelated registry/default drift occurred, and explicitly selects the new Main. The primary
first-run route defaults to Milestone; the split menu's Development route is explicit and cannot
silently alter the primary default. Normal and advanced GUI installs initially use true, but
preserve a user's later false default for each new copy. The normal quote displays that choice and
re-reads the exact settings configuration before transfer or parent creation; change, corruption,
or an unknown result requires a fresh quote. Existing per-copy settings are never rewritten.
Legacy/CLI behavior is unchanged and false remains the omitted-option default. Every record remains
`updateEligible=false`; no Java is bundled and no game is automatically launched.

Before publication, policy-version-1 hashes are made only from the pristine verified extracted
package. After registration generates its UUID, a receipt bound to that UUID, canonical root,
registration time, fixed repository/tag/asset identity, size, GitHub SHA-256, protected-path
contract, complete managed manifest, and excluded-file inventory is created beside the registry
in `<registry-name>.metadata/<uuid>.json`. Archives are not retained.

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
**PREVIEW UNAVAILABLE**, never **NOT REGISTERED**. Removing a registry entry leaves its receipt;
the stale UUID/root/time binding cannot authorize a later registration.

The GUI writes a terminal sanitized operation log outside both the destination and receipt metadata
in `<registry-name>.launcher-logs/`. A corrupt/unreadable registry or unsafe overlap prevents log
directory creation and produces a separate logging warning without hiding the original install
outcome. Standalone CLI arguments, output/exit behavior, package model, and lack of cache/resume
semantics are unchanged.
