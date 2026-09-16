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
    private final JavaRuntime javaRuntime;
    private final RootCoordinator coordinator;

    public ApplicationLauncher() {
        this(new DirectProcessRunner(), new RootCoordinator());
    }

    public ApplicationLauncher(ProcessRunner runner) {
        this(runner, RootCoordinator.inMemory());
    }

    public ApplicationLauncher(ProcessRunner runner, RootCoordinator coordinator) {
        this.runner = runner;
        this.javaRuntime = new JavaRuntime(runner);
        this.coordinator = coordinator;
    }

    public List<String> command(InstallationRecord record, String productKey)
            throws IOException, InterruptedException {
        if (record.javaExecutable() == null) {
            throw new IOException("no Java selected; run java-select explicitly");
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
        Path java = javaRuntime.resolve(record.javaExecutable());
        if (java.startsWith(root)) {
            throw new IOException("selected Java must be external to the application directory");
        }
        javaRuntime.validate(java, root);
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

    public int launch(InstallationRecord record, String product)
            throws IOException, InterruptedException {
        Path root = Path.of(record.canonicalRoot());
        try (RootCoordinator.Lease lease = coordinator.acquire(root, false)) {
            lease.requireNoPendingUpdate();
            List<String> command = command(record, product);
            lease.markLaunchStarting();
            try {
                ProcessRunner.Result result = runner.runTracked(command, root,
                        Duration.ofDays(30), true, identity -> {
                            try {
                                lease.markChild(identity);
                            } catch (IOException e) {
                                throw new LaunchMarkerFailure(e);
                            }
                        });
                if (result.timedOut()) {
                    throw new IOException("application exceeded launcher wait limit");
                }
                return result.exitCode();
            } catch (LaunchMarkerFailure e) {
                throw e.failure;
            } finally {
                lease.clearLaunchMarker();
            }
        }
    }

    private static final class LaunchMarkerFailure extends RuntimeException {
        private final IOException failure;
        private LaunchMarkerFailure(IOException failure) {
            super(failure);
            this.failure = failure;
        }
    }
}
