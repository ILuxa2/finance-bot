package financeos.core;

import java.math.BigDecimal;
import java.util.Objects;

public record ReportInput(BigDecimal actualBalance, BigDecimal plannedExpenses,
                          BigDecimal unplannedExpenses) {
    public ReportInput {
        Objects.requireNonNull(actualBalance);
        Objects.requireNonNull(plannedExpenses);
        Objects.requireNonNull(unplannedExpenses);
        if (plannedExpenses.signum() < 0 || unplannedExpenses.signum() < 0) {
            throw new IllegalArgumentException("Expenses cannot be negative");
        }
    }
}
