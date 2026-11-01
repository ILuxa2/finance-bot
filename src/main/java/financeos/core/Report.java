package financeos.core;

import java.math.BigDecimal;

public record Report(BigDecimal actualBalance, BigDecimal plannedExpenses,
                     BigDecimal freeMoney, BigDecimal unplannedExpenses) {}
