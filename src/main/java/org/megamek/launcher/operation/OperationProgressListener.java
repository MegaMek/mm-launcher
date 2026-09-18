package org.megamek.launcher.operation;

@FunctionalInterface
public interface OperationProgressListener {
    OperationProgressListener NONE = progress -> {
    };

    void onProgress(OperationProgress progress);

    static OperationProgressListener combine(OperationProgressListener first,
                                             OperationProgressListener second) {
        return progress -> {
            first.onProgress(progress);
            second.onProgress(progress);
        };
    }
}
