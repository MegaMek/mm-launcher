package org.megamek.launcher.operation;

public enum OperationPhase {
    METADATA("Reading metadata"),
    DOWNLOAD("Downloading package"),
    VERIFY("Verifying package"),
    EXTRACT("Extracting package"),
    PLAN("Planning changes"),
    AWAIT_CONSENT("Awaiting confirmation"),
    PREPARE_INSTALL("Preparing installation"),
    APPLY("Applying update"),
    RECOVER("Recovering update"),
    CLEANUP("Cleaning temporary files"),
    FINAL("Finished");

    private final String displayName;

    OperationPhase(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}
