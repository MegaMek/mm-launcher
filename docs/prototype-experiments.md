# Developer reference: planner and disposable-update experiments

These are earlier engineering checkpoints, **not the current user-review instructions**.
For the existing-copy launch review, use the [README](../README.md).

Keep these examples for regression investigation. The updater remains restricted to disposable
test copies and must not be used to update a real installation.

## Build and automated checks

Use installed Java 21 or newer and the checked-in Gradle 8.9 wrapper:

```powershell
Set-Location C:\repos\megamek\mm-launcher
.\gradlew.bat test installDist
if ($LASTEXITCODE -ne 0) { throw "Build or tests failed." }
```

## Read-only ownership planner

```powershell
Set-Location C:\repos\megamek\mm-launcher
.\build\install\mm-launcher\bin\mm-launcher.bat plan `
  --baseline .\examples\baseline.json `
  --target .\examples\target.json `
  --installation .\examples\installation
if ($LASTEXITCODE -ne 0) { throw "Planning failed." }
```

This prints `ADD`, `REPLACE`, `REMOVE`, `KEEP`, or `SKIP` decisions and
`DRY-RUN ONLY: no files were changed.` The original invocation without the `plan` word also works.

## Disposable apply/recovery demo

Build first. This creates mutable material under `%TEMP%`; the checked-in source fixture stays
untouched. Byte-writing calls produce LF-terminated payloads matching the sample target manifest.
A unique demo directory prevents collisions when repeating the example.

```powershell
Set-Location C:\repos\megamek\mm-launcher
$cli = ".\build\install\mm-launcher\bin\mm-launcher.bat"
$demo = Join-Path $env:TEMP ("mm-launcher-demo-" + [guid]::NewGuid().ToString())
$sandbox = Join-Path $demo "sandbox"
$payload = Join-Path $demo "payload"
New-Item -ItemType Directory -Path $demo, $payload -ErrorAction Stop | Out-Null
$utf8NoBom = [Text.UTF8Encoding]::new($false)
[IO.File]::WriteAllText((Join-Path $payload "app.txt"), "new application`n", $utf8NoBom)
[IO.File]::WriteAllText((Join-Path $payload "missing.txt"), "new file`n", $utf8NoBom)

& $cli init-test-sandbox `
  --baseline .\examples\baseline.json `
  --fixture .\examples\installation `
  --sandbox $sandbox `
  --acknowledge I-UNDERSTAND-THIS-IS-A-DISPOSABLE-TEST
if ($LASTEXITCODE -ne 0) { throw "Sandbox initialization failed." }

& $cli apply-test-sandbox `
  --target .\examples\target.json `
  --payload $payload `
  --sandbox $sandbox `
  --acknowledge I-UNDERSTAND-THIS-IS-A-DISPOSABLE-TEST
if ($LASTEXITCODE -ne 0) { throw "Apply failed; inspect the error and use recovery if needed." }

Get-ChildItem -Recurse $sandbox
Get-Content (Join-Path $sandbox ".mm-launcher\state.json")

& $cli recover-test-sandbox `
  --sandbox $sandbox `
  --acknowledge I-UNDERSTAND-THIS-IS-A-DISPOSABLE-TEST
if ($LASTEXITCODE -ne 0) { throw "Recovery failed; preserve the sandbox and recovery files." }
```

Expected: replace `app.txt`, add `missing.txt`, remove `obsolete.txt`, and preserve the untracked
`local.txt` collision with `SKIP`. Protected and unknown content remain unchanged.
After a successful apply, recovery reports no pending transaction; repeating recovery is harmless.

The gate prevents accidental use, not hostile use. Initialization copies a trusted fixture only
into an absent/empty directory below system temp or the checkout's `build` directory. Mutating
commands require explicit acknowledgement; this does not authorize real-install adoption.

## Ownership and overrides

- Local matches target: keep it and clear a reconciled override.
- Local matches the current official baseline: replace or remove as required.
- Locally modified managed content, modified obsolete content, and untracked collisions: skip.
- Persist the official manifest separately from overrides; never bless arbitrary local hashes.
- Retain official ancestor hashes across updates. Restoring a recorded official ancestor allows
  reconciliation against the next target.
- Preserve unknown files, protected roots, and skipped content. Report conflicting paths and
  retain override records; unknown non-conflicting files need not be listed.

## Recovery boundary and limitations

Apply verifies required local payloads, stages independent copies, takes backups, journals intent,
and uses same-volume atomic replacement. Recovery rolls back a pre-commit transaction, including
previous state, or verifies and finishes cleanup after a committed transaction.

An exclusive OS file lock refuses a second updater. Cleanup targets recorded artifacts and
known-created empty directories, not whole installations. Unsafe paths, aliases, reserved metadata
conflicts, links, junctions, and reparse entries are rejected. Detected external-edit conflicts
block recovery rather than being overwritten.

The guarantee is process-crash/restart recovery on supported filesystems, **not** complete
power-loss durability, storage-failure recovery, hostile-race resistance, or production security.
Applications and external processes must not edit the sandbox concurrently. Backups remain until
commit verification or successful rollback.

There are no downloads, archive installation, signatures, feed freshness checks, GUI, real-install
update eligibility, running-application detection, or user-data migration in this experiment.
Application-update recovery does not undo save changes made after running a game.
