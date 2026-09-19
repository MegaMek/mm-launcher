package org.megamek.launcher.registry;

import java.util.List;
import java.util.Map;

public record RegistryData(int schemaVersion, String defaultInstallationId,
                           Map<String, String> preferredInstallationIds,
                           List<InstallationRecord> installations) {
    /**
     * Source compatibility for callers which still construct the schema-1 shape.  RegistryStore
     * performs the product-aware legacy migration when it reads schema 1 from disk; an in-memory
     * value created through this constructor intentionally starts with no explicit preferences.
     */
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
