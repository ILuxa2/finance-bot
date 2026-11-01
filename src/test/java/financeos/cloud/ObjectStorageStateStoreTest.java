package financeos.cloud;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;

public final class ObjectStorageStateStoreTest {
    public static void main(String[] args) throws Exception {
        var saved = new AtomicReference<byte[]>();
        var etag = new AtomicReference<String>();
        var version = new java.util.concurrent.atomic.AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/test-bucket/state.json", exchange -> {
            try {
                if (!"Bearer local-test-token".equals(
                        exchange.getRequestHeaders().getFirst("Authorization"))) {
                    exchange.sendResponseHeaders(403, -1);
                } else if ("GET".equals(exchange.getRequestMethod())) {
                    if (saved.get() == null) exchange.sendResponseHeaders(404, -1);
                    else {
                        byte[] bytes = saved.get();
                        exchange.getResponseHeaders().set("ETag", etag.get());
                        exchange.sendResponseHeaders(200, bytes.length);
                        exchange.getResponseBody().write(bytes);
                    }
                } else if ("PUT".equals(exchange.getRequestMethod())) {
                    String expected = exchange.getRequestHeaders().getFirst("If-Match");
                    String absent = exchange.getRequestHeaders().getFirst("If-None-Match");
                    boolean allowed = saved.get() == null ? "*".equals(absent)
                            : etag.get().equals(expected);
                    if (!allowed) exchange.sendResponseHeaders(412, -1);
                    else {
                        byte[] bytes = exchange.getRequestBody().readAllBytes();
                        saved.set(bytes);
                        etag.set("\"etag-" + version.incrementAndGet() + "\"");
                        exchange.sendResponseHeaders(200, -1);
                    }
                } else exchange.sendResponseHeaders(405, -1);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            var store = new ObjectStorageStateStore(HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    "test-bucket", "state.json", () -> "local-test-token");
            if (store.load().isPresent()) throw new AssertionError("Missing object was read");
            byte[] first = "{\"one\":1}".getBytes(StandardCharsets.UTF_8);
            if (!store.save(null, first)) throw new AssertionError("First write failed");
            var snapshot = store.load().orElseThrow();
            if (!Arrays.equals(first, snapshot.bytes())) throw new AssertionError("Readback differs");
            if (store.save(null, first)) throw new AssertionError("Duplicate create overwrote state");
            byte[] second = "{\"two\":2}".getBytes(StandardCharsets.UTF_8);
            if (!store.save(snapshot.etag(), second)) throw new AssertionError("CAS update failed");
            if (store.save(snapshot.etag(), first)) throw new AssertionError("Stale CAS overwrote state");
            if (!Arrays.equals(second, store.load().orElseThrow().bytes())) {
                throw new AssertionError("Final state differs");
            }
        } finally {
            server.stop(0);
        }
        System.out.println("Object Storage CAS checks passed.");
    }
}
