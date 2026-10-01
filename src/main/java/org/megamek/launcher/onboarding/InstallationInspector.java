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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

public final class InstallationInspector {
    private static final int MAX_ENTRY = 1_048_576;
    private static final Map<String, Spec> SPECS;
    static {
        Map<String, Spec> specs = new LinkedHashMap<>();
        specs.put("megamek", new Spec("MegaMek.jar", "megamek.MegaMek", 4096));
        specs.put("mekhq", new Spec("MekHQ.jar", "mekhq.MekHQ", 4096));
        specs.put("lab", new Spec("MegaMekLab.jar", "megameklab.MegaMekLab", 1024));
        SPECS = java.util.Collections.unmodifiableMap(specs);
    }

    public Inspection inspect(Path requested) throws IOException {
        Path root = SafePath.directory(requested, "application root");
        Inspection.RootIdentity rootIdentity = rootIdentity(root);
        for (String required : List.of("data", "mmconf", "lib")) {
            Path directory = root.resolve(required);
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("unsupported layout: missing directory " + required);
            }
            SafePath.rejectLinks(directory);
        }
        if (Files.exists(root.resolve(".mm-launcher"), LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("application root contains reserved sandbox state .mm-launcher");
        }

        List<Product> products = new ArrayList<>();
        Set<String> ignoredMissingTransitive = new HashSet<>();
        for (Map.Entry<String, Spec> candidate : SPECS.entrySet()) {
            Optional<Path> candidateJar = SafePath.existingOrMissing(root,
                    candidate.getValue().jar(), "product jar");
            if (candidateJar.isEmpty()) {
                continue;
            }
            JarInfo info = inspectJar(root, candidate.getValue().jar(), new HashSet<>(), 0,
                    ignoredMissingTransitive);
            if (!candidate.getValue().mainClass().equals(info.mainClass())) {
                throw new IOException("inconsistent " + candidate.getValue().jar()
                        + " Main-Class: expected " + candidate.getValue().mainClass());
            }
            String build = version(root, candidate.getKey(), candidate.getValue().jar());
            products.add(new Product(candidate.getKey(), candidate.getValue().jar(),
                    candidate.getValue().mainClass(), build == null ? "unknown" : build,
                    info.classPath()));
        }
        if (products.isEmpty()) {
            throw new IOException("unsupported layout: no recognized MegaMek suite root jar");
        }
        if (Files.isRegularFile(root.resolve("MegaMek.jar"), LinkOption.NOFOLLOW_LINKS)
                && Files.isRegularFile(root.resolve("lib").resolve("MegaMek.jar"), LinkOption.NOFOLLOW_LINKS)) {
            String primary = version(root, "megamek", "MegaMek.jar");
            String dependency = version(root, "megamek", "lib/MegaMek.jar");
            if (primary != null && dependency != null && !primary.equals(dependency)) {
                throw new IOException("inconsistent build metadata between copies of MegaMek.jar");
            }
        }
        String primaryKey = products.stream().anyMatch(product -> product.key().equals("mekhq"))
                ? "mekhq" : products.stream().anyMatch(product -> product.key().equals("lab"))
                ? "lab" : "megamek";
        String build = products.stream().filter(product -> product.key().equals(primaryKey))
                .findFirst().orElseThrow(() -> new IOException("bundle primary product is unavailable")).build();
        String confidence = ignoredMissingTransitive.isEmpty()
                ? "recognized-packaging"
                : "recognized-packaging-optional-transitive-missing";
        Inspection.RootIdentity finalIdentity = rootIdentity(root);
        if (!rootIdentity.equals(finalIdentity)) {
            throw new IOException("application root changed during static inspection");
        }
        return new Inspection(root.toString(), products, build, confidence, rootIdentity);
    }

    private static Inspection.RootIdentity rootIdentity(Path root) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(root, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        return new Inspection.RootIdentity(attributes.fileKey(),
                attributes.creationTime().toMillis(), attributes.lastModifiedTime().toMillis());
    }

