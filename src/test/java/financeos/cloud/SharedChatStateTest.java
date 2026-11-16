package financeos.cloud;
import financeos.core.*;import financeos.data.*;import financeos.vk.*;import com.fasterxml.jackson.databind.*;import com.fasterxml.jackson.databind.node.*;import java.time.*;import java.nio.file.*;import java.util.*;import java.net.*;import java.net.http.*;
public final class SharedChatStateTest {
 static final ObjectMapper M=new ObjectMapper();static final long OWNER=42,MEMBER=77,PEER=2000000001L;static long id;
 static ObjectNode root;static byte[] base;static java.lang.reflect.Method calculate;static CloudVkProcessor processor;
 static BotReply send(long actor,String text)throws Exception {
   var event=new VkEvent("message_new",99,actor,PEER,"shared-"+(++id),text,id);
   var result=calculate.invoke(processor,base,Base64.getEncoder().encodeToString(M.writeValueAsBytes(root)),event);
   var stateMethod=result.getClass().getDeclaredMethod("state");stateMethod.setAccessible(true);root=(ObjectNode)M.readTree((byte[])stateMethod.invoke(result));
   var replyMethod=result.getClass().getDeclaredMethod("reply");replyMethod.setAccessible(true);return (BotReply)replyMethod.invoke(result);
 }
 static void has(BotReply reply,String value){if(reply==null||!reply.text().contains(value))throw new AssertionError("Missing "+value);}
 public static void main(String[]a)throws Exception {
  var sheets=new LinkedHashMap<String,List<Map<String,String>>>();LegacySheetSchema.headers().keySet().forEach(k->sheets.put(k,List.of()));
  sheets.put("StartupState",List.of(Map.of("telegram_user_id","7","created_at",OffsetDateTime.now().minusDays(1).toString(),"total_balance","1000")));
  base=M.writeValueAsBytes(Map.of("formatVersion",1,"sheets",sheets));var data=new LegacyDataMapper().user(sheets,"7");
  var path=Files.createTempFile("shared-chat-",".json");var file=new LocalStateFile(path,OWNER,data);file.save(new LocalBotEngine(OWNER,Set.of(OWNER,PEER),data,Clock.systemUTC()).exportState());root=(ObjectNode)M.readTree(Files.readAllBytes(path));Files.delete(path);
  var url=URI.create("http://127.0.0.1:1");processor=new CloudVkProcessor(HttpClient.newHttpClient(),url,url,url,url,()->"dummy","test-bucket","dummy",99,OWNER,PEER,"base.json","state.json",Set.of(OWNER,MEMBER));
  calculate=CloudVkProcessor.class.getDeclaredMethod("calculate",byte[].class,String.class,VkEvent.class);calculate.setAccessible(true);
  has(send(OWNER,"/expense"),"категорию");send(OWNER,"Еда");has(send(MEMBER,"/start"),"Главное меню");has(send(MEMBER,"/report"),"1000.00");
  has(send(OWNER,"10, ужин"),"сохранён");has(send(MEMBER,"/report"),"990.00");
  send(MEMBER,"/income");send(MEMBER,"Зарплата");send(OWNER,"/history_all");has(send(MEMBER,"20, аванс"),"сохранён");has(send(OWNER,"/report"),"1010.00");
  if(root.path("entries").size()!=2||root.path("operationAuthors").path("local-1").asLong()!=OWNER||root.path("operationAuthors").path("local-2").asLong()!=MEMBER)throw new AssertionError("Shared entries/author audit incorrect");
  root=(ObjectNode)M.readTree(M.writeValueAsBytes(root));has(send(MEMBER,"/report"),"1010.00");
  has(send(MEMBER,"/chat_clear"),"только в настроенной");if(send(999,"/report")!=null)throw new AssertionError("Foreign actor accepted");
  if(!ChatAccess.eventAllowed(MEMBER,PEER,OWNER,PEER,Set.of(OWNER,MEMBER))||ChatAccess.eventAllowed(MEMBER,MEMBER,OWNER,PEER,Set.of(OWNER,MEMBER)))throw new AssertionError("DM scope failed");
  if(ChatAccess.afterActivation(new VkEvent("message_new",99,MEMBER,PEER,"old","/expense",50),OWNER,50)||!ChatAccess.afterActivation(new VkEvent("message_new",99,MEMBER,PEER,"new","/expense",51),OWNER,50))throw new AssertionError("Pre-authorization message accepted");
  send(OWNER,"/defaults_setup");send(MEMBER,"/defaults_setup");send(OWNER,"/defaults_confirm");var categories=root.path("categorySections").deepCopy();has(send(MEMBER,"/defaults_confirm"),"изменилась");if(!root.path("categorySections").equals(categories))throw new AssertionError("Second member duplicated defaults");
  var ownerQuick=send(OWNER,"-3, быстрый расход владельца");var memberQuick=send(MEMBER,"+5, быстрый доход участника");
  String ownerChoice=ownerQuick.buttons().stream().filter(b->b.command().startsWith("/choose/")).findFirst().orElseThrow().command();
  String memberChoice=memberQuick.buttons().stream().filter(b->b.command().startsWith("/choose/")).findFirst().orElseThrow().command();
  root=(ObjectNode)M.readTree(M.writeValueAsBytes(root));has(send(OWNER,ownerChoice),"Расход сохранён");has(send(MEMBER,memberChoice),"Доход сохранён");
  if(root.path("entries").size()!=4||root.path("operationAuthors").path("local-3").asLong()!=OWNER||root.path("operationAuthors").path("local-4").asLong()!=MEMBER)throw new AssertionError("Quick drafts crossed actors");
  has(send(OWNER,"/report"),"1012.00");
  var categoryList=send(OWNER,"/report_categories");String categoryChoice=categoryList.buttons().stream().filter(b->b.command().startsWith("/choose/")).findFirst().orElseThrow().command();send(OWNER,categoryChoice);
  if(!root.path("historyKind").asText().equals("EXPENSE"))throw new AssertionError("Category kind missing");
  send(MEMBER,"/history_all");if(!root.path("historyKind").asText().isEmpty())throw new AssertionError("Owner expense filter leaked to member");
  root=(ObjectNode)M.readTree(M.writeValueAsBytes(root));send(OWNER,"/history_prev");if(!root.path("historyKind").asText().equals("EXPENSE"))throw new AssertionError("Owner filter lost after member history");
  var beforeUnplannedPlans=root.path("plans").deepCopy();var beforeUnplannedEdits=root.path("planEdits").deepCopy();int beforeUnplannedCount=root.path("entries").size();
  send(OWNER,"/expense");send(OWNER,"Питание");send(OWNER,"QA Owner unplanned");var ownerUnplanned=send(OWNER,"500, unplanned owner");
  send(MEMBER,"/expense");send(MEMBER,"Питание");send(MEMBER,"QA Member unplanned");var memberUnplanned=send(MEMBER,"100, unplanned member");
  String ownerNoPlan=ownerUnplanned.buttons().stream().filter(b->b.command().startsWith("/new_plan_unplanned/")).findFirst().orElseThrow().command();
  String memberNoPlan=memberUnplanned.buttons().stream().filter(b->b.command().startsWith("/new_plan_unplanned/")).findFirst().orElseThrow().command();
  send(MEMBER,ownerNoPlan);if(root.path("entries").size()!=beforeUnplannedCount)throw new AssertionError("Another member confirmed unplanned draft");
  has(send(OWNER,ownerNoPlan),"Незапланированный расход сохранён");has(send(MEMBER,memberNoPlan),"Незапланированный расход сохранён");
  if(root.path("entries").size()!=beforeUnplannedCount+2||!root.path("plans").equals(beforeUnplannedPlans)||!root.path("planEdits").equals(beforeUnplannedEdits))throw new AssertionError("Shared unplanned expenses changed plan or were lost");
  System.out.println("Shared budget: independent drafts, interleaved actors, shared balance, restart, authors and outsider/cleanup guards passed.");
 }
}
