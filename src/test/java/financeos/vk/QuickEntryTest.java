package financeos.vk;
import financeos.core.*;import financeos.data.*;import java.time.*;import java.util.*;import java.nio.file.*;
public final class QuickEntryTest {
 static final Clock CLOCK=Clock.fixed(Instant.parse("2026-11-13T09:00:00Z"),ZoneId.of("Europe/Moscow"));static long sequence;
 static BotReply send(LocalBotEngine e,String text){return e.handle(new VkEvent("message_new",99,42,42,"quick-"+(++sequence),text)).orElseThrow();}
 static void has(BotReply r,String s){if(!r.text().contains(s))throw new AssertionError("Missing "+s+" in "+r.text());check(r);}
 static void check(BotReply r){new VkKeyboardFactory().keyboard(r.buttons()==null?MenuCatalog.buttons(r.screen()):r.buttons());}
 static String choose(BotReply r,String label){return r.buttons().stream().filter(b->b.command().startsWith("/choose/")&&b.label().equals(label)).findFirst().orElseThrow().command();}
 static String first(BotReply r){check(r);return r.buttons().stream().filter(b->b.command().startsWith("/choose/")).findFirst().orElseThrow().command();}
 static LocalBotEngine reload(LocalBotEngine e,LegacyUserData base,Path path)throws Exception{var f=new LocalStateFile(path,42,base);f.save(e.exportState());var other=new LocalBotEngine(42,base,CLOCK);other.restoreState(f.load().orElseThrow());return other;}
 public static void main(String[]a)throws Exception{
  var now=OffsetDateTime.now(CLOCK);var base=new LegacyUserData(Money.of("1000"),now.minusDays(10),List.of(new LegacyUserData.TimedEntry(now.minusDays(1),new LedgerEntry("base1",now.minusDays(1).toLocalDate(),EntryKind.EXPENSE,Money.of("10"),"Еда","старый комментарий",false))),List.of(new BudgetPlan(YearMonth.from(now),"Еда",Money.of("100"))));
  var e=new LocalBotEngine(42,base,CLOCK);var dir=Files.createTempDirectory("quick-entry-");var path=dir.resolve("state.json");
  try{
   var r=send(e,"−450.50, кофе");has(r,"Расход");has(r,"450.50");if(!e.exportState().entries().isEmpty())throw new AssertionError("Draft wrote finance");var command=choose(r,"Еда");
   e=reload(e,base,path);has(send(e,command),"сохранён");if(e.exportState().entries().size()!=1)throw new AssertionError();has(send(e,command),"устарела");if(e.exportState().entries().size()!=1)throw new AssertionError("Stale quick button duplicated expense");
   has(send(e,"/undo"),"отменено");if(!e.exportState().entries().isEmpty())throw new AssertionError();
   r=send(e,"+500, аванс");e=reload(e,base,path);r=send(e,"/quick_all");has(r,"Все категории");has(send(e,choose(r,"Зарплата")),"Доход сохранён");
   var old=send(e,"-1, старый черновик");var stale=first(old);r=send(e,"-2, новый черновик");has(send(e,stale),"недоступен");if(e.exportState().entries().size()!=1)throw new AssertionError("Old draft button saved new amount");
   has(send(e,"/back"),"не изменены");if(e.exportState().entries().size()!=1)throw new AssertionError("Cancel undid real income");
   send(e,"/edit_operations");r=send(e,"/edit_operations");send(e,first(r));r=send(e,"/repeat_operation");has(r,"Повтор операции");e=reload(e,base,path);has(send(e,"25.50"),"сохранён");var added=e.exportState().entries().getLast().entry();if(!added.category().equals("Зарплата")||!added.description().equals("аванс"))throw new AssertionError("Repeat lost category/comment");if(!added.date().equals(now.toLocalDate())||added.amount().compareTo(Money.of("25.50"))!=0)throw new AssertionError("Repeat did not create today's operation");
   has(send(e,"/undo"),"отменено");
   r=send(e,"-3, отмена");send(e,"/quick_cancel");if(e.exportState().entries().size()!=1)throw new AssertionError();
   var event=new VkEvent("message_new",99,42,42,"same-event","-4, одно событие");e.handle(event);if(e.handle(event).isPresent())throw new AssertionError("Duplicate event accepted");
   has(send(e,"/start"),"Быстрый ввод");has(send(e,"/help"),"Быстрый ввод");
   send(e,"/defaults_setup");send(e,"/defaults_confirm");r=send(e,"-5, стандартные");check(r);r=send(e,"/quick_all");check(r);for(int i=0;i<8;i++){var next=r.buttons().stream().filter(b->b.command().startsWith("/choices/")&&b.label().contains("Далее")).findFirst();if(next.isEmpty())break;r=send(e,next.get().command());check(r);}
   send(e,"/start");r=send(e,"/expense");for(int i=0;i<3&&!e.exportState().pending().endsWith("_AMOUNT");i++)r=send(e,first(r));r=send(e,"1, обычный ввод");if(e.exportState().pending().equals("NEW_EXPENSE_PLAN"))r=send(e,r.buttons().stream().filter(b->b.command().startsWith("/new_plan_expense/")).findFirst().orElseThrow().command());has(r,"сохранён");
   var blankBase=new LegacyUserData(Money.of("100"),now.minusDays(5),List.of(new LegacyUserData.TimedEntry(now.minusDays(1),new LedgerEntry("blank",now.minusDays(1).toLocalDate(),EntryKind.EXPENSE,Money.of("1"),"Еда","",false))),List.of());
   var blankEngine=new LocalBotEngine(42,blankBase,CLOCK);r=send(blankEngine,"/edit_operations");send(blankEngine,first(r));send(blankEngine,"/repeat_operation");has(send(blankEngine,"2"),"сохранён");if(!blankEngine.exportState().entries().getLast().entry().description().isBlank())throw new AssertionError("Blank comment changed");
   var linkedBase=new LegacyUserData(Money.of("100"),now.minusDays(1),List.of(),List.of(),new LegacyExtras(List.of(new Debt("Loan",Money.of("20"),Money.of("5"),19,true)),List.of(),List.of(),Map.of(),Map.of()));
   var linked=new LocalBotEngine(42,linkedBase,CLOCK);r=send(linked,"/pay_debt");send(linked,first(r));send(linked,"5");r=send(linked,"/edit_operations");r=send(linked,first(r));if(r.buttons().stream().anyMatch(b->b.command().equals("/repeat_operation")))throw new AssertionError("Debt repeat offered as normal expense");send(linked,"/repeat_operation");if(linked.exportState().entries().size()!=1)throw new AssertionError("Debt repeated without link");
   var longPlans=new ArrayList<BudgetPlan>();for(int n=0;n<12;n++)longPlans.add(new BudgetPlan(YearMonth.from(now),"Длинная категория номер "+n,Money.of("1")));
   var longEngine=new LocalBotEngine(42,new LegacyUserData(Money.of("100"),now,List.of(),longPlans),CLOCK);r=send(longEngine,"-1, длинные категории");check(r);r=send(longEngine,"/quick_all");check(r);for(int n=0;n<4;n++){var next=r.buttons().stream().filter(b->b.label().contains("Далее")).findFirst();if(next.isEmpty())break;r=send(longEngine,next.get().command());check(r);}
   // Names in the list and on buttons must agree on every page, including long names.
   var namedPlans=new ArrayList<BudgetPlan>();
   var names=List.of("Еда","Ремонт машины","Ногти","Продукты","Бензин","Курение","Категория "+"Ш".repeat(65),"😀".repeat(30));
   for(var name:names)namedPlans.add(new BudgetPlan(YearMonth.from(now),name,Money.of("100")));
   var namedEngine=new LocalBotEngine(42,new LegacyUserData(Money.of("10000"),now.minusDays(1),List.of(),namedPlans),CLOCK);
   send(namedEngine,"-1, подписи");r=send(namedEngine,"/quick_all");var found=new HashSet<String>();
   for(int page=0;page<12;page++){
    check(r);var rendered=new com.fasterxml.jackson.databind.ObjectMapper().readTree(new VkKeyboardFactory().keyboard(r.buttons()));
    var order=new ArrayList<String>();for(var row:rendered.path("buttons"))for(var cell:row){var label=cell.path("action").path("label").asText();order.add(label);if(label.codePointCount(0,label.length())>12 && row.size()!=1)throw new AssertionError("Wide name shares row");}
    if(!order.equals(r.buttons().stream().map(MenuButton::label).toList()))throw new AssertionError("Keyboard reordered choices");
    for(var b:r.buttons())if(b.command().startsWith("/choose/")){
     var match=names.stream().filter(name->b.label().equals(name)||(b.label().endsWith("…")&&name.startsWith(b.label().substring(0,b.label().length()-1)))).findFirst().orElseThrow(()->new AssertionError("Category replaced by number: "+b.label()));
     if(!r.text().contains(match))throw new AssertionError("Button does not match full list label");found.add(match);
    }
    var next=r.buttons().stream().filter(b->b.command().startsWith("/choices/")&&b.label().contains("Далее")).findFirst();if(next.isEmpty())break;r=send(namedEngine,next.get().command());
   }
   if(!found.containsAll(names))throw new AssertionError("Pagination lost categories: "+found);
   System.out.println("Quick entry: expense/income, categories/recent/pages, persisted drafts, stale/duplicate buttons, undo/cancel, repeat and original flow passed.");
  } finally {Files.deleteIfExists(path);Files.deleteIfExists(dir);}
 }
}
