package financeos.vk;

public record VkEvent(String type, long groupId, long userId, long peerId,
                      String eventId, String text, long conversationMessageId) {
    public VkEvent(String type, long groupId, long userId, long peerId,
                   String eventId, String text) {
        this(type, groupId, userId, peerId, eventId, text, 0);
    }
}
