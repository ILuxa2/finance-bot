package financeos.vk;
import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
public final class VkChatCleanerTest {
    static final long PEER=2000000001L;
    static final ObjectMapper M=new ObjectMapper();
    static Map<String,String> form(String body){var result=new HashMap<String,String>();for(var field:body.split("&")){var p=field.split("=",2);result.put(URLDecoder.decode(p[0],StandardCharsets.UTF_8),URLDecoder.decode(p[1],StandardCharsets.UTF_8));}return result;}
    static Map<String,Object> msg(long cmid,long author,long date){var m=new HashMap<String,Object>();m.put("conversation_message_id",cmid);m.put("from_id",author);m.put("peer_id",PEER);m.put("date",date);return m;}
    public static void main(String[]args)throws Exception{
        var now=Instant.now();long old=now.minus(Duration.ofDays(3)).getEpochSecond();var history=new ArrayList<Map<String,Object>>();
        history.add(msg(1,123,old));history.add(msg(2,-99,old));history.add(msg(3,999,old));history.add(msg(501,123,old));history.add(msg(5,123,now.minusSeconds(100).getEpochSecond()));
        var action=msg(6,123,old);action.put("action",Map.of("type","chat_invite_user"));history.add(action);
        var other=msg(7,123,old);other.put("peer_id",PEER+1);history.add(other);
        var deleted=msg(8,123,old);deleted.put("is_deleted",true);history.add(deleted);history.add(msg(9,123,0));
        var allowed=new HashSet<Long>(Set.of(1L,2L));for(long i=10;i<260;i++){history.add(msg(i,i%2==0?123:-99,old));allowed.add(i);}
        var removed=new HashSet<Long>();var deletes=new AtomicInteger();var moderate=new AtomicBoolean(true);var privateChat=new AtomicBoolean(true);
        var server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        server.createContext("/method/",exchange->{try{
            var f=form(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));Object response;
            switch(exchange.getRequestURI().getPath()){
                case "/method/messages.getConversationMembers" -> response=Map.of("count",privateChat.get()?2:3,"items",privateChat.get()?List.of(Map.of("member_id",123),Map.of("member_id",-99)):List.of(Map.of("member_id",123),Map.of("member_id",-99),Map.of("member_id",999)));
                case "/method/messages.getConversationsById" -> response=Map.of("items",List.of(Map.of("peer",Map.of("id",PEER),"chat_settings",Map.of("acl",Map.of("can_moderate",moderate.get())))));
                case "/method/messages.getHistory" -> {int start=Integer.parseInt(f.get("offset"));var items=new ArrayList<Map<String,Object>>();for(var item:history.subList(Math.min(start,history.size()),Math.min(start+200,history.size()))){var value=new HashMap<>(item);if(removed.contains(((Number)item.get("conversation_message_id")).longValue()))value.put("is_deleted",true);items.add(value);}response=Map.of("items",items);}
                case "/method/messages.delete" -> {
                    if(!"1".equals(f.get("delete_for_all"))||!Long.toString(PEER).equals(f.get("peer_id"))||f.containsKey("message_ids"))throw new AssertionError("Wrong delete scope");
                    var ids=Arrays.stream(f.get("cmids").split(",")).map(Long::parseLong).toList();if(ids.size()>100)throw new AssertionError("Batch too big");
                    var answer=new ArrayList<Map<String,Object>>();for(long cmid:ids){if(!allowed.contains(cmid)&&cmid!=5)throw new AssertionError("Unsafe deletion "+cmid);if(removed.contains(cmid))throw new AssertionError("Already deleted message retried");if(cmid!=1)removed.add(cmid);answer.add(Map.of("peer_id",PEER,"conversation_message_id",cmid,"response",cmid==1?0:1));}deletes.incrementAndGet();response=answer;
                }
                default -> throw new AssertionError("Unexpected method");
            }
            var bytes=M.writeValueAsBytes(Map.of("response",response));exchange.sendResponseHeaders(200,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}
        }catch(Throwable failure){failure.printStackTrace();exchange.sendResponseHeaders(500,-1);exchange.close();}});
        server.start();try{
            var c=new VkChatCleaner(HttpClient.newHttpClient(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/method/messages.send"),"dummy",99,123,PEER);
            var result=c.clean(300,now.minus(Duration.ofDays(1)));if(result.removed()!=251||result.refused()!=1||result.more()||deletes.get()!=3)throw new AssertionError("Partial delete result/batching incorrect: "+result);
            result=c.clean(300,now.minus(Duration.ofDays(1)));if(result.removed()!=0||result.refused()!=1)throw new AssertionError("Repeated clear not idempotent");
            result=c.clean(300,now.plusSeconds(1));if(result.removed()!=1||!removed.contains(5L)||removed.contains(501L))throw new AssertionError("Manual clear lost processing boundary");
            int before=deletes.get();moderate.set(false);if(c.clean(300,now).blocked().isBlank()||deletes.get()!=before)throw new AssertionError("Missing moderation rights ignored");
            moderate.set(true);privateChat.set(false);if(c.clean(300,now).blocked().isBlank()||deletes.get()!=before)throw new AssertionError("Foreign member ignored");
            System.out.println("VK cleanup: both authors, age/checkpoint/privacy/rights guards, 100-item batches, refusal and idempotency passed.");
        }finally{server.stop(0);}
    }
}
