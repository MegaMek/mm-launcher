# Existing-copy launch-only contract (registry schema 1)

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

The Java candidate list contains only the launcher's `java.home` and `JAVA_HOME`. Discovery does
not run either candidate. Explicit selection resolves a home or executable, rejects
installation-contained Java, and directly executes `<java> -version` without a shell, with bounded
output and timeout. Java 21 or newer is required by this prototype.

Before preview or launch, the launcher re-inspects the canonical root and requires the observed
build and selected product metadata to equal the registered evidence. It then constructs a fixed
classpath/main-class invocation and supported memory/open/disable-grab options. Preview validates
Java and layout but does not start the game. Actual launch is the only application execution and
is foreground/waiting; direct process-start errors and the child's exit code are returned.

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
