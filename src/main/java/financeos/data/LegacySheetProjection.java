package financeos.data;

import financeos.vk.BotLocalState;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Rebuilds visible legacy rows from the immutable base and current Java state. */
public final class LegacySheetProjection {
    public Map<String, List<Map<String, String>>> project(
            Map<String, List<Map<String, String>>> base, String userId,
            BotLocalState state, String revisionTime) {
        var result = new LinkedHashMap<String, List<Map<String, String>>>();
        base.forEach((name, rows) -> result.put(name, new ArrayList<>(rows)));
        for (var timed : state.entries()) {
            var entry = timed.entry();
            result.get("Transactions").add(Map.of("created_at", timed.timestamp().toString(),
                    "telegram_user_id", userId, "type", entry.kind().name().toLowerCase(java.util.Locale.ROOT),
                    "amount", entry.amount().toPlainString(), "category", entry.category(),
                    "description", entry.description()));
        }
        for (var plan : state.plans()) {
            result.get("BudgetPlan").add(Map.of("created_at", revisionTime,
                    "telegram_user_id", userId, "month", plan.month().toString(),
                    "category", plan.category(), "planned_amount", plan.amount().toPlainString()));
        }
        // Preserve historical rows; append one current card only when its values changed.
        for (var debt : state.debts()) {
            var old = latest(base.get("Debts"), userId, "debt_name", debt.name());
            var row = new LinkedHashMap<>(old);
            row.putAll(Map.of("telegram_user_id", userId, "debt_name", debt.name(),
                    "current_amount", debt.remaining().toPlainString(),
                    "monthly_payment", debt.monthlyPayment().toPlainString(),
                    "payment_day", Integer.toString(debt.paymentDay()),
                    "status", debt.active() ? "active" : "closed"));
            row.putIfAbsent("initial_amount", debt.remaining().toPlainString());
            appendChanged(result.get("Debts"), old, row, revisionTime);
        }
        for (var goal : state.goals()) {
            var old = latest(base.get("Goals"), userId, "goal_name", goal.name());
            var row = new LinkedHashMap<>(old);
            row.putAll(Map.of("telegram_user_id", userId, "goal_name", goal.name(),
                    "target_amount", goal.target().toPlainString(), "current_amount", goal.current().toPlainString(),
                    "monthly_target", goal.monthlyTarget().toPlainString(),
                    "status", goal.active() ? "active" : "closed"));
            appendChanged(result.get("Goals"), old, row, revisionTime);
        }
        state.limits().forEach((month, amount) -> {
            var old = latest(base.get("DailyLimits"), userId, "month", month.toString());
            var row = new LinkedHashMap<>(old);
            row.putAll(Map.of("telegram_user_id", userId, "month", month.toString(),
                    "daily_limit", amount.toPlainString(), "status", "active"));
            appendChanged(result.get("DailyLimits"), old, row, revisionTime);
        });
        return result;
    }

    private Map<String, String> latest(List<Map<String, String>> rows, String user,
                                      String field, String value) {
        return rows.stream().filter(row -> user.equals(row.get("telegram_user_id"))
                        && value.equals(row.get(field)))
                .max(java.util.Comparator.comparing(row -> row.getOrDefault("created_at", "")))
                .orElse(Map.of());
    }

    private void appendChanged(List<Map<String, String>> rows, Map<String, String> old,
                               Map<String, String> updated, String time) {
        boolean changed = updated.entrySet().stream().anyMatch(entry ->
                !entry.getValue().equals(old.get(entry.getKey())));
        if (changed) {
            updated.put("created_at", time);
            rows.add(Map.copyOf(updated));
        }
    }
}
