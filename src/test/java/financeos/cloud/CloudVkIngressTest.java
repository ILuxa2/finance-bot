package financeos.cloud;

import com.sun.net.httpserver.HttpServer;
import financeos.vk.VkEventParser;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CloudVkIngressTest {
    private static CloudVkIngress.Request request(String method, String body) {
        var value = new CloudVkIngress.Request();
        value.httpMethod = method;
        value.body = body;
        return value;
    }

    public static void main(String[] args) throws Exception {
        var objects = new ConcurrentHashMap<String, byte[]>();
        var fail = new AtomicBoolean();
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/test-bucket/inbox/", exchange -> {
            try {
                if (fail.get()) exchange.sendResponseHeaders(503, -1);
                else if (!"Bearer local-test-token".equals(
                        exchange.getRequestHeaders().getFirst("Authorization"))) {
                    exchange.sendResponseHeaders(403, -1);
                } else if (!"*".equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                    exchange.sendResponseHeaders(400, -1);
                } else {
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    if (objects.putIfAbsent(exchange.getRequestURI().getPath(), body) == null) {
                        exchange.sendResponseHeaders(200, -1);
                    } else exchange.sendResponseHeaders(412, -1);
                }
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            var ingress = new CloudVkIngress(new VkEventParser(99, "local-test-secret"),
                    "confirmation-code", "test-bucket", HttpClient.newHttpClient(),
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    () -> "local-test-token",123,2000000001L);
            var confirmation = ingress.apply(request("POST",
                    "{\"type\":\"confirmation\",\"group_id\":99,\"secret\":\"local-test-secret\"}"));
            if (confirmation.statusCode != 200 || !"confirmation-code".equals(confirmation.body)) {
                throw new AssertionError("VK confirmation failed");
            }
            if (ingress.apply(request("POST", "{\"type\":\"message_new\",\"group_id\":99,"
                    + "\"secret\":\"wrong\"}")).statusCode != 403) {
                throw new AssertionError("Wrong VK secret was accepted");
            }
            String event = "{\"type\":\"message_new\",\"group_id\":99,"
                    + "\"secret\":\"local-test-secret\",\"object\":{\"message\":{"
                    + "\"id\":7,\"from_id\":123,\"peer_id\":123,\"text\":\"/report\"}}}";
            if (ingress.apply(request("POST", event)).statusCode != 200 || objects.size() != 1) {
                throw new AssertionError("Authenticated event was not persisted");
            }
            if (ingress.apply(request("POST", event)).statusCode != 200 || objects.size() != 1) {
                throw new AssertionError("Repeated VK event was not idempotent");
            }
            String stored = new String(objects.values().iterator().next(), StandardCharsets.UTF_8);
            if (!stored.contains("/report") || stored.contains("local-test-secret")) {
                throw new AssertionError("Stored event is invalid or contains the callback secret");
            }
            int count=objects.size();
            if(ingress.apply(request("POST",event.replace("\"from_id\":123","\"from_id\":999"))).statusCode!=200||objects.size()!=count)throw new AssertionError("Foreign user entered inbox");
            if(ingress.apply(request("POST",event.replace("\"peer_id\":123","\"peer_id\":2000000002"))).statusCode!=200||objects.size()!=count)throw new AssertionError("Foreign chat entered inbox");
            if(ingress.apply(request("POST","null")).statusCode!=400)throw new AssertionError("Malformed callback accepted");
            var sharedIngress=new CloudVkIngress(new VkEventParser(99,"local-test-secret"),"confirmation-code","test-bucket",HttpClient.newHttpClient(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()),()->"local-test-token",123,2000000001L,java.util.Set.of(123L,456L));
            String memberEvent=event.replace("\"from_id\":123","\"from_id\":456").replace("\"peer_id\":123","\"peer_id\":2000000001").replace("\"id\":7","\"id\":9,\"conversation_message_id\":9");
            int beforeMember=objects.size();if(sharedIngress.apply(request("POST",memberEvent)).statusCode!=200||objects.size()!=beforeMember+1)throw new AssertionError("Approved member not accepted");
            if(sharedIngress.apply(request("POST",memberEvent.replace("2000000001","456"))).statusCode!=200||objects.size()!=beforeMember+1)throw new AssertionError("Member direct message entered shared state");
            fail.set(true);
            String second = event.replace("\"id\":7", "\"id\":8");
            if (ingress.apply(request("POST", second)).statusCode != 503) {
                throw new AssertionError("Storage failure was acknowledged to VK");
            }
        } finally {
            server.stop(0);
        }
        System.out.println("Cloud VK ingress checks passed.");
    }
}
