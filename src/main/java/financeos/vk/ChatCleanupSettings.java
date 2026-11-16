package financeos.vk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.Set;

public record ChatCleanupSettings(int days, String lastDate) {
    public ChatCleanupSettings {
        if (!Set.of(0,1,7,30).contains(days) || lastDate == null) throw new IllegalArgumentException("Invalid cleanup settings");
        if (!lastDate.isBlank()) LocalDate.parse(lastDate);
    }
    public static ChatCleanupSettings off() { return new ChatCleanupSettings(0, ""); }
    public static ChatCleanupSettings read(JsonNode root) {
        if (!root.has("chatCleanup")) return off();
        var node = root.path("chatCleanup");
        if (!node.isObject() || !node.path("days").isIntegralNumber() || !node.path("lastDate").isTextual()) throw new IllegalArgumentException("Invalid cleanup settings");
        return new ChatCleanupSettings(node.path("days").asInt(), node.path("lastDate").asText());
    }
    public void write(ObjectNode root) { var node = root.putObject("chatCleanup"); node.put("days", days); node.put("lastDate", lastDate); }
}
