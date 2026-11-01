package financeos.vk;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

public final class VkHistoryClientTest {
    public static void main(String[] args) throws Exception {
        var fail = new AtomicBoolean();
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/history", exchange -> {
            try {
                String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (!request.contains("access_token=test-token")) {
                    exchange.sendResponseHeaders(403, -1);
                    return;
                }
                String body = fail.get() ? "{\"error\":{\"error_code\":15}}"
                        : "{\"response\":{\"items\":["
                        + "{\"id\":105,\"conversation_message_id\":105,\"from_id\":42,\"peer_id\":2000000001,\"out\":0,\"text\":\"second\"},"
                        + "{\"id\":104,\"conversation_message_id\":104,\"from_id\":-99,\"peer_id\":2000000001,\"out\":1,\"text\":\"reply\"},"
                        + "{\"id\":103,\"conversation_message_id\":103,\"from_id\":42,\"peer_id\":2000000001,\"out\":0,\"text\":\"button\",\"payload\":\"{\\\"cmd\\\":\\\"/report\\\"}\"},"
                        + "{\"id\":102,\"conversation_message_id\":102,\"from_id\":77,\"peer_id\":2000000001,\"out\":0,\"text\":\"other user\"},"
                        + "{\"id\":101,\"conversation_message_id\":101,\"from_id\":42,\"peer_id\":2000000001,\"out\":0,\"text\":\"first\"},"
                        + "{\"id\":100,\"conversation_message_id\":100,\"from_id\":42,\"peer_id\":2000000001,\"out\":0,\"text\":\"old\"}]}}";
                byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            var history = new VkHistoryClient(HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/history"),
                    "test-token", 99);
            if (history.latestConversationMessageId(2000000001L) != 105) {
                throw new AssertionError("VK cutover checkpoint was not read");
            }
            var events = history.since(2000000001L, 42, 100);
            if (events.size() != 3 || events.get(0).conversationMessageId() != 101
                    || events.get(1).conversationMessageId() != 103
                    || events.get(2).conversationMessageId() != 105
                    || !"/report".equals(events.get(1).text())) {
                throw new AssertionError("VK messages are not replayed in owner order");
            }
            fail.set(true);
            try {
                history.since(2000000001L, 42, 100);
                throw new AssertionError("VK history failure was ignored");
            } catch (java.io.IOException expected) {
                if (!expected.getMessage().contains("error 15")) throw expected;
            }
        } finally {
            server.stop(0);
        }
        System.out.println("VK history ordering checks passed.");
    }
}
