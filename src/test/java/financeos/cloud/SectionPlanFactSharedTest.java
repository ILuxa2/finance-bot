package financeos.cloud;
import financeos.vk.*;import java.time.*;import com.fasterxml.jackson.databind.node.*;
public final class SectionPlanFactSharedTest {
 static String next(BotReply r){return r.buttons().stream().filter(b->b.command().startsWith("/plan_page/")&&b.label().contains("Следующие")).findFirst().orElseThrow().command();}
 static void check(boolean c,String why){if(!c)throw new AssertionError(why);}
 public static void main(String[] args)throws Exception{
  SharedChatStateTest.main(args);String month=YearMonth.now(ZoneId.of("Europe/Moscow")).toString();
  for(int i=0;i<100;i++){String name=String.format("QA Page %03d",i);((ArrayNode)SharedChatStateTest.root.path("plans")).addObject().put("month",month).put("category",name).put("amount","100.00");((ObjectNode)SharedChatStateTest.root.path("categorySections").path("assignments")).put(name,"Питание");}
  var beforeEntries=SharedChatStateTest.root.path("entries").deepCopy();var beforePlans=SharedChatStateTest.root.path("plans").deepCopy();
  var owner=SharedChatStateTest.send(42,"/plan_fact_current");var member=SharedChatStateTest.send(77,"/plan_fact_current");String ownerNext=next(owner);
  var denied=SharedChatStateTest.send(77,ownerNext);check(denied.text().contains("устарела"),"Cross-member page token accepted");
  SharedChatStateTest.root=(ObjectNode)SharedChatStateTest.M.readTree(SharedChatStateTest.M.writeValueAsBytes(SharedChatStateTest.root));
  var page=SharedChatStateTest.send(42,ownerNext);check(page.text().contains("Страница · 2 /"),"Owner page did not survive another member/restart");
  SharedChatStateTest.send(77,next(member));
  check(SharedChatStateTest.root.path("entries").equals(beforeEntries)&&SharedChatStateTest.root.path("plans").equals(beforePlans),"Viewing pages modified finances");
  System.out.println("Shared plan/fact pages: independent member tokens, restart, navigation and unchanged financial data passed.");
 }
}
