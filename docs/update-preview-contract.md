# Real-package update preview contract

## Scope and authority

`preview-update --registry <json> [--id <uuid>] --tag <exact-tag>` is a diagnostic,
observational point-in-time comparison. It may download, verify,
extract, and statically inspect one official release in launcher metadata staging, then disposes
that workspace. It never writes beneath an installed application root,
changes its timestamps, updates the registry or receipt, locks an installation, converts saves, or
applies a decision. Its result is not authority to apply later. Tags are exact labels, not
semantic-version upgrade/downgrade recommendations.

Registry schema 3 keeps every record at `updateEligible=false`; that legacy guard is not
preview or Apply eligibility. Eligibility comes from a valid immutable local ownership receipt and,
after the first successful update, coherent latest current provenance.
Imported and pre-receipt copies remain launchable/manageable but cannot preview, and provenance is
not backfilled. Download one new official release with this launcher to obtain a receipt.

## Receipt and trust model

Receipts live in `<registry-file>.metadata/<installation-uuid>.json`, outside application roots.
They bind schema and ownership-policy versions, exact installation UUID, canonical root,
`registeredAt`, fixed repository, exact tag, asset name/size/SHA-256, complete official managed
manifest, ordered protected paths, and a complete inventory of official package files excluded by
policy. Archives are deleted after use.

Parsing is stream-bounded and rejects malformed, duplicate, unknown, missing, null, trailing,
aliased, unsafe-path, schema, policy, source, product/package, ownership-allowlist, inventory-count,
and binding failures. Metadata, installation, and staging paths must have only real canonical
ancestors and cannot overlap any registered installation. Receipt creation uses an OS lock,
a uniquely owned staging file, and atomic create-new hard-link publication: an existing or
concurrently appearing final receipt is never replaced, and another writer's staging object is
never removed. The per-receipt lock file may remain as inert serialization state. **Remove from launcher…** removes the exact registration and all sidecars for its ID while leaving
the application root byte-for-byte untouched. A new registration gets another UUID/time.

The receipt trusts launcher-local state derived from HTTPS plus GitHub's same-source digest. This
detects transfer corruption but is not an independent signature. Hostile same-user modification of
both registry and receipt is outside this model; structural and binding errors still fail closed.

## Ownership policy version 1

Archive membership alone never establishes ownership. The allowlist manages root suite launch
binaries named `MegaMek`, `MekHQ`, or `MegaMekLab` with `.jar`, `.exe`, or `.sh`; ordinary files
below `lib/`, `docs/`, and `licenses/`; and ordinary official files below `data/` except protected
descendants.

The stable ordered protected contract is `campaigns`, `saves`, `userdata`, `custom`, `mmconf`,
`logs`, `backups`, and `data/campaigns`, `data/saves`, `data/userdata`, `data/custom`,
`data/logs`, and `data/backups`. These stay protected even when an official package ships a
byte-identical sample campaign, save, or configuration. Root `.l4j.ini`, configuration
`.properties`, defaults, and every unknown root location are excluded unless a future policy
explicitly assesses them. Excluded package paths are reported so the manifest never implies that
the launcher manages the whole archive.

Both manifests use the fixed repository-derived product/package identity and identical
policy/protected contract. Safe extraction and manifest validation reject links, reparse points,
case/Unicode aliases, traversal, unsafe names, path-parent conflicts, excessive counts/sizes, and
unsupported archive types.

## Planning and failures

Only the union of baseline and target managed paths is inventoried; unknown local additions remain
untouched. A missing target is `ADD`; a target match is `KEEP`; an untouched changed baseline is
`REPLACE`; an untouched obsolete baseline is `REMOVE`; modified official files, modified obsolete
files, unknown target-name collisions, and non-regular collisions are `SKIP`. Modified official
data is therefore preserved. Hashing checks identity, size, and modification time again to reject
observable concurrent changes.

