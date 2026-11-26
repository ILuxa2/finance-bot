package financeos.vk;
import financeos.core.*;import financeos.data.*;import java.time.*;import java.nio.file.*;import java.util.*;
public final class CategorySectionsTest {
 static long id;static final Clock CLOCK=Clock.fixed(Instant.parse("2026-11-07T10:00:00Z"),ZoneId.of("Europe/Moscow"));
 static BotReply send(LocalBotEngine e,String text){return e.handle(new VkEvent("message_new",99,123,123,"sections-"+(++id),text)).orElseThrow();}
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 static void has(BotReply r,String value){check(r.text().contains(value),"Missing "+value+" in "+r.text());}
 static LocalBotEngine reload(LocalBotEngine e,LegacyUserData data,Path path)throws Exception{var f=new LocalStateFile(path,123,data);f.save(e.exportState());var n=new LocalBotEngine(123,data,CLOCK);n.restoreState(f.load().orElseThrow());return n;}
 static void choose(LocalBotEngine e,String section){send(e,"/sections");send(e,"/section_pick");send(e,section);}
 static void category(LocalBotEngine e,String section,String category){choose(e,section);send(e,"/section_categories");send(e,category);}
 static void configure(LocalBotEngine e,CategorySections config,List<PlanEdit> edits){var s=e.exportState();e.restoreState(new BotLocalState(s.entries(),s.plans(),s.debts(),s.goals(),s.limits(),s.nextEntryId(),s.seenEvents(),s.recurring(),s.pending(),s.pendingCategory(),s.navigation(),s.undoHistory(),s.operationLinks(),s.history(),s.deletedIds(),s.chartTheme(),s.chatCleanup(),edits,config));}
 public static void main(String[] args)throws Exception{
  var month=YearMonth.of(2026,11);var at=OffsetDateTime.parse("2026-11-04T10:00:00+03:00");
  var entries=List.of(new LegacyUserData.TimedEntry(at,new LedgerEntry("a",at.toLocalDate(),EntryKind.EXPENSE,Money.of("150"),"Еда","a",false)),new LegacyUserData.TimedEntry(at,new LedgerEntry("b",at.toLocalDate(),EntryKind.EXPENSE,Money.of("20"),"Еде","b",false)));
  var data=new LegacyUserData(Money.of("10000"),at.minusDays(1),entries,List.of(new BudgetPlan(month,"Еда",Money.of("100")),new BudgetPlan(month,"Еде",Money.of("50")),new BudgetPlan(month,"Продукты",Money.of("100"))));
  var config=new CategorySections(List.of("Питание","Машина"),Map.of("Вкусняшки","Питание","Продукты","Питание"),Map.of("Еда","Вкусняшки","Еде","Вкусняшки"),Set.of());
  var merged=config.apply(data);for(int i=0;i<2;i++){var old=entries.get(i).entry();var n=merged.transactions().get(i).entry();check(old.id().equals(n.id())&&old.amount().equals(n.amount())&&old.date().equals(n.date()),"Migration changed finance");check(n.category().equals("Вкусняшки"),"Alias not merged");}
  var lines=SectionViews.budget(merged,config,List.of(),at.plusDays(1),month);var food=lines.stream().filter(l->l.category().equals("Питание")).findFirst().orElseThrow();check(food.planned().compareTo(Money.of("250"))==0&&food.remaining().compareTo(Money.of("100"))==0,"Section overspending consumed another category reserve");
  check(config.renameCategory("Вкусняшки","Еда","Питание").canonical("Еде").equals("Еда"),"Rename back created alias cycle");
  var e=new LocalBotEngine(123,data,CLOCK);configure(e,config,List.of(new PlanEdit(month,"Еда","Еда",Money.of("200"))));
  has(send(e,"/plan_fact_current"),"350 ₽");has(send(e,"/report"),"Фактический баланс · 9830.00");
  var dir=Files.createTempDirectory("sections-");var path=dir.resolve("state.json");try{
   e=reload(e,data,path);send(e,"/add_plan");has(send(e,"10, Еде"),"Дубликат не создан");var sectionReply=send(e,"/expense");
   var categoryReply=send(e,sectionReply.buttons().stream().filter(b->b.label().equals("Питание")).findFirst().orElseThrow().command());has(categoryReply,"категорию");
   has(send(e,categoryReply.buttons().stream().filter(b->b.label().equals("Вкусняшки")).findFirst().orElseThrow().command()),"Вкусняшки");send(e,"1, проверка");check(e.exportState().entries().getLast().entry().category().equals("Вкусняшки"),"New alias not canonical");send(e,"/undo");
   send(e,"/section_new");send(e,"Дача");check(!e.exportState().categorySections().names().contains("Дача"),"Preview mutated");e=reload(e,data,path);send(e,"/section_confirm");check(e.exportState().categorySections().names().contains("Дача"),"Create failed");
   choose(e,"Дача");send(e,"/category_new");send(e,"Семена");send(e,"/section_confirm");check(e.exportState().categorySections().section("Семена").equals("Дача"),"Category create");
   category(e,"Дача","Семена");send(e,"/category_rename");send(e,"Рассада");send(e,"/section_confirm");check(e.exportState().categorySections().canonical("Семена").equals("Рассада"),"Category rename");
   category(e,"Дача","Рассада");send(e,"/category_move");send(e,"Машина");send(e,"/section_confirm");check(e.exportState().categorySections().section("Рассада").equals("Машина"),"Category move");
   category(e,"Машина","Рассада");send(e,"/category_delete");send(e,"/section_confirm");check(e.exportState().categorySections().hidden("Рассада"),"Category archive");e=reload(e,data,path);check(e.exportState().categorySections().hidden("Рассада"),"Archive restart");
   send(e,"/category_archive_list");send(e,"Рассада");send(e,"/section_confirm");check(!e.exportState().categorySections().hidden("Рассада"),"Category restore");
   choose(e,"Машина");send(e,"/section_rename");send(e,"Авто");send(e,"/section_confirm");check(e.exportState().categorySections().section("Рассада").equals("Авто"),"Section rename");
   choose(e,"Авто");send(e,"/section_delete");send(e,"Прочее");send(e,"/section_confirm");check(e.exportState().categorySections().section("Рассада").equals("Прочее"),"Section delete fallback");e=reload(e,data,path);send(e,"/undo");check(e.exportState().categorySections().section("Рассада").equals("Авто"),"Persistent undo");
   choose(e,"Питание");send(e,"/category_new");has(send(e,"Еда"),"уже существует");send(e,"/sections");send(e,"/category_delete");send(e,"/section_confirm");check(!e.exportState().categorySections().hidden("Вкусняшки"),"Context-free archive");
   send(e,"/plan_edit_current");send(e,"Питание");send(e,"Вкусняшки");send(e,"/plan_edit_amount");send(e,"400");send(e,"/plan_edit_confirm");has(send(e,"/plan_fact_current"),"500 ₽");
   category(e,"Питание","Вкусняшки");send(e,"/category_rename");send(e,"Перекусы");send(e,"/section_confirm");has(send(e,"/plan_fact_current"),"500 ₽");has(send(e,"/report"),"9830.00");
   check(data.transactions().equals(entries),"Raw data modified");
  }finally{Files.deleteIfExists(path);Files.deleteIfExists(dir);}
  System.out.println("Sections: alias migration, financial invariants, plan edit order, CRUD, archive, restart and undo passed.");
 }
}
