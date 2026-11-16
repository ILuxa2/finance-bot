package financeos.vk;
import financeos.core.*;import financeos.data.*;import java.time.*;import java.util.*;
public class NewExpensePlanTest{
 static long id;static BotReply send(LocalBotEngine e,String t){return e.handle(new VkEvent("message_new",99,42,42,"new-plan-"+(++id),t)).orElseThrow();}
 public static void main(String[]a){for(String action:List.of("/new_plan_keep","/new_plan_expense","/new_plan_custom")){
 var now=OffsetDateTime.parse("2026-11-13T12:00:00+03:00");var data=new LegacyUserData(Money.of("10000"),now.minusDays(1),List.of(),List.of(new BudgetPlan(YearMonth.from(now),"Продукты",Money.of("2000"))));var e=new LocalBotEngine(42,data,Clock.fixed(now.toInstant(),ZoneId.of("Europe/Moscow")));send(e,"/defaults_setup");send(e,"/defaults_confirm");send(e,"/expense");send(e,"Питание");send(e,"Новая");var r=send(e,"500, тест");if(!e.exportState().entries().isEmpty())throw new AssertionError("Early save");r=send(e,r.buttons().stream().filter(b->b.command().startsWith(action+"/")).findFirst().orElseThrow().command());if(action.endsWith("custom"))r=send(e,"1000");if(!r.text().contains("Расход сохранён"))throw new AssertionError(r.text());if(e.exportState().entries().size()!=1)throw new AssertionError();send(e,"/undo");if(!e.exportState().entries().isEmpty()||!e.exportState().plans().isEmpty())throw new AssertionError("Undo incomplete");
 }System.out.println("New category: three plan choices, deferred commit and atomic undo passed.");}
}

