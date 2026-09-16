package org.megamek.launcher.onboarding;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Optional;

final class SafePath {
    private SafePath() {}

    static Path directory(Path requested, String label) throws IOException {
        Path absolute = requested.toAbsolutePath().normalize();
        if (!Files.isDirectory(absolute, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(label + " is not a real directory: " + absolute);
        }
        rejectLinks(absolute);
        return absolute.toRealPath();
    }

    static Path existing(Path root, String relative, String label) throws IOException {
        return existingOrMissing(root, relative, label)
                .orElseThrow(() -> new IOException(label + " is missing: " + relative));
    }

    static Optional<Path> existingOrMissing(Path root, String relative, String label)
            throws IOException {
        Path result = root.resolve(relative.replace('/', java.io.File.separatorChar)).normalize();
        if (!result.startsWith(root)) {
            throw new IOException(label + " escapes installation root: " + relative);
        }
        BasicFileAttributes attributes;
        try {
            rejectLinks(result);
            attributes = Files.readAttributes(result, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return Optional.empty();
        }
        if (!attributes.isRegularFile()) {
            throw new IOException(label + " is not a regular file: " + relative);
        }
        Path real = result.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!real.startsWith(root)) {
            throw new IOException(label + " resolves outside installation root: " + relative);
        }
        return Optional.of(real);
    }

    static void rejectLinks(Path path) throws IOException {
        Path current = path.getRoot();
        for (Path part : path) {
            current = current == null ? part : current.resolve(part);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }
            BasicFileAttributes attrs = Files.readAttributes(current, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (attrs.isSymbolicLink() || attrs.isOther()
                    || isWindowsReparsePoint(current)) {
                throw new IOException("links, reparse points, and special paths are not allowed: "
                        + current);
            }
        }
    }

    private static boolean isWindowsReparsePoint(Path path) throws IOException {
        try {
            Object value = Files.getAttribute(path, "dos:attributes",
                    LinkOption.NOFOLLOW_LINKS);
            return value instanceof Integer attributes && (attributes & 0x400) != 0;
        } catch (UnsupportedOperationException | IllegalArgumentException ignored) {
            return false;
        }
    }
}
