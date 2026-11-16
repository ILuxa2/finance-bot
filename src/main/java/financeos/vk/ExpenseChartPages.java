package financeos.vk;
import financeos.core.*;import financeos.data.*;import java.math.*;import java.time.*;import java.util.*;
/** Read-only category detail; every positive expense appears exactly once, without top-N loss. */
public final class ExpenseChartPages {
 public record Page(ChartData chart,BigDecimal total,String section,int part,int parts) {
  public String caption(){var out=new StringBuilder(chart.title()).append("\nВсего · ").append(PlanFactView.money(total));for(int i=0;i<chart.labels().size();i++){var value=chart.series().getFirst().values().get(i);out.append("\n").append(chart.labels().get(i)).append(" · ").append(PlanFactView.money(value)).append(" · ").append(percent(value,total)).append(" %");}return out.toString();}
 }
 public static String percent(BigDecimal amount,BigDecimal total){return total.signum()==0?"0":amount.multiply(BigDecimal.valueOf(100)).divide(total,1,RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();}
 public static List<Page> build(LegacyUserData original,CategorySections config,List<PlanEdit> edits,OffsetDateTime now,YearMonth month){
  var data=config.apply(original);var groups=new TreeMap<String,Map<String,BigDecimal>>();var names=new HashMap<String,String>();
  for(var timed:data.transactions()){
   var e=timed.entry();if(timed.timestamp().isAfter(now)||e.kind()!=EntryKind.EXPENSE||!YearMonth.from(e.date()).equals(month)||e.amount().signum()<=0)continue;
   String category=config.canonical(e.category());if(category.isBlank())category="Без категории";String key=CategorySections.key(category);names.putIfAbsent(key,category);
   groups.computeIfAbsent(SectionViews.section(config,edits,category,month),k->new TreeMap<>()).merge(key,e.amount(),BigDecimal::add);
  }
  if(groups.isEmpty())return List.of();var result=new ArrayList<Page>();var totals=new LinkedHashMap<String,BigDecimal>();groups.forEach((section,categories)->totals.put(section,categories.values().stream().reduce(BigDecimal.ZERO,BigDecimal::add)));
  var overview=totals.entrySet().stream().sorted(Map.Entry.<String,BigDecimal>comparingByValue().reversed().thenComparing(Map.Entry::getKey)).toList();BigDecimal grand=totals.values().stream().reduce(BigDecimal.ZERO,BigDecimal::add);
  append(result,"Расходы по разделам · "+month,"",overview,grand);
  for(var group:overview){var rows=groups.get(group.getKey()).entrySet().stream().map(e->Map.entry(names.get(e.getKey()),e.getValue())).sorted(Map.Entry.<String,BigDecimal>comparingByValue().reversed().thenComparing(Map.Entry::getKey)).toList();append(result,group.getKey()+" · "+month,group.getKey(),rows,group.getValue());}
  return List.copyOf(result);
 }
 private static void append(List<Page> out,String title,String section,List<Map.Entry<String,BigDecimal>> rows,BigDecimal total){int count=(rows.size()+7)/8;for(int offset=0;offset<rows.size();offset+=8){var part=rows.subList(offset,Math.min(offset+8,rows.size()));int index=offset/8+1;out.add(new Page(new ChartData(title+(count>1?" · "+index+"/"+count:""),part.stream().map(Map.Entry::getKey).toList(),List.of(new ChartSeries("Сумма, ₽ · доля "+(section.isBlank()?"всех расходов":"раздела"),part.stream().map(Map.Entry::getValue).toList()))),total,section,index,count));}}
}
