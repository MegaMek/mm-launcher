package org.megamek.launcher.update;

import org.megamek.launcher.manifest.ManifestException;
import org.megamek.launcher.manifest.ManifestReader;
import org.megamek.launcher.operation.OperationContext;
import org.megamek.launcher.operation.OperationType;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;

/** Narrow real-update filesystem primitives; no sandbox marker or acknowledgement is bypassed. */
final class RealUpdateFiles {
    private RealUpdateFiles() {
    }

    static Path root(Path root) throws IOException {
        return StrictPathSafety.requireDirectory(root, "real update root");
    }

    static Path resolve(Path root, String portable, boolean allowMissing)
            throws IOException, ManifestException {
        ManifestReader.validatePortablePath(portable, "real update path", "transaction");
        Path current = root;
        String[] segments = portable.split("/");
        for (int i = 0; i < segments.length; i++) {
            rejectCaseAlias(current, segments[i], portable);
            current = current.resolve(segments[i]).normalize();
            if (!current.startsWith(root)) {
                throw new ManifestException("transaction path escapes application root: " + portable);
            }
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (allowMissing) continue;
                throw new IOException("required transaction path is absent: " + portable);
            }
            BasicFileAttributes attrs = Files.readAttributes(current, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (attrs.isSymbolicLink() || attrs.isOther()) {
                throw new ManifestException("link/reparse point refused at: " + portable);
            }
            if (i < segments.length - 1 && !attrs.isDirectory()) {
                throw new ManifestException("non-directory parent at: " + portable);
            }
        }
        return current;
    }

    static String hash(Path file) throws IOException, ManifestException {
        try {
            return hash(file, OperationContext.none(OperationType.UPDATE_APPLY));
        } catch (InterruptedException impossible) {
            Thread.currentThread().interrupt();
            throw new IOException("update file hash was interrupted", impossible);
        }
    }

    static String hash(Path file, OperationContext context)
            throws IOException, ManifestException, InterruptedException {
        BasicFileAttributes before = Files.readAttributes(file, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.isSymbolicLink() || before.isOther()) {
            throw new ManifestException("expected a regular update file: " + file);
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new DigestInputStream(Files.newInputStream(file), digest)) {
                byte[] buffer = new byte[128 * 1024];
                while (true) {
                    context.checkpoint();
                    int read = input.read(buffer);
                    if (read < 0) break;
                }
            }
            BasicFileAttributes after = Files.readAttributes(file, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (before.size() != after.size()
                    || !before.lastModifiedTime().equals(after.lastModifiedTime())
                    || !java.util.Objects.equals(before.fileKey(), after.fileKey())) {
                throw new ManifestException("file changed while validating: " + file);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hashIfRegular(Path file) throws IOException, ManifestException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
        return hash(file);
    }

    static void copyDurable(Path source, Path destination) throws IOException {
        Files.copy(source, destination, StandardCopyOption.COPY_ATTRIBUTES);
        try (FileChannel channel = FileChannel.open(destination, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    static void atomicReplace(Path staged, Path destination) throws IOException {
        try {
            Files.move(staged, destination, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            throw new IOException("atomic same-volume application replacement is unsupported", e);
        }
    }

    static void deleteOwnedTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (attrs.isSymbolicLink() || attrs.isOther()) {
                    throw new IOException("refusing cleanup through unexpected update link: " + path);
                }
                Files.delete(path);
            }
        }
    }

    static boolean emptyDirectory(Path path) throws IOException {
        if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(path)) return false;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(path)) {
            return !stream.iterator().hasNext();
        }
    }

    private static void rejectCaseAlias(Path parent, String expected, String portable)
            throws IOException, ManifestException {
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) return;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(parent)) {
            for (Path entry : stream) {
                String actual = entry.getFileName().toString();
                if (actual.equalsIgnoreCase(expected) && !actual.equals(expected)) {
                    throw new ManifestException("local case alias collision for " + portable);
                }
            }
        }
    }
}
