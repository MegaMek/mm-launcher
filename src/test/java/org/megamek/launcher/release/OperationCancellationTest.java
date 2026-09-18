package org.megamek.launcher.release;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.megamek.launcher.operation.OperationCancelledException;
import org.megamek.launcher.operation.OperationContext;
import org.megamek.launcher.operation.OperationOutcome;
import org.megamek.launcher.operation.OperationPhase;
import org.megamek.launcher.operation.OperationProgress;
import org.megamek.launcher.operation.OperationType;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationCancellationTest {
    @TempDir Path temp;

    @Test
    void blockedPackageReadCancellationClosesOwnedStreamAndWorkspacePromptlyOffEdt()
            throws Exception {
        byte[] archive = packageArchive();
        BlockingInputStream blocked = new BlockingInputStream();
        QueueTransport transport = new QueueTransport(
                response(metadata(archive)),
                () -> new ReleaseTransport.Response(200,
                        Map.of("content-length", List.of(Integer.toString(archive.length))),
                        blocked));
        OperationContext context = new OperationContext(OperationType.UPDATE_PREVIEW);
        var executor = Executors.newSingleThreadExecutor(runnable ->
                new Thread(runnable, "fixture-package-worker"));
        try {
            var result = executor.submit(() -> {
                try {
                    new VerifiedPackageFetcher(transport).fetch(OfficialRepository.MEGAMEK,
                            "v2", temp, ".blocked-", quiet(), null, context);
                    return null;
                } catch (Exception error) {
                    return error;
                }
            });
            assertTrue(blocked.entered.await(5, TimeUnit.SECONDS));
            assertTrue(context.requestCancellation().accepted());
            Throwable failure = result.get(5, TimeUnit.SECONDS);
            assertInstanceOf(OperationCancelledException.class, failure);
            assertTrue(blocked.closed.await(5, TimeUnit.SECONDS));
            assertFalse(blocked.closeThread.getName().startsWith("AWT-EventQueue"),
                    "owned network close must stay off the Swing EDT");
            try (var entries = Files.list(temp)) {
                assertTrue(entries.noneMatch(path ->
                        path.getFileName().toString().startsWith(".blocked-")));
            }
            assertFalse(javax.swing.SwingUtilities.isEventDispatchThread());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void successfulFetchReportsMonotonicBytesVerificationAndIndeterminateEntryCounts()
            throws Exception {
        byte[] archive = packageArchive();
        QueueTransport transport = new QueueTransport(
                response(metadata(archive)), binary(archive));
        List<OperationProgress> events = new ArrayList<>();
        OperationContext context = new OperationContext(OperationType.UPDATE_PREVIEW, events::add);

        try (VerifiedPackageFetcher.Workspace ignored =
                     new VerifiedPackageFetcher(transport).fetch(OfficialRepository.MEGAMEK,
                             "v2", temp, ".progress-", quiet(), null, context)) {
            context.finish(OperationOutcome.SUCCEEDED, "done");
        }

        long previous = -1;
        for (OperationProgress event : events) {
            if (event.phase() == OperationPhase.DOWNLOAD && event.completed() >= 0) {
                assertTrue(event.completed() >= previous);
                assertEquals(archive.length, event.total());
                previous = event.completed();
            }
        }
        assertEquals(archive.length, previous);
        assertTrue(events.stream().anyMatch(event ->
                event.phase() == OperationPhase.VERIFY && event.determinate()));
        assertTrue(events.stream().anyMatch(event ->
                event.phase() == OperationPhase.EXTRACT
                        && event.completed() > 0 && !event.determinate()));
        assertEquals(OperationPhase.FINAL, events.getLast().phase());
    }

    @Test
    void freshCancellationAtExtractionCheckpointPublishesNoDestinationOrRegistry()
            throws Exception {
        byte[] archive = packageArchive();
        QueueTransport transport = new QueueTransport(
                response(metadata(archive)), binary(archive));
        AtomicReference<OperationContext> reference = new AtomicReference<>();
        OperationContext context = new OperationContext(OperationType.FRESH_INSTALL, event -> {
            if (event.phase() == OperationPhase.EXTRACT && event.cancellationAllowed()) {
                reference.get().requestCancellation();
            }
        });
        reference.set(context);
        Path destination = temp.resolve("installed");
        Path registry = temp.resolve("registry.json");

        Throwable failure;
        try {
            new FreshInstaller(transport).install(OfficialRepository.MEGAMEK, "v2",
                    destination, registry, "Cancelled", quiet(), context);
            failure = null;
        } catch (Throwable error) {
            failure = error;
        }

        assertNotNull(failure);
        assertInstanceOf(OperationCancelledException.class, failure);
        assertFalse(Files.exists(destination));
        assertFalse(Files.exists(registry));
        assertFalse(Files.exists(registry.resolveSibling("registry.json.metadata")));
        try (var entries = Files.list(temp)) {
            assertTrue(entries.noneMatch(path -> path.getFileName().toString()
                    .startsWith(".mm-launcher-install-")));
        }
    }

    @Test
    void freshCancellationDuringOwnershipPlanningPublishesNoDestinationOrRegistry()
            throws Exception {
        byte[] archive = packageArchive();
        QueueTransport transport = new QueueTransport(
                response(metadata(archive)), binary(archive));
        AtomicReference<OperationContext> reference = new AtomicReference<>();
        OperationContext context = new OperationContext(OperationType.FRESH_INSTALL, event -> {
            if (event.phase() == OperationPhase.PLAN
                    && event.detail().startsWith("Inventoried")
                    && event.cancellationAllowed()) {
                reference.get().requestCancellation();
            }
        });
        reference.set(context);
        Path destination = temp.resolve("planned-install");
        Path registry = temp.resolve("planned-registry.json");

        Throwable failure;
        try {
            new FreshInstaller(transport).install(OfficialRepository.MEGAMEK, "v2",
                    destination, registry, "Cancelled plan", quiet(), context);
            failure = null;
        } catch (Throwable error) {
            failure = error;
        }

        assertInstanceOf(OperationCancelledException.class, failure);
        assertFalse(Files.exists(destination));
        assertFalse(Files.exists(registry));
    }

    @Test
    void lateFreshCancellationIsDeniedAndCannotTurnPublishedInstallIntoCancelled()
            throws Exception {
        byte[] archive = packageArchive();
        QueueTransport transport = new QueueTransport(
                response(metadata(archive)), binary(archive));
        AtomicReference<OperationContext> reference = new AtomicReference<>();
        AtomicReference<OperationContext.CancellationRequest> request =
                new AtomicReference<>();
        OperationContext context = new OperationContext(OperationType.FRESH_INSTALL, event -> {
            if (event.phase() == OperationPhase.PREPARE_INSTALL
                    && !event.cancellationAllowed() && request.get() == null) {
                request.set(reference.get().requestCancellation());
            }
        });
        reference.set(context);
        Path destination = temp.resolve("completed-install");
        Path registry = temp.resolve("completed-registry.json");

        FreshInstaller.Result result = new FreshInstaller(transport).install(
                OfficialRepository.MEGAMEK, "v2", destination, registry,
                "Completed", quiet(), context);

        assertFalse(request.get().accepted());
        assertTrue(request.get().reason().contains("publication"));
        assertTrue(Files.isRegularFile(destination.resolve("MegaMek.jar")));
        assertTrue(Files.isRegularFile(registry));
        assertEquals(result.record().id(),
                new org.megamek.launcher.registry.RegistryStore().read(registry)
                        .defaultInstallationId());
        assertFalse(Thread.currentThread().isInterrupted());
    }

    private static byte[] packageArchive() throws Exception {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("MegaMek.jar", jar());
        files.put("lib/runtime.txt", "runtime".getBytes(StandardCharsets.UTF_8));
        files.put("data/default.txt", "data".getBytes(StandardCharsets.UTF_8));
        files.put("mmconf/clientsettings.xml", "config".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(output);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            tar.putArchiveEntry(new TarArchiveEntry("MegaMek-v2/"));
            tar.closeArchiveEntry();
            for (Map.Entry<String, byte[]> item : files.entrySet()) {
                TarArchiveEntry entry = new TarArchiveEntry("MegaMek-v2/" + item.getKey());
                entry.setSize(item.getValue().length);
                tar.putArchiveEntry(entry);
                tar.write(item.getValue());
                tar.closeArchiveEntry();
            }
        }
        return output.toByteArray();
    }

    private static byte[] jar() throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(Attributes.Name.MAIN_CLASS, "megamek.MegaMek");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(output, manifest)) {
            jar.putNextEntry(new JarEntry("megamek/Version.properties"));
            jar.write("major=2\nminor=0\npatch=0\n".getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return output.toByteArray();
    }

    private static String metadata(byte[] archive) throws Exception {
        return """
                {"tag_name":"v2","name":"v2","draft":false,"prerelease":false,
                "html_url":"https://github.com/MegaMek/megamek/releases/tag/v2",
                "assets":[{"name":"MegaMek-v2.tar.gz","size":%d,"digest":"sha256:%s",
                "browser_download_url":"https://github.com/MegaMek/megamek/releases/download/v2/MegaMek-v2.tar.gz"}]}
                """.formatted(archive.length, sha(archive));
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static Supplier<ReleaseTransport.Response> response(String body) {
        return () -> new ReleaseTransport.Response(200, Map.of(),
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
    }

    private static Supplier<ReleaseTransport.Response> binary(byte[] archive) {
        return () -> new ReleaseTransport.Response(200,
                Map.of("content-length", List.of(Integer.toString(archive.length))),
                new ByteArrayInputStream(archive));
    }

    private static PrintStream quiet() {
        return new PrintStream(new ByteArrayOutputStream());
    }

    private static final class QueueTransport implements ReleaseTransport {
        private final ArrayDeque<Supplier<Response>> responses;

        @SafeVarargs
        private QueueTransport(Supplier<Response>... responses) {
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public Response get(URI uri, String accept) throws IOException {
            Supplier<Response> response = responses.poll();
            if (response == null) throw new IOException("unexpected fixture request");
            return response.get();
        }
    }

    private static final class BlockingInputStream extends InputStream {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private volatile Thread closeThread;
        private boolean isClosed;

        @Override
        public synchronized int read(byte[] bytes, int offset, int length) throws IOException {
            entered.countDown();
            while (!isClosed) {
                try {
                    wait();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("fixture read interrupted", interrupted);
                }
            }
            throw new IOException("fixture stream closed");
        }

        @Override
        public int read() throws IOException {
            return read(new byte[1], 0, 1);
        }

        @Override
        public synchronized void close() {
            closeThread = Thread.currentThread();
            isClosed = true;
            closed.countDown();
            notifyAll();
        }
    }
}
