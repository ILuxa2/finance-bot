package financeos.vk;
import financeos.core.*;import financeos.data.*;import java.math.BigDecimal;import java.time.*;import java.time.format.DateTimeFormatter;import java.util.*;
/** Readable VK text; all budget calculations remain in BudgetService. */
public final class PlanFactView {
 record Row(String name,BigDecimal planned,BigDecimal spent,BigDecimal remaining,boolean hasPlan){}
 static String money(BigDecimal n){String value=String.format(Locale.ROOT,"%,.2f",n).replace(',',' ');return (value.endsWith(".00")?value.substring(0,value.length()-3):value)+" ₽";}
 static List<LedgerEntry> entries(LegacyUserData data,OffsetDateTime now){return data.transactions().stream().filter(t->!t.timestamp().isAfter(now)).map(LegacyUserData.TimedEntry::entry).toList();}
 public static String categories(LegacyUserData data,OffsetDateTime now,YearMonth month,String subtitle){
  var entries=entries(data,now);var lines=new BudgetService().breakdown(month,data.plans(),entries);var rows=new ArrayList<Row>();var planned=new HashSet<String>();
  for(var line:lines){planned.add(CategorySections.key(line.category()));rows.add(new Row(line.category(),line.planned(),line.spent(),line.remaining(),true));}
  var unplanned=new TreeMap<String,BigDecimal>();for(var e:entries)if(e.kind()==EntryKind.EXPENSE&&YearMonth.from(e.date()).equals(month)&&!planned.contains(CategorySections.key(e.category())))unplanned.merge(e.category(),e.amount(),BigDecimal::add);
  unplanned.forEach((name,amount)->rows.add(new Row(name,BigDecimal.ZERO,amount,BigDecimal.ZERO,false)));
  return render(rows,month,subtitle,unplanned.values().stream().reduce(BigDecimal.ZERO,BigDecimal::add));
 }
 static String render(List<Row> rows,YearMonth month,String subtitle,BigDecimal unplanned){
  String title="📋 План / факт · "+month.atDay(1).format(DateTimeFormatter.ofPattern("LLLL yyyy",Locale.forLanguageTag("ru")));
  var text=new StringBuilder(title);if(!subtitle.isBlank())text.append("\n").append(subtitle);
  if(rows.isEmpty())return text.append("\n\nПланов и расходов за этот месяц пока нет.\nВыберите другой месяц или добавьте план.").toString();
  BigDecimal planned=BigDecimal.ZERO,spent=BigDecimal.ZERO,remaining=BigDecimal.ZERO;for(var row:rows){planned=planned.add(row.planned());spent=spent.add(row.spent());remaining=remaining.add(row.remaining());}
  text.append("\n\nИТОГО\nПлан · ").append(money(planned)).append("\nФакт · ").append(money(spent)).append("\nОсталось по плану · ").append(money(remaining));
  if(unplanned.signum()>0)text.append("\nВ том числе без плана · ").append(money(unplanned));
  int shown=0;for(var row:rows){String block=block(row);if(text.length()+block.length()+200>3500)break;text.append(block);shown++;}
  if(shown<rows.size())text.append("\n\nПоказано · ").append(shown).append(" из ").append(rows.size()).append(". Итоги включают всё. Откройте нужный раздел для подробностей.");
  return text.append("\n\n🟢 В пределах плана · 🔴 Перерасход · 🟠 Без плана\nОстаток считается отдельно по каждой категории.").toString();
 }
 static String block(Row r){
  BigDecimal over=r.spent().add(r.remaining()).subtract(r.planned()).max(BigDecimal.ZERO);
  String mark=!r.hasPlan()?"🟠":over.signum()>0?"🔴":r.remaining().signum()>0?"🟢":"⚪";
  var s=new StringBuilder("\n\n").append(mark).append(" ").append(r.name());
  if(!r.hasPlan())return s.append("\nБез плана\nФакт · ").append(money(r.spent())).toString();
  s.append("\nПлан · ").append(money(r.planned())).append("\nФакт · ").append(money(r.spent()));
  if(r.remaining().signum()>0||over.signum()==0)s.append("\nОсталось по плану · ").append(money(r.remaining()));
  if(over.signum()>0)s.append("\nСверх плана · ").append(money(over));
  return s.toString();
 }
}
