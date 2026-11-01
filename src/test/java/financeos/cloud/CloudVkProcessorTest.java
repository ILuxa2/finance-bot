package financeos.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import financeos.data.LegacySheetSchema;
import financeos.vk.VkEvent;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class CloudVkProcessorTest {
    public static void main(String[] args) throws Exception {
        var mapper = new ObjectMapper();
        var objects = new ConcurrentHashMap<String, byte[]>();
        var sent = new AtomicInteger();
        var sheets = new LinkedHashMap<String, Object>();
        LegacySheetSchema.headers().keySet().forEach(name -> sheets.put(name, List.of()));
        sheets.put("StartupState", List.of(Map.of("telegram_user_id", "7",
                "created_at", "2026-10-30T09:00:00+03:00", "total_balance", "100")));
        objects.put("/test-bucket/base.json", mapper.writeValueAsBytes(
                Map.of("formatVersion", 1, "sheets", sheets)));
        objects.put("/test-bucket/state.json", mapper.writeValueAsBytes(
                new CloudVkProcessor.Envelope(1, "", new LinkedHashMap<>(),
                        new java.util.LinkedHashSet<>(), "", 100)));
        VkEvent requested = new VkEvent("message_new", 99, 42, 42,
                "103", "1, test", 103);
        String inbox = "inbox/" + CloudVkIngress.eventId(requested) + ".json";
        objects.put("/test-bucket/" + inbox, mapper.writeValueAsBytes(requested));
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/test-bucket/", exchange -> {
            try {
                String key = exchange.getRequestURI().getPath();
                byte[] existing = objects.get(key);
                if ("GET".equals(exchange.getRequestMethod())) {
                    if (existing == null) exchange.sendResponseHeaders(404, -1);
                    else {
                        exchange.getResponseHeaders().add("ETag", etag(existing));
                        exchange.sendResponseHeaders(200, existing.length);
                        exchange.getResponseBody().write(existing);
                    }
                } else if ("PUT".equals(exchange.getRequestMethod())) {
                    String expected = exchange.getRequestHeaders().getFirst("If-Match");
                    if (existing == null || !etag(existing).equals(expected)) {
                        exchange.sendResponseHeaders(412, -1);
                    } else {
                        objects.put(key, exchange.getRequestBody().readAllBytes());
                        exchange.sendResponseHeaders(200, -1);
                    }
                } else exchange.sendResponseHeaders(405, -1);
            } finally { exchange.close(); }
        });
        server.createContext("/history", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                String json = "{\"response\":{\"items\":["
                        + message(103, "1, test") + ","
                        + message(102, "Еда") + ","
                        + message(101, "/expense") + ","
                        + message(100, "old") + "]}}";
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        server.createContext("/send", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                sent.incrementAndGet();
                byte[] bytes = "{\"response\":1}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var processor = new CloudVkProcessor(HttpClient.newHttpClient(), base,
                    base.resolve("/history"), base.resolve("/send"), base.resolve("/members"),
                    () -> "test-iam", "test-bucket", "test-vk-token", 99, 42,
                    2000000001L, "base.json", "state.json");
            var trigger = Map.<String, Object>of("messages", List.of(Map.of(
                    "event_metadata", Map.of("event_type", "yandex.cloud.events.storage.ObjectCreate"),
                    "details", Map.of("bucket_id", "test-bucket", "object_id", inbox))));
            processor.apply(trigger);
            var state = mapper.readTree(objects.get("/test-bucket/state.json"));
            if (state.path("lastConversationMessageId").asLong() != 103
                    || state.path("replies").size() != 3 || sent.get() != 3) {
                throw new AssertionError("Out-of-order callback did not process all VK commands in order");
            }
            var local = mapper.readTree(java.util.Base64.getDecoder().decode(
                    state.path("stateBase64").asText()));
            if (local.path("entries").size() != 1
                    || !"1.00".equals(local.path("entries").get(0).path("amount").asText())) {
                throw new AssertionError("Ordered VK expense was not saved exactly once");
            }
            processor.apply(trigger);
            var again = mapper.readTree(objects.get("/test-bucket/state.json"));
            if (again.path("stateBase64").asText().equals("")
                    || !again.path("stateBase64").asText().equals(state.path("stateBase64").asText())) {
                throw new AssertionError("Repeated trigger changed the financial state");
            }
        } finally {
            server.stop(0);
        }
        System.out.println("Cloud VK ordered processing checks passed.");
    }

    private static String message(int id, String text) {
        try {
            return new ObjectMapper().writeValueAsString(Map.of("id", id,
                    "conversation_message_id", id, "from_id", 42, "peer_id", 42,
                    "out", 0, "text", text));
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private static String etag(byte[] bytes) {
        try {
            return "\"" + HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes)) + "\"";
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
