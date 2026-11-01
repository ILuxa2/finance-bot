package financeos.vk;

import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;

public final class VkGroupLongPollClientTest {
    public static void main(String[] args) throws Exception {
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/setup", exchange -> {
            byte[] body = ("{\"response\":{\"server\":\"https://example.org/poll\","
                    + "\"key\":\"test-key\",\"ts\":\"70\"}}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.createContext("/poll", exchange -> {
            byte[] body = ("{\"ts\":\"71\",\"updates\":[{\"type\":\"message_new\","
                    + "\"object\":{\"message\":{\"id\":8,\"from_id\":123,"
                    + "\"peer_id\":123,\"text\":\"/report\"}}}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.createContext("/reset", exchange -> {
            byte[] body = "{\"failed\":2}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        try {
            int port = server.getAddress().getPort();
            var client = new VkGroupLongPollClient(HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + port + "/setup"), "test-token", 99);
            if (!client.server().ts().equals("70")) throw new AssertionError("Setup response was not read");
            var batch = client.poll(new VkGroupLongPollClient.Server(
                    URI.create("http://127.0.0.1:" + port + "/poll"), "test-key", "70"), "70");
            if (!batch.nextTs().equals("71") || batch.events().size() != 1
                    || !batch.events().getFirst().text().equals("/report")) {
                throw new AssertionError("Long Poll message was not parsed");
            }
            try {
                client.poll(new VkGroupLongPollClient.Server(
                        URI.create("http://127.0.0.1:" + port + "/reset"), "test-key", "70"), "70");
                throw new AssertionError("Expired Long Poll key was accepted");
            } catch (VkGroupLongPollClient.ResetRequired expected) {
                if (expected.reason() != 2) throw new AssertionError("Wrong reset reason");
            }
        } finally {
            server.stop(0);
        }
        System.out.println("VK Long Poll transport checks passed.");
    }
}
