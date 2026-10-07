# Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
# SPDX-License-Identifier: GPL-3.0-or-later

function Assert-LauncherSignature($Signature, [string]$CertificateSha256) {
    if ($CertificateSha256 -cnotmatch '^[0-9a-f]{64}$') {
        throw 'Expected the approved signing certificate SHA-256.'
    }
    if ($null -eq $Signature -or $Signature.Status -ne 'Valid' -or
        $null -eq $Signature.SignerCertificate -or $null -eq $Signature.TimeStamperCertificate) {
        throw 'Windows MSI must have a valid, trusted Authenticode signature and timestamp.'
    }
    $digest = [Convert]::ToHexString(
        [Security.Cryptography.SHA256]::HashData($Signature.SignerCertificate.RawData)).ToLowerInvariant()
    if ($digest -cne $CertificateSha256) {
        throw 'Windows MSI signing certificate does not match the approved certificate.'
    }
}

function Assert-LauncherMsiIdentity($Identity, [string]$Version) {
    if ($Identity.ProductName -cne 'MegaMek Launcher' -or $Identity.ProductVersion -cne $Version -or
        $Identity.UpgradeCode -cne '{616FFD64-0D7F-4FBE-9BB9-63FD6D9D32FA}') {
        throw 'Signed MSI must preserve the tested launcher product, version and permanent upgrade code.'
    }
}

function Get-LauncherMsiIdentity([string]$Path) {
    $installer = New-Object -ComObject WindowsInstaller.Installer
    $database = $installer.OpenDatabase($Path, 0)
    $identity = @{}
    foreach ($property in @('ProductName', 'ProductVersion', 'UpgradeCode')) {
        $view = $database.OpenView("SELECT ``Value`` FROM ``Property`` WHERE ``Property``='$property'")
        try {
            [void]$view.Execute()
            $record = $view.Fetch()
            if ($null -eq $record) {
                throw "MSI property $property is missing."
            }
            $identity[$property] = $record.StringData(1)
            if ($null -ne $view.Fetch()) {
                throw "MSI property $property is duplicated."
            }
        } finally {
            [void]$view.Close()
        }
    }
    return $identity
}

function Confirm-LauncherMsi {
    param(
        [Parameter(Mandatory)][string]$UnsignedMsi,
        [Parameter(Mandatory)][string]$SignedMsi,
        [Parameter(Mandatory)][string]$Version,
        [Parameter(Mandatory)][string]$Commit,
        [Parameter(Mandatory)][string]$CertificateSha256,
        [Parameter(Mandatory)][string]$Verification
    )
    $ErrorActionPreference = 'Stop'
    if ($Commit -cnotmatch '^[0-9a-f]{40}$') {
        throw 'Signing verification requires the exact release candidate commit.'
    }
    $unsigned = Get-Item -LiteralPath $UnsignedMsi
    $signed = Get-Item -LiteralPath $SignedMsi
    foreach ($file in @($unsigned, $signed)) {
        if ($file.PSIsContainer -or ($file.Attributes -band [IO.FileAttributes]::ReparsePoint) -or
            $file.Length -le 0 -or $file.Length -gt 300MB -or
            $file.Name -cne "MegaMek-Launcher-$Version-windows-x64.msi") {
            throw 'Signing requires the exact nonempty regular Windows MSI within the updater size limit.'
        }
    }
    if ($unsigned.FullName -ieq $signed.FullName) {
        throw 'Signed output must be separate from the original tested MSI.'
    }
    Assert-LauncherSignature (Get-AuthenticodeSignature -LiteralPath $signed.FullName) $CertificateSha256
    Assert-LauncherMsiIdentity (Get-LauncherMsiIdentity $unsigned.FullName) $Version
    Assert-LauncherMsiIdentity (Get-LauncherMsiIdentity $signed.FullName) $Version
    $record = @{
        schema_version = 1
        name = $signed.Name
        version = $Version
        commit = $Commit
        unsigned_sha256 = (Get-FileHash -LiteralPath $unsigned.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        sha256 = (Get-FileHash -LiteralPath $signed.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        size = $signed.Length
        certificate_sha256 = $CertificateSha256
        signature_status = 'Valid'
        timestamped = $true
    }
    if ($record.unsigned_sha256 -ceq $record.sha256) {
        throw 'Signing returned the original unchanged MSI.'
    }
    $content = [Text.UTF8Encoding]::new($false).GetBytes(($record | ConvertTo-Json -Compress))
    $stream = [IO.File]::Open($Verification, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
    try {
        $stream.Write($content, 0, $content.Length)
    } finally {
        $stream.Dispose()
    }
    Write-Host "Verified trusted, timestamped Windows MSI for $Version at $Commit."
}
