package org.megamek.launcher.release;

import org.megamek.launcher.operation.OperationContext;
import org.megamek.launcher.operation.OperationPhase;
import org.megamek.launcher.operation.OperationType;
import org.megamek.launcher.operation.ProgressUnit;
import org.megamek.launcher.update.StrictPathSafety;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * Shared, verified package acquisition boundary. A workspace owns exactly one random staging
 * directory and removes only that directory when closed.
 */
public final class VerifiedPackageFetcher {
    private static final int MAX_REDIRECTS = 5;
    private static final Duration OVERALL_TIMEOUT = Duration.ofHours(2);
    private final ReleaseTransport transport;
    private final ReleaseCatalog catalog;
    private final SafeTarExtractor extractor;

    public VerifiedPackageFetcher(ReleaseTransport transport) {
        this(transport, new SafeTarExtractor());
    }

    VerifiedPackageFetcher(ReleaseTransport transport, SafeTarExtractor extractor) {
        this.transport = transport;
        this.catalog = new ReleaseCatalog(transport);
        this.extractor = extractor;
    }

    public Workspace fetch(OfficialRepository repository, String tag, Path safeParent,
                           String stagingPrefix, PrintStream progress)
            throws IOException, InterruptedException {
        return fetch(repository, tag, safeParent, stagingPrefix, progress, null,
                OperationContext.none(OperationType.UPDATE_PREVIEW));
    }

    public Workspace fetch(OfficialRepository repository, String tag, Path safeParent,
                           String stagingPrefix, PrintStream progress, ExpectedAsset expected)
            throws IOException, InterruptedException {
        return fetch(repository, tag, safeParent, stagingPrefix, progress, expected,
                OperationContext.none(OperationType.UPDATE_PREVIEW));
    }

    public Workspace fetch(OfficialRepository repository, String tag, Path safeParent,
                           String stagingPrefix, PrintStream progress, ExpectedAsset expected,
                           OperationContext context)
            throws IOException, InterruptedException {
        try (OperationContext.WorkerRegistration ignored = context.activate()) {
            context.phase(OperationPhase.METADATA, "Resolving exact release metadata");
            ReleaseCatalog.Release release = catalog.exact(repository, tag);
            ReleaseCatalog.Asset asset = catalog.selectInstallAsset(repository, release);
            requireExpected(asset, expected);
            Path staging = createOwnedStaging(safeParent, stagingPrefix);
            try {
                Path archive = staging.resolve("package.tar.gz");
                download(repository, release.tag(), asset, archive, progress, context);
                context.phase(OperationPhase.EXTRACT, "Opening verified package archive");
                Path extracted = extractor.extract(archive, staging.resolve("expanded"), context,
                        OperationPhase.EXTRACT);
                return new Workspace(staging, archive, extracted, release, asset,
                        FileIdentity.read(archive));
            } catch (IOException | InterruptedException | RuntimeException e) {
                context.cleanupPhase("Removing the operation-owned package workspace");
                try {
                    context.cleanupPreservingCancellation(() -> deleteOwnedTree(staging));
                } catch (Exception cleanup) {
                    e.addSuppressed(cleanup);
                }
                try {
                    context.checkpoint();
                } catch (org.megamek.launcher.operation.OperationCancelledException cancelled) {
                    cancelled.addSuppressed(e);
                    throw cancelled;
                }
                throw e;
            }
        }
    }

    /**
     * Rechecks current release metadata and the retained compressed bytes, then extracts a fresh
     * Apply payload. The preview extraction is never treated as write authority.
     */
    public Path revalidateAndExtract(OfficialRepository repository, String tag,
                                     Workspace workspace, ExpectedAsset expected,
                                     PrintStream progress)
            throws IOException, InterruptedException {
        return revalidateAndExtract(repository, tag, workspace, expected, progress,
                OperationContext.none(OperationType.UPDATE_APPLY));
    }

