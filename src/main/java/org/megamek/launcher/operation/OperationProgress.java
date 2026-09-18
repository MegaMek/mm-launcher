package org.megamek.launcher.operation;

import java.time.Instant;
import java.util.UUID;

public record OperationProgress(
        UUID operationId,
        Instant timestamp,
        OperationType operationType,
        OperationPhase phase,
        long completed,
        long total,
        ProgressUnit unit,
        String detail,
        OperationOutcome outcome,
        boolean cancellationAllowed,
        String cancellationReason) {

    public boolean determinate() {
        return total > 0 && completed >= 0 && completed <= total;
    }
}
