package org.megamek.launcher.manifest;

import java.util.List;

public record Manifest(
        int schemaVersion,
        String product,
        String packageId,
        String releaseId,
        List<String> protectedPaths,
        List<FileEntry> files) {
}
