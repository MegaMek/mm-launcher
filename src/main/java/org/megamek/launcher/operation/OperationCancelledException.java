package org.megamek.launcher.operation;

public final class OperationCancelledException extends InterruptedException {
    public OperationCancelledException(String message) {
        super(message);
    }
}
