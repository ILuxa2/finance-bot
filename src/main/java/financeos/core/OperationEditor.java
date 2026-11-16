package financeos.core;

import financeos.data.LegacyExtras;
import financeos.data.LegacyUserData;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure edit calculation. The caller commits the entire result atomically with its undo snapshot. */
public final class OperationEditor {
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    public enum Field { DATE, AMOUNT, COMMENT, CATEGORY }
    public record Change(List<LegacyUserData.TimedEntry> entries, List<Debt> debts, List<Goal> goals) {}

    public List<LegacyUserData.TimedEntry> operations(LegacyUserData base, List<LegacyUserData.TimedEntry> changes) {
        var result = new LinkedHashMap<String, LegacyUserData.TimedEntry>();
        for (var entry : base.transactions()) {
            if (result.put(entry.entry().id(), entry) != null) throw new IllegalArgumentException("Duplicate base operation ID");
        }
        for (var entry : changes) result.put(entry.entry().id(), entry);
        return List.copyOf(result.values());
    }

    public LegacyUserData combined(LegacyUserData base, List<LegacyUserData.TimedEntry> changes,
                                   List<BudgetPlan> plans, List<Debt> debts, List<Goal> goals,
                                   Map<YearMonth, BigDecimal> limits) {
        return combined(base, changes, plans, debts, goals, limits, Map.of());
    }

    public LegacyUserData combined(LegacyUserData base, List<LegacyUserData.TimedEntry> changes,
                                   List<BudgetPlan> plans, List<Debt> debts, List<Goal> goals,
                                   Map<YearMonth, BigDecimal> limits, Map<String, OperationLink> links) {
        return combined(base, changes, plans, debts, goals, limits, links, java.util.Set.of());
    }

    public LegacyUserData combined(LegacyUserData base, List<LegacyUserData.TimedEntry> changes,
                                   List<BudgetPlan> plans, List<Debt> debts, List<Goal> goals,
                                   Map<YearMonth, BigDecimal> limits, Map<String, OperationLink> links,
                                   java.util.Set<String> deletedIds) {
        // Older goal contributions were saved as transfers. Normalize only explicitly linked
        // local contributions; the original snapshot balance and goal opening savings stay intact.
        changes = changes.stream().map(timed -> {
            var entry = timed.entry();
            var link = links.get(entry.id());
            if (entry.kind() != EntryKind.TRANSFER || link == null || link.type() != OperationLink.Type.GOAL_FUND) return timed;
            return new LegacyUserData.TimedEntry(timed.timestamp(), new LedgerEntry(entry.id(), entry.date(),
                    EntryKind.EXPENSE, entry.amount(), entry.category(), entry.description(), entry.planned()));
        }).toList();
        var replacements = new LinkedHashMap<String, LegacyUserData.TimedEntry>();
        changes.forEach(entry -> replacements.put(entry.entry().id(), entry));
        BigDecimal opening = base.startingBalance();
        for (var old : base.transactions()) {
            if (deletedIds.contains(old.entry().id())) {
                opening = opening.subtract(beforeSnapshot(old, base.snapshotTime()));
                continue;
            }
            var replacement = replacements.get(old.entry().id());
            if (replacement == null) continue;
            // The opening balance already contains old pre-snapshot operations.
            // Remove that contribution and include the corrected pre-snapshot contribution.
            opening = opening.subtract(beforeSnapshot(old, base.snapshotTime()))
                    .add(beforeSnapshot(replacement, base.snapshotTime()));
        }
        var baseIds = base.transactions().stream().map(item -> item.entry().id())
                .collect(java.util.stream.Collectors.toSet());
        for (var entry : changes) {
            if (!deletedIds.contains(entry.entry().id()) && !baseIds.contains(entry.entry().id())) opening = opening.add(beforeSnapshot(entry, base.snapshotTime()));
        }
        var allPlans = new ArrayList<>(base.plans());
        allPlans.addAll(plans);
        var extras = new LegacyExtras(debts, goals, base.extras().categories(), limits, base.extras().dailyOverrides());
        return new LegacyUserData(opening, base.snapshotTime(), operations(base, changes).stream().filter(e -> !deletedIds.contains(e.entry().id())).toList(), List.copyOf(allPlans), extras);
    }

    public record Deletion(List<Debt> debts, List<Goal> goals, java.util.Set<String> deletedIds) {}

    public Deletion delete(LegacyUserData base, List<LegacyUserData.TimedEntry> changes,
                           List<Debt> debts, List<Goal> goals, Map<String, OperationLink> links,
                           java.util.Set<String> deletedIds, String id) {
        if (deletedIds.contains(id)) throw new IllegalArgumentException("Операция уже удалена.");
        var entry = operations(base, changes).stream().filter(e -> e.entry().id().equals(id))
                .findFirst().orElseThrow(() -> new IllegalArgumentException("Операция не найдена.")).entry();
        var nextDebts = new ArrayList<>(debts); var nextGoals = new ArrayList<>(goals);
        var link = links.get(id);
        if (link != null && link.type() == OperationLink.Type.DEBT_PAYMENT) {
            int index = -1;
            for (int i = 0; i < debts.size(); i++) if (debts.get(i).name().equals(link.target())) index = i;
            if (index < 0) throw new IllegalArgumentException("Связанный долг не найден. Удаление не выполнено.");
            var debt = debts.get(index);
            nextDebts.set(index, new Debt(debt.name(), debt.remaining().add(entry.amount()), debt.monthlyPayment(), debt.paymentDay(),
                    debt.active() || debt.remaining().signum() == 0));
        } else if (link != null && link.type() == OperationLink.Type.GOAL_FUND) {
            int index = -1;
            for (int i = 0; i < goals.size(); i++) if (goals.get(i).name().equals(link.target())) index = i;
            if (index < 0) throw new IllegalArgumentException("Связанная цель не найдена. Удаление не выполнено.");
            var goal = goals.get(index); var current = goal.current().subtract(entry.amount());
            if (current.signum() < 0) throw new IllegalArgumentException("Сумма пополнения превышает накопления цели. Удаление не выполнено.");
            nextGoals.set(index, new Goal(goal.name(), goal.target(), current, goal.monthlyTarget(), goal.active()));
        }
        var deleted = new java.util.HashSet<>(deletedIds); deleted.add(id);
        return new Deletion(List.copyOf(nextDebts), List.copyOf(nextGoals), java.util.Set.copyOf(deleted));
    }

