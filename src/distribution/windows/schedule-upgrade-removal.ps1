# Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
# SPDX-License-Identifier: GPL-3.0-or-later
#
# jpackage schedules RemoveExistingProducts before CostInitialize, so the previous
# launcher is removed and committed before the new version starts installing. Any
# later failure then leaves the player with no launcher. Removing it right after
# InstallInitialize makes the removal part of the installation transaction, so
# Windows Installer restores the previous launcher's files when an update fails.
param([Parameter(Mandatory = $true)][string]$Msi)

$ErrorActionPreference = 'Stop'
$installer = New-Object -ComObject WindowsInstaller.Installer
$database = $installer.OpenDatabase($Msi, 1)
try {
    function Invoke-MsiQuery([string]$sql) {
        $view = $database.OpenView($sql)
        [void]$view.Execute($null)
        $rows = @()
        while ($record = $view.Fetch()) { $rows += , @($record.StringData(1), $record.IntegerData(2)) }
        [void]$view.Close()
        return , $rows
    }
    function Get-ActionSequence([string]$action) {
        $rows = Invoke-MsiQuery "SELECT ``Action``, ``Sequence`` FROM ``InstallExecuteSequence`` WHERE ``Action``='$action'"
        if ($rows.Count -ne 1) { throw "InstallExecuteSequence has no single $action action." }
        return $rows[0][1]
    }

    $initialize = Get-ActionSequence 'InstallInitialize'
    $removal = Get-ActionSequence 'RemoveExistingProducts'
    $target = $initialize + 1
    if ($removal -ne $target) {
        $occupied = Invoke-MsiQuery "SELECT ``Action``, ``Sequence`` FROM ``InstallExecuteSequence`` WHERE ``Sequence``=$target"
        if ($occupied.Count -ne 0) { throw "Sequence $target is already used by $($occupied[0][0])." }
        $view = $database.OpenView(
            "UPDATE ``InstallExecuteSequence`` SET ``Sequence``=$target WHERE ``Action``='RemoveExistingProducts'")
        [void]$view.Execute($null)
        [void]$view.Close()
        $database.Commit()
    }
    if ((Get-ActionSequence 'RemoveExistingProducts') -ne $target) {
        throw 'RemoveExistingProducts was not rescheduled after InstallInitialize.'
    }
    Write-Output "RemoveExistingProducts runs at $target, after InstallInitialize ($initialize)."
} finally {
    [void][Runtime.InteropServices.Marshal]::ReleaseComObject($database)
}
