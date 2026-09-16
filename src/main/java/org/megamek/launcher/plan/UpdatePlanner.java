package org.megamek.launcher.plan;

import org.megamek.launcher.manifest.FileEntry;
import org.megamek.launcher.manifest.Manifest;
import org.megamek.launcher.manifest.ManifestException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.Set;

public final class UpdatePlanner {
    public List<Decision> plan(Manifest baseline, Manifest target, Path installation)
            throws IOException, ManifestException {
        return plan(baseline, target, installation, Map.of());
    }

    public List<Decision> plan(Manifest baseline, Manifest target, Path installation,
                               Map<String, Set<String>> trustedPreviousOfficialHashes)
            throws IOException, ManifestException {
        Path root = validateRoot(installation);
        Map<String, FileEntry> before = index(baseline);
        Map<String, FileEntry> after = index(target);
        TreeSet<String> paths = new TreeSet<>(Comparator.comparing(
                        (String value) -> value.toLowerCase(Locale.ROOT))
                .thenComparing(Comparator.naturalOrder()));
        before.values().forEach(entry -> paths.add(entry.path()));
        after.values().forEach(entry -> paths.add(entry.path()));

        List<Decision> decisions = new ArrayList<>();
        for (String portablePath : paths) {
            FileEntry oldEntry = before.get(key(portablePath));
            FileEntry newEntry = after.get(key(portablePath));
            PathState state = inspect(root, portablePath);
            decisions.add(decide(portablePath, oldEntry, newEntry, state,
                    trustedPreviousOfficialHashes.getOrDefault(key(portablePath), Set.of())));
        }
        return List.copyOf(decisions);
    }

    private static Decision decide(String path, FileEntry oldEntry, FileEntry newEntry, PathState state,
                                   Set<String> trustedPreviousOfficialHashes)
            throws IOException {
        if (newEntry != null) {
            if (!state.exists()) {
                return new Decision(Action.ADD, path, oldEntry == null
                        ? "target managed file is missing"
                        : "previously managed target file is missing");
            }
            if (!state.regularFile()) {
                return new Decision(Action.SKIP, path, "target path collides with a non-regular local entry");
            }
            String localHash = sha256(state.path());
            if (localHash.equals(newEntry.sha256())) {
                return new Decision(Action.KEEP, path, "local file already matches target SHA-256");
            }
            if (trustedPreviousOfficialHashes.contains(localHash)) {
                return new Decision(Action.REPLACE, path,
                        "local file matches a previously official override ancestor");
            }
            if (oldEntry == null) {
                return new Decision(Action.SKIP, path, "target collides with an untracked local file");
            }
            if (localHash.equals(oldEntry.sha256())) {
                return new Decision(Action.REPLACE, path, "local file matches baseline; target differs");
            }
            return new Decision(Action.SKIP, path, "local file differs from both baseline and target");
        }

        if (!state.exists()) {
            return new Decision(Action.KEEP, path, "obsolete managed file is already absent");
        }
        if (!state.regularFile()) {
            return new Decision(Action.SKIP, path, "obsolete path is a non-regular local entry");
        }
        String localHash = sha256(state.path());
        if (localHash.equals(oldEntry.sha256())) {
            return new Decision(Action.REMOVE, path, "obsolete local file matches baseline");
        }
        if (trustedPreviousOfficialHashes.contains(localHash)) {
            return new Decision(Action.REMOVE, path,
                    "obsolete local file matches a previously official override ancestor");
        }
        return new Decision(Action.SKIP, path, "obsolete local file was modified");
    }

    private static Path validateRoot(Path installation) throws IOException, ManifestException {
        Path absolute = installation.toAbsolutePath().normalize();
        if (!Files.exists(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new ManifestException("installation directory does not exist: " + absolute);
        }
        BasicFileAttributes attributes = Files.readAttributes(
                absolute, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()) {
            throw new ManifestException("installation root must be a real directory, not a link/reparse point: "
                    + absolute);
        }
        return absolute.toRealPath(LinkOption.NOFOLLOW_LINKS);
    }

    private static PathState inspect(Path root, String portablePath) throws IOException, ManifestException {
        Path current = root;
        String[] segments = portablePath.split("/");
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            verifyNoCaseAlias(current, segment, portablePath);
            current = current.resolve(segment).normalize();
            if (!current.startsWith(root)) {
                throw new ManifestException("path escapes installation root: " + portablePath);
            }
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                return new PathState(current, false, false);
            }
            BasicFileAttributes attributes = Files.readAttributes(
                    current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (attributes.isSymbolicLink() || attributes.isOther()) {
                throw new ManifestException("link/reparse escape refused at managed path: " + portablePath);
            }
            if (i < segments.length - 1 && !attributes.isDirectory()) {
                return new PathState(current, true, false);
            }
        }
        return new PathState(current, true, Files.isRegularFile(current, LinkOption.NOFOLLOW_LINKS));
    }

    private static void verifyNoCaseAlias(Path parent, String expected, String portablePath)
            throws IOException, ManifestException {
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(parent)) {
            for (Path entry : entries) {
                String actual = entry.getFileName().toString();
                if (actual.equalsIgnoreCase(expected) && !actual.equals(expected)) {
                    throw new ManifestException("local case alias collision for " + portablePath
                            + ": found component " + actual + ", expected " + expected);
                }
            }
        }
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new DigestInputStream(Files.newInputStream(path), digest)) {
                input.transferTo(OutputStreamSink.INSTANCE);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java runtime lacks SHA-256", impossible);
        }
    }

    private static Map<String, FileEntry> index(Manifest manifest) {
        Map<String, FileEntry> result = new HashMap<>();
        manifest.files().forEach(entry -> result.put(key(entry.path()), entry));
        return result;
    }

    private static String key(String path) {
        return path.toLowerCase(Locale.ROOT);
    }

    private record PathState(Path path, boolean exists, boolean regularFile) {
    }

    private static final class OutputStreamSink extends java.io.OutputStream {
        private static final OutputStreamSink INSTANCE = new OutputStreamSink();

        @Override
        public void write(int ignored) {
        }

        @Override
        public void write(byte[] ignored, int offset, int length) {
        }
    }
}
