package org.megamek.launcher.release;

import org.megamek.launcher.onboarding.Inspection;
import org.megamek.launcher.onboarding.InstallationInspector;
import org.megamek.launcher.registry.InstallationRecord;
import org.megamek.launcher.registry.RegistryData;
import org.megamek.launcher.registry.RegistryStore;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;

public final class FreshInstaller {
    public static final long MAX_DOWNLOAD = 2L * 1024 * 1024 * 1024;
    private static final int MAX_REDIRECTS = 5;
    private static final Duration OVERALL_TIMEOUT = Duration.ofHours(2);
    private final ReleaseTransport transport;
    private final ReleaseCatalog catalog;
    private final RegistryStore registryStore;
    private final InstallationInspector inspector;
    private final SafeTarExtractor extractor;

    public FreshInstaller(ReleaseTransport transport) {
        this(transport, new RegistryStore(), new InstallationInspector(), new SafeTarExtractor());
    }

    FreshInstaller(ReleaseTransport transport, RegistryStore registryStore,
                   InstallationInspector inspector, SafeTarExtractor extractor) {
        this.transport = transport;
        this.catalog = new ReleaseCatalog(transport);
        this.registryStore = registryStore;
        this.inspector = inspector;
        this.extractor = extractor;
    }

    public Result install(OfficialRepository repository, String tag, Path destination,
                          Path registry, String name, PrintStream progress)
            throws IOException, InterruptedException {
        Path target = destination.toAbsolutePath().normalize();
        Path registryPath = registry.toAbsolutePath().normalize();
        Path parent = requireSafeParent(target);
        preflight(target, registryPath, name);

        ReleaseCatalog.Release release = catalog.exact(repository, tag);
        ReleaseCatalog.Asset asset = catalog.selectInstallAsset(repository, release);
        Path staging = createOwnedStaging(parent);
        try {
            Path archive = staging.resolve("package.tar.gz");
            download(repository, release.tag(), asset, archive, progress);
            Path extracted = extractor.extract(archive, staging.resolve("expanded"));
            Inspection inspection = inspector.inspect(extracted);
            if (inspection.products().stream()
                    .noneMatch(product -> product.key().equals(repository.requiredProduct()))) {
                throw new IOException("downloaded package does not contain selected application "
                        + repository.requiredProduct());
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
            try {
                InstallationRecord record = registryStore.register(registryPath, name, target, null);
                return new Result(record, release, asset, target);
            } catch (IOException e) {
                throw new IOException("installation is valid at " + target
                        + " but was NOT REGISTERED: " + e.getMessage()
                        + ". Use the register-existing flow for this folder.", e);
            }
        } finally {
            // Once published, only the operation-owned staging sibling is removed. The final folder
            // is intentionally retained even when registry publication fails.
            deleteOwnedTree(staging);
        }
    }

    private void download(OfficialRepository repository, String tag, ReleaseCatalog.Asset asset,
                          Path output, PrintStream progress) throws IOException, InterruptedException {
        ReleaseCatalog.validateInitialAssetUri(repository, tag, asset);
        URI uri = asset.url();
        long deadline = System.nanoTime() + OVERALL_TIMEOUT.toNanos();
        ReleaseTransport.Response response = null;
        try {
            for (int redirects = 0; ; redirects++) {
                ReleaseCatalog.validateDownloadUri(uri, redirects == 0);
                response = transport.get(uri, "application/octet-stream");
                if (response.status() >= 300 && response.status() <= 399) {
                    if (redirects >= MAX_REDIRECTS) throw new IOException("too many download redirects");
                    String location = response.firstHeader("location");
                    response.close();
                    response = null;
                    if (location == null) throw new IOException("download redirect has no Location");
                    try { uri = uri.resolve(URI.create(location)); }
                    catch (IllegalArgumentException e) {
                        throw new IOException("download redirect has invalid Location", e);
                    }
                    ReleaseCatalog.validateDownloadUri(uri, false);
                    continue;
                }
                if (response.status() != 200) {
                    throw new IOException("release asset download returned HTTP " + response.status());
                }
                break;
            }
            String length = response.firstHeader("content-length");
            if (length != null) {
                try {
                    if (Long.parseLong(length) != asset.size()) {
                        throw new IOException("download Content-Length differs from release metadata");
                    }
                } catch (NumberFormatException e) {
                    throw new IOException("download Content-Length is invalid", e);
                }
            }
            MessageDigest digest = sha256();
            long count = 0;
            long nextProgress = 64L * 1024 * 1024;
            try (var input = response.body();
                 var file = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW,
                         StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[128 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                    if (System.nanoTime() > deadline) throw new IOException("download overall timeout");
                    if (read == 0) continue;
                    if (count > asset.size() - read) {
                        throw new IOException("download exceeds release metadata size");
                    }
                    file.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    count += read;
                    if (count >= nextProgress) {
                        progress.printf("DOWNLOAD %,d / %,d bytes (%.1f%%)%n", count, asset.size(),
                                100.0 * count / asset.size());
                        nextProgress += 64L * 1024 * 1024;
                    }
                }
            }
            if (count != asset.size()) {
                throw new IOException("truncated download: expected " + asset.size()
                        + " bytes, received " + count);
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            String expected = asset.digest().substring("sha256:".length()).toLowerCase();
            if (!actual.equals(expected)) throw new IOException("download SHA-256 mismatch");
            progress.printf("VERIFIED %,d bytes SHA-256 %s%n", count, actual);
        } finally {
            if (response != null) response.close();
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
        if (Files.exists(registry, LinkOption.NOFOLLOW_LINKS)) {
            RegistryData data = registryStore.read(registry);
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

    private static Path createOwnedStaging(Path parent) throws IOException {
        for (int attempt = 0; attempt < 10; attempt++) {
            Path candidate = parent.resolve(".mm-launcher-install-" + UUID.randomUUID());
            try {
                return Files.createDirectory(candidate);
            } catch (FileAlreadyExistsException ignored) {
                // Try another unguessable create-new name.
            }
        }
        throw new IOException("could not create private install staging directory");
    }

    private static void deleteOwnedTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (attrs.isSymbolicLink() || attrs.isOther()) {
                    throw new IOException("refusing cleanup of unexpected staging link/type: " + path);
                }
                Files.delete(path);
            }
        }
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

    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    public record Result(InstallationRecord record, ReleaseCatalog.Release release,
                         ReleaseCatalog.Asset asset, Path destination) {}
}
