package org.megamek.launcher.sandbox;

import java.util.List;

public record Journal(
        int schemaVersion,
        String transactionId,
        Phase phase,
        SandboxState previousState,
        SandboxState nextState,
        List<Operation> operations,
        List<String> createdDirectories) {
    public enum Phase {
        PREPARED,
        MUTATING,
        STATE_COMMITTED,
        CLEANUP
    }

    public record Operation(
            String action,
            String path,
            String beforeHash,
            String afterHash,
            String backupPath,
            boolean started,
            boolean completed) {
        public Operation markStarted() {
            return new Operation(action, path, beforeHash, afterHash, backupPath, true, completed);
        }

        public Operation markCompleted() {
            return new Operation(action, path, beforeHash, afterHash, backupPath, true, true);
        }
    }
}
