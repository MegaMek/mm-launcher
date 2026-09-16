package org.megamek.launcher.release;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.List;
import java.util.Map;

public interface ReleaseTransport {
    Response get(URI uri, String accept) throws IOException, InterruptedException;

    record Response(int status, Map<String, List<String>> headers, InputStream body)
            implements AutoCloseable {
        public Response {
            headers = Map.copyOf(headers);
        }
        public String firstHeader(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .flatMap(e -> e.getValue().stream()).findFirst().orElse(null);
        }
        @Override public void close() throws IOException { body.close(); }
    }
}
