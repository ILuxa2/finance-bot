package financeos.vk;
import financeos.core.*;import financeos.data.*;import java.time.*;import java.nio.file.*;import java.util.*;
public final class CategoryMaintenanceTest {
 static long id;static final Clock CLOCK=Clock.fixed(Instant.parse("2026-11-08T10:00:00Z"),ZoneId.of("Europe/Moscow"));
 static BotReply send(LocalBotEngine e,String text){return e.handle(new VkEvent("message_new",99,123,123,"maintenance-"+(++id),text)).orElseThrow();}
 static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
 static void configure(LocalBotEngine e,CategorySections config){var s=e.exportState();e.restoreState(new BotLocalState(s.entries(),s.plans(),s.debts(),s.goals(),s.limits(),s.nextEntryId(),s.seenEvents(),s.recurring(),s.pending(),s.pendingCategory(),s.navigation(),s.undoHistory(),s.operationLinks(),s.history(),s.deletedIds(),s.chartTheme(),s.chatCleanup(),s.planEdits(),config));}
 public static void main(String[]args)throws Exception {
  var empty=new LegacyUserData(Money.of("1000"),OffsetDateTime.parse("2026-11-01T00:00:00+03:00"),List.of(),List.of());
  var e=new LocalBotEngine(123,empty,CLOCK);check(send(e,"/start").screen()==Screen.CATEGORY_SETUP,"New budget has no setup offer");check(!e.exportState().categorySections().enabled(),"Defaults created without confirmation");
  send(e,"/defaults_confirm");var defaults=e.exportState().categorySections();check(defaults.enabled()&&defaults.setup().equals("READY"),"Defaults missing");int count=defaults.assignments().size();send(e,"/defaults_confirm");check(e.exportState().categorySections().assignments().size()==count,"Repeated setup duplicated categories");check(send(e,"/start").screen()==Screen.MAIN,"Setup repeated");
  check(send(e,"/expense").buttons().stream().anyMatch(b->b.label().equals("Питание")),"Unused brand new defaults hidden");
  var skipped=new LocalBotEngine(123,empty,CLOCK);send(skipped,"/start");send(skipped,"/defaults_skip");var dir=Files.createTempDirectory("category-setup-");var filePath=dir.resolve("state.json");try{
   var file=new LocalStateFile(filePath,123,empty);file.save(skipped.exportState());skipped=new LocalBotEngine(123,empty,CLOCK);skipped.restoreState(file.load().orElseThrow());check(send(skipped,"/start").screen()==Screen.MAIN&&!skipped.exportState().categorySections().enabled(),"Skip lost on restart");
   file.save(e.exportState());var restarted=new LocalBotEngine(123,empty,CLOCK);restarted.restoreState(file.load().orElseThrow());check(send(restarted,"/start").screen()==Screen.MAIN,"Confirmed setup repeated after restart");
  }finally{Files.deleteIfExists(filePath);Files.deleteIfExists(dir);}
  var at=OffsetDateTime.parse("2025-01-02T10:00:00+03:00");
  var data=new LegacyUserData(Money.of("1000"),at.minusDays(1),List.of(new LegacyUserData.TimedEntry(at,new LedgerEntry("old",at.toLocalDate(),EntryKind.EXPENSE,Money.of("10"),"Старая", "old",false))),List.of(new BudgetPlan(YearMonth.of(2030,1),"Будущая",Money.of("20"))));
  var config=new CategorySections(List.of("История","Будущее","Пустой","Доходы"),Map.of("Старая","История","Будущая","Будущее","Не нужна","Пустой","Зарплата","Доходы"),Map.of(),Set.of());
  check(CategoryMaintenance.prune(config.archive("Старая"),List.of("Старая","Будущая","Не нужна"),Set.of("Старая","Будущая")).names().contains("История"),"Archived historical section lost its grouping");
  var used=new LocalBotEngine(123,data,CLOCK);configure(used,config);check(send(used,"/start").screen()==Screen.MAIN,"Existing budget prompted setup");
  send(used,"/sections_prune");check(!used.exportState().categorySections().hidden("Не нужна"),"Pruned without confirmation");send(used,"/sections_prune_confirm");var next=used.exportState().categorySections();
  check(next.hidden("Не нужна")&&!next.hidden("Старая")&&!next.hidden("Будущая")&&!next.hidden("Зарплата"),"Usage/income not preserved");check(!next.names().contains("Пустой"),"Empty section remains");
  var menu=send(used,"/expense");check(menu.buttons().stream().noneMatch(b->b.label().equals("Пустой")),"Empty expense section visible");check(data.transactions().size()==1&&data.plans().size()==1&&used.exportState().entries().isEmpty(),"Prune changed finances");
  send(used,"/undo");check(!used.exportState().categorySections().hidden("Не нужна"),"Prune undo lost category");
  var protectedEngine=new LocalBotEngine(123,empty,CLOCK);
  configure(protectedEngine,new CategorySections(List.of("Правила","Финансы","Пустой","Доходы"),Map.of("Регулярная","Правила","Кредит · Тестовый","Финансы","Пустая","Пустой","Зарплата","Доходы"),Map.of(),Set.of()));
  send(protectedEngine,"/payment_rule");send(protectedEngine,"100; 15; Регулярная; проверка");send(protectedEngine,"/add_debt");send(protectedEngine,"Тестовый; 1000; 100; 15");
  send(protectedEngine,"/sections_prune");send(protectedEngine,"/sections_prune_confirm");var protectedConfig=protectedEngine.exportState().categorySections();
  check(!protectedConfig.hidden("Регулярная")&&!protectedConfig.hidden("Кредит · Тестовый")&&protectedConfig.hidden("Пустая"),"Recurring rule or active debt category archived");
  check(protectedEngine.exportState().entries().isEmpty()&&protectedEngine.exportState().debts().getFirst().remaining().compareTo(Money.of("1000"))==0,"Cleanup changed active debt or posted a payment");
  System.out.println("Category cleanup/setup: all-time history and future plans protected, empty sections removed, confirm/undo, opt-in/skip and restart passed.");
 }
}
