package financeos.vk;
import financeos.core.*;import financeos.data.*;import java.math.BigDecimal;import java.time.*;import java.time.format.*;import java.util.*;
/** Complete, paginated section reports. Formatting never changes financial data. */
public final class SectionPlanFactView {
 private static final int PAGE_LIMIT=3400;
 private static final String FOOTER="\n\n🟢 В пределах плана · 🔴 Перерасход · 🟠 Без плана\nРезервы категорий считаются отдельно: перерасход одной не списывается из другой.";
 private static List<PlanFactView.Row> categories(LegacyUserData data,CategorySections config,OffsetDateTime now,YearMonth month){
  var entries=PlanFactView.entries(data,now);var rows=new LinkedHashMap<String,PlanFactView.Row>();
  for(var line:new BudgetService().breakdown(month,data.plans(),entries))rows.put(CategorySections.key(line.category()),new PlanFactView.Row(line.category(),line.planned(),line.spent(),line.remaining(),true));
  for(var e:entries)if(e.kind()==EntryKind.EXPENSE&&YearMonth.from(e.date()).equals(month)){
   String key=CategorySections.key(e.category());var row=rows.get(key);
   if(row==null)rows.put(key,new PlanFactView.Row(e.category(),BigDecimal.ZERO,e.amount(),BigDecimal.ZERO,false));
   else if(!row.hasPlan())rows.put(key,new PlanFactView.Row(row.name(),BigDecimal.ZERO,row.spent().add(e.amount()),BigDecimal.ZERO,false));
  }
  return List.copyOf(rows.values());
 }
 private static String categoryBlock(PlanFactView.Row row){
  String block=!row.hasPlan()&&row.spent().signum()==0?"⚪ "+row.name()+"\nПлан · 0 ₽\nФакт · 0 ₽\nНет плана и расходов":PlanFactView.block(row).stripLeading();
  return "\n\n  "+block.replace("\n","\n  ");
 }
 public static List<String> pages(LegacyUserData original,CategorySections config,List<PlanEdit> edits,OffsetDateTime now,YearMonth month,String selected){
  var data=config.apply(original);var rows=categories(data,config,now,month);var sections=SectionViews.budget(data,config,edits,now,month);
  String title="📋 План / факт · "+month.atDay(1).format(DateTimeFormatter.ofPattern("LLLL yyyy",Locale.forLanguageTag("ru")))+"\n"+(selected.isBlank()?"По разделам и категориям":"📂 "+selected);
  var visible=sections.stream().filter(s->selected.isBlank()||s.category().equals(selected)).filter(s->s.spent().signum()>0||rows.stream().anyMatch(r->r.hasPlan()&&SectionViews.section(config,edits,r.name(),month).equals(s.category()))||(!selected.isBlank()&&config.assignments().keySet().stream().anyMatch(c->!config.hidden(c)&&SectionViews.section(config,edits,c,month).equals(selected)))).toList();
  if(visible.isEmpty())return List.of(title+"\n\nПланов и расходов за этот месяц пока нет.\nВыберите другой месяц или добавьте план.");
  var filtered=rows.stream().filter(r->selected.isBlank()||SectionViews.section(config,edits,r.name(),month).equals(selected)).toList();
  BigDecimal planned=BigDecimal.ZERO,spent=BigDecimal.ZERO,remaining=BigDecimal.ZERO,unplanned=BigDecimal.ZERO;
  for(var row:filtered){planned=planned.add(row.planned());spent=spent.add(row.spent());remaining=remaining.add(row.remaining());if(!row.hasPlan())unplanned=unplanned.add(row.spent());}
  String header=title+"\n\nИТОГО (все страницы)\nПлан · "+PlanFactView.money(planned)+"\nФакт · "+PlanFactView.money(spent)+"\nОсталось по плану · "+PlanFactView.money(remaining)+(unplanned.signum()>0?"\nВ том числе без плана · "+PlanFactView.money(unplanned):"");
  var pages=new ArrayList<String>();var text=new StringBuilder(header);
  for(var section:visible){
   var members=new TreeMap<String,PlanFactView.Row>();for(var row:rows)if(SectionViews.section(config,edits,row.name(),month).equals(section.category()))members.put(CategorySections.key(row.name()),row);
   // Include active empty categories within the section; preserve archived categories with facts/plans.
   for(var name:config.assignments().keySet())if(!config.hidden(name)&&SectionViews.section(config,edits,name,month).equals(section.category())){String display=config.canonical(name);members.putIfAbsent(CategorySections.key(display),new PlanFactView.Row(display,BigDecimal.ZERO,BigDecimal.ZERO,BigDecimal.ZERO,false));}
   boolean hasPlan=members.values().stream().anyMatch(PlanFactView.Row::hasPlan);
   BigDecimal net=section.planned().subtract(section.spent()).max(BigDecimal.ZERO);
   String summary=hasPlan||section.spent().signum()>0?PlanFactView.block(new PlanFactView.Row(section.category(),section.planned(),section.spent(),net,hasPlan)):"\n\n⚪ "+section.category()+"\nПлан · 0 ₽\nФакт · 0 ₽";
   String body=summary.stripLeading();int firstLine=body.indexOf('\n');
   String status=!hasPlan?"🟠 Без плана":section.spent().compareTo(section.planned())>0?"🔴 Перерасход":"🟢 В пределах плана";
   summary="\n\n━━━━━━━━━━━━━━\n📂 "+section.category().toUpperCase(Locale.forLanguageTag("ru"))+"\n"+status+"\n\n"+(firstLine<0?"":body.substring(firstLine+1));
   if(section.remaining().compareTo(net)!=0)summary+="\nРезервы категорий · "+PlanFactView.money(section.remaining());
   summary+="\nКатегории раздела:";
   String first=members.isEmpty()?"":categoryBlock(members.firstEntry().getValue());
   if(text.length()>header.length()&&text.length()+summary.length()+first.length()+FOOTER.length()+60>PAGE_LIMIT){pages.add(text.toString());text=new StringBuilder(header);}
   text.append(summary);boolean shown=false;
   for(var row:members.values()){
    String block=categoryBlock(row);
    if(shown&&text.length()+block.length()+FOOTER.length()+60>PAGE_LIMIT){pages.add(text.toString());text=new StringBuilder(header).append(summary.replace("Категории раздела:","Категории раздела (продолжение):"));}
    text.append(block);shown=true;
   }
  }
  pages.add(text.toString());var complete=new ArrayList<String>();for(int i=0;i<pages.size();i++)complete.add(pages.get(i)+FOOTER+(pages.size()>1?"\nСтраница · "+(i+1)+" / "+pages.size():""));return List.copyOf(complete);
 }
}
