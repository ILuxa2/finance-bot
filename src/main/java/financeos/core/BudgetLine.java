package financeos.core;

import java.math.BigDecimal;

public record BudgetLine(String category, BigDecimal planned, BigDecimal spent,
                         BigDecimal remaining) {}
