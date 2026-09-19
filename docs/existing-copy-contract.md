# Existing-copy launch-only contract (registry schema 2)

The persistent registry is an explicitly supplied JSON file outside all application roots. It is
not `.mm-launcher` sandbox state, an ownership manifest, or an update authorization. Every
installation record has a random stable UUID, unique name, canonical root, exact observed build
(or `unknown`), detected products and classpaths, optional user pin text, optional explicitly
validated external Java executable, registration timestamp, and literal
`"updateEligible": false`.

Unknown/missing JSON fields, duplicate keys, wrong schema, malformed IDs, duplicate names or IDs,
noncanonical roots, root aliases/overlaps, bad defaults, and any update-eligible record fail closed.
A corrupt registry is never reset or replaced. Registry changes use an adjacent exclusive OS lock,
a create-new staging file, flush, and same-volume atomic replacement. An unexpected staging path
or non-file lock path is an error. The first record is the deterministic initial default; only
`select` changes a surviving default. Removing the default chooses the first surviving registered
record, or null when empty.

Schema 2 retains that legacy default for CLI/API compatibility and adds strict independent
preferred-installation UUIDs for `megamek`, `mekhq`, and `lab`. Schema 1 is migrated in memory by
initializing only applications actually contained by its default record; the read itself never
writes. A missing/stale UUID has a deterministic first-matching-record runtime fallback without
repairing the stored value. New registration fills only unset included applications. Removal
clears only preferences that pointed to the removed UUID. Unknown/malformed preference keys or
values fail closed.

Registration requires `REGISTER-LAUNCH-ONLY`. Removal requires `REMOVE-LAUNCH-ONLY`. Both modify
registry metadata only. Inspection, default selection, removal, and Java selection do not modify
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

The optional Java candidate list used by a **Change Java** action contains only the launcher's
`java.home` and `JAVA_HOME`. Existing-copy import uses the validated launcher-level default game
Java when configured, otherwise the exact runtime which started MM Launcher from `java.home`,
using `java.exe` on Windows and `java` elsewhere. The absent fallback is not silently persisted.
It rejects links/reparse points, missing/nonregular executables, and Java
canonically contained by the selected application root, then directly executes only
`<current-java> -version` without a shell, with bounded output and timeout. Java 21 or newer is a
registration prerequisite; failure leaves no null-Java imported record.

Before preview or launch, the launcher re-inspects the canonical root and requires the observed
build and selected product metadata to equal the registered evidence. It then constructs a fixed
classpath/main-class invocation and supported memory/open/disable-grab options. Preview validates
Java and layout but does not start the game. Actual launch is the only application execution and
is foreground/waiting; direct process-start errors and the child's exit code are returned.

The GUI's visible first-launch and Installations import actions use one flow. Before confirmation,
static inspection and current-Java validation are read-only. Confirmation names the detected build,
actual programs, canonical root, Java feature, and no-move behavior. Immediately before one atomic
registry mutation, current Java is revalidated; under the registry lock RegistryStore re-inspects
the root, compares the captured canonical inspection, rechecks validated Java file identity and
external placement, and applies duplicate/overlap rules. It never holds that lock while executing
a process. First registration supplies the compatibility default only if locked current state has
none and initializes only unset preferences for its included applications. Existing or
concurrently added preferences win, and all existing record fields are retained. Imported records
have no ownership receipt, channel sidecar, or provenance and remain launch-only; Home includes
their detected programs in the application union.

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

Then use the README's common external-Java selection, preview, and explicit launch steps.
Inspection may report
`confidence=recognized-packaging-optional-transitive-missing`; that is normal for supported suite
packages which relocate a shared JAR. Registration neither modifies nor launches the application.
