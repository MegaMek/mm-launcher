# Explicit real-package update and recovery contract

## Authorization, eligibility, and source trust

Real Apply is never automatic and does not update MM Launcher itself. The GUI **Update…** flow and
the CLI command

```text
apply-update --registry <json> [--id <uuid>] --from-tag <current-tag>
  --tag <exact-target-tag> --size <bytes> --digest <sha256:hex>
  --confirm CLOSE-ALL-SUITE-APPS-AND-APPLY
```

are available only for a copy freshly downloaded and registered by this launcher. Eligibility
comes from the immutable create-new receipt bound to installation UUID, canonical root,
`registeredAt`, fixed official repository, package identity, ownership policy, and original
manifest. An imported, manually registered, pre-receipt, stale, corrupt, or incorrectly bound copy
remains launch-only. The launcher never creates update provenance from current local hashes.
Registry schema remains 1; legacy `updateEligible=false` continues to mean that registry membership
alone is not authority and is not the source of real-update eligibility.

The preview report is still observational and is never write authority. Apply takes a captured
record/current-provenance snapshot, exact target tag, full byte size, and GitHub SHA-256. Under the
root update gate it re-reads the registry, immutable receipt, and latest current provenance,
requires exact equality with the captured source, re-fetches the exact fixed-repository release,
and rejects changed target size/digest before downloading its asset. It then verifies, extracts,
statically inspects, inventories, and replans against fresh installed bytes. Invalid consent,
source tags, sizes, or digests fail before network or installation writes.

The GUI shows source/target, full second download, digest, decision counts, destructive managed-file
intent, preserved `SKIP` count, backup/recovery behavior, and the close-all-applications requirement
before consent. Cancellation performs no Apply write. CLI requires the same exact confirmation.
There is no automatic release selection, background update, bundled JRE, arbitrary repository,
custom URL, downgrade/upgrade version interpretation, elevation, process termination, or
self-update.

## Ownership and all-or-nothing runtime boundary

Only ownership-policy managed paths are candidates. Saves, campaigns, configuration, logs, backups,
custom/userdata descendants, excluded package files, and unknown local paths are not migrated or
written. Modified data, obsolete-modified data, and data collisions are retained as `SKIP`
overrides instead of unnecessarily aborting Apply. Unknown artifacts are never broadly scanned for
deletion.

Runtime is stricter because a partially updated runtime is unsafe. Every suite-root `.jar`, `.exe`,
or `.sh` and every ordinary file below `lib/` must match the current verified manifest, the exact
target manifest, or a recorded official ancestor. A missing, modified, linked, special, or
unrecognized runtime path, or any runtime `SKIP`/case conflict, refuses the **whole Apply before
application mutation**. After mutations, `InstallationInspector` must report exactly the verified
target build/product layout before metadata can commit.

Cross-release case-only execution remains deferred. Affected files/prefixes are `SKIP`, including
runtime prefixes (which therefore stop Apply). Data case-prefix overrides are persisted separately
with their local spelling and all official spellings. Later previews and updates skip the folded
prefix before traversing it, so advancing `data/Icons` to `data/icons` cannot cause a V2→V3 alias
exception, mixed spelling, accidental removal, or later overwrite.

Latest current provenance is stored atomically beside the immutable receipt as
`<registry>.metadata/<uuid>.current.json`. It binds the original identity/repository and records
the latest verified tag, asset name/size/SHA-256, managed manifest, excluded inventory, committed
transaction, ordinary override history, trusted official ancestor hashes, and sticky case-prefix
history. A missing current file means the immutable receipt is still current; an unreadable file or
interrupted publication is a blocker, not success-shaped absence. Restoring a recorded older
official data file allows a later update to recognize that hash without treating arbitrary local
content as official.

## Transaction and recovery

Apply creates the temporary owned `.mm-launcher-update` namespace only after download, fresh
planning, runtime checks, and writable/same-volume preflight. This is not `.mm-launcher` sandbox
state and never bypasses `SandboxUpdater` initialization, acknowledgement, or replay rules.
Presence of the real-update namespace blocks launch and another Apply until explicit recovery.

The transaction is bound to schema, transaction UUID, canonical root and registry, installation
UUID and registration time, prior/current provenance, captured record, verified target layout,
the complete managed decision union, exact mutation allowlist, created-directory allowlist, and
transaction-owned backup/staging paths. Validation rejects stale/wrong identity, unknown JSON,
unsafe/duplicate/protected/non-managed paths, operation/plan disagreement, source-policy mismatch,
and artifact paths outside the exact transaction. A durable journal is atomically published before
any application mutation.

All target files are copied to the installation volume and hash-verified. Every file that may be
replaced or removed has a durable, hash-verified backup. Immediately before each operation, the
destination is resolved without following links/reparse points or case aliases and its SHA-256 is
rechecked against the journal. Replacements are atomic same-volume moves. Deletes occur only after
their backup is verified. Each result hash is checked. Missing parents are created only for
allowlisted operations and recorded for conservative empty-only rollback cleanup.

Commit order is installed runtime validation, atomic current-provenance publication, locked atomic
registry build/product refresh, committed journal, then exact owned cleanup. Registry refresh
compares the selected identity/layout and preserves default selection, UUID, name, root, external
Java, pin, registration time, and every unrelated record. A concurrent unrelated Java/name/default
change is retained; a removed/rebound or unexpectedly relaid-out selected record fails.

`recover-update --registry <json> [--id <uuid>]
--confirm CLOSE-ALL-SUITE-APPS-AND-APPLY` and GUI **Check update recovery…** do not need network or
a launchable/inspectable partial application. Recovery validates the external receipt plus the
root-bound pending identity and journal. If every operation has its exact target hash, recovery
validates the resulting runtime and completes metadata publication idempotently. Otherwise it
rolls back in reverse with exact backups. A destination containing neither before nor after hash,
an invalid/missing needed backup, corrupt journal, protected-operation tamper, wrong binding, link,
or unknown artifact fails closed and is retained for manual review. Recovery never overwrites an
unexpected post-crash/user edit and never kills a process.

## Launch/update coordination and limitation

A per-OS-user coordination directory outside installations is keyed by canonical root. An OS file
lock serializes launches, Apply, recovery, and unblock across GUI/CLI processes and across multiple
registries naming the same root. A launch holds the lock for the full game lifetime. Imported-copy
launches remain usable and do not create coordination metadata inside the installation.

Direct launches first persist `STARTING` with the launcher identity, then replace it with `RUNNING`
and the child PID plus process creation time before waiting. `STARTING` means child publication may
have been interrupted: even a dead or reused launcher PID does not prove that no child was started,
so only explicit close-all acknowledgement clears it. A `RUNNING` identity is cleared
automatically only when its child is dead or its PID is demonstrably reused. A verified live
`RUNNING` child is refused even with acknowledgement. Unreadable/unknown markers likewise require
the explicit close-all acknowledgement. `unblock-launch` only checks/clears eligible coordination
state and never terminates anything.

Users must close **all** MegaMek, MekHQ, and MegaMekLab applications before Apply/recovery,
including manually started instances. Coordination prevents cooperating current launcher
processes from starting or updating the same root concurrently and pending transactions block
cooperating launches. It cannot prevent old launchers or external commands from starting an
application, cannot universally detect those processes, and does not rely on or claim that Windows
file locks guarantee application closure.
