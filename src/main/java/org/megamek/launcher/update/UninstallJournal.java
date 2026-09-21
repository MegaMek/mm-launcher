package org.megamek.launcher.update;

import org.megamek.launcher.registry.InstallationRecord;

import java.util.List;

/**
 * Durable authority for one uninstall.  The registry removal is the commit barrier: a
 * COMMIT_AUTHORIZED journal plus an absent bound registration is committed, while every journal
 * whose registration still exists is rolled back.
 */
record UninstallJournal(
        int schemaVersion,
        String transactionId,
        String phase,
        String canonicalRegistry,
        InstallationRecord record,
        CurrentUpdateState current,
        String rootBackup,
        List<FileMove> files,
        List<MetadataMove> metadata,
        List<DirectoryState> removedDirectories,
        boolean rootRemovalStarted) {

    UninstallJournal {
        files = files == null ? null : List.copyOf(files);
        metadata = metadata == null ? null : List.copyOf(metadata);
        removedDirectories = removedDirectories == null
                ? null : List.copyOf(removedDirectories);
    }

    record FileMove(String relativePath, String expectedSha256, long expectedSize,
                    String state) {
    }

    record MetadataMove(String fileName, String sha256, long size, String state) {
    }

    record DirectoryState(String relativePath, long modifiedMillis) {
    }
}
