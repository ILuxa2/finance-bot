package financeos.vk;
import financeos.core.*;import financeos.data.*;import java.time.*;import java.util.*;import java.nio.file.*;
public final class CategoryExpenseHistoryTest {
 static long seq;static final Clock CLOCK=Clock.fixed(Instant.parse("2026-11-13T09:00:00Z"),ZoneId.of("Europe/Moscow"));
 static BotReply send(LocalBotEngine e,String text){return e.handle(new VkEvent("message_new",99,42,42,"category-history-"+(++seq),text)).orElseThrow();}
 static void has(BotReply r,String s){if(!r.text().contains(s))throw new AssertionError("Missing "+s+" in "+r.text());new VkKeyboardFactory().keyboard(r.buttons()==null?MenuCatalog.buttons(r.screen()):r.buttons());}
 static String first(BotReply r){return r.buttons().stream().filter(b->b.command().startsWith("/choose/")).findFirst().orElseThrow().command();}
 static LegacyUserData.TimedEntry item(String id,EntryKind kind,String amount,String category,String comment,OffsetDateTime at){return new LegacyUserData.TimedEntry(at,new LedgerEntry(id,at.toLocalDate(),kind,Money.of(amount),category,comment,false));}
 public static void main(String[]args)throws Exception{
  var now=OffsetDateTime.now(CLOCK);var entries=new ArrayList<LegacyUserData.TimedEntry>();for(int n=1;n<=21;n++)entries.add(item("e"+n,EntryKind.EXPENSE,"1","Еда","покупка "+n,now.minusHours(1)));
  entries.add(item("income",EntryKind.INCOME,"999","Еда","не расход",now));entries.add(item("old",EntryKind.EXPENSE,"888","Еда","прошлый месяц",now.minusMonths(1)));entries.add(item("future",EntryKind.EXPENSE,"777","Еда","будущее",now.plusDays(1)));
  var base=new LegacyUserData(Money.of("10000"),now.minusMonths(2),entries,List.of());var engine=new LocalBotEngine(42,base,CLOCK);var dir=Files.createTempDirectory("category-expenses-");var path=dir.resolve("state.json");
  try{
   var r=send(engine,"/report_categories");has(r,"Расходы по категориям · 2026-11");has(r,"Еда · 21 ₽");var command=first(r);String finance=engine.exportState().entries().toString();
   var file=new LocalStateFile(path,42,base);file.save(engine.exportState());engine=new LocalBotEngine(42,base,CLOCK);engine.restoreState(file.load().orElseThrow());r=send(engine,command);has(r,"Еда · 21 ₽");has(r,"страница 1 из 3");has(r,"Период · 2026-11-01 — 2026-11-13");if(r.text().contains("не расход")||r.text().contains("прошлый месяц")||r.text().contains("будущее"))throw new AssertionError("Unrelated operations visible");
   file.save(engine.exportState());engine=new LocalBotEngine(42,base,CLOCK);engine.restoreState(file.load().orElseThrow());has(send(engine,"/history_next"),"страница 2 из 3");has(send(engine,"/history_prev"),"страница 1 из 3");has(send(engine,"/back"),"Расходы по категориям");
   r=send(engine,"/report_categories");send(engine,first(r));r=send(engine,"/history_edit");has(r,"операци");send(engine,first(r));has(send(engine,"/edit_return"),"Фильтры и страница сохранены");if(!engine.exportState().history().kind().equals("EXPENSE"))throw new AssertionError("Kind lost through editor");
   send(engine,"/history_all");send(engine,"/history_next");has(send(engine,"/history_next"),"не расход");if(!engine.exportState().history().kind().isEmpty())throw new AssertionError("Expense filter leaked to full history");
   if(!finance.equals(engine.exportState().entries().toString()))throw new AssertionError("Read-only drilldown changed finance");
   var empty=new LocalBotEngine(42,new LegacyUserData(Money.of("1"),now,List.of(),List.of()),CLOCK);has(send(empty,"/report_categories"),"Расходов за текущий месяц нет");
   var longEntries=new ArrayList<LegacyUserData.TimedEntry>();for(int i=0;i<12;i++)longEntries.add(item("long"+i,EntryKind.EXPENSE,"1","Длинная категория номер "+i,"тест",now));var large=new LocalBotEngine(42,new LegacyUserData(Money.of("100"),now.minusDays(1),longEntries,List.of()),CLOCK);r=send(large,"/report_categories");has(r,"Расходы по категориям");for(int i=0;i<4;i++){var next=r.buttons().stream().filter(b->b.label().contains("Следующие")).findFirst();if(next.isEmpty())break;r=send(large,next.get().command());has(r,"Варианты");}
   send(engine,"/defaults_setup");send(engine,"/defaults_confirm");send(engine,"/report_period");send(engine,"2026-10-01..2026-10-31");r=send(engine,"/report_sections");has(r,"напишите его название");
   file.save(engine.exportState());var restarted=new LocalBotEngine(42,base,CLOCK);restarted.restoreState(file.load().orElseThrow());String section=r.buttons().stream().filter(b->b.command().startsWith("/choose/")).findFirst().orElseThrow().label();r=send(restarted,section.toLowerCase(Locale.ROOT));has(r,"напишите её название");has(r,"Еда · 888 ₽");
   r=send(restarted," еда ");has(r,"прошлый месяц");has(r,"Период · 2026-10-01 — 2026-10-31");if(r.text().contains("покупка 1")||r.text().contains("не расход"))throw new AssertionError("Report period changed during typed drilldown");
   file.save(restarted.exportState());var restored=new LocalBotEngine(42,base,CLOCK);restored.restoreState(file.load().orElseThrow());has(send(restored,"/back"),"2026-10-01 — 2026-10-31");
   if(!finance.equals(restored.exportState().entries().toString()))throw new AssertionError("Typed report browsing changed entries");
   System.out.println("Category/section typed browsing, period retention, restart, pages, edit/back and financial isolation passed.");
  }finally{Files.deleteIfExists(path);Files.deleteIfExists(dir);}
 }
}
