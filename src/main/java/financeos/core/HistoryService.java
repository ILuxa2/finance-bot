package financeos.core;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

public final class HistoryService {
    public List<LedgerEntry> between(List<LedgerEntry> entries, LocalDate from, LocalDate through) {
        if (from.isAfter(through)) throw new IllegalArgumentException("Start date follows end date");
        return entries.stream()
                .filter(entry -> !entry.date().isBefore(from) && !entry.date().isAfter(through))
                .sorted(Comparator.comparing(LedgerEntry::date).reversed().thenComparing(LedgerEntry::id))
                .toList();
    }

    public PeriodTotals totals(List<LedgerEntry> entries, LocalDate from, LocalDate through) {
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;
        BigDecimal unplanned = BigDecimal.ZERO;
        for (LedgerEntry entry : between(entries, from, through)) {
            if (entry.kind() == EntryKind.INCOME) income = income.add(entry.amount());
            if (entry.kind() == EntryKind.EXPENSE) {
                expense = expense.add(entry.amount());
                if (!entry.planned()) unplanned = unplanned.add(entry.amount());
            }
        }
        return new PeriodTotals(income, expense, unplanned);
    }
}
