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

import org.megamek.launcher.gui.GuiLauncher;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Desktop-only bootstrap. The developer command line remains {@link Main}.
 */
public final class DesktopLauncher {
    private static final int MINIMUM_JAVA = 21;

    private DesktopLauncher() {
    }

    public static void main(String[] args) {
        int result = run(args, System.out, System.err, GuiLauncher::start);
        if (result != 0) {
            System.exit(result);
        }
    }

    static int run(String[] args, PrintStream out, PrintStream err, DesktopStarter starter) {
        try {
            if (args.length == 1 && args[0].matches("-psn_[0-9]+_[0-9]+")) {
                // Finder may add this legacy process-serial argument to an app launch.
                args = new String[0];
            }
            if (args.length > 0
                    && (args[0].equals("--version") || args[0].equals("--startup-check"))) {
                return diagnostic(args, out);
            }

            String[] guiArgs = new String[args.length + 1];
            guiArgs[0] = "gui";
            System.arraycopy(args, 0, guiArgs, 1, args.length);
            return starter.start(guiArgs, err);
        } catch (IllegalArgumentException | IOException e) {
            err.println("ERROR: " + e.getMessage());
            err.println("Usage: MegaMek Launcher [--registry <absolute-path>]");
            err.println("       MegaMek Launcher --version [--report <new-absolute-file>]");
            err.println("       MegaMek Launcher --startup-check "
                    + "[--registry <absolute-path>] [--report <new-absolute-file>]");
            return 2;
        }
    }

    private static int diagnostic(String[] args, PrintStream out) throws IOException {
        boolean startupCheck = args[0].equals("--startup-check");
        Path registry = null;
        Path report = null;
        List<String> remaining = new ArrayList<>(Arrays.asList(args).subList(1, args.length));
        while (!remaining.isEmpty()) {
            String option = remaining.removeFirst();
            if (remaining.isEmpty()) {
                throw new IllegalArgumentException(option + " requires a path");
            }
            Path value = absolutePath(remaining.removeFirst(), option);
            switch (option) {
                case "--registry" -> {
                    if (!startupCheck) {
                        throw new IllegalArgumentException("--registry is valid only with --startup-check");
                    }
                    if (registry != null) {
                        throw new IllegalArgumentException("--registry may be specified only once");
                    }
                    registry = value;
                }
                case "--report" -> {
                    if (report != null) {
                        throw new IllegalArgumentException("--report may be specified only once");
                    }
                    report = value;
                }
                default -> throw new IllegalArgumentException("unknown diagnostic option: " + option);
            }
        }

        BuildInformation information = buildInformation();
        String result;
        if (startupCheck) {
            int javaFeature = Runtime.version().feature();
            if (javaFeature < MINIMUM_JAVA) {
                throw new IllegalArgumentException(
                        "Java " + MINIMUM_JAVA + " or newer is required; found " + javaFeature);
            }
            result = "MM-LAUNCHER-STARTUP-OK"
                    + " version=" + information.version()
                    + " build=" + information.buildIdentifier()
                    + " java=" + javaFeature
                    + " packaged=" + information.packaged()
                    + " codeSource=" + information.codeSourceName()
                    + " registryOverride=" + (registry == null ? "none" : "validated");
        } else {
            result = "MegaMek Launcher " + information.version()
                    + " (build " + information.buildIdentifier() + ")";
        }

        out.println(result);
        if (report != null) {
            Path parent = report.getParent();
            if (parent == null || !Files.isDirectory(parent)) {
                throw new IllegalArgumentException("--report parent must be an existing directory");
            }
            Files.writeString(report, result + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        }
        return 0;
    }

    private static Path absolutePath(String value, String option) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(option + " path must not be blank");
        }
        Path path = Path.of(value);
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException(option + " path must be absolute");
        }
        return path.normalize();
    }

    private static BuildInformation buildInformation() {
        String version = "development";
        String build = "development";
        Path source = null;
        String sourceName = "unknown";
        boolean packaged = false;
        Package launcherPackage = DesktopLauncher.class.getPackage();
        if (launcherPackage != null && launcherPackage.getImplementationVersion() != null) {
            version = launcherPackage.getImplementationVersion();
        }

        try {
            source = Path.of(DesktopLauncher.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path fileName = source.getFileName();
            sourceName = fileName == null ? "unknown" : fileName.toString();
            packaged = Files.isRegularFile(source)
                    && sourceName.toLowerCase().endsWith(".jar");
        } catch (NullPointerException | URISyntaxException | IllegalArgumentException e) {
            source = null;
        }

        if (packaged) {
            try (JarFile jar = new JarFile(source.toFile())) {
                Manifest manifest = jar.getManifest();
                if (manifest != null) {
                    Attributes attributes = manifest.getMainAttributes();
                    String manifestBuild = attributes.getValue("Build-Identifier");
                    if (manifestBuild != null && !manifestBuild.isBlank()) {
                        build = manifestBuild;
                    }
                }
            } catch (IOException ignored) {
                // Stable development fallbacks remain available.
            }
        }
        return new BuildInformation(version, build, packaged, safeToken(sourceName));
    }

    private static String safeToken(String value) {
        String sanitized = value.replaceAll("[^A-Za-z0-9._-]", "_");
        return sanitized.isBlank() ? "unknown" : sanitized;
    }

    @FunctionalInterface
    interface DesktopStarter {
        int start(String[] args, PrintStream error);
    }

    private record BuildInformation(
            String version,
            String buildIdentifier,
            boolean packaged,
            String codeSourceName
    ) {
    }
}
