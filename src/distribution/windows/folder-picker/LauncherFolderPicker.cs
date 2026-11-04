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
using System.Runtime.InteropServices;
using System.Threading;
using Microsoft.Deployment.WindowsInstaller;

namespace MegaMek.Launcher.Installer
{
    public static class LauncherFolderPicker
    {
        private const string LauncherFolderName = "MegaMek Launcher";
        private const string ResultProperty = "LAUNCHER_PICKED_DIR";

        // Shows the standard Windows folder dialog and stores the resulting install
        // folder (always a dedicated "MegaMek Launcher" folder, with a trailing
        // backslash) in LAUNCHER_PICKED_DIR; leaves it empty when cancelled.
        [CustomAction]
        public static ActionResult PickInstallFolder(Session session)
        {
            session[ResultProperty] = string.Empty;
            try
            {
                string current = session["INSTALLDIR"];
                IntPtr owner = GetForegroundWindow();
                string start = ExistingAncestor(ParentChoice(current));
                while (true)
                {
                    string picked = ShowOnStaThread(owner, start);
                    if (picked == null)
                    {
                        return ActionResult.Success;
                    }
                    if (IsWritable(picked))
                    {
                        string folder = InstallFolderFor(picked);
                        string localAppData =
                            Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
                        // Launchers older than the InstallDir record sit in the jpackage default.
                        string legacyDefault = Path.Combine(localAppData, "Programs", LauncherFolderName);
                        if (!File.Exists(Path.Combine(legacyDefault, LauncherFolderName + ".exe")))
                        {
                            legacyDefault = null;
                        }
                        string problem = FolderProblem(folder, localAppData,
                            current, session["LAUNCHER_PREVIOUS_INSTALLDIR"], legacyDefault);
                        if (problem == null)
                        {
                            session[ResultProperty] = folder;
                            return ActionResult.Success;
                        }
                        MessageBox(owner, problem, "MegaMek Launcher Setup", 0x30);
                        start = picked;
                        continue;
                    }
                    MessageBox(owner,
                        "Setup can't create files in\n" + picked + "\n\n"
                            + "Choose a folder you can write to without administrator rights, "
                            + "for example one in your user folder or on another drive.",
                        "MegaMek Launcher Setup", 0x30);
                    start = picked;
                }
            }
            catch (Exception e)
            {
                session.Log("LauncherFolderPicker failed: " + e);
                return ActionResult.Success;
            }
        }

        // The launcher always gets its own folder: jpackage's uninstall removes
        // INSTALLDIR recursively, so it must never be a folder the user shares.
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
                            + "Choose another folder, for example your user folder or another drive.";
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
                + "Choose another folder.";
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

        private static string ParentChoice(string installDir)
        {
            if (string.IsNullOrEmpty(installDir))
            {
                return Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            }
            string trimmed = installDir.TrimEnd('\\');
            if (trimmed.EndsWith("\\" + LauncherFolderName, StringComparison.OrdinalIgnoreCase))
            {
                return trimmed.Substring(0, trimmed.Length - LauncherFolderName.Length - 1);
            }
            return trimmed;
        }

        private static string ExistingAncestor(string path)
        {
            string candidate = path;
            while (!string.IsNullOrEmpty(candidate) && !Directory.Exists(candidate))
            {
                candidate = Path.GetDirectoryName(candidate);
            }
            return candidate;
        }

