package org.megamek.launcher.release;

import org.megamek.launcher.onboarding.Inspection;
import org.megamek.launcher.manifest.ManifestException;
import org.megamek.launcher.onboarding.InstallationInspector;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryData;
import org.megamek.launcher.registry.RegistryStore;
import org.megamek.launcher.update.OwnershipPolicy;
import org.megamek.launcher.update.OwnershipReceipt;
import org.megamek.launcher.update.ReceiptStore;
import org.megamek.launcher.update.StrictPathSafety;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

public final class FreshInstaller {
    public static final long MAX_DOWNLOAD = 2L * 1024 * 1024 * 1024;
    private final ReleaseTransport transport;
    private final RegistryStore registryStore;
    private final InstallationInspector inspector;
    private final SafeTarExtractor extractor;
    private final ReceiptStore receiptStore;

    public FreshInstaller(ReleaseTransport transport) {
        this(transport, new RegistryStore(), new InstallationInspector(), new SafeTarExtractor());
    }

    FreshInstaller(ReleaseTransport transport, RegistryStore registryStore,
                   InstallationInspector inspector, SafeTarExtractor extractor) {
        this(transport, registryStore, inspector, extractor, new ReceiptStore());
    }

    FreshInstaller(ReleaseTransport transport, RegistryStore registryStore,
                   InstallationInspector inspector, SafeTarExtractor extractor,
                   ReceiptStore receiptStore) {
        this.transport = transport;
        this.registryStore = registryStore;
        this.inspector = inspector;
        this.extractor = extractor;
        this.receiptStore = receiptStore;
    }

    public Result install(OfficialRepository repository, String tag, Path destination,
                          Path registry, String name, PrintStream progress)
            throws IOException, InterruptedException {
        Path target = destination.toAbsolutePath().normalize();
        Path registryPath = registry.toAbsolutePath().normalize();
        Path parent = requireSafeParent(target);
        preflight(target, registryPath, name);

        VerifiedPackageFetcher fetcher = new VerifiedPackageFetcher(transport, extractor);
        try (VerifiedPackageFetcher.Workspace workspace = fetcher.fetch(repository, tag, parent,
                ".mm-launcher-install-", progress)) {
            ReleaseCatalog.Release release = workspace.release();
            ReleaseCatalog.Asset asset = workspace.asset();
            Path extracted = workspace.extracted();
            Inspection inspection = inspector.inspect(extracted);
            if (inspection.products().stream()
                    .noneMatch(product -> product.key().equals(repository.requiredProduct()))) {
                throw new IOException("downloaded package does not contain selected application "
                        + repository.requiredProduct());
            }
            OwnershipPolicy.Build ownership;
            try {
                ownership = new OwnershipPolicy().build(extracted, repository, release.tag());
            } catch (ManifestException e) {
                throw new IOException("downloaded package ownership inventory is invalid: "
                        + e.getMessage(), e);
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileAlreadyExistsException("destination appeared during install: " + target);
            }
            try {
                // Deliberately no REPLACE_EXISTING or ATOMIC_MOVE: a concurrent target must win.
                Files.move(extracted, target);
            } catch (FileAlreadyExistsException e) {
                throw new IOException("destination appeared during finalization; nothing was overwritten: "
                        + target, e);
            }
            Inspection finalInspection = inspector.inspect(target);
            if (!finalInspection.observedBuild().equals(inspection.observedBuild())
                    || !finalInspection.products().equals(inspection.products())) {
                throw new IOException("published installation changed during final validation: " + target);
            }
            InstallationRecord record;
            try {
                record = registryStore.register(registryPath, name, target, null);
            } catch (IOException e) {
                throw new IOException("installation is valid at " + target
                        + " but was NOT REGISTERED: " + e.getMessage()
                        + ". Use the register-existing flow for this folder.", e);
            }
            try {
                RegistryData registered = registryStore.read(registryPath);
                OwnershipReceipt receipt = receiptStore.write(registryPath, registered, record,
                        repository, release.tag(), asset.name(), asset.size(), asset.digest(),
                        ownership);
                return new Result(record, release, asset, target, receipt);
            } catch (IOException e) {
                throw new IOException("installation is valid and REGISTERED at " + target
                        + " but ownership receipt creation failed; PREVIEW UNAVAILABLE: "
                        + e.getMessage() + ". Keep using it for launch/manage.", e);
            }
        }
    }

    private void preflight(Path target, Path registry, String name) throws IOException {
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("destination must be wholly nonexistent (even an empty folder is refused): "
                    + target + "; use register-existing for an extracted copy");
        }
        if (name == null || name.isBlank() || name.length() > 120
                || name.chars().anyMatch(Character::isISOControl)) {
            throw new IOException("installation name must be 1-120 printable characters");
        }
        Path registryParent = registry.getParent();
        if (registryParent == null || !Files.isDirectory(registryParent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("registry parent must already be a real directory: " + registryParent);
        }
        rejectLinks(registryParent);
        if (target.startsWith(registry) || registry.startsWith(target)) {
            throw new IOException("registry and destination must not overlap");
        }
        Path metadata = receiptStore.metadataDirectory(registry);
        if (target.equals(metadata) || target.startsWith(metadata) || metadata.startsWith(target)) {
            throw new IOException("destination overlaps reserved receipt metadata: " + metadata);
        }
        RegistryData data = Files.exists(registry, LinkOption.NOFOLLOW_LINKS)
                ? registryStore.read(registry)
                : new RegistryData(RegistryStore.SCHEMA, null, java.util.List.of());
        // This must precede fetching: receipt publication enforces the same rule, but finding
        // an unusable metadata location after download/publication/registration is too late.
        ReceiptStore.validateMetadataPlacement(metadata, data);
        if (Files.exists(metadata, LinkOption.NOFOLLOW_LINKS)) {
            StrictPathSafety.requireDirectory(metadata, "receipt metadata directory");
        }
        if (Files.exists(registry, LinkOption.NOFOLLOW_LINKS)) {
            for (InstallationRecord record : data.installations()) {
                Path root = Path.of(record.canonicalRoot());
                if (target.equals(root) || target.startsWith(root) || root.startsWith(target)) {
                    throw new IOException("destination overlaps registered installation: " + root);
                }
                if (record.name().equals(name)) {
                    throw new IOException("installation name is already registered: " + name);
                }
            }
        }
    }

    private static Path requireSafeParent(Path target) throws IOException {
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("destination parent must already be a real directory: " + parent);
        }
        rejectLinks(parent);
        Path real = parent.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!Files.isWritable(real)) throw new IOException("destination parent is not writable: " + real);
        if (!target.equals(real.resolve(target.getFileName()))) {
            throw new IOException("destination parent is not canonical: " + parent);
        }
        return real;
    }

    private static void rejectLinks(Path path) throws IOException {
        Path cursor = path.getRoot();
        for (Path part : path) {
            cursor = cursor == null ? part : cursor.resolve(part);
            if (!Files.exists(cursor, LinkOption.NOFOLLOW_LINKS)) continue;
            BasicFileAttributes attrs = Files.readAttributes(cursor, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (attrs.isSymbolicLink() || attrs.isOther()) {
                throw new IOException("links, reparse points, and special paths are not allowed: " + cursor);
            }
        }
    }

    public record Result(InstallationRecord record, ReleaseCatalog.Release release,
                         ReleaseCatalog.Asset asset, Path destination,
                         OwnershipReceipt ownershipReceipt) {}
}
