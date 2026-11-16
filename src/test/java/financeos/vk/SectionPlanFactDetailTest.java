package financeos.vk;
import financeos.core.*;import financeos.data.*;import java.time.*;import java.util.*;import java.nio.file.*;
public final class SectionPlanFactDetailTest {
 static final OffsetDateTime NOW=OffsetDateTime.parse("2026-11-16T12:00:00+03:00");static final YearMonth MONTH=YearMonth.from(NOW);static final Clock CLOCK=Clock.fixed(NOW.toInstant(),ZoneId.of("Europe/Moscow"));static long id;
 static void check(boolean v,String why){if(!v)throw new AssertionError(why);}
 static LegacyUserData.TimedEntry expense(String id,String category,String amount,OffsetDateTime time){return new LegacyUserData.TimedEntry(time,new LedgerEntry(id,time.toLocalDate(),EntryKind.EXPENSE,Money.of(amount),category,"QA",false));}
 static BotReply send(LocalBotEngine e,String text){var r=e.handle(new VkEvent("message_new",99,42,42,"section-detail-"+(++id),text)).orElseThrow();new VkKeyboardFactory().keyboard(r.buttons()==null?MenuCatalog.buttons(r.screen()):r.buttons());check(r.text().length()<=4096,"Page too long: "+r.text().length());return r;}
 static String next(BotReply r){return r.buttons().stream().filter(b->b.command().startsWith("/plan_page/")&&b.label().contains("Далее")).findFirst().orElseThrow().command();}
 static void configure(LocalBotEngine e,CategorySections config)throws Exception{var f=LocalBotEngine.class.getDeclaredField("categorySections");f.setAccessible(true);f.set(e,config);}
 public static void main(String[] args)throws Exception{
  var config=new CategorySections(List.of("Питание","Машина","Пусто","Доходы"),Map.of("Продукты","Питание","Вкусняшки","Питание","Без плана","Питание","Пустая категория","Питание","Архивная","Питание","Бензин","Машина"),Map.of("Еда","Вкусняшки","Еде","Вкусняшки"),Set.of("Архивная"));
  var data=new LegacyUserData(Money.of("10000"),NOW.minusMonths(2),List.of(expense("a","Продукты","300",NOW),expense("b","Еда","100",NOW),expense("c","Еде","50",NOW),expense("d","Без плана","50",NOW),expense("e","Бензин","700",NOW),expense("f","Архивная","5",NOW),expense("future","Продукты","9999",NOW.plusDays(1)),expense("old","Продукты","15",NOW.minusMonths(1))),List.of(new BudgetPlan(MONTH,"Продукты",Money.of("1000")),new BudgetPlan(MONTH,"Вкусняшки",Money.of("500")),new BudgetPlan(MONTH,"Бензин",Money.of("1000"))));
  String original=data.toString();var report=String.join("\n",SectionPlanFactView.pages(data,config,List.of(),NOW,MONTH,""));
  for(String category:List.of("Продукты","Вкусняшки","Без плана","Пустая категория","Архивная","Бензин"))check(report.contains(" "+category+"\n"),"Missing category: "+category);
  check(report.contains("━━━━━━━━━━━━━━\n📂 ПИТАНИЕ\n🟢 В пределах плана\n\nПлан · 1 500 ₽\nФакт · 505 ₽\nОсталось по плану · 995 ₽"),"Section sums wrong");
  check(report.contains("🟢 Вкусняшки\n  План · 500 ₽\n  Факт · 150 ₽"),"Aliases not combined");check(report.contains("План · 2 500 ₽\nФакт · 1 205 ₽\nОсталось по плану · 1 350 ₽"),"Global protected reserves wrong");
  check(!report.contains("9999")&&!report.contains("  Еда\n")&&!report.contains("  Еде\n")&&!report.contains("Пусто")&&!report.contains("Доходы"),"Future/aliases/empty groups leaked");
  var food=String.join("\n",SectionPlanFactView.pages(data,config,List.of(),NOW,MONTH,"Питание"));check(!food.contains("Бензин"),"Detail includes another section");
  check(String.join("\n",SectionPlanFactView.pages(data,config,List.of(),NOW,MONTH.minusMonths(1),"Питание")).contains("Факт · 15 ₽"),"Wrong previous month");check(original.equals(data.toString()),"Formatter changed finance");
  var plans=new ArrayList<BudgetPlan>();var entries=new ArrayList<LegacyUserData.TimedEntry>();var assignments=new LinkedHashMap<String,String>();
  for(int i=0;i<120;i++){String name=String.format("Категория %03d",i);plans.add(new BudgetPlan(MONTH,name,Money.of("100")));entries.add(expense("large-"+i,name,"1",NOW));assignments.put(name,"Большой раздел");}
  entries.add(expense("outside","Незапланированное","10",NOW));assignments.put("Незапланированное","Большой раздел");assignments.put("Пустая категория","Большой раздел");
  var large=new LegacyUserData(Money.of("100000"),NOW.minusDays(1),entries,plans);var groups=new CategorySections(List.of("Большой раздел"),assignments,Map.of(),Set.of());var pages=SectionPlanFactView.pages(large,groups,List.of(),NOW,MONTH,"");check(pages.size()>1,"Large report not paginated");String all=String.join("\n",pages);
  for(int i=0;i<120;i++){String marker=" "+String.format("Категория %03d",i)+"\n";check(all.split(java.util.regex.Pattern.quote(marker),-1).length-1==1,"Category lost or duplicated: "+i);}
  for(var page:pages){check(page.length()<=3400,"Formatter page limit");check(page.contains("План · 12 000 ₽\nФакт · 130 ₽\nОсталось по плану · 11 880 ₽"),"Totals changed between pages");}
  check(!all.contains("Показано")&&!all.contains("Сообщение сокращено"),"Categories silently truncated");
  var engine=new LocalBotEngine(42,large,CLOCK);configure(engine,groups);var r=send(engine,"/plan_fact_current");String stale=next(r);var filePath=Files.createTempFile("section-detail-",".json");
  try{var f=new LocalStateFile(filePath,42,large);f.save(engine.exportState());var other=new LocalBotEngine(42,large,CLOCK);other.restoreState(f.load().orElseThrow());engine=other;}finally{Files.delete(filePath);}
  int visited=1;while(r.buttons().stream().anyMatch(b->b.command().startsWith("/plan_page/")&&b.label().contains("Далее"))){r=send(engine,next(r));if(++visited>100)throw new AssertionError("Page loop");}check(visited==pages.size(),"Engine lost pages");
  check(engine.exportState().entries().isEmpty()&&engine.exportState().plans().isEmpty()&&engine.exportState().planEdits().isEmpty(),"Views wrote finance");send(engine,"/start");check(send(engine,stale).text().contains("устарела"),"Old page hijacked another screen");
  send(engine,"/plan_fact_previous");check(send(engine,stale).text().contains("устарела"),"Old page switched month");
  send(engine,"/plan_fact_current");r=send(engine,"/plan_sections");r=send(engine,"Большой раздел");check(r.screen()==Screen.PLAN_SECTION_DETAIL&&r.text().contains("Категории раздела"),"Section detail missing");check(r.buttons().stream().anyMatch(b->b.command().startsWith("/plan_page/")),"Section detail not paginated");
  System.out.println("Section plan/fact: nested categories, aliases/archived/empty rows, totals/reserves, months, all 120 categories, page limits, persistent cursor and stale guards passed.");
 }
}
