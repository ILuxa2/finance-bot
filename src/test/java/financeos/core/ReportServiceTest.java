package financeos.core;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import financeos.data.LegacySheetSchema;

public final class ReportServiceTest {
    private static void equal(String expected, BigDecimal actual) {
        if (Money.of(expected).compareTo(actual) != 0) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }

    public static void main(String[] args) {
        Report report = new ReportService().calculate(new ReportInput(
                Money.of("104000"), Money.of("25000"), Money.of("3000")));
        equal("79000", report.freeMoney());
        equal("3000", report.unplannedExpenses());
        equal("104000", report.actualBalance());
        Report afterUnplannedExpense = new ReportService().calculate(new ReportInput(
                Money.of("101000"), Money.of("25000"), Money.of("6000")));
        equal("76000", afterUnplannedExpense.freeMoney());
        equal("6000", afterUnplannedExpense.unplannedExpenses());
        Report deficit = new ReportService().calculate(new ReportInput(
                Money.of("100"), Money.of("150"), Money.of("0")));
        equal("-50", deficit.freeMoney());
        try {
            Money.of("12,5");
            throw new AssertionError("Comma should be rejected");
        } catch (IllegalArgumentException expected) {
            // The user-facing input rule requires a dot.
        }
        OperationDraft draft = new OperationDraftParser().parse("1250.50, Продукты, хлеб");
        equal("1250.50", draft.amount());
        if (!draft.description().equals("Продукты, хлеб")) {
            throw new AssertionError("Description was changed");
        }
        CategoryCatalog categories = new CategoryCatalog();
        categories.ensure("Еда");
        categories.ensure(" еда ");
        categories.ensure("ЕДА");
        if (categories.all().size() != 1) {
            throw new AssertionError("Duplicate category was created");
        }
        var history = new HistoryService();
        var entries = List.of(
                new LedgerEntry("1", LocalDate.of(2026, 10, 30), EntryKind.INCOME,
                        Money.of("1000"), "Зарплата", "", true),
                new LedgerEntry("2", LocalDate.of(2026, 10, 30), EntryKind.EXPENSE,
                        Money.of("100"), "Еда", "", false),
                new LedgerEntry("3", LocalDate.of(2026, 10, 29), EntryKind.TRANSFER,
                        Money.of("500"), "", "", false));
        var today = history.totals(entries, LocalDate.of(2026, 10, 30), LocalDate.of(2026, 10, 30));
        equal("1000", today.income());
        equal("100", today.expense());
        equal("100", today.unplannedExpense());
        var budget = new BudgetService().calculate(YearMonth.of(2026, 10),
                List.of(new BudgetPlan(YearMonth.of(2026, 10), "Еда", Money.of("500"))), entries);
        equal("400", budget.remaining());
        var lines = new BudgetService().breakdown(YearMonth.of(2026, 10),
                List.of(new BudgetPlan(YearMonth.of(2026, 10), "Еда", Money.of("500"))), entries);
        if (lines.size() != 1 || lines.get(0).spent().compareTo(Money.of("100")) != 0) {
            throw new AssertionError("Budget breakdown does not match total");
        }
        equal("0", budget.unplannedExpense());
        var extra = new LedgerEntry("4", LocalDate.of(2026, 10, 30), EntryKind.EXPENSE,
                Money.of("75"), "Такси", "", false);
        var withExtra = new BudgetService().calculate(YearMonth.of(2026, 10),
                List.of(new BudgetPlan(YearMonth.of(2026, 10), "Еда", Money.of("500"))),
                List.of(entries.get(0), entries.get(1), extra));
        equal("75", withExtra.unplannedExpense());
        var forecast = new DebtForecastService().payments(
                new DebtSchedule("Кредит", Money.of("1500"), Money.of("1000"),
                        LocalDate.of(2026, 10, 31), LocalDate.of(2026, 11, 30)),
                LocalDate.of(2026, 10, 30), 12);
        equal("1000", forecast.get(YearMonth.of(2026, 10)));
        equal("500", forecast.get(YearMonth.of(2026, 11)));
        if (forecast.size() != 2) throw new AssertionError("Debt continues after its final month");
        var shortMonth = new DebtForecastService().payments(new DebtSchedule("Кредит",
                Money.of("3000"), Money.of("1000"), LocalDate.of(2027, 2, 28),
                LocalDate.of(2027, 4, 30), 31), LocalDate.of(2027, 2, 1), 12);
        equal("1000", shortMonth.get(YearMonth.of(2027, 2)));
        equal("1000", shortMonth.get(YearMonth.of(2027, 3)));
        equal("1000", shortMonth.get(YearMonth.of(2027, 4)));
        var salary = new MonthlyRule("salary", EntryKind.INCOME, Money.of("50000"), 31,
                LocalDate.of(2026, 1, 1), null, "Зарплата", true);
        var dates = new MonthlySchedule().occurrences(salary,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31));
        if (!dates.equals(List.of(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 28),
                LocalDate.of(2026, 3, 31)))) {
            throw new AssertionError("Monthly schedule drifted");
        }
        var undo = new UndoStack<String>(2);
        undo.remember("before first change");
        undo.remember("before second change");
        if (!undo.undo().orElseThrow().equals("before second change")) {
            throw new AssertionError("Undo order is wrong");
        }
        LegacySheetSchema.requireCompatible("Transactions", LegacySheetSchema.headers().get("Transactions"));
        try {
            LegacySheetSchema.requireCompatible("Transactions", List.of("wrong", "columns"));
            throw new AssertionError("Unexpected sheet columns were accepted");
        } catch (IllegalArgumentException expected) {
            // Reject schema changes before any future write operation.
        }
        Navigation nav = new Navigation();
        nav.open(Screen.ANALYTICS);
        nav.open(Screen.HISTORY);
        if (nav.back() != Screen.ANALYTICS || nav.back() != Screen.MAIN) {
            throw new AssertionError("Back button does not follow the path");
        }
        Debt debt = new Debt("Кредит", Money.of("2000"), Money.of("1000"), 15, true);
        equal("500", debt.pay(Money.of("1500")).remaining());
        if (debt.pay(Money.of("2000")).active()) throw new AssertionError("Repaid debt remained active");
        Goal goal = new Goal("Резерв", Money.of("1000"), Money.of("250"), Money.of("100"), true);
        equal("500", goal.contribute(Money.of("250")).remaining());
        equal("400", new DailyLimitService().remaining(LocalDate.of(2026, 10, 30),
                Money.of("500"), null, entries));
        System.out.println("Report calculations passed.");
    }
}
