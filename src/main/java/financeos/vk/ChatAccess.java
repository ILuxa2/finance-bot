package financeos.vk;
import java.util.*;
public final class ChatAccess {
 public static Set<Long> configured(long owner){return parse(owner,System.getenv("FINANCEOS_VK_MEMBER_IDS"));}
 public static Set<Long> parse(long owner,String value){var ids=new HashSet<Long>();ids.add(owner);if(value!=null&&!value.isBlank())for(var part:value.split(",")){long id=Long.parseLong(part.strip());if(id<=0)throw new IllegalArgumentException("Invalid member");ids.add(id);}if(ids.size()>50)throw new IllegalArgumentException("Too many members");return Set.copyOf(ids);}
 public static long memberStart(){String value=System.getenv("FINANCEOS_VK_MEMBER_START");long start=value==null||value.isBlank()?0:Long.parseLong(value);if(start<0)throw new IllegalArgumentException("Invalid activation checkpoint");return start;}
 public static boolean afterActivation(VkEvent event,long owner,long start){return event.userId()==owner||event.conversationMessageId()>start;}
 public static boolean eventAllowed(long actor,long peer,long owner,long chat,Set<Long> members){return actor==owner&&(peer==owner||peer==chat)||peer==chat&&members.contains(actor);}
}
