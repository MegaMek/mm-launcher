package org.megamek.launcher.update;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/** Shared boundary check which rejects links/special objects in every existing path component. */
public final class StrictPathSafety {
    private StrictPathSafety() {
    }

    public static Path requireDirectory(Path requested, String label) throws IOException {
        return require(requested, true, label);
    }

    public static Path requireFile(Path requested, String label) throws IOException {
        return require(requested, false, label);
    }

    private static Path require(Path requested, boolean directory, String label) throws IOException {
        Path absolute = requested.toAbsolutePath().normalize();
        Path cursor = absolute.getRoot();
        for (Path part : absolute) {
            cursor = cursor == null ? part : cursor.resolve(part);
            BasicFileAttributes attrs = Files.readAttributes(cursor, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (attrs.isSymbolicLink() || attrs.isOther()) {
                throw new IOException(label + " has a linked, reparse, or special ancestor: " + cursor);
            }
        }
        BasicFileAttributes attrs = Files.readAttributes(absolute, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (directory ? !attrs.isDirectory() : !attrs.isRegularFile()) {
            throw new IOException(label + " has an unexpected type: " + absolute);
        }
        Path followed = absolute.toRealPath();
        if (!absolute.equals(followed)) {
            throw new IOException(label + " is noncanonical or traverses a link: " + absolute);
        }
        return absolute;
    }
}
