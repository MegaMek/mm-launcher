package org.megamek.launcher.update;

import org.megamek.launcher.manifest.FileEntry;
import org.megamek.launcher.manifest.Manifest;
import org.megamek.launcher.manifest.ManifestException;
import org.megamek.launcher.manifest.ManifestReader;
import org.megamek.launcher.release.OfficialRepository;
import org.megamek.launcher.release.SafeTarExtractor;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Versioned conservative definition of files the launcher may regard as package-owned. */
public final class OwnershipPolicy {
    public static final int VERSION = 1;
    public static final List<String> PROTECTED_PATHS = List.of(
            "campaigns", "saves", "userdata", "custom", "mmconf", "logs", "backups",
            "data/campaigns", "data/saves", "data/userdata", "data/custom", "data/logs",
            "data/backups");
    private static final Set<String> ROOT_BINARIES = Set.of(
            "megamek.jar", "megamek.exe", "megamek.sh",
            "mekhq.jar", "mekhq.exe", "mekhq.sh",
            "megameklab.jar", "megameklab.exe", "megameklab.sh");

    public Build build(Path root, OfficialRepository repository, String releaseId)
            throws IOException, ManifestException {
        Path canonical = requireRoot(root);
        List<FileEntry> managed = new ArrayList<>();
        List<String> excluded = new ArrayList<>();
        try (var walk = Files.walk(canonical)) {
            List<Path> paths = walk.skip(1).sorted(Comparator
                    .comparing((Path path) -> portable(canonical.relativize(path))
                            .toLowerCase(Locale.ROOT))
                    .thenComparing(path -> portable(canonical.relativize(path)))).toList();
            if (paths.size() > SafeTarExtractor.MAX_ENTRIES) {
                throw new IOException("package inventory exceeds entry limit");
            }
            String previousKey = null;
            for (Path path : paths) {
                BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (attrs.isSymbolicLink() || attrs.isOther()) {
                    throw new IOException("package inventory contains a link or special entry: " + path);
                }
                String relative = portable(canonical.relativize(path));
                String key = Normalizer.normalize(relative, Normalizer.Form.NFC)
                        .toLowerCase(Locale.ROOT);
                if (key.equals(previousKey)) {
                    throw new IOException("package inventory contains a case/Unicode alias: " + relative);
                }
                previousKey = key;
                if (!attrs.isRegularFile()) continue;
                if (managed(relative)) {
                    managed.add(new FileEntry(relative, sha256(path)));
                } else {
                    excluded.add(relative);
                }
            }
        }
        Manifest manifest = new Manifest(ManifestReader.SCHEMA_VERSION, repository.key(),
                repository.key() + "-official-package", releaseId, PROTECTED_PATHS,
                List.copyOf(managed));
        new ManifestReader().validateInMemory(manifest, "official package inventory");
        return new Build(manifest, List.copyOf(excluded));
    }

    static boolean managed(String path) {
        String key = path.toLowerCase(Locale.ROOT);
        if (protectedPath(key)) return false;
        int slash = key.indexOf('/');
        if (slash < 0) return ROOT_BINARIES.contains(key);
        String root = key.substring(0, slash);
        return root.equals("lib") || root.equals("docs") || root.equals("licenses")
                || root.equals("data");
    }

    /** Runtime files are an all-or-nothing safety boundary for real updates. */
    public static boolean runtimePath(String path) {
        String key = path.toLowerCase(Locale.ROOT);
        int slash = key.indexOf('/');
        if (slash < 0) {
            return key.endsWith(".jar") || key.endsWith(".exe") || key.endsWith(".sh");
        }
        return key.substring(0, slash).equals("lib");
    }

    private static boolean protectedPath(String key) {
        return PROTECTED_PATHS.stream().anyMatch(protectedPath ->
                key.equals(protectedPath) || key.startsWith(protectedPath + "/"));
    }

    private static Path requireRoot(Path root) throws IOException {
        Path absolute = root.toAbsolutePath().normalize();
        BasicFileAttributes attrs = Files.readAttributes(
                absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isDirectory() || attrs.isSymbolicLink() || attrs.isOther()) {
            throw new IOException("package root must be a real directory: " + absolute);
        }
        return absolute.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }

    private static String portable(Path path) {
        return path.toString().replace('\\', '/');
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
                input.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record Build(Manifest manifest, List<String> excludedPaths) {
    }
}
