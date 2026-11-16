package financeos.core;

import financeos.data.LegacyExtras;
import financeos.data.LegacyUserData;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public final class OperationEditorTest {
    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-10T12:00:00+03:00");
    private static LegacyUserData.TimedEntry operation(String id, String timestamp, EntryKind kind, String amount) {
        var at = OffsetDateTime.parse(timestamp);
        return new LegacyUserData.TimedEntry(at, new LedgerEntry(id, at.toLocalDate(), kind, Money.of(amount), "Еда", "test", false));
    }
    private static void equal(String expected, java.math.BigDecimal actual) {
        if (Money.of(expected).compareTo(actual) != 0) throw new AssertionError("Expected " + expected + " but got " + actual);
    }
    public static void main(String[] args) {
        var editor = new OperationEditor();
        var expense = operation("legacy-row-2", "2026-10-31T12:00:00+03:00", EntryKind.EXPENSE, "100");
        var income = operation("legacy-row-3", "2026-10-30T12:00:00+03:00", EntryKind.INCOME, "200");
        var after = operation("legacy-row-4", "2026-11-02T12:00:00+03:00", EntryKind.EXPENSE, "50");
        var base = new LegacyUserData(Money.of("1000"), OffsetDateTime.parse("2026-11-01T12:00:00+03:00"),
                List.of(expense, income, after), List.of());
        var changed = editor.change(base, List.of(), List.of(), List.of(), Map.of(), expense.entry().id(), OperationEditor.Field.AMOUNT, "200", NOW);
        equal("850", new BalanceService().actualBalance(editor.combined(base, changed.entries(), List.of(), List.of(), List.of(), Map.of()), NOW));
        var moved = editor.change(base, List.of(), List.of(), List.of(), Map.of(), expense.entry().id(), OperationEditor.Field.DATE, "2026-11-03", NOW);
        var movedData = editor.combined(base, moved.entries(), List.of(), List.of(), List.of(), Map.of());
        equal("950", new BalanceService().actualBalance(movedData, NOW));
        if (movedData.transactions().size() != 3) throw new AssertionError("Override duplicated the original operation");
        changed = editor.change(base, List.of(), List.of(), List.of(), Map.of(), income.entry().id(), OperationEditor.Field.AMOUNT, "300", NOW);
        equal("1050", new BalanceService().actualBalance(editor.combined(base, changed.entries(), List.of(), List.of(), List.of(), Map.of()), NOW));
        changed = editor.change(base, List.of(), List.of(), List.of(), Map.of(), after.entry().id(), OperationEditor.Field.DATE, "2026-10-29", NOW);
        equal("950", new BalanceService().actualBalance(editor.combined(base, changed.entries(), List.of(), List.of(), List.of(), Map.of()), NOW));
        var local = operation("local-1", "2026-11-05T12:00:00+03:00", EntryKind.EXPENSE, "30");
        changed = editor.change(base, List.of(local), List.of(), List.of(), Map.of(), "local-1", OperationEditor.Field.DATE, "2026-10-28", NOW);
        equal("920", new BalanceService().actualBalance(editor.combined(base, changed.entries(), List.of(), List.of(), List.of(), Map.of()), NOW));
        var payment = operation("local-2", "2026-11-05T12:00:00+03:00", EntryKind.EXPENSE, "100");
        var debts = List.of(new Debt("Кредит", Money.of("400"), Money.of("100"), 19, true));
        var debtLinks = Map.of("local-2", new OperationLink(OperationLink.Type.DEBT_PAYMENT, "Кредит"));
        changed = editor.change(base, List.of(payment), debts, List.of(), debtLinks, "local-2", OperationEditor.Field.AMOUNT, "150", NOW);
        equal("350", changed.debts().getFirst().remaining());
        try { editor.change(base, List.of(payment), debts, List.of(), debtLinks, "local-2", OperationEditor.Field.AMOUNT, "501", NOW); throw new AssertionError("Overpayment accepted"); }
        catch (IllegalArgumentException expected) { }
        var paid = List.of(new Debt("Кредит", Money.of("0"), Money.of("100"), 19, false));
        changed = editor.change(base, List.of(payment), paid, List.of(), debtLinks, "local-2", OperationEditor.Field.AMOUNT, "70", NOW);
        equal("30", changed.debts().getFirst().remaining());
        if (!changed.debts().getFirst().active()) throw new AssertionError("Debt was not reopened after reducing final payment");
        var fund = operation("local-3", "2026-11-05T12:00:00+03:00", EntryKind.TRANSFER, "100");
        var goals = List.of(new Goal("Резерв", Money.of("1000"), Money.of("200"), Money.of("50"), true));
        var goalLinks = Map.of("local-3", new OperationLink(OperationLink.Type.GOAL_FUND, "Резерв"));
        changed = editor.change(base, List.of(fund), List.of(), goals, goalLinks, "local-3", OperationEditor.Field.AMOUNT, "150", NOW);
        equal("250", changed.goals().getFirst().current());
        equal("800", new BalanceService().actualBalance(editor.combined(base, changed.entries(), List.of(), List.of(), changed.goals(), Map.of(), goalLinks), NOW));
        equal("950", new BalanceService().actualBalance(editor.combined(base, changed.entries(), List.of(), List.of(), changed.goals(), Map.of()), NOW));
        var backdated = editor.change(base, changed.entries(), List.of(), changed.goals(), goalLinks, "local-3", OperationEditor.Field.DATE, "2026-10-01", NOW);
        equal("800", new BalanceService().actualBalance(editor.combined(base, backdated.entries(), List.of(), List.of(), backdated.goals(), Map.of(), goalLinks), NOW));
        changed = editor.change(base, List.of(payment), debts, List.of(), debtLinks, "local-2", OperationEditor.Field.COMMENT, "новый комментарий", NOW);
        equal("400", changed.debts().getFirst().remaining());
        try { editor.change(base, List.of(), List.of(), List.of(), Map.of(), expense.entry().id(), OperationEditor.Field.DATE, "2026-11-11", NOW); throw new AssertionError("Future date accepted"); }
        catch (IllegalArgumentException expected) { }
        equal("1000", base.startingBalance());
        System.out.println("Operation edits, snapshot boundaries, linked debt and goal checks passed.");
    }
}
