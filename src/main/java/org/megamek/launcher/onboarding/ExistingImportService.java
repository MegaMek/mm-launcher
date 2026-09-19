package org.megamek.launcher.onboarding;

import org.megamek.launcher.launch.JavaRuntime;
import org.megamek.launcher.operation.OperationContext;
import org.megamek.launcher.operation.OperationPhase;
import org.megamek.launcher.registry.RegistryStore;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Shared backend for every GUI existing-copy entry point.
 *
 * <p>Preparation is read-only: it statically inspects the selected application and validates only
 * the exact Java runtime which started MM Launcher. Registration revalidates that runtime before
 * the cancellation cutoff, then delegates one locked record publication to {@link RegistryStore}.
 */
public final class ExistingImportService {
    private final Path registry;
    private final RegistryStore registries;
    private final InstallationInspector inspector;
    private final JavaRuntime javaRuntime;

    public ExistingImportService(Path registry, RegistryStore registries,
                                 InstallationInspector inspector, JavaRuntime javaRuntime) {
        this.registry = registry.toAbsolutePath().normalize();
        this.registries = Objects.requireNonNull(registries);
        this.inspector = Objects.requireNonNull(inspector);
        this.javaRuntime = Objects.requireNonNull(javaRuntime);
    }

    /** Performs no write, classloading, application execution, download, or registry mutation. */
    public Plan prepare(Path selectedRoot, OperationContext context)
            throws IOException, InterruptedException {
        return prepare(selectedRoot, null, context);
    }

    public Plan prepare(Path selectedRoot, Path selectedJava, OperationContext context)
            throws IOException, InterruptedException {
        requireContext(context);
        context.phase(OperationPhase.METADATA,
                "Statically inspecting the selected existing installation");
        Inspection inspection = inspector.inspect(selectedRoot);
        context.phase(OperationPhase.VERIFY,
                "Validating the default game Java runtime");
        JavaRuntime.CurrentJava java = selectedJava == null
                ? javaRuntime.validateCurrentExternal(Path.of(inspection.canonicalRoot()))
                : javaRuntime.validateExternal(selectedJava,
                Path.of(inspection.canonicalRoot()));
        context.checkpoint();
        return new Plan(inspection, java);
    }

    /**
     * Revalidates the current JVM before finalization and publishes exactly one launch-only record.
     * A concurrent registry default wins because Main selection is decided under the store lock.
     */
    public Result register(Plan plan, String name, OperationContext context)
            throws IOException, InterruptedException {
        if (plan == null) throw new IOException("a confirmed existing-copy plan is required");
        requireContext(context);
        context.phase(OperationPhase.VERIFY,
                "Revalidating the launcher Java and selected application");
        JavaRuntime.CurrentJava current = javaRuntime.validateExternal(
                plan.java().executable(), Path.of(plan.inspection().canonicalRoot()));
        if (!plan.java().sameRuntime(current)) {
            throw new IOException("the selected default game Java changed; inspect and "
                    + "confirm the existing copy again");
        }
        context.checkpoint();
        context.enterFinalization("Existing-copy registration has begun; cancellation can no "
                + "longer be performed safely.");
        context.phase(OperationPhase.PREPARE_INSTALL,
                "Atomically registering the confirmed launch-only copy");
        ensureRegistryParent();
        RegistryStore.ImportedRegistration registered = registries.registerImported(
                registry, name, plan.inspection(), current);
        return new Result(registered.record(), registered.becameMain());
    }

    private void ensureRegistryParent() throws IOException {
        Path parent = registry.getParent();
        if (parent == null) throw new IOException("registry has no parent directory: " + registry);
        if (Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)
                    || Files.isSymbolicLink(parent)) {
                throw new IOException("registry parent must be a real directory: " + parent);
            }
            return;
        }
        Path grandparent = parent.getParent();
        if (grandparent == null || !Files.isDirectory(grandparent, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(grandparent)) {
            throw new IOException("registry parent cannot be created safely; its parent must "
                    + "already be a real directory: " + grandparent);
        }
        Files.createDirectory(parent);
    }

    private static void requireContext(OperationContext context) throws IOException {
        if (context == null) throw new IOException("operation context is required");
    }

    public record Plan(Inspection inspection, JavaRuntime.CurrentJava java) {
        public Plan {
            Objects.requireNonNull(inspection);
            Objects.requireNonNull(java);
        }

        public int javaFeature() {
            return java.feature();
        }
    }

    public record Result(org.megamek.launcher.registry.InstallationRecord record,
                         boolean becameMain) {
    }
}
