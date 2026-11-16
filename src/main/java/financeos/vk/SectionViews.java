package financeos.vk;
import financeos.core.*;import financeos.data.*;import java.math.BigDecimal;import java.time.*;import java.util.*;
public final class SectionViews {
 public static String section(CategorySections config,List<PlanEdit> edits,String category,YearMonth month){
  String name=config.canonical(category);for(int i=edits.size();i>=0;i--){String lookup=name;
   if(config.assignments().keySet().stream().anyMatch(k->CategorySections.key(config.canonical(k)).equals(CategorySections.key(lookup))))return config.section(name);
   if(i>0){var e=edits.get(i-1);if(e.month().equals(month)&&CategorySections.key(config.canonical(e.category())).equals(CategorySections.key(name)))name=config.canonical(e.source());}
  }return CategorySections.OTHER;
 }
 static String money(BigDecimal n){return n.setScale(2).toPlainString();}
 public static List<BudgetLine> budget(LegacyUserData data,CategorySections config,List<PlanEdit> edits,OffsetDateTime now,YearMonth month){
  var entries=data.transactions().stream().filter(t->!t.timestamp().isAfter(now)).map(LegacyUserData.TimedEntry::entry).toList();
  var totals=new LinkedHashMap<String,BigDecimal[]>();for(var group:config.allSections())totals.put(group,new BigDecimal[]{BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO});
  for(var line:new BudgetService().breakdown(month,data.plans(),entries)){String group=section(config,edits,line.category(),month);var values=totals.computeIfAbsent(group,k->new BigDecimal[]{BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO});values[0]=values[0].add(line.planned());values[2]=values[2].add(line.remaining());}
  for(var e:entries)if(e.kind()==EntryKind.EXPENSE&&YearMonth.from(e.date()).equals(month)){var v=totals.computeIfAbsent(section(config,edits,e.category(),month),k->new BigDecimal[]{BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO});v[1]=v[1].add(e.amount());}
  return totals.entrySet().stream().map(e->new BudgetLine(e.getKey(),e.getValue()[0],e.getValue()[1],e.getValue()[2])).sorted(Comparator.comparing(BudgetLine::category)).toList();
 }
 public static String plan(LegacyUserData data,CategorySections config,List<PlanEdit> edits,OffsetDateTime now,YearMonth month,String selected){
  return SectionPlanFactView.pages(data,config,edits,now,month,selected).getFirst();
 }

 public static ChartData chart(LegacyUserData data,CategorySections config,List<PlanEdit> edits,OffsetDateTime now,ChartKind kind){
  YearMonth month=YearMonth.from(now);if(kind==ChartKind.PLAN){var lines=budget(data,config,edits,now,month);return new ChartData("План / факт по разделам · "+month,lines.stream().map(BudgetLine::category).toList(),List.of(new ChartSeries("План, ₽",lines.stream().map(BudgetLine::planned).toList()),new ChartSeries("Факт, ₽",lines.stream().map(BudgetLine::spent).toList())));}
  var totals=new TreeMap<String,BigDecimal>();EntryKind type=kind==ChartKind.INCOME?EntryKind.INCOME:EntryKind.EXPENSE;
  for(var t:data.transactions())if(!t.timestamp().isAfter(now)&&t.entry().kind()==type&&YearMonth.from(t.entry().date()).equals(month))totals.merge(section(config,edits,t.entry().category(),month),t.entry().amount(),BigDecimal::add);
  return new ChartData((type==EntryKind.INCOME?"Доходы":"Расходы")+" по разделам · "+month,new ArrayList<>(totals.keySet()),List.of(new ChartSeries("Сумма, ₽",new ArrayList<>(totals.values()))));
 }
 public static String report(LegacyUserData data,CategorySections config,List<PlanEdit> edits,OffsetDateTime now,boolean grouped){
  var chart=grouped?chart(data,config,edits,now,ChartKind.EXPENSES):new ChartService().prepare(ChartKind.EXPENSES,data,now);var s=new StringBuilder(LocalBotViews.report(data,now));s.append(grouped?"\n\n📂 Расходы по разделам":"\n\n📋 Расходы по категориям");for(int i=0;i<chart.labels().size();i++)s.append("\n").append(chart.labels().get(i)).append(" · ").append(money(chart.series().getFirst().values().get(i))).append(" ₽");return s.toString();
 }
}
