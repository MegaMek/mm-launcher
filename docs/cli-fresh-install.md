# MM Launcher command-line prototype

This walkthrough downloads one explicitly selected official release into a **new** folder,
validates it, and registers it for later launch. There is no automatic launch, bundled Java, or
implicit update authority. The `install-release` command itself never modifies an existing game
installation; explicit receipt-backed updates use the separate
[real-update contract](real-update-contract.md).

## 1. Build and choose local state

Requires Java 21 or newer. Run the steps in the same PowerShell window and stop on any error.

```powershell
Set-Location C:\repos\megamek\mm-launcher
.\gradlew.bat installDist
if ($LASTEXITCODE -ne 0) { throw "Build failed." }
$cli = ".\build\install\mm-launcher\bin\mm-launcher.bat"
$registry = Join-Path $env:LOCALAPPDATA "MegaMek\launcher-registry.json"
New-Item -ItemType Directory -Force (Split-Path $registry) -ErrorAction Stop | Out-Null
```

## 2. Review releases and install an exact MekHQ bundle

MekHQ packages contain MekHQ, MegaMek, and MegaMekLab. The release list is paged and prints the
tag, title, assets, byte sizes, digest availability, and notes link. It does not guess “stable” or
combine programs from different releases.

```powershell
& $cli releases --application mekhq --page 1 --per-page 10
if ($LASTEXITCODE -ne 0) { throw "Could not list official releases." }
$tag = Read-Host "Exact MekHQ tag shown above (for example v0.51.0)"
$installParent = Read-Host "Existing writable parent folder for the new installation"
$folderName = Read-Host "New folder name (it must not exist)"
$destination = Join-Path $installParent $folderName
if (Test-Path -LiteralPath $destination) {
    throw "Destination already exists. Choose a new folder, or use the existing-copy flow."
}
$name = Read-Host "Name to show in the launcher registry"
& $cli install-release --application mekhq --tag $tag --destination $destination `
    --registry $registry --name $name --channel milestone
if ($LASTEXITCODE -ne 0) {
    throw "Install failed. Read the error carefully; it says if a valid folder was retained but not registered."
}
```

The installer re-fetches that exact tag, accepts one official `MekHQ-*.tar.gz`, and requires the
GitHub size and SHA-256 digest. It streams and verifies the download before safely extracting,
statically inspects the application, and registers it with `updateEligible=false`. GitHub HTTPS
plus a digest published by the same GitHub source detects corruption, but is not an independent
signature. Nothing downloaded is executed.

To install MegaMek or MegaMekLab independently, use `--application megamek` or
`--application lab` consistently in both commands. A new registration becomes default only if the
registry was empty.

`--channel milestone|development` is required. It becomes this new installation's immutable
update track after the exact ownership receipt is published. The manually selected tag does not
classify the release; it only supplies the new copy's initial bytes. To follow another channel,
run `install-release` again with another nonexistent destination. Nightly is not yet supported.

## 2a. Perform a metadata-only check

For a receipt-backed copy:

```powershell
$installationId = Read-Host "Installation UUID printed by install-release (without id=)"
& $cli check-updates --registry $registry --id $installationId
```

`check-updates` reports `currentTag`, fixed `channel`, `target`, `status`, `reason`, notes, size,
and digest where available. It reads canonical channel and exact release API metadata; it
downloads zero package bytes. An unconfigured, malformed, offline, or otherwise unavailable check
is never reported as current. If the installed verified package is newer than its own channel
target, no downgrade is offered. The former `channel-set` compatibility command rejects with
`Channel is fixed; install another managed copy` and performs no write.

## 3. Select external Java and preview

Copy the `id=` from successful installation output. Choose a Java path printed by discovery (or
another installed Java 21+ home/executable), then choose a program present in the package.

```powershell
$installationId = Read-Host "Installation UUID (without id=)"
$installationId = ([guid]::Parse($installationId.Trim())).ToString()
& $cli java-discover
if ($LASTEXITCODE -ne 0) { throw "Java discovery failed." }
$javaPath = Read-Host "Full path to the external Java home or java executable"
& $cli java-select --registry $registry --id $installationId --java $javaPath
if ($LASTEXITCODE -ne 0) { throw "Java selection failed." }
$product = Read-Host "Program: megamek, mekhq, or lab"
& $cli launch --registry $registry --id $installationId --product $product --dry-run true
if ($LASTEXITCODE -ne 0) { throw "Launch preview failed." }
```

**Nothing has started yet.** Check the root, program, and Java in the preview.

## 4. Launch explicitly

```powershell
& $cli launch --registry $registry --id $installationId --product $product
if ($LASTEXITCODE -ne 0) { throw "Launch failed or exited with code $LASTEXITCODE." }
```

The program can then write its normal settings, logs, and saves. For a folder you already
extracted, follow the [existing-copy registration walkthrough](existing-copy-contract.md).

## 5. Preview and explicitly update a receipt-backed copy

Only a copy installed by this launcher's `install-release` flow with its fixed-channel sidecar has
managed update provenance. Imported or copied folders remain launch-only. Preview is read-only and
prints the exact target size/digest:

```powershell
& $cli preview-update --registry $registry --id $installationId --tag $targetTag
```

Review all decisions. Then close every MegaMek, MekHQ, and MegaMekLab process, including manually
started ones. Copy the current baseline tag and exact target size/digest from the report; Apply
rechecks those values, redownloads, and replans:

```powershell
& $cli apply-update --registry $registry --id $installationId `
    --from-tag $currentTag --tag $targetTag --size $exactBytes `
    --digest $exactSha256 `
    --confirm CLOSE-ALL-SUITE-APPS-AND-APPLY
```

If Apply is interrupted or reports pending recovery, keep all suite applications closed and run:

```powershell
& $cli recover-update --registry $registry --id $installationId `
    --confirm CLOSE-ALL-SUITE-APPS-AND-APPLY
```

The launcher coordinates its own current launches but cannot universally detect applications
started by old launchers or external commands. It never kills them. See the
[real-update contract](real-update-contract.md) before a first trial.

The graphical launcher also has an explicit **Enable managed updates…** flow for a genuine
launch-only import. It verifies one exact official package and publishes only external launcher
metadata; it does not replace this CLI fresh-install procedure, modify the imported root, add a
channel-switching command, or enable Nightly. Old releases without a valid published package
SHA-256 remain launch-only.

## Technical reference

- [Release transport, archive, fresh-install, and recovery contract](fresh-install-contract.md)
- [Existing-copy detection, registry, and launch contract](existing-copy-contract.md)
- [Manifest and updater sandbox contract](manifest-contract.md)
- [Explicit real-package update and recovery contract](real-update-contract.md)
- [Older planner and disposable-update experiments](prototype-experiments.md)
- [Overall proposal and delivery plan](../plan.md)
