package org.megamek.launcher.sandbox;

import org.megamek.launcher.manifest.Manifest;

import java.util.List;

public record SandboxState(
        int schemaVersion,
        String sandboxId,
        String canonicalRoot,
        String lastTransactionId,
        Manifest officialManifest,
        List<OverrideEntry> overrides) {
}
