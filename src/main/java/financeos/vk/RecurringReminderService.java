package financeos.vk;

import financeos.core.RecurringBook;
import financeos.core.RecurringOccurrence;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

/** Offers each recent due occurrence once after 09:00 Moscow time. */
public final class RecurringReminderService {
    public List<RecurringOccurrence> toNotify(RecurringBook book, LocalDate today,
                                              LocalTime time, Set<String> notified) {
        if (time.isBefore(LocalTime.of(9, 0))) return List.of();
        LocalDate oldest = today.minusDays(7);
        return book.pendingThrough(today).stream()
                .filter(item -> !item.date().isBefore(oldest))
                .filter(item -> !notified.contains(item.code()))
                .toList();
    }
}