    private BigDecimal beforeSnapshot(LegacyUserData.TimedEntry entry, OffsetDateTime snapshot) {
        if (entry.timestamp().isAfter(snapshot)) return BigDecimal.ZERO;
        return switch (entry.entry().kind()) {
            case INCOME -> entry.entry().amount();
            case EXPENSE -> entry.entry().amount().negate();
            case TRANSFER -> BigDecimal.ZERO;
        };
    }

    public Change change(LegacyUserData base, List<LegacyUserData.TimedEntry> changes,
                         List<Debt> debts, List<Goal> goals, Map<String, OperationLink> links,
                         String id, Field field, String value, OffsetDateTime now) {
        var old = operations(base, changes).stream().filter(e -> e.entry().id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Операция не найдена."));
        var entry = old.entry();
        OffsetDateTime timestamp = old.timestamp();
        BigDecimal amount = entry.amount();
        String description = entry.description();
        String category = entry.category();
        switch (field) {
            case DATE -> {
                if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) throw new IllegalArgumentException("Введите дату ГГГГ-ММ-ДД.");
                LocalDate date;
                try { date = LocalDate.parse(value); }
                catch (java.time.DateTimeException invalid) { throw new IllegalArgumentException("Неверная дата."); }
                var current = now.atZoneSameInstant(MOSCOW).toLocalDate();
                if (date.getYear() < 1 || date.isAfter(current)) throw new IllegalArgumentException("Дата операции не может быть в будущем.");
                if (!date.equals(entry.date())) timestamp = date.equals(current) ? now
                        : date.atTime(old.timestamp().atZoneSameInstant(MOSCOW).toLocalTime()).atZone(MOSCOW).toOffsetDateTime();
            }
            case AMOUNT -> {
                try { amount = Money.of(value); }
                catch (RuntimeException invalid) { throw new IllegalArgumentException("Введите сумму числом, дробную часть через точку."); }
                if (amount.signum() <= 0) throw new IllegalArgumentException("Сумма должна быть больше нуля.");
            }
            case CATEGORY -> {
                category = value.strip().replaceAll("\\s+", " ");
                if (category.isBlank() || category.length()>80 || category.codePoints().anyMatch(Character::isISOControl))
                    throw new IllegalArgumentException("Выберите название категории длиной до 80 символов.");
            }
            case COMMENT -> {
                description = value.equals("-") ? "" : value.strip();
                if (description.length() > 500) throw new IllegalArgumentException("Комментарий — не более 500 символов.");
            }
        }
        var updatedDebts = new ArrayList<>(debts);
        var updatedGoals = new ArrayList<>(goals);
        var link = links.get(id);
        BigDecimal delta = amount.subtract(entry.amount());
        if (link != null && delta.signum() != 0) {
            if (link.type() == OperationLink.Type.DEBT_PAYMENT) {
                int index = -1;
                for (int i = 0; i < debts.size(); i++) if (debts.get(i).name().equals(link.target())) index = i;
                if (index < 0) throw new IllegalArgumentException("Связанный долг не найден.");
                Debt debt = debts.get(index);
                BigDecimal remaining = debt.remaining().subtract(delta);
                if (remaining.signum() < 0) throw new IllegalArgumentException("Платёж превышает доступный остаток долга с учётом прежнего платежа.");
                updatedDebts.set(index, new Debt(debt.name(), remaining, debt.monthlyPayment(), debt.paymentDay(),
                        remaining.signum() > 0 && (debt.active() || debt.remaining().signum() == 0)));
            } else {
                int index = -1;
                for (int i = 0; i < goals.size(); i++) if (goals.get(i).name().equals(link.target())) index = i;
                if (index < 0) throw new IllegalArgumentException("Связанная цель не найдена.");
                Goal goal = goals.get(index);
                BigDecimal current = goal.current().add(delta);
                if (current.signum() < 0) throw new IllegalArgumentException("Исправление превышает текущую сумму цели.");
                updatedGoals.set(index, new Goal(goal.name(), goal.target(), current, goal.monthlyTarget(), goal.active()));
            }
        }
        var result = new ArrayList<>(changes);
        result.removeIf(e -> e.entry().id().equals(id));
        result.add(new LegacyUserData.TimedEntry(timestamp, new LedgerEntry(id,
                timestamp.atZoneSameInstant(MOSCOW).toLocalDate(), entry.kind(), amount, category, description, entry.planned())));
        return new Change(List.copyOf(result), List.copyOf(updatedDebts), List.copyOf(updatedGoals));
    }
}
