# Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
# SPDX-License-Identifier: GPL-3.0-or-later

. "$PSScriptRoot\verify_launcher_signature.ps1"

function Assert-LauncherTestCertificate($Signature, [string]$CertificateSha256) {
    if ($CertificateSha256 -cnotmatch '^[0-9a-f]{64}$' -or $null -eq $Signature -or
        $null -eq $Signature.SignerCertificate -or $null -eq $Signature.TimeStamperCertificate) {
        throw 'Test signing requires the pinned signer certificate and an Authenticode timestamp.'
    }
    $certificate = $Signature.SignerCertificate
    $digest = [Convert]::ToHexString(
        [Security.Cryptography.SHA256]::HashData($certificate.RawData)).ToLowerInvariant()
    if ($digest -cne $CertificateSha256 -or $certificate.Subject -cne $certificate.Issuer) {
        throw 'Test signing must use exactly the approved self-signed test certificate.'
    }
    # Windows trust status is not acceptance: the later osslsigncode check verifies the signature and file digest.
}

function Confirm-LauncherTestMsi {
    param(
        [Parameter(Mandatory)][string]$UnsignedMsi,
        [Parameter(Mandatory)][string]$SignedDirectory,
        [Parameter(Mandatory)][string]$Version,
        [Parameter(Mandatory)][string]$Commit,
        [Parameter(Mandatory)][string]$CertificateSha256,
        [Parameter(Mandatory)][string]$OutputDirectory
    )
    $ErrorActionPreference = 'Stop'
    $directory = Get-Item -LiteralPath $SignedDirectory
    $files = @(Get-ChildItem -LiteralPath $directory.FullName -Force)
    if (!$directory.PSIsContainer -or ($directory.Attributes -band [IO.FileAttributes]::ReparsePoint) -or
        $files.Count -ne 1) {
        throw 'SignPath test output must contain exactly the expected MSI.'
    }
    $pair = Get-LauncherMsiPair -UnsignedMsi $UnsignedMsi -SignedMsi $files[0].FullName -Version $Version -Commit $Commit
    $signature = Get-AuthenticodeSignature -LiteralPath $pair.Signed.FullName
    Assert-LauncherTestCertificate $signature $CertificateSha256
    Assert-LauncherMsiIdentity (Get-LauncherMsiIdentity $pair.Unsigned.FullName) $Version
    Assert-LauncherMsiIdentity (Get-LauncherMsiIdentity $pair.Signed.FullName) $Version
    $record = @{
        schema_version = 1
        purpose = 'signpath-test-only'
        name = $pair.Signed.Name
        version = $Version
        commit = $Commit
        unsigned_sha256 = (Get-FileHash -LiteralPath $pair.Unsigned.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        sha256 = (Get-FileHash -LiteralPath $pair.Signed.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        size = $pair.Signed.Length
        certificate_sha256 = $CertificateSha256
        timestamped = $true
    }
    if ($record.unsigned_sha256 -ceq $record.sha256) {
        throw 'Test signing returned the original unchanged MSI.'
    }
    if (Test-Path -LiteralPath $OutputDirectory) {
        throw 'Test-signing output directory must not already exist.'
    }
    $output = New-Item -ItemType Directory -Path $OutputDirectory
    Copy-Item -LiteralPath $pair.Signed.FullName -Destination $output.FullName
    [IO.File]::WriteAllBytes((Join-Path $output.FullName 'test-certificate.cer'), $signature.SignerCertificate.RawData)
    [IO.File]::WriteAllText((Join-Path $output.FullName "$($record.name).sha256"),
        "$($record.sha256)  $($record.name)`n", [Text.UTF8Encoding]::new($false))
    [IO.File]::WriteAllText((Join-Path $output.FullName 'test-signing-inspection.json'),
        ($record | ConvertTo-Json -Compress), [Text.UTF8Encoding]::new($false))
    Write-Host 'Inspected test-only MSI identity and pinned signer; cryptographic verification is still required.'
}
