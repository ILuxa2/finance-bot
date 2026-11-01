package financeos.vk;

import financeos.core.EntryKind;
import financeos.core.Money;
import financeos.core.RecurringBook;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

public final class RecurringReminderServiceTest {
    public static void main(String[] args) {
        var book = new RecurringBook();
        book.add(EntryKind.INCOME, Money.of("1000"), 30,
                LocalDate.of(2026, 10, 30), "Зарплата", "оклад");
        var service = new RecurringReminderService();
        var date = LocalDate.of(2026, 10, 30);
        if (!service.toNotify(book, date, LocalTime.of(8, 59), Set.of()).isEmpty()) {
            throw new AssertionError("Reminder was sent before 09:00");
        }
        var due = service.toNotify(book, date, LocalTime.of(9, 0), Set.of());
        if (due.size() != 1 || !due.getFirst().code().equals("rule-1@2026-10-30")) {
            throw new AssertionError("Due salary was not offered for confirmation");
        }
        if (!service.toNotify(book, date.plusDays(1), LocalTime.NOON,
                Set.of(due.getFirst().code())).isEmpty()) {
            throw new AssertionError("Reminder was repeated after sending");
        }
        System.out.println("Recurring reminder checks passed.");
    }
}
