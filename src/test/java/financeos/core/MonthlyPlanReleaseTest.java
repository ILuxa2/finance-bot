package financeos.core;
import financeos.data.*;import financeos.vk.*;import java.time.*;import java.util.*;import java.math.BigDecimal;
/** Old month reservations expire; real money is never credited twice. Synthetic data only. */
public final class MonthlyPlanReleaseTest {
 static void equal(String value,BigDecimal actual){if(Money.of(value).compareTo(actual)!=0)throw new AssertionError(value+" != "+actual);}
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 public static void main(String[] args){
  var nov=YearMonth.of(2026,11);var before=OffsetDateTime.parse("2026-11-30T23:59:59+03:00");var after=before.plusSeconds(1);
  var spent=new LedgerEntry("qa-1",LocalDate.of(2026,11,20),EntryKind.EXPENSE,Money.of("2000"),"Еда","QA",false);
  var entries=List.of(new LegacyUserData.TimedEntry(OffsetDateTime.parse("2026-11-20T12:00:00+03:00"),spent));
  var old=new BudgetPlan(nov,"Еда",Money.of("7000"));
  var data=new LegacyUserData(Money.of("32000"),before.minusMonths(2),entries,List.of(old));String original=data.toString();
  var service=new LegacyReportService();var november=service.calculate(data,before);equal("30000",november.actualBalance());equal("5000",november.remainingPlans());equal("25000",november.freeMoney());
  var december=service.calculate(data,after);equal("30000",december.actualBalance());equal("0",december.remainingPlans());equal("30000",december.freeMoney());
  // Same instant in UTC must also select December, according to Moscow budget time.
  equal("30000",service.calculate(data,after.withOffsetSameInstant(ZoneOffset.UTC)).freeMoney());
  var next=new LegacyUserData(data.startingBalance(),data.snapshotTime(),entries,List.of(old,new BudgetPlan(nov.plusMonths(1),"Еда",Money.of("4000"))));
  var withNewPlan=service.calculate(next,after);equal("30000",withNewPlan.actualBalance());equal("4000",withNewPlan.remainingPlans());equal("26000",withNewPlan.freeMoney());
  var later=service.calculate(next,after.plusDays(10));equal("26000",later.freeMoney());equal("30000",later.actualBalance());
  var historical=new BudgetService().calculate(nov,next.plans(),List.of(spent));equal("7000",historical.planned());equal("2000",historical.spentAgainstPlan());equal("5000",historical.remaining());
  check(original.equals(data.toString()),"Month rollover mutated data");check(LocalBotViews.report(data,after).contains("Свободные деньги · 30000.00"),"UI retained old reserve");
  for(String end:List.of("2026-12-31T23:59:59+03:00","2028-02-29T23:59:59+03:00")){
   var last=OffsetDateTime.parse(end);var sample=new LegacyUserData(Money.of("100"),last.minusDays(1),List.of(),List.of(new BudgetPlan(YearMonth.from(last),"QA",Money.of("40"))));
   equal("60",service.calculate(sample,last).freeMoney());equal("100",service.calculate(sample,last.plusSeconds(1)).freeMoney());equal("100",service.calculate(sample,last.plusSeconds(1)).actualBalance());
  }
  System.out.println("Monthly plan release: Moscow midnight, new month plans, historical plan preserved, repeated reads, year/leap boundaries and unchanged balance passed.");
 }
}

