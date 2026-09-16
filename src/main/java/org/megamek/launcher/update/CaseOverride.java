package org.megamek.launcher.update;

import java.util.List;

/**
 * A capitalization-only package transition which cannot safely be executed on every supported
 * filesystem.  The folded prefix remains sticky so later releases cannot accidentally traverse,
 * replace, or remove the locally retained spelling.
 */
public record CaseOverride(String foldedPrefix, String localPrefix,
                           List<String> officialSpellings) {
    public CaseOverride {
        officialSpellings = officialSpellings == null ? null : List.copyOf(officialSpellings);
    }
}
