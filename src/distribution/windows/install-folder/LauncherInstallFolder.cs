/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek Launcher.
 *
 * MegaMek Launcher is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek Launcher is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's "Game Content Usage Rules"
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */


// Compiled by the .NET Framework 4 csc.exe (C# 5): keep to C# 5 syntax.
using System;
using System.IO;
using WixToolset.Dtf.WindowsInstaller;

namespace MegaMek.Launcher.Installer
{
    public static class LauncherInstallFolder
    {
        private const string LauncherFolderName = "MegaMek Launcher";
        private const string ChosenProperty = "LAUNCHER_INSTALL_FOLDER";
        private const string PreviousProperty = "LAUNCHER_PREVIOUS_INSTALLDIR";

        // The setup bundle passes the folder the user picked (or the remembered or
        // default one) in LAUNCHER_INSTALL_FOLDER. Runs before anything changes:
        // a folder that is unsafe to install into fails setup with an explanation,
        // a safe one becomes INSTALLDIR. Burn needs a new detect before it can plan
        // again after a failed apply and WixStdBA never re-detects, so the user picks
        // again by rerunning setup.
        [CustomAction]
        public static ActionResult ResolveInstallFolder(Session session)
        {
            string chosen = session[ChosenProperty];
            if (string.IsNullOrEmpty(chosen))
            {
                return ActionResult.Success;
            }
            string problem;
            string folder = null;
            if (!Path.IsPathRooted(chosen) || chosen.StartsWith("\\") && !chosen.StartsWith("\\\\"))
            {
                problem = chosen + "\n\nThis is not a full folder path. Run setup again and choose a folder with the Change button.";
            }
            else
            {
                folder = InstallFolderFor(Path.GetFullPath(chosen));
                string localAppData =
                    Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
                // Launchers older than the InstallDir record sit in the jpackage default.
                string legacyDefault = Path.Combine(localAppData, "Programs", LauncherFolderName);
                if (!File.Exists(Path.Combine(legacyDefault, LauncherFolderName + ".exe")))
                {
                    legacyDefault = null;
                }
                problem = FolderProblem(folder, localAppData, session[PreviousProperty], legacyDefault);
                if (problem == null && !IsWritable(folder))
                {
                    problem = "Setup can't create files in\n" + folder.TrimEnd('\\') + "\n\n"
                        + "Run setup again and choose a folder you can write to without administrator rights, "
                        + "for example one in your user folder or on another drive.";
                }
            }
            if (problem != null)
            {
                session.Log("Install folder rejected: " + problem.Replace("\n", " "));
                // Field 1 carries the text so brackets in a path are not formatted.
                using (Record record = new Record(1))
                {
                    record.FormatString = "[1]";
                    record[1] = problem;
                    session.Message(InstallMessage.Error
                        | (InstallMessage)MessageButtons.OK | (InstallMessage)MessageIcon.Warning, record);
                }
                return ActionResult.Failure;
            }
            session["INSTALLDIR"] = folder;
            return ActionResult.Success;
        }

        // The launcher always gets its own folder: uninstall removes INSTALLDIR
        // recursively, so it must never be a folder the user shares.
        public static string InstallFolderFor(string picked)
        {
            string trimmed = picked.TrimEnd('\\');
            if (trimmed.EndsWith("\\" + LauncherFolderName, StringComparison.OrdinalIgnoreCase))
            {
                return trimmed + "\\";
            }
            return trimmed + "\\" + LauncherFolderName + "\\";
        }

        // Uninstall deletes the install folder recursively, so it must never hold the
        // launcher's own settings or anybody else's files. Returns null when it is safe.
        public static string FolderProblem(string installFolder, string localAppData,
            params string[] currentInstallDirs)
        {
            string target = Normalize(installFolder);
            if (!string.IsNullOrEmpty(localAppData))
            {
                foreach (string stateName in new[] { "MegaMek Launcher", "MegaMek" })
                {
                    string state = Normalize(Path.Combine(localAppData, stateName));
                    if (Contains(target, state) || Contains(state, target))
                    {
                        return target + "\n\nThis is where MegaMek Launcher keeps your settings "
                            + "and game list, so it can't be installed here.\n\n"
                            + "Run setup again and choose another folder, for example your user folder or another drive.";
                    }
                }
            }
            if (!Directory.Exists(target))
            {
                return null;
            }
            foreach (string current in currentInstallDirs)
            {
                if (!string.IsNullOrEmpty(current)
                    && string.Equals(Normalize(current), target, StringComparison.OrdinalIgnoreCase))
                {
                    return null;
                }
            }
            using (var entries = Directory.EnumerateFileSystemEntries(target).GetEnumerator())
            {
                if (!entries.MoveNext())
                {
                    return null;
                }
            }
            return target + "\n\nThis folder already has other files in it. Uninstalling the "
                + "launcher would delete them, so setup needs a new or empty folder.\n\n"
                + "Run setup again and choose another folder.";
        }

        private static string Normalize(string path)
        {
            return Path.GetFullPath(path).TrimEnd('\\');
        }

        private static bool Contains(string outer, string inner)
        {
            return string.Equals(outer, inner, StringComparison.OrdinalIgnoreCase)
                || inner.StartsWith(outer + "\\", StringComparison.OrdinalIgnoreCase);
        }

        // Probes the nearest folder that already exists: the install folder itself
        // is usually created by setup.
        private static bool IsWritable(string folder)
        {
            string candidate = folder.TrimEnd('\\');
            while (!string.IsNullOrEmpty(candidate) && !Directory.Exists(candidate))
            {
                candidate = Path.GetDirectoryName(candidate);
            }
            if (string.IsNullOrEmpty(candidate))
            {
                return false;
            }
            string probe = Path.Combine(candidate, ".megamek-launcher-setup-" + Guid.NewGuid().ToString("N"));
            try
            {
                using (new FileStream(probe, FileMode.CreateNew, FileAccess.Write, FileShare.None,
                    1, FileOptions.DeleteOnClose))
                {
                }
                return true;
            }
            catch (Exception)
            {
                return false;
            }
        }
    }
}