# Explicit real-package update and recovery contract

## Exact-current-release repair

The Installations **More → Repair installation…** action is separate from Update and never
chooses a newer release. The GUI captures valid local managed provenance, displays the selected
package size (or unknown size) in a plain-language confirmation, and requires consent before
any package download or mutation. It rechecks the captured registry record, receipt and current
state before invoking `RealUpdateService.repair` with `REPAIR_CONFIRM`. Imported/adopted copies,
pending update or uninstall recovery, and running launcher-started suite games are ineligible.
Users must close **all** suite games, including externally started ones.

Repair downloads and verifies the exact persisted package and restores **all** missing or modified
official managed files, including locally edited documentation and data. Protected and unowned
paths are preserved; extra unowned `lib` JARs remain and are reported as a gameplay risk. Rollback
copies are temporary and not retained after successful repair: users must make their own backups.
Failures after mutation may require the existing update recovery flow. A completed repair records
restoration counts and extra-JAR paths in logs, with a visible caveat when extra JARs remain.
The GUI passes its typed operation context through the facade to the verified package fetcher.
Repair enters non-cancellable finalization before any acquisition or provenance check, so an
operation cancellation request cannot interrupt download, preflight, or transaction. Typed events
report actual compressed download bytes/total, followed by verification, extraction, planning,
preparation, Apply, and cleanup; the repair-only GUI groups these into download (percentage of
package transfer only), indeterminate preparation, repair, and finishing stages. It never
estimates overall completion. The zero-change result says no missing or changed files were
found; a changed result says the installation is ready to use. Unowned lib JARs retain an
independent visible caveat with file paths in the logs, not a claim that all additions were fixed.

## Authorization, eligibility, and source trust

Real Apply is never unconsented and does not update MegaMek Launcher itself. The GUI **Update…** flow
uses one initial styled consent for the complete download, verification, internal planning, and
Apply operation. The CLI command

```text
apply-update --registry <json> [--id <uuid>] --from-tag <current-tag>
  --tag <exact-target-tag> --size <bytes> --digest <sha256:hex>
  --confirm CLOSE-ALL-SUITE-APPS-AND-APPLY
```

are available only for a copy with both a valid ownership receipt and fixed-channel sidecar,
created either by a fresh install or successful exact-ancestor adoption. Eligibility comes from the immutable create-new
receipt bound to installation UUID, canonical root, `registeredAt`, fixed official repository,
package identity, ownership policy, and original manifest, together with the equally bound channel
chosen during publication. An imported, manually registered, pre-receipt, missing-channel, stale,
corrupt, or incorrectly bound copy remains launch-only. Adoption is the sole explicit exception:
it constructs provenance from one verified official package and a full read-only comparison, not
from current local hashes. Legacy
`updateEligible=false` continues to mean that registry membership alone is not authority and is
not the source of real-update eligibility.

The preview report is still observational and is never write authority. Standalone CLI Apply keeps
its existing independent one-package-download behavior. A normal GUI Update attempt instead creates
an opaque process-local prepared handle after the first consent. It captures the exact
record/receipt/current-provenance snapshot, target tag, asset name/full byte size/optional
published GitHub SHA-256 and
URL identity, pristine target manifest/excluded inventory/static inspection, and owns the verified
archive plus extraction. It is neither serialized nor a public path token.

Under the root update gate, prepared Apply re-reads the registry, immutable receipt, and latest
current provenance and requires exact equality with the captured source/root. It refreshes the
exact fixed-repository tag and asset metadata (and the captured channel/preference recommendation
when applicable), rejecting drift with a restart message and no second package transfer. It
rechecks the retained compressed archive's stable file identity, exact size, and resolved
SHA-256 (published when available, otherwise computed during the one bounded transfer), safely
extracts it into another owned area, and requires that extraction's static inspection and complete
ownership inventory to equal the pristine captured target. A changed observational extraction is
therefore reconstructed from verified bytes, never blessed. Apply then replans against fresh
installed bytes and retains the runtime collision checks and per-operation transaction
verification.

