# Safe uninstall contract

This is a pre-release, breaking contract. Registry schema 3 has no per-installation Java fields
and older registry shapes are rejected without migration.
Uninstall and remove-from-launcher do not read Java settings, resolve a runtime, or execute Java.

**Remove from launcher…** is available for imported and managed copies. It revalidates the exact
registration and canonical product layout under the root/process gate, refuses pending update,
pending uninstall, or a live child, removes all launcher-owned sidecars for the installation ID,
then removes the registration under the registry lock. Application-root bytes and timestamps are
never touched. Preferred application references fall back deterministically to the first remaining
compatible installation.

If the installation folder was manually deleted, its registration remains until the user
confirms **Remove from launcher** in a styled **Installation not found** dialog. This separate
path does not inspect absent product files, recreate the installation, or delete application-root
content. It verifies a canonical accessible parent and positively identifies the missing entry;
an unavailable parent/drive, access error, link, special object, or indeterminate location remains
registered and produces an explicit error. The root/process gate, exact registration snapshot,
and missing-folder check are repeated before committing. A reappearing folder blocks removal.
An interrupted uninstall's valid bound journal can authorize completion of registration removal
and cleanup of its launcher-owned recovery backups; corrupt or mismatched journals cannot.
Cleanup failures remain visible warnings, with durable commit authority retained for startup recovery.

**Uninstall…** is available only for a managed or adopted copy with a valid ownership receipt,
latest current provenance, valid adoption marker when applicable, and fixed channel. Imported,
corrupt, incomplete, running, pending-update, and pending-uninstall copies are ineligible.

The uninstall plan is derived from the latest verified official manifest, not the original
receipt. Only a regular file whose stable size and SHA-256 match that current official entry is
targeted. Missing files are already absent. Modified files, links, non-regular objects, protected
paths, excluded package paths, and unknown/custom files are retained. Only manifest-parent
directories which become empty are removed, deepest first; the installation root is removed only
when empty. No application-root recursive delete is used and links, reparse points, aliases,
case collisions, escapes, and noncanonical roots fail closed.

Before root mutation, the service writes a strict, installation/registry-bound journal under
`<registry>.metadata/uninstall-v1/<id>/journal.json`. Exact official files are atomically moved,
with relative paths and attributes preserved, to a unique sibling backup on the same filesystem.
The complete move intent is durable before any file move. Journal publication is phase-bounded,
not repeated for every file: recovery determines whether each atomic move completed from the
original and backup locations, refusing unexpected destination edits and verifying backup bytes
before restoration. The directory-removal phase and any empty-root removal are durable before
deletion. Existing schema-1 journals with per-file move states remain readable. Launcher sidecars
are then moved to journal-owned backup. Registry removal is the final commit barrier while both
backups exist.

Any pre-commit failure rolls back without overwriting an unexpected edit and retains registration.
Cancellation is accepted only before the first move/finalization cutoff. A registered pending
journal blocks launch and update and exposes **Recover uninstall**, which conservatively restores
the copy under the same root gate. A durable `COMMIT_AUTHORIZED` journal plus an absent exact
registration is explicit post-commit authority; startup performs only a bounded scan of the
uninstall journal namespace and finishes backup cleanup. Backup cleanup failure after commit is
reported as a warning and does not turn success into a retryable uninstall.

The confirmation is:

> Uninstall &lt;name&gt;? Official application files will be removed. Saves, settings, custom
> files, and modified files will be kept.

Success is reported in the centralized Home information area as **Uninstalled.** or, when the
root remains, **Uninstalled. Your custom files were kept in the installation folder.**
