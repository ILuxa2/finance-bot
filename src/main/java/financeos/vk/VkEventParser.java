package financeos.vk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class VkEventParser {
    private final ObjectMapper mapper = new ObjectMapper();
    private final long expectedGroupId;
    private final byte[] expectedSecret;

    public VkEventParser(long expectedGroupId, String secret) {
        if (expectedGroupId <= 0 || secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("VK callback configuration is incomplete");
        }
        this.expectedGroupId = expectedGroupId;
        this.expectedSecret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public VkEvent parse(byte[] body) throws IOException {
        JsonNode root = mapper.readTree(body);
        if (root == null || !root.isObject()) throw new IOException("Invalid VK callback JSON");
        long groupId = root.path("group_id").asLong();
        byte[] supplied = root.path("secret").asText("").getBytes(StandardCharsets.UTF_8);
        if (groupId != expectedGroupId || !MessageDigest.isEqual(expectedSecret, supplied)) {
            throw new SecurityException("VK callback secret or group does not match");
        }
        return parseEvent(root);
    }

    /** Group Long Poll events arrive over the authenticated HTTPS session, without callback secret. */
    public VkEvent parseLongPoll(JsonNode event) throws IOException {
        if (event == null || !event.isObject()) throw new IOException("Invalid VK Long Poll event");
        ObjectNode withGroup = event.deepCopy();
        withGroup.put("group_id", expectedGroupId);
        return parseEvent(withGroup);
    }

    private VkEvent parseEvent(JsonNode root) throws IOException {
        long groupId = root.path("group_id").asLong();
        String type = root.path("type").asText("");
        JsonNode object = root.path("object");
        if (type.equals("message_new")) {
            JsonNode message = object.path("message");
            long userId = message.path("from_id").asLong();
            long peerId = message.path("peer_id").asLong();
            long conversationId = message.path("conversation_message_id").asLong();
            String id = message.path("id").asText("");
            if (id.isBlank() || id.equals("0")) {
                if (conversationId <= 0) throw new IOException("VK message has no stable ID");
                id = "conversation-" + conversationId;
            }
            String text = message.path("text").asText("").strip();
            JsonNode payload = message.path("payload");
            if (payload.isTextual()) payload = mapper.readTree(payload.asText());
            String buttonCommand = payload.path("cmd").asText("");
            if (buttonCommand.startsWith("/")) text = buttonCommand;
            String mention = "@club" + expectedGroupId + " ";
            if (text.startsWith(mention)) text = text.substring(mention.length()).strip();
            if (userId <= 0 || peerId <= 0 || id.isBlank()) throw new IOException("Incomplete VK message");
            var photos=new java.util.ArrayList<String>();
            for(var attachment:message.path("attachments"))if(attachment.path("type").asText().equals("photo")){
                String url="";long pixels=-1;
                for(var size:attachment.path("photo").path("sizes")){
                    long area=(long)size.path("width").asInt()*size.path("height").asInt();
                    if(area>pixels&&!size.path("url").asText().isBlank()){url=size.path("url").asText();pixels=area;}
                }
                if(!url.isBlank()&&photos.size()<10)photos.add(url);
            }
            return new VkEvent(type, groupId, userId, peerId, id, text, conversationId,photos);
        }
        if (type.equals("message_event")) {
            long userId = object.path("user_id").asLong();
            long peerId = object.path("peer_id").asLong();
            String id = object.path("event_id").asText("");
            JsonNode payload = object.path("payload");
            if (payload.isTextual()) payload = mapper.readTree(payload.asText());
            String command = payload.path("cmd").asText("");
            if (userId <= 0 || peerId <= 0 || id.isBlank()) throw new IOException("Incomplete VK button event");
            return new VkEvent(type, groupId, userId, peerId, id, command);
        }
        return new VkEvent(type, groupId, 0, 0, "", "");
    }
}
