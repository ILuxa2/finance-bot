package financeos.vk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import financeos.core.ChartData;
import financeos.core.ChartSeries;
import financeos.core.Money;
import financeos.core.Screen;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class VkMessagesClientTest {
    public static void main(String[] args) throws Exception {
        var lastBody = new AtomicReference<String>();
        var fail = new AtomicBoolean();
        var keyboardInvalid = new AtomicBoolean();
        var sendAttempts = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/method/messages.send", exchange -> {
            sendAttempts.incrementAndGet();
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String response = fail.get() ? "{\"error\":{\"error_code\":901}}"
                    : keyboardInvalid.get() && decode(lastBody.get()).containsKey("keyboard")
                    ? "{\"error\":{\"error_code\":911}}" : "{\"response\":12345}";
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        var uploadedBytes = new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/method/photos.getMessagesUploadServer", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = ("{\"response\":{\"upload_url\":\"http://127.0.0.1:"
                    + server.getAddress().getPort() + "/upload\"}}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.createContext("/upload", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            uploadedBytes.set(body.length);
            if (!new String(body, StandardCharsets.ISO_8859_1).contains("name=\"photo\"")) {
                throw new AssertionError("PNG multipart field was missing");
            }
            byte[] bytes = "{\"server\":5,\"photo\":\"uploaded-photo\",\"hash\":\"hash\"}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.createContext("/method/photos.saveMessagesPhoto", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = "{\"response\":[{\"owner_id\":-99,\"id\":77,\"access_key\":\"abc\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            var endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                    + "/method/messages.send");
            var client = new VkMessagesClient(endpoint, "private-test-token", HttpClient.newHttpClient());
            var event = new VkEvent("message_new", 99, 123, 123, "message-7", "/start");
            var reply = new BotReply("Привет, мир", Screen.MAIN);
            if (client.send(event, reply) != 12345) throw new AssertionError("VK response was ignored");
            Map<String, String> first = decode(lastBody.get());
            if (!first.get("peer_id").equals("123")
                    || !first.get("message").equals("Привет, мир")
                    || !first.get("v").equals("5.199")
                    || !first.get("access_token").equals("private-test-token")) {
                throw new AssertionError("VK request fields are wrong");
            }
            var keyboard = new ObjectMapper().readTree(first.get("keyboard"));
            int buttonCount = 0;
            for (var row : keyboard.path("buttons")) buttonCount += row.size();
            if (keyboard.path("one_time").asBoolean(true)
                    || keyboard.path("buttons").size() > 6
                    || buttonCount != MenuCatalog.buttons(Screen.MAIN).size()) {
                throw new AssertionError("VK keyboard was not included");
            }
            keyboardInvalid.set(true);
            int beforeFallback = sendAttempts.get();
            client.send(new VkEvent("message_new", 99, 123, 123,
                    "fallback-8", "/start"), reply);
            Map<String, String> fallback = decode(lastBody.get());
            if (sendAttempts.get() != beforeFallback + 2 || fallback.containsKey("keyboard")
                    || !fallback.get("message").contains("/report")) {
                throw new AssertionError("Invalid keyboard was not retried as text");
            }
            keyboardInvalid.set(false);
            client.send(event, reply);
            if (!decode(lastBody.get()).get("random_id").equals(first.get("random_id"))) {
                throw new AssertionError("Retry changed VK random_id");
            }
            fail.set(true);
            try {
                client.send(event, reply);
                throw new AssertionError("VK API error was accepted");
            } catch (IOException expected) {
                if (expected.getMessage().contains("private-test-token")) {
                    throw new AssertionError("VK token leaked into error");
                }
            }
            fail.set(false);
            var png = new ChartRenderer().png(new ChartData("Диаграмма", List.of("Еда"),
                    List.of(new ChartSeries("Сумма", List.of(Money.of("100"))))));
            client.send(new VkEvent("message_new", 99, 123, 123, "chart-8", "/chart_expenses"),
                    new BotReply("Диаграмма готова", Screen.CHARTS, png));
            if (uploadedBytes.get() <= png.length
                    || !decode(lastBody.get()).get("attachment").equals("photo-99_77_abc")) {
                throw new AssertionError("Chart PNG was not attached to the VK message");
            }
        } finally {
            server.stop(0);
        }
        System.out.println("VK text delivery checks passed.");
    }

    private static Map<String, String> decode(String body) {
        var result = new HashMap<String, String>();
        for (String pair : body.split("&")) {
            String[] parts = pair.split("=", 2);
            result.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return result;
    }
}