    private JarInfo inspectJar(Path root, String relative, Set<Path> seen, int depth,
                               Set<String> ignoredMissingTransitive) throws IOException {
        Optional<Path> resolved = SafePath.existingOrMissing(root, relative, "classpath jar");
        if (resolved.isEmpty()) {
            if (depth <= 1) {
                throw new IOException("classpath jar is missing: " + relative);
            }
            ignoredMissingTransitive.add(relative);
            return new JarInfo(null, List.of());
        }
        Path jar = resolved.get();
        if (!seen.add(jar)) {
            return new JarInfo(null, List.of());
        }
        Manifest manifest;
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            ZipEntry entry = zip.getEntry("META-INF/MANIFEST.MF");
            if (entry == null) {
                throw new IOException("jar has no manifest: " + relative);
            }
            manifest = new Manifest(new java.io.ByteArrayInputStream(readBounded(zip, entry)));
        } catch (ZipException e) {
            throw new IOException("invalid jar/zip: " + relative, e);
        }
        Attributes attributes = manifest.getMainAttributes();
        String main = attributes.getValue(Attributes.Name.MAIN_CLASS);
        List<String> classPath = parseClassPath(root, relative,
                attributes.getValue(Attributes.Name.CLASS_PATH));
        for (String dependency : classPath) {
            inspectJar(root, dependency, seen, depth + 1, ignoredMissingTransitive);
        }
        return new JarInfo(main, classPath);
    }

    private List<String> parseClassPath(Path root, String owner, String value) throws IOException {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        Path ownerParent = Path.of(owner).getParent();
        List<String> result = new ArrayList<>();
        for (String token : value.trim().split("\\s+")) {
            URI uri;
            try {
                uri = URI.create(token);
            } catch (IllegalArgumentException e) {
                throw new IOException("invalid Class-Path entry in " + owner + ": " + token);
            }
            if (uri.isAbsolute() || token.startsWith("/") || token.startsWith("\\")
                    || token.matches("^[A-Za-z]:.*") || token.contains("\\")
                    || token.contains("?") || token.contains("#") || token.contains("%")) {
                throw new IOException("unsafe Class-Path entry in " + owner + ": " + token);
            }
            Path relative = (ownerParent == null ? Path.of(token) : ownerParent.resolve(token))
                    .normalize();
            if (relative.isAbsolute() || relative.startsWith("..")) {
                throw new IOException("Class-Path escapes installation root in " + owner + ": "
                        + token);
            }
            String portable = relative.toString().replace('\\', '/');
            result.add(portable);
        }
        return List.copyOf(result);
    }

    private String version(Path root, String product, String primaryJar) throws IOException {
        try (ZipFile zip = new ZipFile(SafePath.existing(root, primaryJar, "product version jar").toFile())) {
            ZipEntry entry = zip.getEntry("META-INF/MANIFEST.MF");
            if (entry != null) {
                Manifest manifest = new Manifest(new java.io.ByteArrayInputStream(readBounded(zip, entry)));
                String own = manifest.getMainAttributes().getValue(Attributes.Name.IMPLEMENTATION_VERSION);
                if (own != null) {
                    if (own.isBlank()) throw new IOException("empty product Implementation-Version: " + primaryJar);
                    return own.trim();
                }
            }
        }
        List<String> candidates = new ArrayList<>();
        if (product.equals("megamek")) candidates.add(primaryJar);
        if (product.equals("mekhq")) candidates.add("MegaMek.jar");
        if (product.equals("lab")) candidates.add("lib/MegaMek.jar");
        for (String relative : candidates) {
            if (!Files.exists(root.resolve(relative), LinkOption.NOFOLLOW_LINKS)) continue;
            try (ZipFile zip = new ZipFile(SafePath.existing(root, relative, "version jar").toFile())) {
                Properties base = properties(zip, "megamek/Version.properties");
                if (base == null) base = properties(zip, "Version.properties");
                if (base == null) continue;
                String major = base.getProperty("major");
                String minor = base.getProperty("minor");
                String patch = base.getProperty("patch");
                if (major == null || minor == null || patch == null) continue;
                String result = major + "." + minor + "." + patch;
                String revision = base.getProperty("revision");
                if (revision != null && !revision.isBlank()) result += "-" + revision.trim();
                Properties extra = properties(zip, "megamek/extraVersion.properties");
                if (extra == null) extra = properties(zip, "extraVersion.properties");
                if (extra != null) {
                    String branch = extra.getProperty("branch");
                    String hash = extra.getProperty("gitHash");
                    if (branch != null && !branch.isBlank()) result += " branch=" + branch.trim();
                    if (hash != null && !hash.isBlank()) result += " git=" + hash.trim();
                }
                return result;
            }
        }
        return null;
    }

    private Properties properties(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) return null;
        Properties properties = new Properties();
        try (InputStream input = new java.io.ByteArrayInputStream(readBounded(zip, entry))) {
            properties.load(input);
        }
        return properties;
    }

    private byte[] readBounded(ZipFile zip, ZipEntry entry) throws IOException {
        if (entry.getSize() > MAX_ENTRY) throw new IOException("oversized jar metadata: " + entry);
        try (InputStream input = zip.getInputStream(entry);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (output.size() + count > MAX_ENTRY) {
                    throw new IOException("oversized jar metadata: " + entry);
                }
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    public static int heapMb(String product) {
        Spec spec = SPECS.get(product);
        if (spec == null) throw new IllegalArgumentException("unknown product: " + product);
        return spec.heapMb();
    }

    private record Spec(String jar, String mainClass, int heapMb) {}
    private record JarInfo(String mainClass, List<String> classPath) {}
}
