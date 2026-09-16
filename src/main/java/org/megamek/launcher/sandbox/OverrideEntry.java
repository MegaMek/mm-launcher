package org.megamek.launcher.sandbox;

import java.util.List;

public record OverrideEntry(String path, Kind kind, List<String> officialHashes) {
    public enum Kind {
        MODIFIED,
        OBSOLETE_MODIFIED,
        COLLISION
    }
}
