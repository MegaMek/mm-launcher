package org.megamek.launcher.release;

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
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Comparator;
import java.util.HexFormat;
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
        return fetch(repository, tag, safeParent, stagingPrefix, progress, null);
    }

    public Workspace fetch(OfficialRepository repository, String tag, Path safeParent,
                           String stagingPrefix, PrintStream progress, ExpectedAsset expected)
            throws IOException, InterruptedException {
        ReleaseCatalog.Release release = catalog.exact(repository, tag);
        ReleaseCatalog.Asset asset = catalog.selectInstallAsset(repository, release);
        if (expected != null && (expected.name() != null && !asset.name().equals(expected.name())
                || asset.size() != expected.size()
                || !asset.digest().equalsIgnoreCase(expected.digest()))) {
            throw new IOException("target asset name, size, or digest changed since explicit consent; "
                    + "preview and consent again");
        }
        Path staging = createOwnedStaging(safeParent, stagingPrefix);
        try {
            Path archive = staging.resolve("package.tar.gz");
            download(repository, release.tag(), asset, archive, progress);
            Path extracted = extractor.extract(archive, staging.resolve("expanded"));
            return new Workspace(staging, extracted, release, asset);
        } catch (IOException | InterruptedException | RuntimeException e) {
            deleteOwnedTree(staging);
            throw e;
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
            long nextProgress = 64L * 1024 * 1024;
            try (var input = new DigestInputStream(response.body(), digest);
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

    public record Workspace(Path staging, Path extracted, ReleaseCatalog.Release release,
                            ReleaseCatalog.Asset asset) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            deleteOwnedTree(staging);
        }
    }

    public record ExpectedAsset(String name, long size, String digest) {
        public ExpectedAsset(long size, String digest) {
            this(null, size, digest);
        }
    }
}