The initial GUI consent shows the application, current and target versions, rounded download size,
full-operation intent, and close-all-applications requirement. Planning remains internal and does
not introduce another UI authorization. After preparation, the GUI immediately supplies
`CLOSE-ALL-SUITE-APPS-AND-APPLY`; the backend still validates that exact token and all captured
installation, source, metadata, retained-package, and local-file bindings before mutation.
Cancellation before the transaction cutoff performs no Apply write. CLI requires the same exact
confirmation.
There is no automatic release selection, background update, bundled JRE, arbitrary repository,
custom URL, downgrade/upgrade version interpretation, elevation, process termination, or
self-update.

A pending uninstall journal blocks preview and Apply. See
[Safe uninstall contract](uninstall-contract.md) for current-provenance deletion planning,
same-filesystem backups, the registration commit barrier, and crash recovery.

The shared operation context uses typed metadata/download/verify/extract/plan/await-consent/
prepare-install/Apply/recover/cleanup/final phases; control flow never parses presentation text.
The prepared handle's await-consent boundary is internal in the GUI because consent was already
given before package transfer.
Prepared GUI cancellation remains available while refreshing metadata, re-hashing the retained
archive, re-extracting, rebuilding ownership, and replanning. The atomic cutoff runs before
`transact` and therefore before `.mm-launcher-update`, `pending.json`, a transaction UUID, or a
journal can be created. If cancellation wins, Apply has no root/registry/current-state publication
and the prepared handle performs exact non-cancellable cleanup. If transaction entry wins, the
request is denied, the worker is not interrupted, and any later interruption/failure is a recovery
failure rather than **Cancelled**. Recovery similarly disables cancellation before any replay or
cleanup. Neither path kills suite processes.

The prepared handle is atomically one-use and bound to its creating facade and captured
registry/record/root/provenance. Apply owns it against concurrent discard until all retained bytes
are no longer needed. Cancel, ordinary preparation failure, Apply completion, and Apply failure
remove only that handle's random external workspace; repeated discard is idempotent. Cleanup
failure remains explicit and retryable rather than being reported as success. If it occurs after a
committed update, the result says the update completed and cleanup failed, and does not ask for a
blind Apply retry. If Apply itself failed, that original cause is preserved, cleanup failure is
secondary, and root transaction journals/backups remain available for recovery. A process crash
may leave the exact workspace, but there is no persistent token, startup reuse, resume, or broad
automatic temporary-directory deletion.

Clean GUI success is silent: the progress dialog closes and the current Installations page reloads,
including when normal `SKIP` or retained-override decisions exist. A committed update with a
cleanup warning instead remains visible on the styled progress surface with a concise warning,
Close, and logs access; it does not use a native completion dialog.

## Ownership and all-or-nothing runtime boundary

Only ownership-policy managed paths are candidates. Saves, campaigns, configuration, logs, backups,
custom/userdata descendants, excluded package files, and unknown local paths are not migrated or
written. Modified data, obsolete-modified data, and data collisions are retained as `SKIP`
overrides instead of unnecessarily aborting Apply. Unknown artifacts are never broadly scanned for
deletion.

Runtime is stricter because a partially updated runtime is unsafe. Missing, damaged or modified
official runtime files (suite-root `.jar`, `.exe`, `.sh`, and ordinary files below `lib/`) are
added/replaced from the verified target; obsolete modified official runtime is removed. Their
actual pre-update bytes are copied into a durable, SHA-256-verified transaction backup before
any application mutation and are available for rollback until commit. Backups need not remain
after a successful commit. Unowned extra ordinary JARs below `lib/` are never removed, and
preview/CLI warn they may affect the game. Other unknown runtime files, linked/special runtime
paths, runtime `SKIP`/case conflicts and unowned target collisions refuse the **whole Apply before
application mutation**. User saves, preferences, campaigns and modified non-runtime data retain
their existing preservation policy. After mutations, `InstallationInspector` must report exactly
the verified target build/product layout before metadata can commit.

