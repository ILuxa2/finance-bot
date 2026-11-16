package financeos.vk;

import financeos.core.*;
import financeos.data.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

public final class PlanCopyWorkflowTest {
    private static long eventId;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2027-01-15T09:00:00Z"), ZoneId.of("UTC"));
    private static BotReply send(LocalBotEngine engine, String command) {
        return engine.handle(new VkEvent("message_new", 99, 123, 123, "plan-copy-" + (++eventId), command)).orElseThrow();
    }
    private static void contains(BotReply reply, String value) {
        if (!reply.text().contains(value)) throw new AssertionError("Missing " + value + " in " + reply.text());
    }
    public static void main(String[] args) throws Exception {
        var at = OffsetDateTime.parse("2026-12-10T12:00:00+03:00");
        var data = new LegacyUserData(Money.of("1000"), OffsetDateTime.parse("2026-12-01T12:00:00+03:00"),
                List.of(new LegacyUserData.TimedEntry(at, new LedgerEntry("legacy-row-2", at.toLocalDate(), EntryKind.EXPENSE, Money.of("70"), "Транспорт", "test", false))),
                List.of(new BudgetPlan(YearMonth.of(2026, 12), "Еда", Money.of("100")),
                        new BudgetPlan(YearMonth.of(2026, 12), "еда", Money.of("50")),
                        new BudgetPlan(YearMonth.of(2026, 12), "Транспорт", Money.of("200")),
                        new BudgetPlan(YearMonth.of(2026, 12), "Нулевая", Money.of("0")),
                        new BudgetPlan(YearMonth.of(2027, 1), "ЕДА", Money.of("400"))));
        var engine = new LocalBotEngine(123, data, CLOCK);
        var menu = MenuCatalog.buttons(send(engine, "/plan_fact").screen());
        if (menu.stream().noneMatch(b -> b.command().equals("/plan_copy_previous"))) throw new AssertionError("Copy button missing from plan/fact");
        contains(send(engine, "/plan_copy_previous"), "2026-12 → 2027-01");
        if (!engine.exportState().plans().isEmpty()) throw new AssertionError("Preview already added plans");
        send(engine, "/plan_copy_cancel");
        if (!engine.exportState().plans().isEmpty() || !engine.exportState().undoHistory().isEmpty()) throw new AssertionError("Cancel changed financial state");
        contains(send(engine, "/plan_copy_previous"), "на 200.00 ₽");
        Path dir = Files.createTempDirectory("plan-copy-test-");
        Path path = dir.resolve("state.json");
        try {
            var store = new LocalStateFile(path, 123, data);
            store.save(engine.exportState());
            var resumed = new LocalBotEngine(123, data, CLOCK);
            resumed.restoreState(store.load().orElseThrow());
            contains(send(resumed, "/plan_copy_confirm"), "План скопирован");
            if (resumed.exportState().plans().size() != 2) throw new AssertionError("Copy did not add exactly missing categories");
            contains(send(resumed, "/plan_fact_current"), "План · 200 ₽\nФакт · 0 ₽");
            contains(send(resumed, "/report"), "Фактический баланс · 930.00");
            contains(send(resumed, "/report"), "Планы за месяц · 600.00");
            send(resumed, "/plan_copy_confirm");
            contains(send(resumed, "/plan_copy_previous"), "Ничего не скопировано");
            if (resumed.exportState().plans().size() != 2) throw new AssertionError("Repeated copy duplicated plans");
            store.save(resumed.exportState());
            var undo = new LocalBotEngine(123, data, CLOCK);
            undo.restoreState(store.load().orElseThrow());
            send(undo, "/undo");
            if (!undo.exportState().plans().isEmpty()) throw new AssertionError("One undo did not remove the entire copied batch");
            contains(send(undo, "/report"), "Планы за месяц · 400.00");
            contains(send(undo, "/report"), "Фактический баланс · 930.00");
            var stale = new LocalBotEngine(123, data, CLOCK);
            send(stale, "/plan_copy_previous");
            var previous = stale.exportState();
            var modifiedPlans = new ArrayList<>(previous.plans());
            modifiedPlans.add(new BudgetPlan(YearMonth.of(2027, 1), "Другое", Money.of("1")));
            stale.restoreState(new BotLocalState(previous.entries(), modifiedPlans, previous.debts(), previous.goals(), previous.limits(),
                    previous.nextEntryId(), previous.seenEvents(), previous.recurring(), previous.pending(), previous.pendingCategory(),
                    previous.navigation(), previous.undoHistory(), previous.operationLinks(), previous.history()));
            contains(send(stale, "/plan_copy_confirm"), "План уже изменился");
            if (stale.exportState().plans().size() != 1) throw new AssertionError("Stale confirmation copied categories");
            var rollover = new LocalBotEngine(123, data, Clock.fixed(Instant.parse("2027-02-01T00:00:00Z"), ZoneId.of("UTC")));
            rollover.restoreState(previous);
            contains(send(rollover, "/plan_copy_confirm"), "Месяц изменился");
            if (!rollover.exportState().plans().isEmpty()) throw new AssertionError("Month rollover copied to wrong month");
        } finally { Files.deleteIfExists(path); Files.deleteIfExists(dir); }
        System.out.println("Plan copy confirmation, restart, cancel, batch undo and unchanged actual balance passed.");
    }
}
