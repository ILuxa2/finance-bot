package financeos.data;

import financeos.core.BudgetPlan;
import financeos.core.LedgerEntry;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

public record LegacyUserData(BigDecimal startingBalance, OffsetDateTime snapshotTime,
                             List<TimedEntry> transactions, List<BudgetPlan> plans,
                             LegacyExtras extras) {
    public LegacyUserData(BigDecimal startingBalance, OffsetDateTime snapshotTime,
                          List<TimedEntry> transactions, List<BudgetPlan> plans) {
        this(startingBalance, snapshotTime, transactions, plans, LegacyExtras.empty());
    }

    public record TimedEntry(OffsetDateTime timestamp, LedgerEntry entry) {}
}