    public Path revalidateAndExtract(OfficialRepository repository, String tag,
                                     Workspace workspace, ExpectedAsset expected,
                                     PrintStream progress, OperationContext context)
            throws IOException, InterruptedException {
        Objects.requireNonNull(workspace, "workspace");
        context.phase(OperationPhase.PREPARE_INSTALL,
                "Refreshing metadata for the retained package");
        ReleaseCatalog.Release freshRelease = catalog.exact(repository, tag);
        ReleaseCatalog.Asset freshAsset = catalog.selectInstallAsset(repository, freshRelease);
        requireExpected(freshAsset, expected);
        if (!workspace.release().tag().equals(tag)
                || !workspace.asset().equals(freshAsset)) {
            throw new IOException("target release or asset metadata changed; restart the update "
                    + "attempt (the retained package was not applied)");
        }
        context.phase(OperationPhase.PREPARE_INSTALL,
                "Re-verifying retained package size and SHA-256");
        workspace.verifyArchive(expected, context);
        progress.printf("REUSING already-downloaded %,d byte package; no second package download%n",
                freshAsset.size());
        context.phase(OperationPhase.PREPARE_INSTALL,
                "Extracting retained package for final validation");
        return extractor.extract(workspace.archive, workspace.staging.resolve("apply-expanded"),
                context, OperationPhase.PREPARE_INSTALL);
    }

