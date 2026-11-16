package financeos.core;
import financeos.data.*;import java.time.*;import java.util.*;import java.math.*;
public final class MonthEndBalanceServiceTest {
 static void eq(BigDecimal actual,String expected){if(actual.compareTo(Money.of(expected))!=0)throw new AssertionError(actual+" != "+expected);}
 public static void main(String[] args){
  var now=OffsetDateTime.parse("2026-11-13T12:00:00+03:00");var month=YearMonth.of(2026,11);
  var expense=new LedgerEntry("paid",now.toLocalDate(),EntryKind.EXPENSE,Money.of("10000"),"Еда","",true);
  var extra=new LedgerEntry("extra",now.toLocalDate(),EntryKind.EXPENSE,Money.of("5000"),"Другое","",false);
  var data=new LegacyUserData(Money.of("100000"),now.minusDays(15),List.of(new LegacyUserData.TimedEntry(now,expense),new LegacyUserData.TimedEntry(now,extra)),List.of(new BudgetPlan(month,"Еда",Money.of("30000")),new BudgetPlan(month.plusMonths(1),"Еда",Money.of("20000"))));
  var rule=new MonthlyRule("salary",EntryKind.INCOME,Money.of("20000"),15,month.atDay(1),null,"Зарплата","",true);
  var state=new RecurringBook.State(List.of(rule),Map.of(),2);var service=new MonthEndBalanceService();var result=service.calculate(data,state,now,month);
  eq(result.balance(),"85000");eq(result.expectedIncome(),"20000");eq(result.remainingExpenses(),"20000");eq(result.endingBalance(),"85000");
  var paidIncome=new LedgerEntry("salaryPaid",now.toLocalDate(),EntryKind.INCOME,Money.of("20000"),"Зарплата","",false);
  var transactions=new ArrayList<>(data.transactions());transactions.add(new LegacyUserData.TimedEntry(now,paidIncome));
  var confirmed=new LegacyUserData(data.startingBalance(),data.snapshotTime(),transactions,data.plans());
  eq(service.calculate(confirmed,new RecurringBook.State(List.of(rule),Map.of("salary@2026-11-15",true),2),now,month).endingBalance(),"85000");
  eq(service.calculate(data,new RecurringBook.State(List.of(rule),Map.of("salary@2026-11-15",false),2),now,month).endingBalance(),"65000");
  eq(service.calculate(data,state,now,month.plusMonths(1)).endingBalance(),"85000");
  var lastDay=new MonthlyRule("last",EntryKind.INCOME,Money.of("10"),31,LocalDate.of(2026,1,1),LocalDate.of(2026,11,30),"Доход","",true);
  eq(service.calculate(data,new RecurringBook.State(List.of(lastDay),Map.of(),2),now,month).expectedIncome(),"10");
  eq(service.calculate(data,new RecurringBook.State(List.of(lastDay),Map.of(),2),now,month.plusMonths(1)).expectedIncome(),"10");
  var historical=service.calculate(data,state,now,month.minusMonths(1));if(!historical.historical())throw new AssertionError();eq(historical.expectedIncome(),"0");
  System.out.println("Month-end forecast: paid/unplanned expenses counted once, confirmed/skipped income excluded, future months, month-end bounds and history passed.");
 }
}
