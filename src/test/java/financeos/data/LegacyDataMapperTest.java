package financeos.data;

import financeos.core.BalanceService;
import financeos.core.Money;
import financeos.core.LegacyReportService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.time.YearMonth;

public final class LegacyDataMapperTest {
    public static void main(String[] args) {
        var sheets = Map.of(
                "StartupState", List.of(Map.of(
                        "telegram_user_id", "42", "created_at", "2026-10-30T09:00:00+03:00",
                        "total_balance", "104000.00")),
                "BudgetPlan", List.of(Map.of(
                        "telegram_user_id", "42", "month", "2026-10", "category", "Еда",
                        "planned_amount", "25000")),
                "Debts", List.of(Map.of("telegram_user_id", "42", "created_at", "2026-10-29T09:00:00+03:00",
                        "debt_name", "Кредит", "current_amount", "1500", "monthly_payment", "1000",
                        "payment_day", "31", "status", "active")),
                "Goals", List.of(Map.of("telegram_user_id", "42", "created_at", "2026-10-29T09:00:00+03:00",
                        "goal_name", "Резерв", "target_amount", "10000", "current_amount", "2000",
                        "monthly_target", "1000", "status", "active")),
                "Categories", List.of(
                        Map.of("telegram_user_id", "42", "category_name", "Зарплата", "status", "active"),
                        Map.of("telegram_user_id", "42", "category_name", "Старая", "status", "archived")),
                "DailyLimits", List.of(Map.of("telegram_user_id", "42", "created_at", "2026-10-29T09:00:00+03:00",
                        "month", "2026-10", "daily_limit", "500", "status", "active")),
                "Transactions", List.of(
                        Map.of("telegram_user_id", "42", "created_at", "2026-10-30T08:00:00+03:00",
                                "type", "expense", "amount", "100", "category", "Еда", "description", "до снимка"),
                        Map.of("telegram_user_id", "42", "created_at", "2026-10-30T10:00:00+03:00",
                                "type", "income", "amount", "1000", "category", "Зарплата", "description", "после снимка"),
                        Map.of("telegram_user_id", "42", "created_at", "2026-10-30T11:00:00+03:00",
                                "type", "expense", "amount", "250", "category", "Еда", "description", "после снимка"),
                        Map.of("telegram_user_id", "42", "created_at", "2026-10-30T11:30:00+03:00",
                                "type", "expense", "amount", "300", "category", "Такси", "description", "вне плана")));
        var data = new LegacyDataMapper().user(sheets, "42");
        if (data.transactions().size() != 4 || data.plans().size() != 1
                || !data.transactions().get(1).entry().date().toString().equals("2026-10-30")) {
            throw new AssertionError("Existing sheet rows were mapped incorrectly");
        }
        if (data.extras().debts().size() != 1 || data.extras().goals().size() != 1
                || !data.extras().categories().equals(List.of("Зарплата"))
                || data.extras().monthlyLimits().get(YearMonth.of(2026, 10))
                        .compareTo(Money.of("500")) != 0) {
            throw new AssertionError("Existing debt, goal, category or limit was not mapped");
        }
        var balance = new BalanceService().actualBalance(data,
                OffsetDateTime.parse("2026-10-30T12:00:00+03:00"));
        if (balance.compareTo(Money.of("104450")) != 0) {
            throw new AssertionError("Starting snapshot or later transactions were double-counted");
        }
        var report = new LegacyReportService().calculate(data,
                OffsetDateTime.parse("2026-10-30T12:00:00+03:00"));
        if (report.actualBalance().compareTo(Money.of("104450")) != 0
                || report.plannedExpenses().compareTo(Money.of("25000")) != 0
                || report.remainingPlans().compareTo(Money.of("24650")) != 0
                || report.freeMoney().compareTo(Money.of("79800")) != 0
                || report.unplannedThisMonth().compareTo(Money.of("300")) != 0
                || report.incomeToday().compareTo(Money.of("1000")) != 0
                || report.expenseToday().compareTo(Money.of("650")) != 0) {
            throw new AssertionError("Combined report values are wrong");
        }
        System.out.println("Existing-data mapping checks passed.");
    }
}