Case/Unicode aliases inside one package remain unsafe and fail validation. The legacy manifest
plan command and all sandbox apply/recovery paths also retain strict pair validation: a
capitalization-only difference between releases is ambiguous there and fails closed. Read-only
real-package preview handles that one cross-release condition differently. It identifies the
outermost component whose spelling changed only by capitalization, does not inspect, traverse, or
hash that prefix, and reports every affected baseline and target managed-file spelling as `SKIP`.
Each reason identifies both baseline and target prefix spellings and states that the existing
content is preserved because rename is unsupported. Thus a directory change such as `data/Icons`
to `data/icons` also skips uniquely named added and removed descendants instead of presenting them
as `ADD`/`REMOVE`.

`SKIP` counts are decision-row counts: a capitalization group contributes one row for each
distinct affected managed-file spelling in the two manifests. No affected path is silently
discarded. Same-name file/parent conflicts across releases are structural errors even beneath a
case-changed prefix, and unrelated local case aliases, links, or reparse points still fail closed.
Preview does not normalize or rename an installed file. Case-only rename execution remains deferred. The separate real Apply contract persists affected
data prefixes as sticky `SKIP` overrides and refuses the whole Apply for affected runtime prefixes;
this point-in-time preview still grants no later write authority.

Before network access, preview validates receipt/policy/source binding, canonical root, metadata
separation, and absence of disposable `.mm-launcher` sandbox state. It does not require the local
launch JAR or observed build to remain byte-identical. The target comes from the same fixed
repository using the installer's digest, size, redirect, timeout, extraction, and cleanup rules,
and must contain the expected product. Only operation-created `.preview-<uuid>` staging is removed. A crash can leave that explicitly
named staging directory or a uniquely named receipt staging file; publication itself is
create-new/no-clobber, but directory durability across a power loss is not claimed. No broader
cleanup or transaction is claimed by **preview**.

Normal GUI **Update…** and recommended-update attempts use the same planning/target-validation
engine but deliberately have different ownership: an opaque, process-local, one-use handle retains
that attempt's verified archive and observational extraction through Apply. The initial styled
update consent authorizes download, verification, internal planning, and Apply; there is no second
UI confirmation after planning.
Apply never trusts the extraction merely because preview passed; it refreshes metadata, re-verifies
the retained compressed bytes and stable file identity, safely extracts again, and compares the
result with the pristine captured manifest/inventory/inspection before a fresh local plan. This is
not a persistent cache, resume facility, offline authority, or path token, and separate Preview/CLI
operations cannot share it. Real Apply details are documented in
[real-update-contract.md](real-update-contract.md). For an older exact official asset with no
GitHub digest, the one bounded transfer computes and retains SHA-256 in the prepared handle.
A malformed published digest still fails closed.

GUI preview reports typed metadata/download/verification/extraction/planning phases. Package bytes
use the exact metadata total; archive-entry progress remains indeterminate when no truthful total
exists. Updates are coalesced before Swing rendering. Cancellation closes only the preview-owned
response, checkpoints archive hashing/extraction and local planning, and removes only that random
workspace off the EDT. Standalone preview ends as **Cancelled**, failed, or successful without an
invented overall percentage.

Prepared GUI Update continues from the same one downloaded body directly into backend
revalidation and prepare-install/Apply after internal planning. The existing typed await-consent
boundary does not display or request a second consent. Standalone GUI/CLI preview never exposes
Apply and is not a cache token for another operation. Existing CLI arguments, output, and
one-download compatibility are unchanged. Clean GUI success closes progress and silently reloads
Installations; only a non-null cleanup warning keeps the styled progress surface visible.

Terminal preview errors/progress are persisted as sanitized local structured logs beside the
registry, outside installation and receipt data. Logs remain available after dialog dismissal or
restart through **View logs**; reading/copying is bounded and explicit, with no upload, browser
launch, or arbitrary file input. Logging failure does not change preview read-only semantics or
hide the original preview error.
