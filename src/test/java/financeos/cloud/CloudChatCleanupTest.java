package financeos.cloud;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.*;
import financeos.core.*;
import financeos.data.*;
import financeos.vk.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;

public final class CloudChatCleanupTest {
    static final ObjectMapper M=new ObjectMapper();static final long PEER=2000000001L;
    static String etag(byte[] b){try{return "\""+HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(b))+"\"";}catch(Exception e){throw new RuntimeException(e);}}
    static Map<String,String> form(String body){var r=new HashMap<String,String>();for(var f:body.split("&")){var p=f.split("=",2);r.put(URLDecoder.decode(p[0],StandardCharsets.UTF_8),URLDecoder.decode(p[1],StandardCharsets.UTF_8));}return r;}
    static ObjectNode local(byte[] envelope)throws Exception{return (ObjectNode)M.readTree(Base64.getDecoder().decode(M.readTree(envelope).path("stateBase64").asText()));}
    static byte[] envelope(ObjectNode root,LinkedHashMap<String,CloudVkProcessor.SavedReply> replies)throws Exception{return M.writeValueAsBytes(new CloudVkProcessor.Envelope(1,Base64.getEncoder().encodeToString(M.writeValueAsBytes(root)),replies,new LinkedHashSet<>(),"",100));}
    static ObjectNode finances(ObjectNode node){var c=node.deepCopy();c.remove("chatCleanup");return c;}
    public static void main(String[]args)throws Exception{
        var sheets=new LinkedHashMap<String,List<Map<String,String>>>();
        LegacySheetSchema.headers().keySet().forEach(name->sheets.put(name,List.of()));
        sheets.put("StartupState",List.of(Map.of("telegram_user_id","7","created_at",OffsetDateTime.now().minusDays(10).toString(),"total_balance","1000")));
        var data=new LegacyDataMapper().user(sheets,"7");
        byte[] backup=M.writeValueAsBytes(Map.of("formatVersion",1,"sheets",sheets));
        var engine=new LocalBotEngine(42,Set.of(42L,PEER),data,Clock.systemUTC());
        engine.handle(new VkEvent("message_new",99,42,PEER,"start-clear","/chat_clear",90));
        var confirmation=new VkEvent("message_new",99,42,PEER,"confirmed-clear","/chat_clear_confirm",100);
        var reply=engine.handle(confirmation).orElseThrow();
        var path=Files.createTempFile("cloud-cleanup-", ".json");
        var file=new LocalStateFile(path,42,data);file.save(engine.exportState());var original=(ObjectNode)M.readTree(Files.readAllBytes(path));Files.delete(path);
        var replies=new LinkedHashMap<String,CloudVkProcessor.SavedReply>();
        replies.put(CloudVkIngress.eventId(confirmation),new CloudVkProcessor.SavedReply(reply.text(),reply.screen(),null,null,confirmation,false));
        var state=new AtomicReference<>(envelope(original,replies));var deletes=new AtomicInteger();var sends=new AtomicInteger();
        var emptyChat=new AtomicBoolean();var lastSent=new AtomicReference<String>("");
        var removed=new HashSet<Long>();var privateChat=new AtomicBoolean(true);var moderates=new AtomicBoolean(true);
        long old=Instant.now().minus(Duration.ofDays(3)).getEpochSecond();
        var server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        server.createContext("/test-bucket/",exchange->{try{
            if(exchange.getRequestURI().getPath().contains("/backups/")){exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(200,-1);return;}
            byte[] current=state.get();
            if(exchange.getRequestMethod().equals("GET")){
                if(etag(current).equals(exchange.getRequestHeaders().getFirst("If-None-Match"))){exchange.sendResponseHeaders(304,-1);return;}
                exchange.getResponseHeaders().set("ETag",etag(current));exchange.sendResponseHeaders(200,current.length);exchange.getResponseBody().write(current);
            }else if(exchange.getRequestMethod().equals("PUT")){
                if(!etag(current).equals(exchange.getRequestHeaders().getFirst("If-Match"))){exchange.sendResponseHeaders(412,-1);return;}
                var next=exchange.getRequestBody().readAllBytes();state.set(next);exchange.getResponseHeaders().set("ETag",etag(next));exchange.sendResponseHeaders(200,-1);
            }else exchange.sendResponseHeaders(405,-1);
        }finally{exchange.close();}});
        server.createContext("/method/",exchange->{try{
            var f=form(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));Object response;
            switch(exchange.getRequestURI().getPath()){
                case "/method/messages.getConversationMembers" -> response=Map.of("count",privateChat.get()?2:3,"items",privateChat.get()?List.of(Map.of("member_id",42),Map.of("member_id",-99)):List.of(Map.of("member_id",42),Map.of("member_id",-99),Map.of("member_id",7)));
                case "/method/messages.getConversationsById" -> response=Map.of("items",List.of(Map.of("peer",Map.of("id",PEER),"chat_settings",Map.of("acl",Map.of("can_moderate",moderates.get())))));
                case "/method/messages.getHistory" -> {var items=new ArrayList<Map<String,Object>>();for(long cmid:emptyChat.get()?List.of(1L,2L):List.of(1L,2L,101L,200L)){items.add(Map.of("peer_id",PEER,"conversation_message_id",cmid,"from_id",cmid%2==0?42:-99,"date",old,"is_deleted",removed.contains(cmid)));}response=Map.of("items",items);}
                case "/method/messages.delete" -> {
                    if(M.readTree(state.get()).path("lastConversationMessageId").asLong()!=100)throw new AssertionError("Delete before checkpoint");
                    var result=new ArrayList<Map<String,Object>>();for(var part:f.get("cmids").split(",")){long cmid=Long.parseLong(part);if(cmid>100)throw new AssertionError("Deleted an unprocessed/new message");removed.add(cmid);result.add(Map.of("peer_id",PEER,"conversation_message_id",cmid,"response",1));}deletes.incrementAndGet();response=result;
                }
                case "/method/messages.send" -> {sends.incrementAndGet();lastSent.set(f.get("message"));if(lastSent.get().equals(MenuCatalog.welcomeHelp())&&f.containsKey("keyboard"))throw new AssertionError("Empty help contains keyboard");response=1234;}
                default -> throw new AssertionError("Unknown cleanup method");
            }
            byte[] bytes=M.writeValueAsBytes(Map.of("response",response));exchange.sendResponseHeaders(200,bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}
        }catch(Throwable failure){failure.printStackTrace();exchange.sendResponseHeaders(500,-1);exchange.close();}});
        server.start();try{
            var base=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
            var processor=new CloudVkProcessor(HttpClient.newHttpClient(),base,base.resolve("/method/messages.getHistory"),base.resolve("/method/messages.send"),base.resolve("/method/messages.getConversationMembers"),()->"dummy-iam","test-bucket","dummy-vk",99,42,PEER,"base.json","state.json");
            processor.retryPendingReplies();
            if(deletes.get()!=1||sends.get()!=1||removed.size()!=2)throw new AssertionError("Confirmed clear not executed exactly once");
            var delivered=M.readValue(state.get(),CloudVkProcessor.Envelope.class).replies().get(CloudVkIngress.eventId(confirmation));
            if(!delivered.delivered()||!finances(local(state.get())).equals(finances(original)))throw new AssertionError("Cleanup changed financial state or lost receipt");
            processor.retryPendingReplies();if(deletes.get()!=1||sends.get()!=1)throw new AssertionError("Delivered cleanup executed again");
            if(lastSent.get().equals(MenuCatalog.welcomeHelp()))throw new AssertionError("Help despite remaining new messages");
            emptyChat.set(true);removed.clear();deletes.set(0);sends.set(0);state.set(envelope(original,replies));
            processor.retryPendingReplies();
            if(sends.get()!=1 || !lastSent.get().equals(MenuCatalog.welcomeHelp()) || lastSent.get().contains("Удалено в этой попытке"))throw new AssertionError("Empty chat did not receive help only");
            processor.retryPendingReplies();if(sends.get()!=1)throw new AssertionError("Empty-chat help repeated");
            emptyChat.set(false);
            var direct=new VkEvent("message_new",99,42,42,"wrong-peer","/chat_clear_confirm",100);
            var directReplies=new LinkedHashMap<String,CloudVkProcessor.SavedReply>();
            directReplies.put(CloudVkIngress.eventId(direct),new CloudVkProcessor.SavedReply("intent",Screen.CHAT_CLEAR_RESULT,null,null,direct,false));
            state.set(envelope(original,directReplies));processor.retryPendingReplies();if(deletes.get()!=1)throw new AssertionError("Direct message cleared another conversation");
            engine.handle(new VkEvent("message_new",99,42,PEER,"prepare-auto","/chat_auto_1",110));
            file.save(engine.exportState());String pendingState=Base64.getEncoder().encodeToString(Files.readAllBytes(path));Files.delete(path);
            var calculate=CloudVkProcessor.class.getDeclaredMethod("calculate",byte[].class,String.class,VkEvent.class);calculate.setAccessible(true);
            var computed=calculate.invoke(processor,backup,pendingState,new VkEvent("message_new",99,42,42,"wrong-auto-peer","/chat_auto_confirm",111));
            var bytesAccessor=computed.getClass().getDeclaredMethod("state");bytesAccessor.setAccessible(true);
            if(ChatCleanupSettings.read(M.readTree((byte[])bytesAccessor.invoke(computed))).days()!=0)throw new AssertionError("Direct message enabled deletion in another chat");
            var enabled=original.deepCopy();new ChatCleanupSettings(1,"").write(enabled);removed.clear();deletes.set(0);state.set(envelope(enabled,new LinkedHashMap<>()));
            processor.runAutomaticCleanup();if(deletes.get()!=1||!ChatCleanupSettings.read(local(state.get())).lastDate().equals(LocalDate.now(ZoneId.of("Europe/Moscow")).toString()))throw new AssertionError("Daily cleanup not recorded");
            if(!finances(local(state.get())).equals(finances(original)))throw new AssertionError("Automatic cleanup changed money");
            processor.runAutomaticCleanup();if(deletes.get()!=1)throw new AssertionError("Cleanup repeated on same day");
            var pending=new LinkedHashMap<String,CloudVkProcessor.SavedReply>();pending.put("pending",new CloudVkProcessor.SavedReply("reply",Screen.MAIN,null,null,new VkEvent("message_new",99,42,PEER,"pending","/report",100),false));
            state.set(envelope(enabled,pending));processor.runAutomaticCleanup();if(deletes.get()!=1||!ChatCleanupSettings.read(local(state.get())).lastDate().isEmpty())throw new AssertionError("Cleanup ignored an undelivered result");
            emptyChat.set(true);removed.clear();int helpBefore=sends.get();state.set(envelope(enabled,new LinkedHashMap<>()));processor.runAutomaticCleanup();
            if(sends.get()!=helpBefore+1||!lastSent.get().equals(MenuCatalog.welcomeHelp()))throw new AssertionError("Automatic empty clear did not send help only");
            emptyChat.set(false);int expectedDeletes=deletes.get();
            privateChat.set(false);state.set(envelope(enabled,new LinkedHashMap<>()));processor.runAutomaticCleanup();if(deletes.get()!=expectedDeletes)throw new AssertionError("Privacy guard ignored");
            privateChat.set(true);moderates.set(false);int before=sends.get();state.set(envelope(enabled,new LinkedHashMap<>()));processor.runAutomaticCleanup();if(deletes.get()!=expectedDeletes||sends.get()!=before+1)throw new AssertionError("Blocked cleanup was not reported");
            System.out.println("Cloud cleanup: post-commit manual execution, receipt replay, daily throttling, financial isolation and pending/privacy/rights guards passed.");
        }finally{server.stop(0);}
    }
}
