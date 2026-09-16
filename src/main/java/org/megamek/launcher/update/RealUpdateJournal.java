package org.megamek.launcher.update;

import org.megamek.launcher.onboarding.Product;
import org.megamek.launcher.plan.Decision;
import org.megamek.launcher.registry.InstallationRecord;

import java.util.List;

record RealUpdateJournal(
        int schemaVersion,
        String transactionId,
        String canonicalRoot,
        String canonicalRegistry,
        String installationId,
        String registeredAt,
        String phase,
        boolean previousStatePublished,
        CurrentUpdateState previousState,
        CurrentUpdateState nextState,
        InstallationRecord expectedRecord,
        String targetObservedBuild,
        List<Product> targetProducts,
        List<Decision> decisions,
        List<Operation> operations,
        List<String> createdDirectories) {

    RealUpdateJournal {
        targetProducts = targetProducts == null ? null : List.copyOf(targetProducts);
        decisions = decisions == null ? null : List.copyOf(decisions);
        operations = operations == null ? null : List.copyOf(operations);
        createdDirectories = createdDirectories == null ? null : List.copyOf(createdDirectories);
    }

    record Operation(String action, String path, String beforeHash, String afterHash,
                     String backupPath, String stagedPath) {
    }
}
