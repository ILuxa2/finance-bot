package financeos.core;

import financeos.data.LegacyUserData;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;

public final class LegacyReportService {
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

    public FinanceReport calculate(LegacyUserData data, OffsetDateTime asOf) {
        var date = asOf.atZoneSameInstant(MOSCOW).toLocalDate();
        var entries = data.transactions().stream()
                .filter(timed -> !timed.timestamp().toInstant().isAfter(asOf.toInstant()))
                .map(LegacyUserData.TimedEntry::entry).toList();
        var balance = new BalanceService().actualBalance(data, asOf);
        var budget = new BudgetService().calculate(YearMonth.from(date), data.plans(), entries);
        var today = new HistoryService().totals(entries, date, date);
        return new FinanceReport(balance, budget.planned(), budget.remaining(),
                balance.subtract(budget.remaining()), budget.unplannedExpense(),
                today.income(), today.expense());
    }
}
