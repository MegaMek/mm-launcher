package org.megamek.launcher.launch;

import org.megamek.launcher.onboarding.Inspection;
import org.megamek.launcher.onboarding.InstallationInspector;
import org.megamek.launcher.onboarding.Product;
import org.megamek.launcher.registry.InstallationRecord;

import java.io.IOException;
import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class ApplicationLauncher {
    private final ProcessRunner runner;
    private final RootCoordinator coordinator;

    public ApplicationLauncher() {
        this(new DirectProcessRunner(), new RootCoordinator());
    }

    public ApplicationLauncher(ProcessRunner runner) {
        this(runner, RootCoordinator.inMemory());
    }

    public ApplicationLauncher(ProcessRunner runner, RootCoordinator coordinator) {
        this.runner = runner;
        this.coordinator = coordinator;
    }

    public List<String> command(InstallationRecord record, String productKey,
                                JavaRuntime.CurrentJava gameJava)
            throws IOException, InterruptedException {
        if (gameJava == null) {
            throw new IOException("effective Game Java was not resolved");
        }
        Path root = Path.of(record.canonicalRoot());
        Inspection current = new InstallationInspector().inspect(root);
        if (!current.canonicalRoot().equals(record.canonicalRoot())) {
            throw new IOException("registered installation moved or root identity changed");
        }
        if (!current.observedBuild().equals(record.observedBuild())) {
            throw new IOException("registered installation build changed: expected "
                    + record.observedBuild() + ", observed " + current.observedBuild());
        }
        Product product = current.products().stream()
                .filter(item -> item.key().equals(productKey)).findFirst()
                .orElseThrow(() -> new IOException("product is unavailable in current layout: "
                        + productKey));
        Product registered = record.products().stream()
                .filter(item -> item.key().equals(productKey)).findFirst()
                .orElseThrow(() -> new IOException("product was not registered: " + productKey));
        if (!product.equals(registered)) {
            throw new IOException("registered product layout changed; inspect and register again");
        }
        Path java = gameJava.requireUnchanged();
        if (java.startsWith(root)) {
            throw new IOException("Game Java must be external to the application directory");
        }
        List<String> command = new ArrayList<>();
        command.add(java.toString());
        command.add("-Xmx" + InstallationInspector.heapMb(productKey) + "m");
        command.add("--add-opens=java.base/java.util=ALL-UNNAMED");
        command.add("--add-opens=java.base/java.util.concurrent=ALL-UNNAMED");
        command.add("-Dsun.awt.disablegrab=true");
        command.add("-cp");
        List<String> jars = new ArrayList<>();
        jars.add(product.jar());
        jars.addAll(product.classPath());
        command.add(String.join(File.pathSeparator, jars));
        command.add(product.mainClass());
        return List.copyOf(command);
    }

    public int launch(InstallationRecord record, String product,
                      JavaRuntime.CurrentJava gameJava)
            throws IOException, InterruptedException {
        Path root = Path.of(record.canonicalRoot());
        try (RootCoordinator.Lease lease = coordinator.acquire(root, false)) {
            lease.requireNoPendingUpdate();
            List<String> command = command(record, product, gameJava);
            lease.markLaunchStarting();
            boolean childCompleted = false;
            try {
                java.util.concurrent.atomic.AtomicReference<IOException> markerFailure =
                        new java.util.concurrent.atomic.AtomicReference<>();
                ProcessRunner.Result result = runner.runTracked(command, root,
                        Duration.ofMillis(Long.MAX_VALUE), true, identity -> {
                            try {
                                lease.markChild(identity);
                            } catch (IOException e) {
                                markerFailure.compareAndSet(null, e);
                            }
                        });
                childCompleted = true;
                if (markerFailure.get() != null) throw markerFailure.get();
                if (result.timedOut()) {
                    throw new IOException("application exceeded launcher wait limit");
                }
                return result.exitCode();
            } finally {
                // Any runner failure after STARTING is ambiguous: ProcessBuilder.start may have
                // returned just before callback publication failed or the launcher was
                // interrupted. Keep STARTING/RUNNING unless the child wait completed.
                if (childCompleted) lease.clearLaunchMarker();
            }
        }
    }
}
