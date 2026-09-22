package org.megamek.launcher.update;

import org.megamek.launcher.manifest.FileEntry;
import org.megamek.launcher.manifest.Manifest;
import org.megamek.launcher.manifest.ManifestException;
import org.megamek.launcher.manifest.ManifestReader;
import org.megamek.launcher.operation.OperationContext;
import org.megamek.launcher.operation.OperationPhase;
import org.megamek.launcher.operation.OperationType;
import org.megamek.launcher.operation.ProgressUnit;
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
        try {
            return build(root, repository, releaseId,
                    OperationContext.none(OperationType.UPDATE_PREVIEW), OperationPhase.PLAN);
        } catch (InterruptedException impossible) {
            Thread.currentThread().interrupt();
            throw new IOException("package inventory was interrupted", impossible);
        }
    }

    public Build build(Path root, OfficialRepository repository, String releaseId,
                       OperationContext context, OperationPhase phase)
            throws IOException, ManifestException, InterruptedException {
        Path canonical = requireRoot(root);
        List<FileEntry> managed = new ArrayList<>();
        List<String> excluded = new ArrayList<>();
        List<Path> paths = new ArrayList<>();
        try (var walk = Files.walk(canonical)) {
            var iterator = walk.skip(1).iterator();
            while (iterator.hasNext()) {
                context.checkpoint();
                paths.add(iterator.next());
                if (paths.size() > SafeTarExtractor.MAX_ENTRIES) {
                    throw new IOException("package inventory exceeds entry limit");
                }
            }
        }
        paths.sort(Comparator
                .comparing((Path path) -> portable(canonical.relativize(path))
                        .toLowerCase(Locale.ROOT))
                .thenComparing(path -> portable(canonical.relativize(path))));
        String previousKey = null;
        int completed = 0;
        for (Path path : paths) {
            context.checkpoint();
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
            if (attrs.isRegularFile()) {
                if (managed(relative)) {
                    managed.add(new FileEntry(relative, sha256(path, context)));
                } else {
                    excluded.add(relative);
                }
            }
            context.progress(phase, ++completed, paths.size(), ProgressUnit.FILES,
                    "Inventoried " + completed + " of " + paths.size() + " package entries");
        }
        return finish(repository, releaseId, managed, excluded);
    }

    /**
     * Builds ownership from a full package snapshot without reopening or re-hashing its files.
     */
    Build build(RootSnapshot snapshot, OfficialRepository repository, String releaseId)
            throws ManifestException {
        List<FileEntry> managed = new ArrayList<>();
        List<String> excluded = new ArrayList<>();
        for (EntryEvidence entry : snapshot.entries()) {
            if (!entry.regularFile()) continue;
            if (managed(entry.path())) {
                managed.add(new FileEntry(entry.path(), entry.sha256()));
            } else {
                excluded.add(entry.path());
            }
        }
        return finish(repository, releaseId, managed, excluded);
    }

    private static Build finish(OfficialRepository repository, String releaseId,
                                List<FileEntry> managed, List<String> excluded)
            throws ManifestException {
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

    private static String sha256(Path path, OperationContext context)
            throws IOException, InterruptedException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
                byte[] buffer = new byte[128 * 1024];
                while (true) {
                    context.checkpoint();
                    int read = input.read(buffer);
                    if (read < 0) break;
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record Build(Manifest manifest, List<String> excludedPaths) {
    }
}
