package org.megamek.launcher.release;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class JavaReleaseTransport implements ReleaseTransport {
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    @Override
    public Response get(URI uri, String accept) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(30))
                .header("Accept", accept).header("User-Agent", "mm-launcher-prototype")
                .header("X-GitHub-Api-Version", "2022-11-28").GET().build();
        HttpResponse<java.io.InputStream> response =
                client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        return new Response(response.statusCode(), response.headers().map(),
                new TimedInputStream(response.body()));
    }

    private static final class TimedInputStream extends InputStream {
        private static final long READ_TIMEOUT_SECONDS = 90;
        private final InputStream delegate;
        private final ExecutorService reader = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "mm-launcher-http-read");
            thread.setDaemon(true);
            return thread;
        });

        private TimedInputStream(InputStream delegate) {
            this.delegate = delegate;
        }

        @Override public int read() throws IOException {
            byte[] one = new byte[1];
            int count = read(one, 0, 1);
            return count < 0 ? -1 : one[0] & 0xff;
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            Future<Integer> future = reader.submit(() -> delegate.read(bytes, offset, length));
            try {
                return future.get(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                future.cancel(true);
                try { delegate.close(); } catch (IOException ignored) {}
                throw new IOException("HTTPS response read timed out after "
                        + READ_TIMEOUT_SECONDS + " seconds", e);
            } catch (InterruptedException e) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                try { delegate.close(); } catch (IOException ignored) {}
                throw new InterruptedIOException("interrupted while reading HTTPS response");
            } catch (ExecutionException e) {
                if (e.getCause() instanceof IOException io) throw io;
                throw new IOException("HTTPS response read failed", e.getCause());
            }
        }

        @Override public void close() throws IOException {
            try {
                delegate.close();
            } finally {
                reader.shutdownNow();
            }
        }
    }
}
