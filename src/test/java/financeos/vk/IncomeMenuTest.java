package financeos.vk;
import financeos.core.*;import financeos.data.*;import java.util.*;import java.time.*;import java.nio.file.*;
public final class IncomeMenuTest {
 static long id;static final Clock CLOCK=Clock.fixed(Instant.parse("2026-11-08T10:00:00Z"),ZoneId.of("Europe/Moscow"));
 static BotReply send(LocalBotEngine e,String text){return e.handle(new VkEvent("message_new",99,123,123,"income-menu-"+(++id),text)).orElseThrow();}
 static void has(BotReply r,String value){if(!r.text().contains(value))throw new AssertionError("Missing "+value);}
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 public static void main(String[]a)throws Exception {
  var data=new LegacyUserData(Money.of("1000"),OffsetDateTime.parse("2026-11-01T00:00:00+03:00"),List.of(),List.of(new BudgetPlan(YearMonth.of(2026,11),"Еда",Money.of("100"))));
  var e=new LocalBotEngine(123,data,CLOCK);var s=e.exportState();
  var config=new CategorySections(List.of("Доходы","Машина","Питание"),Map.of("Зарплата","Доходы","Премия","Доходы","Подработка","Доходы","Еда","Питание","Бензин","Машина"),Map.of(),Set.of());
  e.restoreState(new BotLocalState(s.entries(),s.plans(),s.debts(),s.goals(),s.limits(),s.nextEntryId(),s.seenEvents(),s.recurring(),s.pending(),s.pendingCategory(),s.navigation(),s.undoHistory(),s.operationLinks(),s.history(),s.deletedIds(),s.chartTheme(),s.chatCleanup(),s.planEdits(),config));
  var before=e.exportState();var menu=send(e,"/income");check(menu.screen()==Screen.INCOME&&e.exportState().pending().equals("INCOME_CATEGORY"),"Income still opens sections");
  for(int page=0;page<2;page++){var view=page==0?menu:send(e,"/choices/INCOME_CATEGORY/1");for(var b:view.buttons())if(b.command().startsWith("/choose/"))check(!Set.of("Доходы","Машина","Питание","Еда","Бензин").contains(b.label()),"Expense/group leaked into income");}
  menu=send(e,"/income");var salary=menu.buttons().stream().filter(b->b.label().equals("Зарплата")).findFirst().orElseThrow();has(send(e,salary.command()),"сумма");
  has(send(e,"/back"),"источник дохода");check(send(e,"/back").screen()==Screen.MAIN,"Income back does not reach main");
  send(e,"/income");has(send(e,"Бензин"),"категория расходов");has(send(e,"Машина"),"раздел расходов");
  check(e.exportState().entries().isEmpty()&&e.exportState().categorySections().equals(config),"Browsing mutated financial metadata");
  menu=send(e,"/income");send(e,menu.buttons().stream().filter(b->b.label().equals("Зарплата")).findFirst().orElseThrow().command());
  var dir=Files.createTempDirectory("income-menu-");var path=dir.resolve("state.json");try{
   var file=new LocalStateFile(path,123,data);file.save(e.exportState());e=new LocalBotEngine(123,data,CLOCK);e.restoreState(file.load().orElseThrow());has(send(e,"20, зарплата"),"Доход сохранён");
   check(e.exportState().entries().size()==1&&e.exportState().entries().getFirst().entry().category().equals("Зарплата"),"Salary not saved directly");has(send(e,"/report"),"1020.00");send(e,"/undo");has(send(e,"/report"),"1000.00");
   send(e,"/income");send(e,"Фриланс");send(e,"5, заказ");check(e.exportState().categorySections().section("Фриланс").equals("Доходы"),"New source not classified");
   var expenses=send(e,"/expense");for(var b:expenses.buttons())check(!b.label().equals("Доходы"),"Income group leaked into expense");
   has(send(e,"Зарплата"),"источник дохода");
   check(data.plans().size()==1&&before.plans().equals(e.exportState().plans()),"Expense plans removed");
  }finally{Files.deleteIfExists(path);Files.deleteIfExists(dir);}
  System.out.println("Income menu: direct source buttons, no expense sections/categories, back, restart, new source, unchanged expense plans and undo passed.");
 }
}
