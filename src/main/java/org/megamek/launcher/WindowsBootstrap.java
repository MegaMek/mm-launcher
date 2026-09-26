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

package org.megamek.launcher;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Minimal Java bridge embedded by Launch4j. Launch4j changes to the executable
 * directory before this code resolves the one shared lib payload inside the
 * sibling macOS app bundle.
 */
public final class WindowsBootstrap {
    private static final Path SHARED_LIB = Path.of(
            "MegaMek Launcher.app", "Contents", "app", "lib");

    private WindowsBootstrap() {
    }

    public static void main(String[] args) {
        try {
            Path lib = SHARED_LIB.toAbsolutePath().normalize();
            if (!Files.isDirectory(lib)) {
                throw new IllegalStateException(
                        "MegaMek Launcher shared payload is missing at " + lib + ".");
            }
            URL[] jars;
            try (var files = Files.list(lib)) {
                jars = files
                        .filter(path -> path.getFileName().toString()
                                .toLowerCase().endsWith(".jar"))
                        .sorted(Comparator.comparing(path ->
                                path.getFileName().toString().toLowerCase()))
                        .map(WindowsBootstrap::url)
                        .toArray(URL[]::new);
            }
            if (jars.length == 0) {
                throw new IllegalStateException(
                        "MegaMek Launcher shared payload at " + lib
                                + " contains no application JARs.");
            }

            URLClassLoader loader = new URLClassLoader(jars,
                    ClassLoader.getPlatformClassLoader());
            Thread.currentThread().setContextClassLoader(loader);
            Class<?> desktop = Class.forName(
                    "org.megamek.launcher.DesktopLauncher", true, loader);
            Method main = desktop.getMethod("main", String[].class);
            main.invoke(null, (Object) Arrays.copyOf(args, args.length));
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            cause.printStackTrace(System.err);
            System.exit(1);
        } catch (ReflectiveOperationException | IOException | RuntimeException e) {
            System.err.println("ERROR: " + e.getMessage());
            System.exit(1);
        }
    }

    private static URL url(Path path) {
        try {
            return path.toUri().toURL();
        } catch (java.net.MalformedURLException e) {
            throw new IllegalArgumentException("invalid lib payload path", e);
        }
    }
}
