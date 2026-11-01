package financeos.core;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class DailyLimitService {
    public BigDecimal remaining(LocalDate date, BigDecimal baseLimit, BigDecimal override,
                                List<LedgerEntry> entries) {
        if (baseLimit == null || baseLimit.signum() < 0
                || (override != null && override.signum() < 0)) {
            throw new IllegalArgumentException("Invalid daily limit");
        }
        BigDecimal limit = override == null ? baseLimit : override;
        BigDecimal spent = entries.stream()
                .filter(entry -> entry.kind() == EntryKind.EXPENSE && entry.date().equals(date))
                .map(LedgerEntry::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return limit.subtract(spent);
    }
}
