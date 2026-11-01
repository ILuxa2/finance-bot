package financeos.core;

import java.time.LocalDate;

public final class RecurringBookTest {
    public static void main(String[] args) {
        var book = new RecurringBook();
        var rule = book.add(EntryKind.EXPENSE, Money.of("500"), 31,
                LocalDate.of(2027, 1, 1), "Аренда", "комната");
        if (!book.dueToday(LocalDate.of(2027, 2, 28)).getFirst().code()
                .equals(rule.id() + "@2027-02-28")) {
            throw new AssertionError("Monthly payment was lost in a short month");
        }
        book.decide(rule.id() + "@2027-02-28", LocalDate.of(2027, 2, 28), true);
        if (!book.dueToday(LocalDate.of(2027, 2, 28)).isEmpty()) {
            throw new AssertionError("Confirmed payment was offered twice");
        }
        if (book.dueToday(LocalDate.of(2027, 3, 31)).size() != 1) {
            throw new AssertionError("Next month's payment did not appear");
        }
        if (book.pendingThrough(LocalDate.of(2027, 4, 1)).stream().noneMatch(
                item -> item.code().equals(rule.id() + "@2027-03-31"))) {
            throw new AssertionError("Missed confirmation disappeared after the due date");
        }
        book.decide(rule.id() + "@2027-03-31", LocalDate.of(2027, 4, 1), false);
        if (book.pendingThrough(LocalDate.of(2027, 4, 1)).stream().anyMatch(
                item -> item.code().equals(rule.id() + "@2027-03-31"))) {
            throw new AssertionError("Skipped overdue payment was offered again");
        }
        System.out.println("Recurring date and confirmation checks passed.");
    }
}
