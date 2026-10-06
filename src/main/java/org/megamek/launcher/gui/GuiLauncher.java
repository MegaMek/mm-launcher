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

package org.megamek.launcher.gui;

import org.megamek.launcher.onboarding.PlatformInstallLocations;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.GraphicsEnvironment;
import java.io.PrintStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.io.IOException;

public final class GuiLauncher {
    private GuiLauncher() {
    }

    public static int start(String[] args, PrintStream error) {
        RegistrySelection selection;
        try {
            selection = registrySelection(args);
        } catch (IllegalArgumentException e) {
            error.println("ERROR: " + e.getMessage());
            error.println("Usage: mm-launcher gui [--registry <path>]");
            return 2;
        }
        if (GraphicsEnvironment.isHeadless()) {
            error.println("ERROR: the graphical launcher cannot start in a headless environment");
            return 2;
        }
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (ReflectiveOperationException | javax.swing.UnsupportedLookAndFeelException ignored) {
                // The cross-platform Swing look and feel remains usable.
            }
            if (selection.defaultRegistry()) {
                try {
                    DefaultRegistryMigration.migrate(selection.registry(), legacyRegistry());
                } catch (IOException migrationError) {
                    error.println("ERROR: launcher state migration stopped: "
                            + migrationError.getMessage());
                    LauncherAlertDialog.showMessage(null, GuiScale.DEFAULT, "MegaMek Launcher",
                            "MegaMek Launcher cannot safely migrate previous launcher state.\n"
                                    + migrationError.getMessage()
                                    + "\nNo old state was removed. Resolve the conflict before retrying.");
                    return;
                }
            }
            PlatformInstallLocations installLocations = selection.defaultRegistry()
                    ? PlatformInstallLocations.system(selection.registry())
                    : PlatformInstallLocations.customRegistry();
            new LauncherFrame(new LauncherServices(
                    selection.registry(), installLocations)).showWindow();
        });
        return 0;
    }

    static Path registryArgument(String[] args) {
        return registrySelection(args).registry();
    }

    private static RegistrySelection registrySelection(String[] args) {
        if (args.length == 1) return new RegistrySelection(defaultRegistry(), true);
        if (args.length == 3 && args[1].equals("--registry") && !args[2].isBlank()) {
            return new RegistrySelection(
                    Path.of(args[2]).toAbsolutePath().normalize(), false);
        }
        throw new IllegalArgumentException("gui accepts only an optional --registry <path>");
    }

    public static Path defaultRegistry() {
        return stateRegistry("MegaMek Launcher");
    }

    static Path legacyRegistry() {
        return stateRegistry("MegaMek");
    }

    private static Path stateRegistry(String name) {
        String os = System.getProperty("os.name", "").toLowerCase();
        String base;
        if (os.contains("win") && System.getenv("LOCALAPPDATA") != null) {
            base = System.getenv("LOCALAPPDATA");
        } else if (os.contains("mac")) {
            base = absoluteUserHome().resolve("Library")
                    .resolve("Application Support").toString();
        } else {
            base = System.getenv("XDG_STATE_HOME");
            if (base == null || base.isBlank()) {
                return absoluteUserHome().resolve(name.equals("MegaMek") ? ".megamek" : ".megamek-launcher")
                        .resolve("launcher-registry.json").toAbsolutePath().normalize();
            }
        }
        return Path.of(base, name, "launcher-registry.json").toAbsolutePath().normalize();
    }

    private static Path absoluteUserHome() {
        String value = System.getProperty("user.home");
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "user.home must be a nonblank absolute path");
        }
        try {
            Path home = Path.of(value);
            if (!home.isAbsolute()) {
                throw new IllegalArgumentException(
                        "user.home must be a nonblank absolute path");
            }
            return home.normalize();
        } catch (InvalidPathException error) {
            throw new IllegalArgumentException(
                    "user.home must be a valid absolute path", error);
        }
    }

    private record RegistrySelection(Path registry, boolean defaultRegistry) {
    }
}
