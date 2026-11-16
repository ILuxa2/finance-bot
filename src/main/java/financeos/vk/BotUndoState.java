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
                           Map<YearMonth, BigDecimal> limits, RecurringBook.State recurring, Map<String, financeos.core.OperationLink> operationLinks, java.util.Set<String> deletedIds, List<financeos.core.PlanEdit> planEdits, financeos.core.CategorySections categorySections) {
    public BotUndoState(List<LegacyUserData.TimedEntry> entries,List<BudgetPlan> plans,List<Debt> debts,List<Goal> goals,Map<YearMonth,BigDecimal> limits,
            RecurringBook.State recurring,Map<String,financeos.core.OperationLink> operationLinks,java.util.Set<String> deletedIds,List<financeos.core.PlanEdit> planEdits){
        this(entries,plans,debts,goals,limits,recurring,operationLinks,deletedIds,planEdits,financeos.core.CategorySections.empty());
    }
    public BotUndoState(List<LegacyUserData.TimedEntry> entries,List<BudgetPlan> plans,List<Debt> debts,List<Goal> goals,
                        Map<YearMonth,BigDecimal> limits,RecurringBook.State recurring,Map<String,financeos.core.OperationLink> operationLinks,java.util.Set<String> deletedIds) {
        this(entries,plans,debts,goals,limits,recurring,operationLinks,deletedIds,List.of());
    }
    public BotUndoState(List<LegacyUserData.TimedEntry> entries, List<BudgetPlan> plans,
                        List<Debt> debts, List<Goal> goals, Map<YearMonth, BigDecimal> limits,
                        RecurringBook.State recurring, Map<String, financeos.core.OperationLink> operationLinks) {
        this(entries, plans, debts, goals, limits, recurring, operationLinks, java.util.Set.of());
    }
    public BotUndoState(List<LegacyUserData.TimedEntry> entries, List<BudgetPlan> plans,
                        List<Debt> debts, List<Goal> goals, Map<YearMonth, BigDecimal> limits,
                        RecurringBook.State recurring) {
        this(entries, plans, debts, goals, limits, recurring, Map.of());
    }
    public BotUndoState {
        java.util.Objects.requireNonNull(categorySections);
        planEdits = List.copyOf(planEdits);
        deletedIds = java.util.Set.copyOf(deletedIds);
        operationLinks = Map.copyOf(operationLinks);
        entries = List.copyOf(entries);
        plans = List.copyOf(plans);
        debts = List.copyOf(debts);
        goals = List.copyOf(goals);
        limits = Map.copyOf(limits);
        java.util.Objects.requireNonNull(recurring);
    }
}
