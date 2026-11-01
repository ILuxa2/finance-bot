package financeos.core;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

public record MonthlyRule(String id, EntryKind kind, BigDecimal amount, int dayOfMonth,
                          LocalDate startsOn, LocalDate endsOn, String category,
                          String description,
                          boolean requiresConfirmation) {
    public MonthlyRule(String id, EntryKind kind, BigDecimal amount, int dayOfMonth,
                       LocalDate startsOn, LocalDate endsOn, String description,
                       boolean requiresConfirmation) {
        this(id, kind, amount, dayOfMonth, startsOn, endsOn, description, description,
                requiresConfirmation);
    }

    public MonthlyRule {
        if (id == null || id.isBlank() || kind == EntryKind.TRANSFER || dayOfMonth < 1
                || dayOfMonth > 31 || amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Invalid monthly rule");
        }
        Objects.requireNonNull(startsOn);
        if (endsOn != null && endsOn.isBefore(startsOn)) {
            throw new IllegalArgumentException("End precedes start");
        }
        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("Category is required");
        }
        category = category.strip();
        description = description == null ? "" : description.strip();
    }
}
