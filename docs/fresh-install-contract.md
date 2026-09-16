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
operation-owned file, are capped at 2 GiB, report progress every 64 MiB, and must exactly match both
metadata size and SHA-256 before extraction. Streams close on all error and interruption paths.
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

The destination parent must already exist, be real, canonical, and writable. The destination must
be wholly nonexistent—even an empty directory is refused. Registry/destination/registered-root
overlaps, duplicate names, and linked or special ancestors fail preflight. Extraction occurs in a
random create-new sibling staging directory. Final move has no replace option and refuses a target
that appears concurrently.

Only after final static validation does normal `RegistryStore.register` run. Existing default
selection is preserved; the first record is the default for an empty registry. Every new record
remains `updateEligible=false`, with no bundled Java and no automatic launch.

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
