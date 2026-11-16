package financeos.core;

import financeos.data.LegacyUserData;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;

/** Derived debt reserves. Never creates a transaction or changes principal. */
public final class DebtBudgetService {
    public static String category(String name) { return "Кредит · " + name; }
    public LegacyUserData project(LegacyUserData data, Map<String, OperationLink> links, OffsetDateTime now) {
        return project(data, links, now, null);
    }
    public LegacyUserData project(LegacyUserData data, Map<String, OperationLink> links, OffsetDateTime now, YearMonth requestedMonth) {
        LocalDate today = now.atZoneSameInstant(ZoneId.of("Europe/Moscow")).toLocalDate();
        YearMonth current = YearMonth.from(today);
        var offsets = new TreeSet<Long>();
        for (long i = 0; i < 12; i++) offsets.add(i);
        for (var plan : data.plans()) if (!plan.month().isBefore(current)) offsets.add(java.time.temporal.ChronoUnit.MONTHS.between(current, plan.month()));
        if (requestedMonth != null && !requestedMonth.isBefore(current)) offsets.add(java.time.temporal.ChronoUnit.MONTHS.between(current, requestedMonth));
        var entries = new ArrayList<LegacyUserData.TimedEntry>();
        var paid = new HashMap<String, java.util.NavigableMap<YearMonth, BigDecimal>>();
        for (var timed : data.transactions()) {
            var e = timed.entry(); var link = links.get(e.id());
            if (link != null && link.type() == OperationLink.Type.DEBT_PAYMENT && e.kind() == EntryKind.EXPENSE) {
                if (!timed.timestamp().isAfter(now) && !e.date().isAfter(today)) paid.computeIfAbsent(link.target(), ignored -> new TreeMap<>()).merge(YearMonth.from(e.date()), e.amount(), BigDecimal::add);
                e = new LedgerEntry(e.id(), e.date(), e.kind(), e.amount(), category(link.target()), e.description(), true);
                entries.add(new LegacyUserData.TimedEntry(timed.timestamp(), e));
            } else entries.add(timed);
        }
        var plans = new ArrayList<>(data.plans());
        for (var debt : data.extras().debts()) {
            var paidMonths = paid.getOrDefault(debt.name(), new TreeMap<YearMonth, BigDecimal>());
            BigDecimal spent = paidMonths.getOrDefault(current, BigDecimal.ZERO);
            var debtOffsets = new TreeSet<>(offsets);
            for (var month : paidMonths.keySet()) if (month.isBefore(current)) debtOffsets.add(java.time.temporal.ChronoUnit.MONTHS.between(current, month));
            BigDecimal principal = debt.active() ? debt.remaining() : BigDecimal.ZERO;
            boolean nextMonth = spent.signum() == 0 && current.atDay(Math.min(debt.paymentDay(), current.lengthOfMonth())).isBefore(today);
            BigDecimal firstDue = nextMonth ? BigDecimal.ZERO : debt.monthlyPayment().subtract(spent).max(BigDecimal.ZERO).min(principal);
            BigDecimal afterFirst = principal.subtract(firstDue);
            for (long offset : debtOffsets) {
                YearMonth month = current.plusMonths(offset);
                BigDecimal used = offset == 0 ? spent : BigDecimal.ZERO;
                BigDecimal due = offset == 0 ? firstDue : afterFirst.subtract(debt.monthlyPayment().multiply(BigDecimal.valueOf(offset - 1))).max(BigDecimal.ZERO).min(debt.monthlyPayment());
                BigDecimal amount = used.add(due).min(debt.monthlyPayment());
                if (offset < 0) {
                    BigDecimal laterPaid = paidMonths.tailMap(month, true).values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                    amount = debt.monthlyPayment().min(debt.remaining().add(laterPaid));
                }
                String label = category(debt.name());
                // This category belongs to the debt schedule. Replace an older manual copy,
                // including after payoff; never reserve the former full payment after the final one.
                plans.removeIf(p -> p.month().equals(month) && (key(p.category()).equals(key(label)) || key(p.category()).equals(key(debt.name()))));
                if (amount.signum() > 0) plans.add(new BudgetPlan(month, label, amount));
            }
        }
        return new LegacyUserData(data.startingBalance(), data.snapshotTime(), entries, plans, data.extras());
    }
    private String key(String value) { return java.text.Normalizer.normalize(value.strip().replaceAll("\\s+", " "), java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT); }
}
