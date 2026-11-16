package financeos.vk;
import financeos.core.*;import financeos.data.*;import java.time.*;import java.util.*;import java.io.*;import javax.imageio.ImageIO;import java.nio.file.*;
public final class ExpenseChartPagesTest {
 static final OffsetDateTime NOW=OffsetDateTime.parse("2026-11-16T12:00:00+03:00");static long id;
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 static LegacyUserData.TimedEntry entry(String cat,String amount,EntryKind kind,OffsetDateTime date){return new LegacyUserData.TimedEntry(date,new LedgerEntry("qa-"+(++id),date.toLocalDate(),kind,Money.of(amount),cat,"QA",false));}
 static BotReply send(LocalBotEngine e,String text){var r=e.handle(new VkEvent("message_new",99,42,42,"chart-"+(++id),text)).orElseThrow();new VkKeyboardFactory().keyboard(r.buttons()==null?MenuCatalog.buttons(r.screen()):r.buttons());check(r.text().length()<=4096,"VK text limit");return r;}
 static String next(BotReply r){return r.buttons().stream().map(MenuButton::command).filter(c->c.startsWith("/expense_chart_page/")).reduce((a,b)->b).orElseThrow();}
 public static void main(String[] args)throws Exception{
  var assignments=new LinkedHashMap<String,String>();assignments.put("Вкусняшки","Питание");assignments.put("Бензин","Машина");assignments.put("Архив","Машина");var entries=new ArrayList<LegacyUserData.TimedEntry>();
  entries.add(entry("Еда","100",EntryKind.EXPENSE,NOW));entries.add(entry("Еде","50",EntryKind.EXPENSE,NOW));entries.add(entry("Бензин","50",EntryKind.EXPENSE,NOW));entries.add(entry("Архив","50",EntryKind.EXPENSE,NOW));
  entries.add(entry("Бензин","9999",EntryKind.EXPENSE,NOW.plusDays(1)));entries.add(entry("Бензин","9999",EntryKind.EXPENSE,NOW.minusMonths(1)));entries.add(entry("Бензин","9999",EntryKind.INCOME,NOW));
  for(int i=0;i<19;i++){String c=String.format("Категория %02d",i);assignments.put(c,"Большой раздел");entries.add(entry(c,"10",EntryKind.EXPENSE,NOW));}
  var cfg=new CategorySections(List.of("Питание","Машина","Большой раздел","Пусто"),assignments,Map.of("Еда","Вкусняшки","Еде","Вкусняшки"),Set.of("Архив"));
  var data=new LegacyUserData(Money.of("10000"),NOW.minusMonths(2),entries,List.of());String before=data.toString();var pages=ExpenseChartPages.build(data,cfg,List.of(),NOW,YearMonth.from(NOW));
  check(pages.size()==6,"Expected overview + 3 large + 2 small sections");check(pages.getFirst().total().compareTo(Money.of("440"))==0,"Overview total wrong");
  var large=pages.stream().filter(p->p.section().equals("Большой раздел")).toList();check(large.size()==3,"Detail truncated");check(large.stream().mapToInt(p->p.chart().labels().size()).sum()==19,"Lost categories");for(var p:large){check(p.total().compareTo(Money.of("190"))==0,"Percentage denominator changed across pages");check(p.caption().contains("5.3 %"),"Wrong share");}
  var food=pages.stream().filter(p->p.section().equals("Питание")).findFirst().orElseThrow();check(food.chart().labels().equals(List.of("Вкусняшки")),"Aliases duplicated");check(food.caption().contains("150 ₽ · 100 %"),"Alias amounts wrong");
  check(pages.stream().anyMatch(p->p.chart().labels().contains("Архив")),"Archived expense excluded");check(pages.stream().noneMatch(p->p.section().equals("Пусто")),"Empty chart included");
  check(ExpenseChartPages.build(data,cfg,List.of(),NOW,YearMonth.from(NOW).plusMonths(1)).isEmpty(),"Wrong month");
  var renderer=new ChartRenderer();var dark=ImageIO.read(new ByteArrayInputStream(renderer.png(food.chart(),true,food.total())));var light=ImageIO.read(new ByteArrayInputStream(renderer.png(food.chart(),false,food.total())));check(dark.getRGB(0,0)!=light.getRGB(0,0),"Theme ignored");
  Path previews=Path.of(".local-output","expense-chart-preview");Files.createDirectories(previews);Files.write(previews.resolve("dark.png"),renderer.png(food.chart(),true,food.total()));Files.write(previews.resolve("light.png"),renderer.png(food.chart(),false,food.total()));
  var engine=new LocalBotEngine(42,data,Clock.fixed(NOW.toInstant(),ZoneId.of("Europe/Moscow")));var field=LocalBotEngine.class.getDeclaredField("categorySections");field.setAccessible(true);field.set(engine,cfg);var reply=send(engine,"/report_chart");check(reply.imagePng()!=null&&reply.text().contains("Состав")==false,"No overview image");check(reply.images().size()==3,"Grouped expenses, income and comparison were not included");check(reply.buttons()==null,"Old report keyboard replaced");check(reply.text().equals(SectionViews.report(data,cfg,List.of(),NOW,true)),"Old report format changed");
  send(engine,"/chart_theme_light");reply=send(engine,"/report_chart");for(var png:reply.images()){var img=ImageIO.read(new ByteArrayInputStream(png));check((img.getRGB(0,0)&255)>200,"Bundle ignores common theme");}
  send(engine,"/chart_theme_dark");reply=send(engine,"/report_chart");for(var png:reply.images()){var img=ImageIO.read(new ByteArrayInputStream(png));check(img.getRGB(0,0)==dark.getRGB(0,0),"Dark bundle ignores common theme");}
  check(send(engine,"/expense_chart_page/old/1").text().contains("все диаграммы"),"Old page button not retired");check(before.equals(data.toString())&&engine.exportState().entries().isEmpty(),"Charts changed finances");
  System.out.println("Expense chart details: totals, category aliases, archive, dates, pagination without omissions, complete-section percentages, shared theme, complete bundle, original report/keyboard and unchanged finances passed.");
 }
}
