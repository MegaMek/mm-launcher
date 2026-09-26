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

package org.megamek.launcher.onboarding;

import java.io.IOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

/**
 * Selects the managed application-data root for the ordinary GUI registry while preserving
 * registry-local isolation for explicit/custom registries. Path comparison is lexical,
 * absolute, and normalized; it never resolves links or writes to the filesystem.
 */
public final class PlatformInstallLocations {
    private final PlatformSource source;
    private final boolean alwaysUseCustomRegistry;

    public PlatformInstallLocations(PlatformSource source) {
        this(Objects.requireNonNull(source, "source"), false);
    }

    private PlatformInstallLocations(PlatformSource source, boolean alwaysUseCustomRegistry) {
        this.source = source;
        this.alwaysUseCustomRegistry = alwaysUseCustomRegistry;
    }

    /** A deterministic policy for CLI, backend, test, and explicit GUI registry overrides. */
    public static PlatformInstallLocations customRegistry() {
        return new PlatformInstallLocations(null, true);
    }

    /** Captures process platform inputs for the ordinary no-override GUI launch. */
    public static PlatformInstallLocations system(Path defaultRegistry) {
        Path expected = Objects.requireNonNull(defaultRegistry, "defaultRegistry")
                .toAbsolutePath().normalize();
        return new PlatformInstallLocations(new PlatformSource() {
            @Override
            public String operatingSystem() {
                return System.getProperty("os.name", "");
            }

            @Override
            public String environment(String name) {
                return System.getenv(name);
            }

            @Override
            public String userHome() {
                return System.getProperty("user.home");
            }

            @Override
            public Path defaultRegistry() {
                return expected;
            }
        });
    }

    public Path destination(Path registry, String productChannelFolder) throws IOException {
        Path stateFile = Objects.requireNonNull(registry, "registry")
                .toAbsolutePath().normalize();
        String folder = requireSafeFolder(productChannelFolder);
        if (alwaysUseCustomRegistry || !stateFile.equals(expectedDefaultRegistry())) {
            Path state = stateFile.getParent();
            if (state == null) {
                throw new IOException("launcher state file has no parent directory");
            }
            return state.resolve("installations").resolve(folder)
                    .toAbsolutePath().normalize();
        }
        return managedApplicationDataRoot().resolve("MegaMek").resolve(folder)
                .toAbsolutePath().normalize();
    }

    private Path expectedDefaultRegistry() throws IOException {
        Path expected = source.defaultRegistry();
        if (expected == null) {
            throw new IOException("default GUI registry path is unavailable");
        }
        return expected.toAbsolutePath().normalize();
    }

    private Path managedApplicationDataRoot() throws IOException {
        String os = Objects.requireNonNullElse(source.operatingSystem(), "")
                .toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return requireAbsolutePath(source.environment("LOCALAPPDATA"),
                    "LOCALAPPDATA must be a nonblank absolute path for normal installs");
        }
        if (os.contains("mac")) {
            return requireAbsolutePath(source.userHome(),
                    "user.home must be a nonblank absolute path for normal installs")
                    .resolve("Library").resolve("Application Support");
        }

        String xdgData = source.environment("XDG_DATA_HOME");
        if (xdgData != null && !xdgData.isBlank()) {
            try {
                Path configured = Path.of(xdgData);
                if (configured.isAbsolute()) {
                    return configured.normalize();
                }
            } catch (InvalidPathException ignored) {
                // Invalid and relative XDG_DATA_HOME values are ignored by the XDG specification.
            }
        }
        return requireAbsolutePath(source.userHome(),
                "user.home must be a nonblank absolute path for normal installs")
                .resolve(".local").resolve("share");
    }

    private static Path requireAbsolutePath(String value, String message) throws IOException {
        if (value == null || value.isBlank()) throw new IOException(message);
        try {
            Path path = Path.of(value);
            if (!path.isAbsolute()) throw new IOException(message);
            return path.normalize();
        } catch (InvalidPathException error) {
            throw new IOException(message, error);
        }
    }

    private static String requireSafeFolder(String folder) throws IOException {
        if (folder == null || folder.isBlank()) {
            throw new IOException("normal install folder name is required");
        }
        try {
            Path path = Path.of(folder);
            if (path.isAbsolute() || path.getNameCount() != 1
                    || folder.equals(".") || folder.equals("..")) {
                throw new IOException("normal install folder name must be one safe component");
            }
            return folder;
        } catch (InvalidPathException error) {
            throw new IOException("normal install folder name is invalid", error);
        }
    }

    /** Injectable inputs keep platform-path tests independent of global properties/environment. */
    public interface PlatformSource {
        String operatingSystem();

        String environment(String name);

        String userHome();

        Path defaultRegistry();
    }
}
