package financeos.core;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Objects;

public record BudgetPlan(YearMonth month, String category, BigDecimal amount) {
    public BudgetPlan {
        Objects.requireNonNull(month);
        if (category == null || category.isBlank() || amount == null || amount.signum() < 0) {
            throw new IllegalArgumentException("Invalid budget plan");
        }
        category = category.strip();
    }
}
