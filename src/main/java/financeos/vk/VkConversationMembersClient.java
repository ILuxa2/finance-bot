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
import java.util.HashSet;
import java.util.Set;

/** Refuses to disclose financial data in a chat containing anyone else. */
public final class VkConversationMembersClient {
    private final HttpClient http;
    private final URI endpoint;
    private final String token;
    private final long groupId;
    private final ObjectMapper mapper = new ObjectMapper();

    public VkConversationMembersClient(HttpClient http, URI endpoint, String token, long groupId) {
        this.http = java.util.Objects.requireNonNull(http);
        this.endpoint = java.util.Objects.requireNonNull(endpoint);
        if (token == null || token.isBlank() || groupId <= 0) {
            throw new IllegalArgumentException("VK chat configuration is incomplete");
        }
        this.token = token;
        this.groupId = groupId;
    }

    public boolean onlyOwnerAndCommunity(long peerId, long ownerId)
            throws IOException, InterruptedException {
        if (peerId < 2_000_000_000L || ownerId <= 0) return false;
        String form = field("peer_id", Long.toString(peerId)) + "&"
                + field("group_id", Long.toString(groupId)) + "&"
                + field("count", "3") + "&"
                + field("access_token", token) + "&" + field("v", "5.199");
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8)).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("VK member check HTTP " + response.statusCode());
        }
        JsonNode root = mapper.readTree(response.body());
        if (root.path("error").isObject()) {
            throw new IOException("VK member check error " + root.path("error").path("error_code").asInt());
        }
        return onlyOwnerAndCommunity(root.path("response"), ownerId, groupId);
    }

    static boolean onlyOwnerAndCommunity(JsonNode response, long ownerId, long groupId) {
        JsonNode items = response.path("items");
        if (response.path("count").asInt(-1) != 2 || !items.isArray() || items.size() != 2) {
            return false;
        }
        Set<Long> members = new HashSet<>();
        for (JsonNode item : items) {
            long id = item.path("member_id").asLong();
            if (id == 0) return false;
            members.add(id);
        }
        return members.size() == 2 && members.contains(ownerId) && members.contains(-groupId);
    }

    private static String field(String name, String value) {
        return URLEncoder.encode(name, StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
