package financeos.core;

import java.math.BigDecimal;

public record BudgetSnapshot(BigDecimal planned, BigDecimal spentAgainstPlan,
                             BigDecimal remaining, BigDecimal unplannedExpense) {}
