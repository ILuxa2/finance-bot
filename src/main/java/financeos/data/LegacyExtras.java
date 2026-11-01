package financeos.data;

import financeos.core.Debt;
import financeos.core.Goal;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

public record LegacyExtras(List<Debt> debts, List<Goal> goals, List<String> categories,
                           Map<YearMonth, BigDecimal> monthlyLimits,
                           Map<LocalDate, BigDecimal> dailyOverrides) {
    public LegacyExtras {
        debts = List.copyOf(debts);
        goals = List.copyOf(goals);
        categories = List.copyOf(categories);
        monthlyLimits = Map.copyOf(monthlyLimits);
        dailyOverrides = Map.copyOf(dailyOverrides);
    }

    public static LegacyExtras empty() {
        return new LegacyExtras(List.of(), List.of(), List.of(), Map.of(), Map.of());
    }
}
