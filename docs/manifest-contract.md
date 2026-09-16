# Local manifest and sandbox-state contract (schema version 1)

## Official manifests

An official manifest is a UTF-8 JSON object with exactly:

```json
{
  "schemaVersion": 1,
  "product": "MegaMek",
  "packageId": "standalone",
  "releaseId": "0.50.0",
  "protectedPaths": ["saves", "campaigns", "userdata", "custom"],
  "files": [
    {"path": "MegaMek.jar", "sha256": "<64 lowercase hexadecimal characters>"}
  ]
}
```

All fields are required. Unknown fields, nulls, an unsupported schema, malformed hashes, duplicate
paths, case aliases, and file/parent conflicts fail closed. Baseline and target must have identical
`product`, `packageId`, and ordered `protectedPaths`; `releaseId` is an opaque exact identity.
Public plan/apply entry points revalidate both manifests and their pairing.

Paths are `/`-separated, relative Unicode NFC. Empty, `.`, `..`, backslash, drive-qualified,
absolute, control-character, Windows-forbidden-character, Windows console-device-name,
trailing-dot, and trailing-space segments are rejected. Comparison is case-insensitive for portable
alias detection. No publisher file or protected-path ancestor may conflict with the reserved
`.mm-launcher` metadata namespace. A publisher file cannot be at/below a protected root, and a
protected root cannot have a publisher-file parent.

The baseline is publisher inventory, never a hash inventory made from local files. Manifests and
payload directories are trusted local inputs; authentication, signatures, network, and archives
are absent.

## Sandbox state

`.mm-launcher/state.json` has schema 1 and contains:

- random `sandboxId`;
- exact canonical root binding;
- `lastTransactionId` (nullable before the first apply);
- the complete current official manifest;
- sorted override entries of `MODIFIED`, `OBSOLETE_MODIFIED`, or `COLLISION`.

Override entries contain a portable path and zero or more hashes copied only from previously
official manifests. Local hashes are inspected but never persisted as trusted ownership. This
separation preserves a skipped A-era modification/tombstone/collision through B and C without
turning arbitrary content into official content. A local file matching a recorded official
ancestor can be reconciled; a file matching the current target is official and clears its override.
Otherwise it remains `SKIP`.

State rejects unknown/missing fields, wrong schemas, malformed IDs/hashes, duplicate aliases,
unsafe paths, incompatible embedded manifests, or a root-binding mismatch.

## Apply journal

`.mm-launcher/journal.json` is a single schema-1 write-ahead transaction. It embeds validated
previous and next states, transaction ID and phase, an ordered operation list, exact backup paths,
before/after official hashes, and explicitly known-created directories. Transaction files live at
`.mm-launcher/transactions/<id>` on the sandbox volume.

Phases are `PREPARED`, `MUTATING`, `STATE_COMMITTED`, and `CLEANUP`. Operation intent is persisted
before each mutation and completion afterward. Required payloads are stable-size/hash checked and
copied (not hard-linked) to staging before journaled application mutation. Application and state
replacement require atomic same-volume moves; lack of that primitive is an error, never a silent
downgrade.

On restart, state `lastTransactionId` distinguishes commit:

- no matching transaction ID: reverse operations from exact backups, restore previous state, then
  remove only recorded empty directories and transaction artifacts;
- matching transaction ID: verify every resulting official hash/absence, then finish cleanup.

Both paths are idempotent. A conflicting external edit, missing/corrupt required backup, malformed
journal, path alias, or link/reparse point blocks recovery without clobbering. Backups and journal
remain until successful rollback or committed verification.

## Filesystem and concurrency constraints

Initialization accepts only an absent/empty child under the system temp or checkout `build`
location and copies a link-free trusted fixture. It never marks an arbitrary existing installation.
Fixture, payload, sandbox, manifest, state, staging, and backup roots must not overlap where
applicable. Relevant roots, ancestors, path components, metadata, mutation targets, and transaction
trees reject symlinks, junctions, and other reparse/special entries. An OS file lock serializes
updater processes. No shell-script operations, wildcards, recursive installation deletion, or
hard-linked payload staging are used.

These measures are an accidental-use gate and process-crash recovery design, not an adversarial
security boundary. Concurrent application/external edits are unsupported. Full power-loss
durability, hostile races, running-process detection, arbitrary filesystem faults, and production
platform certification remain out of scope.

The separate existing-copy launch registry is specified in `existing-copy-contract.md`. A root
containing this sandbox metadata is rejected by launch-only registration; registry membership
never grants update eligibility and cannot bypass this contract or pending-journal recovery.
