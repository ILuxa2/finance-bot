package financeos.core;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

public record LedgerEntry(String id, LocalDate date, EntryKind kind, BigDecimal amount,
                          String category, String description, boolean planned) {
    public LedgerEntry {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Entry ID is required");
        Objects.requireNonNull(date);
        Objects.requireNonNull(kind);
        Objects.requireNonNull(amount);
        if (amount.signum() <= 0) throw new IllegalArgumentException("Amount must be positive");
        category = category == null ? "" : category.strip();
        description = description == null ? "" : description.strip();
    }
}
