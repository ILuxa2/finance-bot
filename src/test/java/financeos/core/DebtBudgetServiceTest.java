package financeos.core;

import financeos.data.*;
import java.time.*;
import java.util.*;
import java.math.BigDecimal;
public final class DebtBudgetServiceTest {
    static final OffsetDateTime NOW=OffsetDateTime.parse("2026-11-07T12:00:00+03:00");
    static LegacyUserData fixture(String remaining,String paid,boolean active,List<BudgetPlan> plans) {
        var entries=paid.equals("0")?List.<LegacyUserData.TimedEntry>of():List.of(new LegacyUserData.TimedEntry(NOW,new LedgerEntry("payment",NOW.toLocalDate(),EntryKind.EXPENSE,Money.of(paid),"Кредит","платёж",false)));
        return new LegacyUserData(Money.of("50000"),NOW.minusDays(1),entries,plans,
                new LegacyExtras(List.of(new Debt("Кредит",Money.of(remaining),Money.of("7620"),19,active)),List.of(),List.of(),Map.of(),Map.of()));
    }
    static LegacyUserData project(LegacyUserData data) { return new DebtBudgetService().project(data,Map.of("payment",new OperationLink(OperationLink.Type.DEBT_PAYMENT,"Кредит")),NOW); }
    static void equal(BigDecimal actual,String expected) { if(actual.compareTo(Money.of(expected))!=0)throw new AssertionError(actual+" != "+expected); }
    static BigDecimal plan(LegacyUserData data,String month) {return data.plans().stream().filter(p->p.month().equals(YearMonth.parse(month))).map(BudgetPlan::amount).reduce(BigDecimal.ZERO,BigDecimal::add);}
    public static void main(String[] args) {
        var fresh=project(fixture("15060","0",true,List.of()));equal(plan(fresh,"2026-11"),"7620");equal(plan(fresh,"2026-12"),"7440");equal(plan(fresh,"2027-01"),"0");
        var before=new LegacyReportService().calculate(fresh,NOW);equal(before.freeMoney(),"42380");
        var partial=project(fixture("14060","1000",true,List.of()));var after=new LegacyReportService().calculate(partial,NOW);
        equal(after.remainingPlans(),"6620");equal(after.freeMoney(),"42380");equal(after.unplannedThisMonth(),"0");equal(plan(partial,"2026-12"),"7440");
        var paid=project(fixture("7440","7620",true,List.of()));equal(new LegacyReportService().calculate(paid,NOW).remainingPlans(),"0");equal(plan(paid,"2026-12"),"7440");
        var closed=project(fixture("0","7440",false,List.of()));equal(plan(closed,"2026-11"),"7440");equal(plan(closed,"2026-12"),"0");
        var existing=project(fixture("15060","0",true,List.of(new BudgetPlan(YearMonth.of(2026,11),"Кредит",Money.of("7620")))));equal(plan(existing,"2026-11"),"7620");
        equal(new BalanceService().actualBalance(partial,NOW),"49000");
        var longDebt = fixture("200000", "0", true, List.of());
        var far = new DebtBudgetService().project(longDebt, Map.of(), NOW, YearMonth.of(2028, 1));
        equal(plan(far, "2028-01"), "7620");
        var wrongManual = project(fixture("15060", "0", true, List.of(new BudgetPlan(YearMonth.of(2026,12), "Кредит", Money.of("7620")), new BudgetPlan(YearMonth.of(2027,1), "Кредит", Money.of("7620")))));
        equal(plan(wrongManual, "2026-12"), "7440"); equal(plan(wrongManual, "2027-01"), "0");
        var late = new DebtBudgetService().project(fixture("15060", "0", true, List.of()), Map.of(), NOW.withDayOfMonth(25));
        equal(plan(late, "2026-11"), "0");equal(plan(late, "2026-12"), "7620");
        var extra = project(fixture("5060", "10000", true, List.of()));
        equal(plan(extra, "2026-11"), "7620");equal(plan(extra, "2026-12"), "5060");
        equal(new LegacyReportService().calculate(extra, NOW).freeMoney(), "40000");
        var rolled = new DebtBudgetService().project(fixture("14060", "1000", true, List.of()),
                Map.of("payment", new OperationLink(OperationLink.Type.DEBT_PAYMENT, "Кредит")), NOW.plusMonths(1));
        equal(plan(rolled, "2026-11"), "7620");
        var oldBudget = new BudgetService().calculate(YearMonth.of(2026,11), rolled.plans(), rolled.transactions().stream().map(LegacyUserData.TimedEntry::entry).toList());
        equal(oldBudget.planned(), "7620");equal(oldBudget.spentAgainstPlan(), "1000");
        System.out.println("Debt reserves: partial/final payments, no double debit, no manual duplicate and payoff boundary passed.");
    }
}
