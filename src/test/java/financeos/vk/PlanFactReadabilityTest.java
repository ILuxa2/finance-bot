package financeos.vk;
import financeos.core.*;import financeos.data.*;import java.time.*;import java.util.*;
public final class PlanFactReadabilityTest {
 static final OffsetDateTime NOW=OffsetDateTime.parse("2026-11-12T12:00:00+03:00");static final YearMonth MONTH=YearMonth.of(2026,11);
 static LegacyUserData.TimedEntry expense(String id,String category,String amount,OffsetDateTime at){return new LegacyUserData.TimedEntry(at,new LedgerEntry(id,at.toLocalDate(),EntryKind.EXPENSE,Money.of(amount),category,"test",false));}
 static void has(String text,String part){if(!text.contains(part))throw new AssertionError("Missing "+part+" in "+text);}
 public static void main(String[]a){
  var data=new LegacyUserData(Money.of("50000"),NOW.minusDays(20),List.of(expense("a","Еда","1500.50",NOW.minusDays(1)),expense("b","Проезд","20",NOW.minusDays(1)),expense("c","Вне плана","30.25",NOW.minusDays(1)),expense("future","Еда","9000",NOW.plusDays(1)),expense("old","Еда","700",NOW.minusMonths(1))),List.of(new BudgetPlan(MONTH,"Еда",Money.of("1000")),new BudgetPlan(MONTH,"Продукты",Money.of("1000")),new BudgetPlan(MONTH,"Проезд",Money.of("20"))));
  String original=data.toString();var text=LocalBotViews.planFact(data,NOW,MONTH);
  has(text,"План / факт · ноябрь 2026");has(text,"ИТОГО\nПлан · 2 020 ₽\nФакт · 1 550.75 ₽\nОсталось по плану · 1 000 ₽");has(text,"В том числе без плана · 30.25 ₽");
  has(text,"🔴 Еда\nПлан · 1 000 ₽\nФакт · 1 500.50 ₽\nСверх плана · 500.50 ₽");has(text,"⚪ Проезд\nПлан · 20 ₽\nФакт · 20 ₽\nОсталось по плану · 0 ₽");has(text,"🟠 Вне плана\nБез плана\nФакт · 30.25 ₽");
  var config=new CategorySections(List.of("Питание","Поездки","Пусто","Доходы"),Map.of("Еда","Питание","Продукты","Питание","Проезд","Поездки"),Map.of(),Set.of());
  var grouped=SectionViews.plan(data,config,List.of(),NOW,MONTH,"");has(grouped,"━━━━━━━━━━━━━━\n📂 ПИТАНИЕ\n🟢 В пределах плана\n\nПлан · 2 000 ₽\nФакт · 1 500.50 ₽\nОсталось по плану · 499.50 ₽");
  if(grouped.contains("Пусто")||grouped.contains("Доходы"))throw new AssertionError("Empty sections visible");has(SectionViews.plan(data,config,List.of(),NOW,MONTH,"Питание"),"📂 Питание");
  has(LocalBotViews.planFact(data,NOW,MONTH.minusMonths(1)),"Факт · 700 ₽");has(LocalBotViews.planFact(data,NOW,YearMonth.of(2020,1)),"Планов и расходов за этот месяц пока нет");
  var plans=new ArrayList<BudgetPlan>();for(int i=0;i<100;i++)plans.add(new BudgetPlan(MONTH,"Категория номер "+i+" длинное название",Money.of("1000")));
  var large=LocalBotViews.planFact(new LegacyUserData(Money.of("1"),NOW,List.of(),plans),NOW,MONTH);has(large,"План · 100 000 ₽");has(large,"Итоги включают всё");if(large.length()>4096)throw new AssertionError("Plan/fact exceeds message length");
  var newExpense=new LegacyUserData(Money.of("10000"),NOW.minusDays(2),List.of(expense("new","Кофе","500",NOW)),List.of(new BudgetPlan(MONTH,"Еда",Money.of("2000"))));
  var sectionConfig=new CategorySections(List.of("Питание"),Map.of("Еда","Питание","Кофе","Питание"),Map.of(),Set.of());
  var sectionText=SectionViews.plan(newExpense,sectionConfig,List.of(),NOW,MONTH,"");has(sectionText,"━━━━━━━━━━━━━━\n📂 ПИТАНИЕ\n🟢 В пределах плана\n\nПлан · 2 000 ₽\nФакт · 500 ₽\nОсталось по плану · 1 500 ₽");if(sectionText.contains("Сверх плана"))throw new AssertionError("New category falsely marks section overspent");
  has(SectionViews.plan(newExpense,sectionConfig,List.of(),NOW,MONTH,"Питание"),"🟠 Кофе");
  var overData=new LegacyUserData(Money.of("10000"),NOW.minusDays(2),List.of(expense("over","Кофе","2500",NOW)),newExpense.plans());has(SectionViews.plan(overData,sectionConfig,List.of(),NOW,MONTH,""),"Сверх плана · 500 ₽");
  if(!original.equals(data.toString()))throw new AssertionError("Formatting mutated finance");
  System.out.println("Plan/fact readability: grouped/category blocks, thousands/cents, actual unplanned amounts, overspending without consuming other reserves, months/empty/length and immutable data passed.");
 }
}
