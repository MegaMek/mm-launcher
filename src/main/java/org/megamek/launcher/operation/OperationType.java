package org.megamek.launcher.operation;

public enum OperationType {
    FRESH_INSTALL("Fresh install"),
    IMPORT_EXISTING("Import existing installation"),
    ADOPT_EXISTING("Enable managed updates"),
    UPDATE_PREVIEW("Update preview"),
    UPDATE_APPLY("Update"),
    RECOVERY("Update recovery"),
    GUI_ERROR("Launcher error");

    private final String displayName;

    OperationType(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
