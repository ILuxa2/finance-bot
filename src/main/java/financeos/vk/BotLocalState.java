package financeos.vk;

import financeos.core.BudgetPlan;
import financeos.core.Debt;
import financeos.core.Goal;
import financeos.core.Navigation;
import financeos.core.RecurringBook;
import financeos.data.LegacyUserData;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

public record BotLocalState(List<LegacyUserData.TimedEntry> entries, List<BudgetPlan> plans,
                            List<Debt> debts, List<Goal> goals,
                            Map<YearMonth, BigDecimal> limits, long nextEntryId,
                            List<String> seenEvents, RecurringBook.State recurring,
                            String pending, String pendingCategory, Navigation.State navigation,
                            List<BotUndoState> undoHistory) {
    public BotLocalState(List<LegacyUserData.TimedEntry> entries, List<BudgetPlan> plans,
                         List<Debt> debts, List<Goal> goals,
                         Map<YearMonth, BigDecimal> limits, long nextEntryId,
                         List<String> seenEvents) {
        this(entries, plans, debts, goals, limits, nextEntryId, seenEvents,
                new RecurringBook().state());
    }

    public BotLocalState(List<LegacyUserData.TimedEntry> entries, List<BudgetPlan> plans,
                         List<Debt> debts, List<Goal> goals,
                         Map<YearMonth, BigDecimal> limits, long nextEntryId,
                         List<String> seenEvents, RecurringBook.State recurring) {
        this(entries, plans, debts, goals, limits, nextEntryId, seenEvents,
                recurring, "NONE", "", new Navigation().state());
    }

    public BotLocalState(List<LegacyUserData.TimedEntry> entries, List<BudgetPlan> plans,
                         List<Debt> debts, List<Goal> goals,
                         Map<YearMonth, BigDecimal> limits, long nextEntryId,
                         List<String> seenEvents, RecurringBook.State recurring,
                         String pending, String pendingCategory, Navigation.State navigation) {
        this(entries, plans, debts, goals, limits, nextEntryId, seenEvents,
                recurring, pending, pendingCategory, navigation, List.of());
    }

    public BotLocalState {
        entries = List.copyOf(entries);
        plans = List.copyOf(plans);
        debts = List.copyOf(debts);
        goals = List.copyOf(goals);
        limits = Map.copyOf(limits);
        seenEvents = List.copyOf(seenEvents);
        undoHistory = List.copyOf(undoHistory);
        java.util.Objects.requireNonNull(recurring);
        java.util.Objects.requireNonNull(pending);
        java.util.Objects.requireNonNull(pendingCategory);
        java.util.Objects.requireNonNull(navigation);
        if (nextEntryId < 1 || seenEvents.size() > 2000 || undoHistory.size() > 30) {
            throw new IllegalArgumentException("Invalid local state");
        }
    }
}
