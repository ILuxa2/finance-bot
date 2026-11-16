package financeos.cloud;
import com.fasterxml.jackson.databind.*;import com.fasterxml.jackson.databind.node.*;import java.util.*;
/** Keeps financial state shared while selecting only the current actor's UI draft. */
public final class SharedChatState {
 private static final List<String> UI=List.of("pending","pendingCategory","currentScreen","navigationBackStack","historyFrom","historyThrough","historyPage","historyCategory","historyKind","historyQuery","historyAmount","editorPage","editorContext","chartTheme");
 private static ObjectNode ui(ObjectNode root){var out=new ObjectMapper().createObjectNode();for(var k:UI)if(root.has(k))out.set(k,root.get(k).deepCopy());return out;}
 public static ObjectNode prepare(ObjectNode previous,ObjectNode defaults,long owner,long actor){
  var root=previous.deepCopy();var sessions=root.withObject("/memberSessions");
  long prior=root.path("uiActorId").asLong(owner);sessions.set(""+prior,ui(root));
  JsonNode selected=sessions.get(""+actor);ObjectNode selectedUi=selected!=null&&selected.isObject()?(ObjectNode)selected:ui(defaults);
  for(var k:UI){root.remove(k);if(selectedUi.has(k))root.set(k,selectedUi.get(k).deepCopy());}
  root.put("uiActorId",actor);return root;
 }
 public static ObjectNode finish(ObjectNode prepared,ObjectNode saved,long actor){
  var root=saved.deepCopy();var sessions=prepared.withObject("/memberSessions").deepCopy();sessions.set(""+actor,ui(root));root.set("memberSessions",sessions);root.put("uiActorId",actor);
  var authors=prepared.withObject("/operationAuthors").deepCopy();var existing=new HashSet<String>();for(var e:prepared.path("entries"))existing.add(e.path("id").asText());
  for(var e:root.path("entries"))if(!existing.contains(e.path("id").asText()))authors.put(e.path("id").asText(),actor);
  root.set("operationAuthors",authors);return root;
 }
}
