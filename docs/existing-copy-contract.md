# Existing-copy launch-only contract (registry schema 3)

The persistent registry is an explicitly supplied JSON file outside all application roots. It is
not `.mm-launcher` sandbox state, an ownership manifest, or an update authorization. Every
installation record has a random stable UUID, unique name, canonical root, exact observed build
(or `unknown`), detected products and classpaths, optional user pin text, registration timestamp,
and literal
`"updateEligible": false`.

Unknown/missing JSON fields, duplicate keys, wrong schema, malformed IDs, duplicate names or IDs,
noncanonical roots, root aliases/overlaps, bad defaults, and any update-eligible record fail closed.
A corrupt registry is never reset or replaced. Registry changes use an adjacent exclusive OS lock,
a create-new staging file, flush, and same-volume atomic replacement. An unexpected staging path
or non-file lock path is an error. The first record is the deterministic initial default; only
`select` changes a surviving default. Removing the default chooses the first surviving registered
record, or null when empty.

Schema 3 contains strict independent preferred-installation UUIDs for `megamek`, `mekhq`, and
`lab`. Older pre-release schemas and records with obsolete Java fields are rejected without
migration. A missing/stale UUID has a deterministic first-matching-record runtime fallback without
repairing the stored value. New registration fills only unset included applications. Removal
clears only preferences that pointed to the removed UUID. Unknown/malformed preference keys or
values fail closed.

Registration requires `REGISTER-LAUNCH-ONLY`. Removal requires `REMOVE-LAUNCH-ONLY`. Both modify
launcher metadata only. Inspection, default selection, and removal do not modify
application content. Registration rejects application roots containing `.mm-launcher`; therefore
an existing sandbox or its pending/unsafe transaction cannot be treated as launch-only to bypass
the updater gate.

## Detection and launch

Only fixed known product jar names and main classes are accepted. Jar inspection uses bounded reads
of `META-INF/MANIFEST.MF`, `Version.properties`, and `extraVersion.properties`; it performs no
classloading and executes no application artifact. Manifest classpaths are whitespace-separated
relative local paths. URLs, absolute paths, traversal, escaped paths, backslashes, links/reparse
points, outside-root resolutions, non-files, and corrupt jars fail. Direct classpath entries of each
recognized root product are required. A genuinely missing transitive entry is ignored as the JVM
ignores an unresolved manifest classpath URL; inspection reports this with
`confidence=recognized-packaging-optional-transitive-missing`. Unsafe entries, indeterminate access,
non-files, and existing corrupt transitive jars still fail, and every reachable jar is checked.
The expected root directories are `data`, `mmconf`, and `lib`; directory names alone and nested
source trees are not detection evidence. Conflicting version evidence fails rather than inventing
a release identity.

The Game Java candidate list contains only the launcher's `java.home` and `JAVA_HOME`.
Existing-copy import does not read settings or resolve, execute, or validate Java. Its immutable
plan contains only static inspection evidence, and registration remains valid when Java settings
are absent, corrupt, or changed. Java is launch-time state, never per-copy registration state.

Before preview or launch, the launcher re-inspects the canonical root and requires the observed
build and selected product metadata to equal the registered evidence. It then constructs a fixed
classpath/main-class invocation and supported memory/open/disable-grab options. Preview validates
Java and layout but does not start the game. Actual launch is the only application execution and
is foreground/waiting; direct process-start errors and the child's exit code are returned.

The GUI's visible first-launch and Installations import actions use one flow. The folder chooser is
the only pre-operation prompt. One styled progress window performs static inspection and direct
registration; there is no separate name, confirmation, or completion
dialog. The record name is derived from detected product and version without inferring a channel.
Immediately before one atomic registry mutation, RegistryStore re-inspects the root, compares the
captured canonical inspection, and applies
duplicate/overlap rules. It never holds that
lock while executing a process. First registration supplies the compatibility default only if
locked current state has none and initializes only unset preferences for its included applications.
Existing or concurrently added preferences win, and all existing record fields are retained.
Imported records have no ownership receipt, channel sidecar, or provenance and remain launch-only;
Home includes their detected programs in the application union. Installations labels each one
**Imported copy · Launch only · Updates unavailable** and exposes launch/preference,
**Open location**, **Remove from launcher…**, and a separate **Enable Updates** control, but no check, Preview,
Update, or Recover action. Import itself never prompts for a channel and never starts adoption or
a download. Successful exact-copy adoption creates the fixed-channel sidecar with check-on-open
enabled, after which the direct per-card checkbox is the sole automatic-check setting.

An official package folder copied without its launcher registry, ownership receipt, and
fixed-channel sidecar may still identify its build during static inspection, but that identity
does not recreate installation provenance or update eligibility. Version, title, prerelease state,
and current canonical channel membership are never used to infer which track that copied folder
was created to follow.

The existing-copy commands themselves perform no network, download, or archive extraction. The
separate `releases` and `install-release` flow is bounded by
[the fresh-install contract](fresh-install-contract.md). Neither flow includes recursive runtime
search, updater eligibility, migration, script/INI interpretation, arbitrary main classes, custom
argument passthrough, background process management, or GUI. Once explicitly launched, the game
itself may write within its directory or user profile; that behavior is not a launcher mutation.

