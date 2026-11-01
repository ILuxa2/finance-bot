package financeos.vk;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public final class LocalVkWebhookServerTest {
    private static HttpResponse<String> post(HttpClient client, int port, String json) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/vk"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }

    public static void main(String[] args) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var seen = new AtomicReference<VkEvent>();
        try (var server = new LocalVkWebhookServer(0, new VkEventParser(99, "local-test-secret"),
                "confirmation-code", event -> {
                    entered.countDown();
                    try { release.await(2, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    seen.set(event);
                })) {
            server.start();
            var client = HttpClient.newHttpClient();
            var confirmation = post(client, server.port(),
                    "{\"type\":\"confirmation\",\"group_id\":99,\"secret\":\"local-test-secret\"}");
            if (confirmation.statusCode() != 200 || !confirmation.body().equals("confirmation-code"))
                throw new AssertionError("Confirmation failed");
            var denied = post(client, server.port(),
                    "{\"type\":\"message_new\",\"group_id\":99,\"secret\":\"wrong\"}");
            if (denied.statusCode() != 403) throw new AssertionError("Wrong secret was accepted");
            var accepted = post(client, server.port(),
                    "{\"type\":\"message_new\",\"group_id\":99,\"secret\":\"local-test-secret\","
                    + "\"object\":{\"message\":{\"id\":7,\"from_id\":123,\"peer_id\":123,"
                    + "\"text\":\"@club99 /report\"}}}");
            if (accepted.statusCode() != 200 || !accepted.body().equals("ok"))
                throw new AssertionError("Callback was not acknowledged");
            if (!entered.await(2, TimeUnit.SECONDS)) throw new AssertionError("Worker did not start");
            if (seen.get() != null) throw new AssertionError("HTTP response waited for business work");
            release.countDown();
            for (int attempt = 0; attempt < 20 && seen.get() == null; attempt++) Thread.sleep(10);
            if (seen.get() == null || !seen.get().text().equals("/report"))
                throw new AssertionError("Mentioned command was not parsed");
            var button = new VkEventParser(99, "local-test-secret").parse((
                    "{\"type\":\"message_new\",\"group_id\":99,\"secret\":\"local-test-secret\","
                    + "\"object\":{\"message\":{\"id\":8,\"from_id\":123,\"peer_id\":123,"
                    + "\"text\":\"@club99 История операций\","
                    + "\"payload\":\"{\\\"cmd\\\":\\\"/history\\\"}\"}}}")
                    .getBytes(StandardCharsets.UTF_8));
            if (!button.text().equals("/history")) throw new AssertionError("VK button payload was ignored");
        }
        System.out.println("Local VK callback checks passed.");
    }
}
