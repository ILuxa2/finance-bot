package financeos.vk;
import financeos.core.*;import financeos.data.*;import java.time.*;import java.nio.file.*;import java.util.*;
public final class HistoryNavigationTest {
 static long id;static final Clock CLOCK=Clock.fixed(Instant.parse("2026-11-12T10:00:00Z"),ZoneId.of("Europe/Moscow"));
 static BotReply send(LocalBotEngine e,String text){return e.handle(new VkEvent("message_new",99,123,123,"history-nav-"+(++id),text)).orElseThrow();}
 static boolean button(BotReply r,String command){return r.buttons()!=null&&r.buttons().stream().anyMatch(b->b.command().equals(command));}
 static void has(BotReply r,String text){if(!r.text().contains(text))throw new AssertionError("Missing "+text);}
 public static void main(String[]a)throws Exception {
  var at=OffsetDateTime.parse("2026-11-01T10:00:00+03:00");var entries=new ArrayList<LegacyUserData.TimedEntry>();for(int i=0;i<25;i++)entries.add(new LegacyUserData.TimedEntry(at.plusMinutes(i),new LedgerEntry("op-"+i,at.toLocalDate(),EntryKind.EXPENSE,Money.of("1"),"Еда","Операция "+i,false)));
  var data=new LegacyUserData(Money.of("1000"),at.minusDays(1),entries,List.of());var e=new LocalBotEngine(123,data,CLOCK);var before=e.exportState();
  var first=send(e,"/history_all");has(first,"страница 1 из 3");has(first,"За всё время");if(button(first,"/history_prev")||!button(first,"/history_next"))throw new AssertionError("First-page controls incorrect");
  var older=first.buttons().stream().filter(b->b.label().contains("Следующие")).findFirst().orElseThrow();if(!older.command().equals("/history_next"))throw new AssertionError("Earlier points to newer page");
  var second=send(e,older.command());has(second,"страница 2 из 3");if(!button(second,"/history_prev")||!button(second,"/history_next"))throw new AssertionError("Middle-page controls incorrect");
  var path=Files.createTempFile("history-page-",".json");try{var file=new LocalStateFile(path,123,data);file.save(e.exportState());e=new LocalBotEngine(123,data,CLOCK);e.restoreState(file.load().orElseThrow());
   var last=send(e,"/history_next");has(last,"страница 3 из 3");if(!button(last,"/history_prev")||button(last,"/history_next"))throw new AssertionError("Last-page controls incorrect");
   has(send(e,"/history_next"),"последняя страница");has(send(e,"/history_prev"),"страница 2 из 3");has(send(e,"/history_prev"),"страница 1 из 3");has(send(e,"/history_prev"),"самые новые");
   send(e,"/history_period");has(send(e,"2026-11-01..2026-11-01"),"Период · 2026-11-01 — 2026-11-01");
   if(!e.exportState().entries().equals(before.entries())||!e.exportState().undoHistory().equals(before.undoHistory())||!e.exportState().debts().equals(before.debts()))throw new AssertionError("Pagination mutated finance");
  }finally{Files.deleteIfExists(path);}
  var empty=new LocalBotEngine(123,new LegacyUserData(Money.of("0"),at,List.of(),List.of()),CLOCK);var none=send(empty,"/history_all");if(button(none,"/history_prev")||button(none,"/history_next"))throw new AssertionError("Empty history has paging buttons");
  System.out.println("History navigation: earlier/newer directions, page boundaries, period, restart, empty state and unchanged finances passed.");
 }
}
