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
        var messages=new java.util.ArrayList<String>();
        var fail = new AtomicBoolean();
        var keyboardInvalid = new AtomicBoolean();
        var sendAttempts = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/method/messages.send", exchange -> {
            sendAttempts.incrementAndGet();
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            messages.add(lastBody.get());
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
        server.createContext("/method/messages.edit", exchange -> {
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = "{\"response\":1}".getBytes(StandardCharsets.UTF_8);
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
                    || !fallback.get("message").contains("/start") || fallback.get("message").contains("/report")) {
                throw new AssertionError("Invalid keyboard was not retried as text");
            }
            keyboardInvalid.set(false);
            var obsoleteButtons = new java.util.ArrayList<MenuButton>();
            for (int i = 0; i < 5; i++) obsoleteButtons.add(new MenuButton("Длинная сохранённая подпись варианта", "/choose/OLD/" + i));
            obsoleteButtons.add(new MenuButton("↩️ Отменить изменение", "/undo"));
            obsoleteButtons.add(new MenuButton("⬅️ Назад", "/back"));
            obsoleteButtons.add(new MenuButton("🏠 Главное меню", "/start"));
            client.send(new VkEvent("message_new", 99, 123, 123, "old-keyboard", ""),
                    new BotReply("Сохранённый результат", Screen.MAIN, null, obsoleteButtons));
            var upgraded = decode(lastBody.get());
            if (!upgraded.get("message").equals("Сохранённый результат")
                    || new ObjectMapper().readTree(upgraded.get("keyboard")).path("buttons").size() > 6) {
                throw new AssertionError("Previously queued reply lost its result during keyboard upgrade");
            }
            client.send(event, reply);
            if (!decode(lastBody.get()).get("random_id").equals(first.get("random_id"))) {
                throw new AssertionError("Retry changed VK random_id");
            }
            if (client.edit(event, reply, 12345) != 12345) throw new AssertionError("Edited page lost its message ID");
            var edited = decode(lastBody.get());
            if (!edited.get("message_id").equals("12345") || !edited.get("message").equals(reply.text())
                    || !edited.containsKey("keyboard")) throw new AssertionError("Page edit lost text or keyboard");
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
            messages.clear();var bundleEvent=new VkEvent("message_new",99,123,123,"bundle","/report_chart");
            var bundle=new BotReply("Original report",Screen.REPORT,png,null,java.util.Collections.nCopies(11,png));client.send(bundleEvent,bundle);
            if(messages.size()!=2)throw new AssertionError("12 charts not split into 10 + 2");var one=decode(messages.get(0));var two=decode(messages.get(1));
            if(one.get("attachment").split(",").length!=10||two.get("attachment").split(",").length!=2||!one.get("message").equals("Original report")||!one.containsKey("keyboard")||two.containsKey("keyboard"))throw new AssertionError("Bundle lost charts/report/keyboard");
            if(one.get("random_id").equals(two.get("random_id")))throw new AssertionError("Batch IDs collide");
            messages.clear();client.send(bundleEvent,bundle);if(!decode(messages.get(0)).get("random_id").equals(one.get("random_id"))||!decode(messages.get(1)).get("random_id").equals(two.get("random_id")))throw new AssertionError("Retry duplicates batches");
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
