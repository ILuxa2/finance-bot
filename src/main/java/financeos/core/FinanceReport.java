package financeos.core;

import java.math.BigDecimal;

public record FinanceReport(BigDecimal actualBalance, BigDecimal plannedExpenses,
                            BigDecimal remainingPlans,
                            BigDecimal freeMoney, BigDecimal unplannedThisMonth,
                            BigDecimal incomeToday, BigDecimal expenseToday) {}
