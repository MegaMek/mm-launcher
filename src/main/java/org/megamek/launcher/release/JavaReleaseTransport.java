/*
 * Copyright (C) 2026 The MegaMek Team. All Rights Reserved.
 *
 * This file is part of MegaMek Launcher.
 *
 * MegaMek Launcher is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License (GPL),
 * version 3 or (at your option) any later version,
 * as published by the Free Software Foundation.
 *
 * MegaMek Launcher is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty
 * of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 * A copy of the GPL should have been included with this project;
 * if not, see <https://www.gnu.org/licenses/>.
 *
 * NOTICE: The MegaMek organization is a non-profit group of volunteers
 * creating free software for the BattleTech community.
 *
 * MechWarrior, BattleMech, `Mech and AeroTech are registered trademarks
 * of The Topps Company, Inc. All Rights Reserved.
 *
 * Catalyst Game Labs and the Catalyst Game Labs logo are trademarks of
 * InMediaRes Productions, LLC.
 *
 * MechWarrior Copyright Microsoft Corporation. MegaMek was created under
 * Microsoft's "Game Content Usage Rules"
 * <https://www.xbox.com/en-US/developers/rules> and it is not endorsed by or
 * affiliated with Microsoft.
 */

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
        Duration requestTimeout = "application/octet-stream".equals(accept)
                ? Duration.ofMinutes(30) : Duration.ofSeconds(45);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(requestTimeout)
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
