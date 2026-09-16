package org.megamek.launcher.update;

record PendingUpdate(int schemaVersion, String transactionId, String installationId,
                     String canonicalRoot, String canonicalRegistry, String registeredAt) {
}
