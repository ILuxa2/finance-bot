package financeos.vk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Reads a VK conversation in its original order, including messages whose callbacks arrive late. */
public final class VkHistoryClient {
    private static final int PAGE_SIZE = 200;
    private final HttpClient http;
    private final URI endpoint;
    private final String token;
    private final long groupId;
    private final VkEventParser parser;
    private final ObjectMapper mapper = new ObjectMapper();

    public VkHistoryClient(HttpClient http, URI endpoint, String token, long groupId) {
        if (token == null || token.isBlank() || groupId <= 0) {
            throw new IllegalArgumentException("VK history configuration is incomplete");
        }
        this.http = http;
        this.endpoint = endpoint;
        this.token = token;
        this.groupId = groupId;
        this.parser = new VkEventParser(groupId, "history-read-only");
    }

    public long latestConversationMessageId(long peerId) throws IOException, InterruptedException {
        var items = page(peerId, 0, 1);
        return items.isEmpty() ? 0 : items.getFirst().path("conversation_message_id").asLong();
    }

    public List<VkEvent> since(long peerId, long ownerId, long checkpoint)
            throws IOException, InterruptedException {
        if (checkpoint < 0) throw new IllegalArgumentException("Invalid VK history checkpoint");
        List<VkEvent> result = new ArrayList<>();
        int offset = 0;
        for (int pageNumber = 0; pageNumber < 50; pageNumber++) {
            var items = page(peerId, offset, PAGE_SIZE);
            if (items.isEmpty()) break;
            boolean reachedCheckpoint = false;
            for (var message : items) {
                long sequence = message.path("conversation_message_id").asLong();
                if (sequence <= 0) throw new IOException("VK history lacks conversation message ID");
                if (sequence <= checkpoint) {
                    reachedCheckpoint = true;
                    continue;
                }
                if (message.path("from_id").asLong() != ownerId
                        || message.path("peer_id").asLong() != peerId
                        || message.path("out").asInt() != 0) continue;
                ObjectNode event = mapper.createObjectNode();
                event.put("type", "message_new");
                event.set("object", mapper.createObjectNode().set("message", message));
                result.add(parser.parseLongPoll(event));
            }
            if (reachedCheckpoint || items.size() < PAGE_SIZE) break;
            offset += items.size();
            if (pageNumber == 49) throw new IOException("VK history gap is too large for safe replay");
        }
        result.sort(Comparator.comparingLong(VkEvent::conversationMessageId));
        return result;
    }

    private List<JsonNode> page(long peerId, int offset, int count)
            throws IOException, InterruptedException {
        String body = field("peer_id", Long.toString(peerId)) + "&"
                + field("offset", Integer.toString(offset)) + "&"
                + field("count", Integer.toString(count)) + "&"
                + field("access_token", token) + "&" + field("v", "5.199");
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) throw new IOException("VK history HTTP " + response.statusCode());
        var root = mapper.readTree(response.body());
        if (root.path("error").isObject()) {
            throw new IOException("VK history API error " + root.path("error").path("error_code").asInt());
        }
        var items = root.path("response").path("items");
        if (!items.isArray()) throw new IOException("VK history response has no items");
        List<JsonNode> result = new ArrayList<>();
        items.forEach(result::add);
        return result;
    }

    private static String field(String key, String value) {
        return URLEncoder.encode(key, StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
