package org.megamek.launcher.registry;

import java.util.List;

public record RegistryData(int schemaVersion, String defaultInstallationId,
                           List<InstallationRecord> installations) {
    public RegistryData {
        installations = installations == null ? null : List.copyOf(installations);
    }
}
