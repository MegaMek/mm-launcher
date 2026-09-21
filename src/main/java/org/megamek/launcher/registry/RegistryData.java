package org.megamek.launcher.registry;

import java.util.List;
import java.util.Map;

public record RegistryData(int schemaVersion, String defaultInstallationId,
                           Map<String, String> preferredInstallationIds,
                           List<InstallationRecord> installations) {
    /** Convenience constructor for a registry with no explicit per-application preferences. */
    public RegistryData(int schemaVersion, String defaultInstallationId,
                        List<InstallationRecord> installations) {
        this(schemaVersion, defaultInstallationId, Map.of(), installations);
    }

    public RegistryData {
        preferredInstallationIds = preferredInstallationIds == null
                ? null : Map.copyOf(preferredInstallationIds);
        installations = installations == null ? null : List.copyOf(installations);
    }
}
