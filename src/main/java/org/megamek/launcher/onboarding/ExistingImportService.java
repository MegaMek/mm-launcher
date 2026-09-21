package org.megamek.launcher.onboarding;

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
 * <p>Preparation is read-only and statically inspects the selected application. Registration
 * delegates one locked record publication to {@link RegistryStore}. Java is launch-time state
 * and is deliberately not read or executed by either operation.
 */
public final class ExistingImportService {
    private final Path registry;
    private final RegistryStore registries;
    private final InstallationInspector inspector;

    public ExistingImportService(Path registry, RegistryStore registries,
                                 InstallationInspector inspector) {
        this.registry = registry.toAbsolutePath().normalize();
        this.registries = Objects.requireNonNull(registries);
        this.inspector = Objects.requireNonNull(inspector);
    }

    /** Performs no write, classloading, application execution, download, or registry mutation. */
    public Plan prepare(Path selectedRoot, OperationContext context)
            throws IOException, InterruptedException {
        requireContext(context);
        context.phase(OperationPhase.METADATA,
                "Statically inspecting the selected existing installation");
        Inspection inspection = inspector.inspect(selectedRoot);
        context.checkpoint();
        return new Plan(inspection);
    }

    /**
     * Revalidates the captured static inspection and publishes one launch-only record. A
     * concurrent registry default wins because Main selection is decided under the store lock.
     */
    public Result register(Plan plan, String name, OperationContext context)
            throws IOException, InterruptedException {
        if (plan == null) throw new IOException("a confirmed existing-copy plan is required");
        requireContext(context);
        context.phase(OperationPhase.VERIFY,
                "Revalidating the selected application");
        context.checkpoint();
        context.enterFinalization("Existing-copy registration has begun; cancellation can no "
                + "longer be performed safely.");
        context.phase(OperationPhase.PREPARE_INSTALL,
                "Atomically registering the confirmed launch-only copy");
        ensureRegistryParent();
        RegistryStore.ImportedRegistration registered = registries.registerImported(
                registry, name, plan.inspection());
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

    public static final class Plan {
        private final Inspection inspection;

        private Plan(Inspection inspection) {
            this.inspection = Objects.requireNonNull(inspection);
        }

        public Inspection inspection() {
            return inspection;
        }
    }

    public record Result(org.megamek.launcher.registry.InstallationRecord record,
                         boolean becameMain) {
    }
}
