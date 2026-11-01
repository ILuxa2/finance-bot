package financeos.vk;

import financeos.core.BudgetPlan;
import financeos.core.Debt;
import financeos.core.Goal;
import financeos.core.RecurringBook;
import financeos.data.LegacyUserData;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/** State before one confirmed local change. Newest entries are undone first. */
public record BotUndoState(List<LegacyUserData.TimedEntry> entries, List<BudgetPlan> plans,
                           List<Debt> debts, List<Goal> goals,
                           Map<YearMonth, BigDecimal> limits, RecurringBook.State recurring) {
    public BotUndoState {
        entries = List.copyOf(entries);
        plans = List.copyOf(plans);
        debts = List.copyOf(debts);
        goals = List.copyOf(goals);
        limits = Map.copyOf(limits);
        java.util.Objects.requireNonNull(recurring);
    }
}
