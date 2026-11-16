package financeos.cloud;
import financeos.vk.*;import com.fasterxml.jackson.databind.node.*;
public final class ExpenseChartSharedTest {
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 static String next(BotReply r){return r.buttons().stream().map(MenuButton::command).filter(c->c.startsWith("/expense_chart_page/")).findFirst().orElseThrow();}
 public static void main(String[] args)throws Exception{
  SharedChatStateTest.main(args);var a=SharedChatStateTest.send(42,"/report_chart");var b=SharedChatStateTest.send(77,"/report_chart");var entries=SharedChatStateTest.root.path("entries").deepCopy();var plans=SharedChatStateTest.root.path("plans").deepCopy();
  check(a.images().size()>1&&b.images().size()==a.images().size(),"Participants did not receive complete charts");check(a.buttons()==null&&b.buttons()==null,"Original report keyboard replaced");
  var saved=CloudVkProcessor.SavedReply.from(a).forEvent(new VkEvent("message_new",99,42,2000000001L,"bundle","/report_chart")).withProgress(123);
  var restored=SharedChatStateTest.M.readValue(SharedChatStateTest.M.writeValueAsBytes(saved),CloudVkProcessor.SavedReply.class);var restoredReply=restored.asDelivered(456).toReply();
  check(restoredReply.images().size()==a.images().size(),"Outbox lost bundle across restart/delivery");for(int i=0;i<a.images().size();i++)check(java.util.Arrays.equals(a.images().get(i),restoredReply.images().get(i)),"Image changed in outbox");
  var old=SharedChatStateTest.M.readValue("{\"text\":\"Old\",\"screen\":\"REPORT\"}",CloudVkProcessor.SavedReply.class);check(old.toReply().images().isEmpty(),"Old outbox not supported");
  check(!SharedChatStateTest.M.writeValueAsString(old).contains("additionalImages"),"Empty new field breaks rolling deployment");
  check(SharedChatStateTest.root.path("entries").equals(entries)&&SharedChatStateTest.root.path("plans").equals(plans),"Charts changed finances");
  System.out.println("Shared expense chart: complete bundles, original keyboard, outbox persistence, old-schema compatibility and immutable finances passed.");
 }
}
