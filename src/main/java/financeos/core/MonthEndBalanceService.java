package financeos.core;

import financeos.data.LegacyUserData;
import java.math.BigDecimal;
import java.time.*;

/** Read-only forecast. Confirmed and skipped recurring occurrences are never added again. */
public final class MonthEndBalanceService {
    public record Projection(BigDecimal balance, BigDecimal expectedIncome,
                             BigDecimal remainingExpenses, BigDecimal endingBalance, boolean historical) {}
    public Projection calculate(LegacyUserData data, RecurringBook.State recurring,
                                OffsetDateTime now, YearMonth month) {
        return calculate(data, recurring, now, month, java.util.Map.of());
    }
    public Projection calculate(LegacyUserData data, RecurringBook.State recurring,
                                OffsetDateTime now, YearMonth month, java.util.Map<String, OperationLink> links) {
        var zone = ZoneId.of("Europe/Moscow");
        var current = YearMonth.from(now.atZoneSameInstant(zone));
        if (month.isBefore(current)) {
            var end = month.atEndOfMonth().atTime(LocalTime.MAX).atZone(zone).toOffsetDateTime();
            if (end.isBefore(data.snapshotTime())) throw new IllegalArgumentException("Month predates initial balance");
            var balance = new BalanceService().actualBalance(data, end);
            return new Projection(balance, BigDecimal.ZERO, BigDecimal.ZERO, balance, true);
        }
        var balance = new BalanceService().actualBalance(data, now);
        BigDecimal income = BigDecimal.ZERO, expenses = BigDecimal.ZERO;
        for (var m = current; !m.isAfter(month); m = m.plusMonths(1)) {
            var projected = new DebtBudgetService().project(data, links, now, m);
            var projectedEntries = projected.transactions().stream().filter(t -> !t.timestamp().isAfter(now))
                    .map(LegacyUserData.TimedEntry::entry).toList();
            expenses = expenses.add(new BudgetService().calculate(m, projected.plans(), projectedEntries).remaining());
            for (var rule : recurring.rules()) if (rule.kind() == EntryKind.INCOME) {
                for (var date : new MonthlySchedule().occurrences(rule, m.atDay(1), m.atEndOfMonth())) {
                    var code = new RecurringOccurrence(rule, date).code();
                    if (!recurring.decisions().containsKey(code)) income = income.add(rule.amount());
                }
            }
        }
        return new Projection(balance, income, expenses, balance.add(income).subtract(expenses), false);
    }
}
