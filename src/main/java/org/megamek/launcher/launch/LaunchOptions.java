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

package org.megamek.launcher.launch;

import org.megamek.launcher.onboarding.InstallationInspector;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * JVM launch choices for the suite applications. A {@code null} field means "inherit": per-installation
 * overrides inherit from the launcher-wide options, which inherit from the built-in defaults. An empty
 * string for the text fields is an explicit "none" which still overrides an inherited value.
 */
public record LaunchOptions(Integer megamekHeapMb, Integer mekhqHeapMb, Integer labHeapMb,
                            Renderer renderer, String extraJvmArguments, String userDirectory) {
    public static final LaunchOptions INHERIT = new LaunchOptions(null, null, null, null, null, null);
    public static final int MIN_HEAP_MB = 512;
    public static final int MAX_HEAP_MB = 65_536;
    public static final int MAX_ARGUMENTS_LENGTH = 2048;
    public static final int MAX_ARGUMENTS = 64;
    public static final int MAX_USER_DIRECTORY_LENGTH = 1024;
    /** MegaMek, MekHQ, and MegaMekLab read this property in place of their saved User Directory. */
    public static final String USER_DIRECTORY_PROPERTY = "megamek.userDir";
    private static final Set<String> RESERVED = Set.of("-cp", "-classpath", "--class-path", "-jar",
            "-m", "--module", "-p", "--module-path", "--upgrade-module-path", "--source",
            "-version", "--version", "-showversion", "--show-version", "-help", "--help", "-h", "-?",
            "-X", "--help-extra", "--dry-run", "--list-modules", "--describe-module", "-d",
            "--validate-modules");

    public enum Renderer {
        DEFAULT("Java default", List.of()),
        NO_DIRECT3D("Disable Direct3D (Windows)", List.of("-Dsun.java2d.d3d=false")),
        OPENGL("OpenGL", List.of("-Dsun.java2d.opengl=true"));

        private final String label;
        private final List<String> arguments;

        Renderer(String label, List<String> arguments) {
            this.label = label;
            this.arguments = arguments;
        }

        public String label() {
            return label;
        }

        public List<String> arguments() {
            return arguments;
        }

        @Override public String toString() {
            return label;
        }
    }

    public static LaunchOptions builtIn() {
        return new LaunchOptions(InstallationInspector.heapMb("megamek"),
                InstallationInspector.heapMb("mekhq"), InstallationInspector.heapMb("lab"),
                Renderer.DEFAULT, "", "");
    }

    /** Returns these options with every inherited ({@code null}) field taken from {@code base}. */
    public LaunchOptions over(LaunchOptions base) {
        return new LaunchOptions(
                megamekHeapMb != null ? megamekHeapMb : base.megamekHeapMb,
                mekhqHeapMb != null ? mekhqHeapMb : base.mekhqHeapMb,
                labHeapMb != null ? labHeapMb : base.labHeapMb,
                renderer != null ? renderer : base.renderer,
                extraJvmArguments != null ? extraJvmArguments : base.extraJvmArguments,
                userDirectory != null ? userDirectory : base.userDirectory);
    }

    public boolean inheritsEverything() {
        return equals(INHERIT);
    }

    public Integer heapMb(String productKey) {
        return switch (productKey) {
            case "megamek" -> megamekHeapMb;
            case "mekhq" -> mekhqHeapMb;
            case "lab" -> labHeapMb;
            default -> throw new IllegalArgumentException("unknown product: " + productKey);
        };
    }

    /**
     * JVM arguments contributed by these (fully resolved) options, excluding the heap size. They are placed
     * after the launcher's own required arguments so a user-supplied {@code -D} can refine them.
     */
    public List<String> jvmArguments() {
        List<String> arguments = new ArrayList<>(renderer == null ? List.of() : renderer.arguments());
        if (userDirectory != null && !userDirectory.isEmpty()) {
            arguments.add("-D" + USER_DIRECTORY_PROPERTY + "=" + userDirectory);
        }
        if (extraJvmArguments != null) arguments.addAll(parseArguments(extraJvmArguments));
        return List.copyOf(arguments);
    }

    /** @throws IllegalArgumentException with a user-facing message when any field is unusable */
    public void validate() {
        for (Integer heap : new Integer[]{megamekHeapMb, mekhqHeapMb, labHeapMb}) {
            if (heap != null && (heap < MIN_HEAP_MB || heap > MAX_HEAP_MB)) {
                throw new IllegalArgumentException("Memory must be between " + MIN_HEAP_MB
                        + " and " + MAX_HEAP_MB + " MB.");
            }
        }
        if (extraJvmArguments != null) parseArguments(extraJvmArguments);
        if (userDirectory != null && !userDirectory.isEmpty()) {
            if (userDirectory.length() > MAX_USER_DIRECTORY_LENGTH
                    || userDirectory.chars().anyMatch(Character::isISOControl)
                    || !userDirectory.equals(userDirectory.strip())) {
                throw new IllegalArgumentException("The user directory path is not valid.");
            }
            try {
                if (!Path.of(userDirectory).isAbsolute()) {
                    throw new IllegalArgumentException("The user directory must be an absolute path.");
                }
            } catch (InvalidPathException error) {
                throw new IllegalArgumentException("The user directory path is not valid.", error);
            }
        }
    }

    /**
     * Splits free-form JVM options on whitespace; double quotes group a value containing spaces. Every token
     * must be a single self-contained option so it can never become the main class or replace the launcher's
     * class path.
     *
     * @throws IllegalArgumentException with a user-facing message for unsupported input
     */
    public static List<String> parseArguments(String text) {
        if (text.length() > MAX_ARGUMENTS_LENGTH) {
            throw new IllegalArgumentException("Additional Java options are too long.");
        }
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        boolean inToken = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                quoted = !quoted;
                inToken = true;
            } else if (!quoted && Character.isWhitespace(c)) {
                if (inToken) tokens.add(current.toString());
                current.setLength(0);
                inToken = false;
            } else if (Character.isISOControl(c)) {
                throw new IllegalArgumentException("Additional Java options contain a control character.");
            } else {
                current.append(c);
                inToken = true;
            }
        }
        if (quoted) throw new IllegalArgumentException("Additional Java options have an unclosed quote.");
        if (inToken) tokens.add(current.toString());
        if (tokens.size() > MAX_ARGUMENTS) {
            throw new IllegalArgumentException("Use at most " + MAX_ARGUMENTS + " additional Java options.");
        }
        for (String token : tokens) {
            String lower = token.toLowerCase(Locale.ROOT);
            if (!token.startsWith("-") || token.length() < 2) {
                throw new IllegalArgumentException("“" + token + "” is not a Java option. Options start with "
                        + "“-”; write values as --option=value.");
            }
            if (lower.startsWith("-xmx")) {
                throw new IllegalArgumentException("Set memory with the memory fields instead of -Xmx.");
            }
            String name = token.contains("=") ? token.substring(0, token.indexOf('=')) : token;
            if (RESERVED.contains(name) || lower.startsWith("--class-path")) {
                throw new IllegalArgumentException("“" + name + "” cannot be used because the launcher "
                        + "controls how the application starts.");
            }
            if (token.equals("--add-opens") || token.equals("--add-exports") || token.equals("--add-reads")
                    || token.equals("--add-modules") || token.equals("--patch-module")
                    || token.equals("--limit-modules")) {
                throw new IllegalArgumentException("Write “" + token + "” as " + token + "=value.");
            }
        }
        return List.copyOf(tokens);
    }
}
