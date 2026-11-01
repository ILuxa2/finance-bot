package financeos.core;

import java.time.LocalDate;

public record RecurringOccurrence(MonthlyRule rule, LocalDate date) {
    public String code() { return rule.id() + "@" + date; }
}
