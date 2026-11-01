package financeos.core;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Local monthly rules. A due item becomes a transaction only after confirmation. */
public final class RecurringBook {
    private final List<MonthlyRule> rules = new ArrayList<>();
    private final Map<String, Boolean> decisions = new LinkedHashMap<>();
    private long nextRuleId = 1;

    public record State(List<MonthlyRule> rules, Map<String, Boolean> decisions, long nextRuleId) {
        public State {
            rules = List.copyOf(rules);
            decisions = Map.copyOf(decisions);
            if (nextRuleId < 1) throw new IllegalArgumentException("Invalid rule ID sequence");
        }
    }

    public State state() { return new State(rules, decisions, nextRuleId); }

    public void restore(State state) {
        rules.clear();
        rules.addAll(state.rules());
        decisions.clear();
        decisions.putAll(state.decisions());
        nextRuleId = state.nextRuleId();
    }

    public MonthlyRule add(EntryKind kind, BigDecimal amount, int day,
                           LocalDate startsOn, String category, String description) {
        if (kind != EntryKind.INCOME && kind != EntryKind.EXPENSE) {
            throw new IllegalArgumentException("Only income and expense can recur");
        }
        var rule = new MonthlyRule("rule-" + nextRuleId, kind, amount, day,
                startsOn, null, category, description, true);
        boolean duplicate = rules.stream().anyMatch(existing ->
                existing.kind() == rule.kind() && existing.dayOfMonth() == rule.dayOfMonth()
                && existing.amount().compareTo(rule.amount()) == 0
                && existing.category().equalsIgnoreCase(rule.category())
                && existing.description().equalsIgnoreCase(rule.description()));
        if (duplicate) throw new IllegalArgumentException("This monthly rule already exists");
        rules.add(rule);
        nextRuleId++;
        return rule;
    }

    public List<RecurringOccurrence> dueToday(LocalDate today) {
        var due = new ArrayList<RecurringOccurrence>();
        var calendar = new MonthlySchedule();
        for (var rule : rules) {
            if (!calendar.occurrences(rule, today, today).isEmpty()) {
                var occurrence = new RecurringOccurrence(rule, today);
                if (!decisions.containsKey(occurrence.code())) due.add(occurrence);
            }
        }
        return List.copyOf(due);
    }

    /** Includes missed confirmations after a computer or bot outage. */
    public List<RecurringOccurrence> pendingThrough(LocalDate today) {
        var pending = new ArrayList<RecurringOccurrence>();
        var calendar = new MonthlySchedule();
        for (var rule : rules) {
            for (LocalDate date : calendar.occurrences(rule, rule.startsOn(), today)) {
                var occurrence = new RecurringOccurrence(rule, date);
                if (!decisions.containsKey(occurrence.code())) pending.add(occurrence);
            }
        }
        pending.sort(java.util.Comparator.comparing(RecurringOccurrence::date)
                .thenComparing(item -> item.rule().id()));
        return List.copyOf(pending);
    }

    public RecurringOccurrence decide(String code, LocalDate today, boolean confirmed) {
        var occurrence = pendingThrough(today).stream()
                .filter(item -> item.code().equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No pending item with this code today"));
        decisions.put(code, confirmed);
        return occurrence;
    }

    public boolean delete(String id) {
        return rules.removeIf(rule -> rule.id().equals(id));
    }
}
