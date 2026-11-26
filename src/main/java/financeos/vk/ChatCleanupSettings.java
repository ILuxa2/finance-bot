package financeos.vk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.Set;

public record ChatCleanupSettings(int days, String lastDate, String attemptDate, int attempts, String notBefore, String leaseId) {
    public ChatCleanupSettings(int days,String lastDate){this(days,lastDate,"",0,"","");}
    public ChatCleanupSettings {
        if (!Set.of(0,1,7,30).contains(days) || lastDate == null) throw new IllegalArgumentException("Invalid cleanup settings");
        if (!lastDate.isBlank()) LocalDate.parse(lastDate);
        if(attemptDate==null||notBefore==null||leaseId==null||attempts<0||attempts>3)throw new IllegalArgumentException("Invalid cleanup retry settings");
        if(!attemptDate.isBlank())LocalDate.parse(attemptDate);
        if(!notBefore.isBlank())java.time.Instant.parse(notBefore);
    }
    public static ChatCleanupSettings off() { return new ChatCleanupSettings(0, ""); }
    public static ChatCleanupSettings read(JsonNode root) {
        if (!root.has("chatCleanup")) return off();
        var node = root.path("chatCleanup");
        if (!node.isObject() || !node.path("days").isIntegralNumber() || !node.path("lastDate").isTextual()) throw new IllegalArgumentException("Invalid cleanup settings");
        return new ChatCleanupSettings(node.path("days").asInt(), node.path("lastDate").asText(),node.path("attemptDate").asText(""),node.path("attempts").asInt(0),node.path("notBefore").asText(""),node.path("leaseId").asText(""));
    }
    public void write(ObjectNode root) { var node = root.putObject("chatCleanup"); node.put("days", days); node.put("lastDate", lastDate);node.put("attemptDate",attemptDate);node.put("attempts",attempts);node.put("notBefore",notBefore);node.put("leaseId",leaseId); }
}