Cross-release case-only execution remains deferred. Affected files/prefixes are `SKIP`, including
runtime prefixes (which therefore stop Apply). Data case-prefix overrides are persisted separately
with their local spelling and all official spellings. Later previews and updates skip the folded
prefix before traversing it, so advancing `data/Icons` to `data/icons` cannot cause a V2→V3 alias
exception, mixed spelling, accidental removal, or later overwrite.
Fresh-registry adoption of an updater-produced root reconstructs these outermost case-only
data/docs/licenses prefixes from its verified official package and the actual local spellings,
including both spellings in sticky current provenance. If such a file is also modified, adoption
keeps both its case prefix and ordinary official-hash override. Case-only managed runtime remains
ineligible. Excluded official paths, including modified `bin/*.bat` launch scripts, remain
unmanaged/untouched and are not treated as critical solely by extension.

Latest current provenance is stored atomically beside the immutable receipt as
`<registry>.metadata/<uuid>.current.json`. It binds the original identity/repository and records
the latest verified tag, asset name/size/SHA-256, managed manifest, excluded inventory, committed
transaction, ordinary override history, trusted official ancestor hashes, and sticky case-prefix
history. A missing current file means the immutable receipt is still current; an unreadable file or
interrupted publication is a blocker, not success-shaped absence. Restoring a recorded older
official data file allows a later update to recognize that hash without treating arbitrary local
content as official.

For an adopted copy, the initial current state names the exact verified ancestor and seeds every
allowed pre-existing modified managed path as `MODIFIED` with only its official ancestor hash.
Runtime content modifications and folder/file conflicts block adoption, so the first later preview
cannot mistake mixed executable bytes for pristine content. A managed path simply absent at
adoption time does not block adoption and needs no seeded state: the first later preview plans it
as an ordinary `ADD`, the same outcome any later update would give any other missing official path.
Unknown and protected files remain outside the manifest. Adoption publishes no root transaction
and performs no application mutation; the first post-adoption update remains an ordinary,
separately requested Check/Preview/Apply.

## Transaction and recovery

Apply creates the temporary owned `.mm-launcher-update` namespace only after download, fresh
planning, runtime checks, and writable/same-volume preflight. This is not `.mm-launcher` sandbox
state and never bypasses `SandboxUpdater` initialization, acknowledgement, or replay rules.
Presence of the real-update namespace blocks launch and another Apply until explicit recovery.

Persistent diagnostics are a separate registry-sidecar concern, never transaction authority.
The default `<registry-name>.launcher-logs/` store keeps 20 proven-owned closed structured logs of
at most 1 MiB each. Terminal outcome/error survives bounded progress truncation. The sanitizer
removes URL query/fragment/userinfo, authentication/secret fields, command/environment dumps, and
Jackson source-content snippets before persistence and explicit copy/view. Unknown files/links and
root journals/backups are not retention targets. Placement, permission, or I/O failure is reported
separately in memory and cannot throw through a mutating transaction, convert committed Apply to
cancellation/failure, or replace the original Apply/recovery error.

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
compares the selected identity/layout and preserves default selection, UUID, name, root, pin,
registration time, and every unrelated record. A concurrent unrelated launcher Java/name/default
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
lock serializes launch publication, Apply, recovery, and unblock across GUI/CLI processes and across multiple
registries naming the same root. It is released after durable child publication rather than held
for the game lifetime, allowing additional launches from the same root. Imported-copy
launches remain usable and do not create coordination metadata inside the installation.

Direct launches append a uniquely identified `STARTING` entry with the launcher identity, then
replace that entry with `RUNNING` and the child PID plus process creation time before waiting.
Earlier single-entry markers are read and migrated under the same gate. All live `RUNNING` entries
block mutation until their respective children exit. `STARTING` means child publication may
have been interrupted: even a dead or reused launcher PID does not prove that no child was started,
so only explicit close-all acknowledgement clears it. A `RUNNING` identity is cleared
automatically only when its child is dead or its PID is demonstrably reused. A verified live
`RUNNING` child is refused even with acknowledgement. Unreadable/unknown markers likewise require
the explicit close-all acknowledgement. `unblock-launch` only checks/clears eligible coordination
state and never terminates anything.

Users must close **all** MegaMek, MekHQ, and MegaMekLab applications before Apply/recovery,
including manually started instances. Coordination prevents cooperating current launcher
processes from publishing launches while updating the same root and pending transactions block
cooperating launches. It cannot prevent old launchers or external commands from starting an
application, cannot universally detect those processes, and does not rely on or claim that Windows
file locks guarantee application closure.