## Existing-copy walkthrough

Use this when the application folder already exists. Use a disposable copy for prototype review;
enter paths without surrounding quotes. Build as described in the README, then:

```powershell
$registry = Join-Path $env:LOCALAPPDATA "MegaMek\launcher-registry.json"
New-Item -ItemType Directory -Force (Split-Path $registry) -ErrorAction Stop | Out-Null
$installation = Read-Host "Full path to the already extracted application folder"
& $cli inspect --installation $installation
if ($LASTEXITCODE -ne 0) { throw "Inspection failed." }
$name = Read-Host "Name for this copy"
& $cli register --registry $registry --installation $installation `
    --name $name --confirm REGISTER-LAUNCH-ONLY
if ($LASTEXITCODE -ne 0) { throw "Registration failed." }
& $cli list --registry $registry
if ($LASTEXITCODE -ne 0) { throw "Could not read the registry." }
$installationId = Read-Host "Paste its UUID from id= above (without id=)"
$installationId = ([guid]::Parse($installationId.Trim())).ToString()
```

Then use the README's explicit launch steps. The exact Java runtime executing MM Launcher is used
automatically unless an explicit launcher-wide default is later selected.
Inspection may report
`confidence=recognized-packaging-optional-transitive-missing`; that is normal for supported suite
packages which relocate a shared JAR. Registration neither modifies nor launches the application.

## Explicit adoption

Adoption is a GUI/backend operation separate from import. It is offered only when the exact
registered record is still a genuine import: no ownership receipt, current state, adoption
publication, fixed-channel sidecar, sidecar staging, or pending update/recovery exists. Any
managed-incomplete or corrupt combination is an attention/repair state and cannot be overwritten
by adoption. Before network access the backend re-reads the exact UUID/root/`registeredAt`
binding, re-inspects the product/build, checks canonical non-link/non-overlapping placement,
acquires the shared root gate, and rejects a live launcher-tracked application or pending update.
Network work is refused on the Swing event thread.

The observed product set maps deterministically: any supported bundle containing MekHQ uses the
MekHQ repository, lab-only uses MegaMekLab, and MegaMek-only uses MegaMek. Other mixed or unknown
sets are rejected. After the user chooses the future update policy (Milestone by default or
Development), the launcher searches bounded release-list metadata rather than guessing
`/tags/v<observed>`. It accepts exactly one eligible immutable tag whose normalized dotted version
matches the observed build; leading zeroes are insignificant, but prerelease/build suffixes and
component count are not discarded. Draft and ineligible releases do not qualify. A full final
bounded page is inconclusive rather than permission to guess.

Zero, multiple, incomplete, or failed searches leave the copy launch-only and expose only a simple
message plus **Choose a different version…**. That explicit fallback opens the bounded official
release browser and binds its exact selection to the same record and chosen future channel.
Neither path classifies a historical release into a channel from its title or prerelease flag.
Nightly is unsupported. `ReleaseCatalog` must return one supported full
`tar.gz` asset with a bounded size and either a valid published SHA-256 or no published digest.
The verified fetcher downloads it
once and the safe extractor opens it in an attempt-owned external workspace. Official static
inspection must reproduce the record's exact product set, build, root JAR manifests, and
classpaths.

Preparation reports **Checking existing installation** while taking the initial complete local
snapshot without following links, rejecting case/Unicode/file-parent conflicts, and hashing all
regular files while checking stable identity. The extracted package then reports
**Checking official package** and is traversed and hashed exactly once. The same immutable
per-entry SHA-256 evidence produces both the official ownership manifest and the full official
comparison snapshot. Every official runtime path (root application executable/JAR/script and all
`lib/` dependencies) must be present and byte-identical. Missing managed paths are currently
unsupported and block adoption. Modified non-runtime managed paths are allowed only as explicit
`MODIFIED` overrides seeded with the pristine official ancestor hash. Unknown regular files are
retained. Protected saves, campaigns, userdata, custom content, configuration, logs, and backups
remain excluded and unmanaged.

The opaque prepared handle is one-use, process-local, non-serializable, and bound to its creating
service, registry, exact record, root snapshot, repository/tag/asset/size/resolved digest, fixed
channel, immutable official inspection/ownership/snapshot evidence, and pristine package
inventory. Successful validation proceeds directly to publication for the already-selected
channel in the same cancellable operation and progress surface. Publication acquires the root gate
again, rechecks the registry record, true-import and pending state, then takes one final local root
snapshot under **Confirming installation has not changed** and requires it to equal the prepared
local snapshot. It performs no remote metadata request, archive re-hash, second extraction, or
rebuilt official evidence. Cancellation remains available until atomic metadata publication
begins. Local drift discards the attempt.

Publication writes only launcher metadata outside the application root: immutable ownership
receipt, explicit current ancestor and override history, adoption binding, then fixed channel as
the final eligibility barrier. Exact-value rollback removes only attempt-owned publications after
an injected or ordinary failure. A cancelled, ineligible, or failed attempt removes its owned
temporary workspace and remains launch-only; application bytes and timestamps are never changed.
Success makes the existing Check/Preview/Update services available but performs no update and no
launch. Normal result UI contains only the safe/ineligible statement; bounded
exact/modified/missing/protected/unknown/conflict details are local diagnostics only.