    private void download(OfficialRepository repository, String tag, ReleaseCatalog.Asset asset,
                          Path output, PrintStream progress, OperationContext context)
            throws IOException, InterruptedException {
        ReleaseCatalog.validateInitialAssetUri(repository, tag, asset);
        URI uri = asset.url();
        long deadline = System.nanoTime() + OVERALL_TIMEOUT.toNanos();
        ReleaseTransport.Response response = null;
        Throwable failure = null;
        try {
            context.phase(OperationPhase.DOWNLOAD, "Connecting to the verified package source");
            for (int redirects = 0; ; redirects++) {
                context.checkpoint();
                ReleaseCatalog.validateDownloadUri(uri, redirects == 0);
                response = transport.get(uri, "application/octet-stream");
                if (response.status() >= 300 && response.status() <= 399) {
                    if (redirects >= MAX_REDIRECTS) throw new IOException("too many download redirects");
                    String location = response.firstHeader("location");
                    response.close();
                    response = null;
                    if (location == null) throw new IOException("download redirect has no Location");
                    try {
                        uri = uri.resolve(URI.create(location));
                    } catch (IllegalArgumentException e) {
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
            try (OperationContext.ResourceRegistration ignored =
                         context.interruptible(response);
                 var input = new DigestInputStream(response.body(), digest);
                 var file = Files.newOutputStream(output, StandardOpenOption.CREATE_NEW,
                         StandardOpenOption.WRITE)) {
                byte[] buffer = new byte[128 * 1024];
                int read;
                while (true) {
                    context.checkpoint();
                    try {
                        read = input.read(buffer);
                    } catch (IOException error) {
                        context.checkpoint();
                        throw error;
                    }
                    if (read < 0) break;
                    if (System.nanoTime() > deadline) throw new IOException("download overall timeout");
                    if (read == 0) continue;
                    if (count > asset.size() - read) {
                        throw new IOException("download exceeds release metadata size");
                    }
                    file.write(buffer, 0, read);
                    count += read;
                    context.progress(OperationPhase.DOWNLOAD, count, asset.size(),
                            ProgressUnit.BYTES, "Downloaded " + count + " of "
                                    + asset.size() + " bytes");
                }
            }
            if (count != asset.size()) {
                throw new IOException("truncated download: expected " + asset.size()
                        + " bytes, received " + count);
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            String expected = asset.digest().substring("sha256:".length()).toLowerCase();
            if (!actual.equals(expected)) throw new IOException("download SHA-256 mismatch");
            context.progress(OperationPhase.VERIFY, count, count, ProgressUnit.BYTES,
                    "Package size and SHA-256 verified");
            progress.printf("VERIFIED %,d bytes SHA-256 %s%n", count, actual);
        } catch (IOException | InterruptedException | RuntimeException error) {
            failure = error;
            throw error;
        } finally {
            if (response != null) {
                try {
                    response.close();
                } catch (IOException closeFailure) {
                    if (failure == null) throw closeFailure;
                    failure.addSuppressed(closeFailure);
                }
            }
        }
    }

    private static Path createOwnedStaging(Path parent, String prefix) throws IOException {
        requireRealDirectory(parent);
        for (int attempt = 0; attempt < 10; attempt++) {
            Path candidate = parent.resolve(prefix + UUID.randomUUID());
            try {
                return Files.createDirectory(candidate);
            } catch (FileAlreadyExistsException ignored) {
                // Retry with another random create-new name.
            }
        }
        throw new IOException("could not create private package staging directory");
    }

    public static void requireRealDirectory(Path directory) throws IOException {
        StrictPathSafety.requireDirectory(directory, "working directory");
    }

    static void deleteOwnedTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (var walk = Files.walk(root)) {
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (attrs.isSymbolicLink() || attrs.isOther()) {
                    throw new IOException("refusing cleanup of unexpected staging link/type: " + path);
                }
                Files.delete(path);
            }
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void requireExpected(ReleaseCatalog.Asset asset, ExpectedAsset expected)
            throws IOException {
        if (expected != null && (expected.name() != null && !asset.name().equals(expected.name())
                || asset.size() != expected.size()
                || !asset.digest().equalsIgnoreCase(expected.digest())
                || expected.url() != null && !asset.url().equals(expected.url()))) {
            throw new IOException("target asset name, URL, size, or digest changed since explicit "
                    + "consent; restart the update attempt");
        }
    }

    public static final class Workspace implements AutoCloseable {
        private final Path staging;
        private final Path archive;
        private final Path extracted;
        private final ReleaseCatalog.Release release;
        private final ReleaseCatalog.Asset asset;
        private final FileIdentity archiveIdentity;

        private Workspace(Path staging, Path archive, Path extracted,
                          ReleaseCatalog.Release release, ReleaseCatalog.Asset asset,
                          FileIdentity archiveIdentity) {
            this.staging = staging;
            this.archive = archive;
            this.extracted = extracted;
            this.release = release;
            this.asset = asset;
            this.archiveIdentity = archiveIdentity;
        }

        public Path staging() {
            return staging;
        }

        public Path extracted() {
            return extracted;
        }

        public ReleaseCatalog.Release release() {
            return release;
        }

        public ReleaseCatalog.Asset asset() {
            return asset;
        }

        private void verifyArchive(ExpectedAsset expected, OperationContext context)
                throws IOException, InterruptedException {
            requireExpected(asset, expected);
            FileIdentity before = FileIdentity.read(archive);
            if (!archiveIdentity.equals(before)) {
                throw new IOException("retained package identity changed after preparation; "
                        + "restart the update attempt");
            }
            MessageDigest digest = sha256();
            long count = 0;
            try (var input = new DigestInputStream(Files.newInputStream(archive), digest)) {
                byte[] buffer = new byte[128 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    context.checkpoint();
                    if (read == 0) continue;
                    if (count > asset.size() - read) {
                        throw new IOException("retained package exceeds consented size");
                    }
                    count += read;
                    context.progress(OperationPhase.PREPARE_INSTALL, count, asset.size(),
                            ProgressUnit.BYTES, "Re-verified " + count + " of "
                                    + asset.size() + " retained bytes");
                }
            }
            FileIdentity after = FileIdentity.read(archive);
            if (!before.equals(after)) {
                throw new IOException("retained package changed while it was being verified");
            }
            String actual = HexFormat.of().formatHex(digest.digest());
            String expectedHash = asset.digest().substring("sha256:".length()).toLowerCase();
            if (count != asset.size() || !actual.equals(expectedHash)) {
                throw new IOException("retained package no longer matches consented size/SHA-256");
            }
        }

        @Override
        public void close() throws IOException {
            deleteOwnedTree(staging);
        }
    }

    private record FileIdentity(long size, FileTime lastModified, Object fileKey) {
        static FileIdentity read(Path archive) throws IOException {
            BasicFileAttributes attrs = Files.readAttributes(archive, BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (!attrs.isRegularFile() || attrs.isSymbolicLink() || attrs.isOther()) {
                throw new IOException("retained package must be an ordinary file");
            }
            return new FileIdentity(attrs.size(), attrs.lastModifiedTime(), attrs.fileKey());
        }
    }

    public record ExpectedAsset(String name, long size, String digest, URI url) {
        public ExpectedAsset(String name, long size, String digest) {
            this(name, size, digest, null);
        }

        public ExpectedAsset(long size, String digest) {
            this(null, size, digest, null);
        }
    }
}
