package financeos.vk;
public record VkEvent(String type,long groupId,long userId,long peerId,String eventId,String text,long conversationMessageId,java.util.List<String> photoUrls){
 public VkEvent {photoUrls=photoUrls==null?java.util.List.of():java.util.List.copyOf(photoUrls);if(photoUrls.size()>10)throw new IllegalArgumentException("Too many photos");}
 public VkEvent(String type,long groupId,long userId,long peerId,String eventId,String text,long conversationMessageId){this(type,groupId,userId,peerId,eventId,text,conversationMessageId,java.util.List.of());}
 public VkEvent(String type,long groupId,long userId,long peerId,String eventId,String text){this(type,groupId,userId,peerId,eventId,text,0);}
}
