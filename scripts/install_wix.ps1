# Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Installs the pinned WiX Toolset v7 command-line tool and Util extension into
# .tools/wix7 for the Windows MSI build. Both come from nuget.org and are
# verified against the SHA-256 pins below before extraction. Running wix.dll
# needs a .NET 8+ runtime; it is never installed globally.

param([string]$Destination = (Join-Path $PSScriptRoot '..\.tools\wix7'))

$ErrorActionPreference = 'Stop'
$wixVersion = '7.0.1'
$packages = @(
    @{ Id = 'wix'; Sha256 = '25209a1f73b20106621f11abe5e1a4e87c1d9f5c596795d246de7bb64446df17'
       Entries = 'tools/net8.0/any/' },
    @{ Id = 'wixtoolset.util.wixext'; Sha256 = 'e654f578e0a7d433819fe4cc343f9e37cb14f600fa061a6480857ba51334a075'
       Entries = 'wixext7/' }
)

Add-Type -AssemblyName System.IO.Compression.FileSystem
$Destination = [IO.Path]::GetFullPath($Destination)
if (Test-Path -LiteralPath $Destination) {
    Remove-Item -LiteralPath $Destination -Recurse -Force
}
New-Item -ItemType Directory -Force $Destination | Out-Null
$download = Join-Path ([IO.Path]::GetTempPath()) "mm-launcher-wix-$wixVersion"
New-Item -ItemType Directory -Force $download | Out-Null

foreach ($package in $packages) {
    $name = "$($package.Id).$wixVersion.nupkg"
    $file = Join-Path $download $name
    Invoke-WebRequest -Uri "https://api.nuget.org/v3-flatcontainer/$($package.Id)/$wixVersion/$name" -OutFile $file
    $actual = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -cne $package.Sha256) { throw "WiX package SHA-256 mismatch for ${name}: $actual" }

    # Flatten only the tool or extension directory; nupkg metadata stays behind.
    $archive = [IO.Compression.ZipFile]::OpenRead($file)
    try {
        foreach ($entry in $archive.Entries) {
            if (!$entry.FullName.StartsWith($package.Entries) -or $entry.Name -eq '') { continue }
            $target = [IO.Path]::GetFullPath((Join-Path $Destination $entry.FullName.Substring($package.Entries.Length)))
            if (!$target.StartsWith($Destination + [IO.Path]::DirectorySeparatorChar)) {
                throw "Refusing to extract outside ${Destination}: $($entry.FullName)"
            }
            New-Item -ItemType Directory -Force (Split-Path $target) | Out-Null
            [IO.Compression.ZipFileExtensions]::ExtractToFile($entry, $target, $true)
        }
    } finally {
        $archive.Dispose()
    }
}

foreach ($required in 'wix.dll', 'WixToolset.Util.wixext.dll') {
    if (!(Test-Path -LiteralPath (Join-Path $Destination $required))) {
        throw "WiX $wixVersion is incomplete after extraction: $required is missing."
    }
}
Write-Host "WiX $wixVersion installed in $Destination"
