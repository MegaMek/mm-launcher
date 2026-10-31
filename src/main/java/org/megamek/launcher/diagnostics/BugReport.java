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

package org.megamek.launcher.diagnostics;

import org.megamek.launcher.update.LauncherSelfUpdate;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Builds what a launcher bug report needs: a link to the repository's issue form with the
 * environment fields already filled in, and a ZIP of sanitized diagnostics to attach to it.
 *
 * <p>The field ids are a contract with {@code .github/ISSUE_TEMPLATE/bug_report.yml}: if one is
 * renamed there, that field silently stops being filled in. A {@code labels} query parameter is
 * never added, because GitHub serves a 404 instead of the form to visitors without triage
 * permission; the template applies its own issue type for everyone.</p>
 */
public final class BugReport {
    public static final String ISSUE_FORM_URL = "https://github.com/MegaMek/mm-launcher/issues/new";
    public static final String TEMPLATE = "bug_report.yml";

    static final String FIELD_VERSION = "launcher-version";
    static final String FIELD_INSTALL_TYPE = "install-type";
    static final String FIELD_OPERATING_SYSTEM = "operating-system";
    static final String FIELD_JAVA_VERSION = "java-version";

    static final String SYSTEM_INFO_ENTRY = "system-info.txt";
    static final String OPERATION_LOGS_ENTRY = "operation-logs.txt";

    /** Kept well inside what browsers and GitHub accept, so the link is never broken by its length. */
    private static final int MAX_URL_LENGTH = 6000;

    private BugReport() {
    }

    /** The details the issue form asks for, gathered from the running launcher. */
    public record Environment(String launcherVersion, String installType, String operatingSystem,
                              String javaVersion) {
        public static Environment current() {
            String version = BugReport.class.getPackage().getImplementationVersion();
            return new Environment(version == null || version.isBlank() ? "development build" : version,
                    BugReport.installType(System.getProperty("os.name", ""), LauncherSelfUpdate.available(),
                            System.getProperty("jpackage.app-path", "")),
                    "%s %s (%s)".formatted(System.getProperty("os.name"),
                            System.getProperty("os.version"), System.getProperty("os.arch")),
                    "%s %s".formatted(System.getProperty("java.vendor"),
                            System.getProperty("java.version")));
        }
    }

    /**
     * @param osName          the {@code os.name} property
     * @param nativeInstall   whether this is a self-updating native installation
     * @param packagedAppPath the {@code jpackage.app-path} property, blank outside a packaged app
     */
    static String installType(String osName, boolean nativeInstall, String packagedAppPath) {
        if (nativeInstall) {
            if (osName.startsWith("Windows")) return "Windows installer (.msi)";
            if (osName.startsWith("Mac")) return "macOS installer (.pkg)";
            return "Linux package (.deb or .rpm)";
        }
        if (packagedAppPath != null && !packagedAppPath.isBlank()) return "Portable archive";
        return "Development build";
    }

    /** The issue form with this environment filled in, or the plain form if that would be too long. */
    public static URI issueForm(Environment environment) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put(FIELD_VERSION, environment.launcherVersion());
        fields.put(FIELD_INSTALL_TYPE, environment.installType());
        fields.put(FIELD_OPERATING_SYSTEM, environment.operatingSystem());
        fields.put(FIELD_JAVA_VERSION, environment.javaVersion());
        StringJoiner query = new StringJoiner("&");
        query.add("template=" + TEMPLATE);
        for (Map.Entry<String, String> field : fields.entrySet()) {
            query.add(field.getKey() + "=" + URLEncoder.encode(
                    SanitizedErrors.text(field.getValue()), StandardCharsets.UTF_8));
        }
        String prefilled = ISSUE_FORM_URL + "?" + query;
        return URI.create(prefilled.length() <= MAX_URL_LENGTH ? prefilled
                : ISSUE_FORM_URL + "?template=" + TEMPLATE);
    }

    public static String systemInfo(Environment environment) {
        return "MegaMek Launcher bug report\n"
                + "Created: " + Instant.now() + "\n"
                + "Launcher version: " + environment.launcherVersion() + "\n"
                + "Installed as: " + environment.installType() + "\n"
                + "Operating system: " + environment.operatingSystem() + "\n"
                + "Java: " + environment.javaVersion() + "\n"
                + "Locale: " + Locale.getDefault().toLanguageTag() + "\n";
    }

    /** A file name for the archive that sorts by creation time and never needs escaping. */
    public static String archiveFileName(Instant now) {
        return "MegaMek-Launcher-bug-report-"
                + now.toString().replaceAll("\\.\\d+Z$", "Z").replace(":", "") + ".zip";
    }

    /**
     * Writes the archive through a sibling temporary file, so a failed write never leaves a
     * truncated ZIP at {@code target}. Both entries pass through {@link SanitizedErrors#document}.
     *
     * @param operationLogs the operation log text, as shown by the log viewer
     */
    public static void writeArchive(Path target, Environment environment, String operationLogs)
            throws IOException {
        Path absolute = target.toAbsolutePath().normalize();
        Path directory = absolute.getParent();
        if (directory == null || !Files.isDirectory(directory)) {
            throw new IOException("The folder for the bug report does not exist");
        }
        Path staging = Files.createTempFile(directory, ".bug-report-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(staging);
                 ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                entry(zip, SYSTEM_INFO_ENTRY, systemInfo(environment));
                entry(zip, OPERATION_LOGS_ENTRY, operationLogs);
            }
            Files.move(staging, absolute, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(staging);
        }
    }

    private static void entry(ZipOutputStream zip, String name, String text) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(SanitizedErrors.document(text).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