        private static bool IsWritable(string folder)
        {
            string probe = Path.Combine(folder, ".megamek-launcher-setup-" + Guid.NewGuid().ToString("N"));
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

        private static string ShowOnStaThread(IntPtr owner, string start)
        {
            string result = null;
            Exception failure = null;
            Thread thread = new Thread(delegate()
            {
                try
                {
                    result = Show(owner, start);
                }
                catch (Exception e)
                {
                    failure = e;
                }
            });
            thread.SetApartmentState(ApartmentState.STA);
            thread.Start();
            thread.Join();
            if (failure != null)
            {
                throw new InvalidOperationException("Folder dialog failed", failure);
            }
            return result;
        }

        private static string Show(IntPtr owner, string start)
        {
            IFileOpenDialog dialog = (IFileOpenDialog) new FileOpenDialogClass();
            try
            {
                uint options;
                dialog.GetOptions(out options);
                dialog.SetOptions(options | FOS_PICKFOLDERS | FOS_FORCEFILESYSTEM
                    | FOS_PATHMUSTEXIST | FOS_NOCHANGEDIR);
                dialog.SetTitle("Choose where to put the MegaMek Launcher folder");
                dialog.SetOkButtonLabel("Select folder");
                if (!string.IsNullOrEmpty(start))
                {
                    IShellItem folder;
                    Guid shellItemId = typeof(IShellItem).GUID;
                    if (SHCreateItemFromParsingName(start, IntPtr.Zero, ref shellItemId, out folder) == 0)
                    {
                        dialog.SetFolder(folder);
                        Marshal.ReleaseComObject(folder);
                    }
                }
                int hr = dialog.Show(owner);
                if (hr == ERROR_CANCELLED)
                {
                    return null;
                }
                Marshal.ThrowExceptionForHR(hr);
                IShellItem item;
                dialog.GetResult(out item);
                try
                {
                    IntPtr text;
                    item.GetDisplayName(SIGDN_FILESYSPATH, out text);
                    try
                    {
                        return Marshal.PtrToStringUni(text);
                    }
                    finally
                    {
                        Marshal.FreeCoTaskMem(text);
                    }
                }
                finally
                {
                    Marshal.ReleaseComObject(item);
                }
            }
            finally
            {
                Marshal.ReleaseComObject(dialog);
            }
        }

        private const uint FOS_NOCHANGEDIR = 0x8;
        private const uint FOS_PICKFOLDERS = 0x20;
        private const uint FOS_FORCEFILESYSTEM = 0x40;
        private const uint FOS_PATHMUSTEXIST = 0x800;
        private const uint SIGDN_FILESYSPATH = 0x80058000;
        private const int ERROR_CANCELLED = unchecked((int) 0x800704C7);

        [DllImport("user32.dll")]
        private static extern IntPtr GetForegroundWindow();

        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        private static extern int MessageBox(IntPtr owner, string text, string caption, uint type);

        [DllImport("shell32.dll", CharSet = CharSet.Unicode, PreserveSig = true)]
        private static extern int SHCreateItemFromParsingName(string path, IntPtr bindContext,
            ref Guid riid, out IShellItem item);

        [ComImport, Guid("DC1C5A9C-E88A-4dde-A5A1-60F82A20AEF7")]
        private class FileOpenDialogClass
        {
        }

        // Vtable order must match shobjidl.h (IModalWindow, IFileDialog, IFileOpenDialog).
        [ComImport, Guid("d57c7288-d4ad-4768-be02-9d969532d960"),
         InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        private interface IFileOpenDialog
        {
            [PreserveSig]
            int Show(IntPtr parent);
            void SetFileTypes(uint count, IntPtr filterSpecs);
            void SetFileTypeIndex(uint index);
            void GetFileTypeIndex(out uint index);
            void Advise(IntPtr events, out uint cookie);
            void Unadvise(uint cookie);
            void SetOptions(uint options);
            void GetOptions(out uint options);
            void SetDefaultFolder(IShellItem item);
            void SetFolder(IShellItem item);
            void GetFolder(out IShellItem item);
            void GetCurrentSelection(out IShellItem item);
            void SetFileName([MarshalAs(UnmanagedType.LPWStr)] string name);
            void GetFileName([MarshalAs(UnmanagedType.LPWStr)] out string name);
            void SetTitle([MarshalAs(UnmanagedType.LPWStr)] string title);
            void SetOkButtonLabel([MarshalAs(UnmanagedType.LPWStr)] string text);
            void SetFileNameLabel([MarshalAs(UnmanagedType.LPWStr)] string label);
            void GetResult(out IShellItem item);
        }

        [ComImport, Guid("43826D1E-E718-42EE-BC55-A1E261C37BFE"),
         InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
        private interface IShellItem
        {
            void BindToHandler(IntPtr bindContext, ref Guid handler, ref Guid riid, out IntPtr result);
            void GetParent(out IShellItem parent);
            void GetDisplayName(uint form, out IntPtr name);
            void GetAttributes(uint mask, out uint attributes);
            void Compare(IShellItem other, uint hint, out int order);
        }
    }
}
