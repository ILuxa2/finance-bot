package financeos.vk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Group Long Poll transport for a local bot process; no public URL is needed. */
public final class VkGroupLongPollClient {
    public record Server(URI uri, String key, String ts) {}
    public record Batch(String nextTs, List<VkEvent> events) {}
    public static final class ResetRequired extends IOException {
        private final int reason;
        ResetRequired(int reason) { super("VK Long Poll session reset required"); this.reason = reason; }
        public int reason() { return reason; }
    }

    private final HttpClient http;
    private final URI methodEndpoint;
    private final String token;
    private final long groupId;
    private final ObjectMapper mapper = new ObjectMapper();
    private final VkEventParser parser;

    public VkGroupLongPollClient(HttpClient http, URI methodEndpoint, String token, long groupId) {
        if (token == null || token.isBlank() || groupId <= 0) {
            throw new IllegalArgumentException("VK Long Poll configuration is incomplete");
        }
        this.http = http;
        this.methodEndpoint = methodEndpoint;
        this.token = token;
        this.groupId = groupId;
        this.parser = new VkEventParser(groupId, "local-long-poll");
    }

    public Server server() throws IOException, InterruptedException {
        String form = field("group_id", Long.toString(groupId)) + "&"
                + field("access_token", token) + "&" + field("v", "5.199");
        var request = HttpRequest.newBuilder(methodEndpoint).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8)).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) throw new IOException("VK Long Poll setup HTTP " + response.statusCode());
        JsonNode json = mapper.readTree(response.body());
        if (json.path("error").isObject()) {
            throw new IOException("VK Long Poll setup error " + json.path("error").path("error_code").asInt());
        }
        JsonNode data = json.path("response");
        URI uri = URI.create(data.path("server").asText(""));
        String key = data.path("key").asText("");
        String ts = data.path("ts").asText("");
        if (!"https".equals(uri.getScheme()) || key.isBlank() || ts.isBlank()) {
            throw new IOException("Invalid VK Long Poll server response");
        }
        return new Server(uri, key, ts);
    }

    public Batch poll(Server server, String ts) throws IOException, InterruptedException {
        String separator = server.uri().getQuery() == null ? "?" : "&";
        URI uri = URI.create(server.uri() + separator + field("act", "a_check") + "&"
                + field("key", server.key()) + "&" + field("ts", ts) + "&"
                + field("wait", "10"));
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(25)).GET().build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) throw new IOException("VK Long Poll HTTP " + response.statusCode());
        JsonNode json = mapper.readTree(response.body());
        if (json.has("failed")) throw new ResetRequired(json.path("failed").asInt());
        String nextTs = json.path("ts").asText("");
        if (nextTs.isBlank() || !json.path("updates").isArray()) {
            throw new IOException("Invalid VK Long Poll response");
        }
        List<VkEvent> events = new ArrayList<>();
        for (JsonNode update : json.path("updates")) {
            VkEvent event = parser.parseLongPoll(update);
            if (event.type().equals("message_new") || event.type().equals("message_event")) {
                events.add(event);
            }
        }
        return new Batch(nextTs, List.copyOf(events));
    }

    private static String field(String name, String value) {
        return URLEncoder.encode(name, StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
