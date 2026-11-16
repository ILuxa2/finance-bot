package financeos.vk;

import financeos.core.*;
import financeos.data.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.*;
import java.util.*;

public final class EditOperationsTest {
    private static long id;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-10T09:00:00Z"), ZoneId.of("UTC"));
    private static BotReply send(LocalBotEngine engine, String value) {
        return engine.handle(new VkEvent("message_new", 99, 123, 123, "edit-test-" + (++id), value)).orElseThrow();
    }
    private static void contains(BotReply reply, String value) {
        if (!reply.text().contains(value)) throw new AssertionError("Missing " + value + " in " + reply.text());
    }
    private static LocalBotEngine reload(LocalBotEngine engine, LegacyUserData data, Path file) throws Exception {
        var store = new LocalStateFile(file, 123, data);
        store.save(engine.exportState());
        var result = new LocalBotEngine(123, data, CLOCK);
        result.restoreState(store.load().orElseThrow());
        return result;
    }
    private static void select(LocalBotEngine engine, String operationId) {
        BotReply picker = send(engine, "/edit_operations");
        // Follow real paginated buttons and identify the card before editing.
        while (true) {
            var values = engine.exportState();
            var all = new OperationEditor().operations(DATA, values.entries()).stream()
                    .sorted(Comparator.comparing((LegacyUserData.TimedEntry e) -> e.entry().date()).reversed()
                            .thenComparing(e -> e.entry().id())).toList();
            int index = 0;
            for (var button : picker.buttons()) {
                if (!button.command().startsWith("/choose/")) continue;
                String[] parts = button.command().split("/");
                index = Integer.parseInt(parts[3]);
                if (all.get(index).entry().id().equals(operationId)) {
                    send(engine, button.command());
                    return;
                }
            }
            var next = picker.buttons().stream().filter(b -> b.command().startsWith("/choices/") && b.label().contains("Следующие")).findFirst();
            if (next.isEmpty()) throw new AssertionError("Operation not offered " + operationId);
            picker = send(engine, next.get().command());
        }
    }
    private static final LegacyUserData DATA;
    static {
        var expenseAt = OffsetDateTime.parse("2026-10-31T10:00:00+03:00");
        var incomeAt = OffsetDateTime.parse("2026-11-03T10:00:00+03:00");
        DATA = new LegacyUserData(Money.of("1000"), OffsetDateTime.parse("2026-11-01T12:00:00+03:00"),
                List.of(new LegacyUserData.TimedEntry(expenseAt, new LedgerEntry("legacy-row-2", expenseAt.toLocalDate(), EntryKind.EXPENSE, Money.of("100"), "Еда", "еда", false)),
                        new LegacyUserData.TimedEntry(incomeAt, new LedgerEntry("legacy-row-3", incomeAt.toLocalDate(), EntryKind.INCOME, Money.of("200"), "Зарплата", "аванс", false))),
                List.of(new BudgetPlan(YearMonth.of(2026, 10), "Еда", Money.of("500")), new BudgetPlan(YearMonth.of(2026, 11), "Еда", Money.of("500"))),
                new LegacyExtras(List.of(new Debt("Кредит", Money.of("500"), Money.of("100"), 19, true)),
                        List.of(new Goal("Резерв", Money.of("1000"), Money.of("100"), Money.of("50"), true)), List.of("Еда"), Map.of(), Map.of()));
    }
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("financeos-editor-test-");
        Path file = directory.resolve("state.json");
        try {
            var engine = new LocalBotEngine(123, DATA, CLOCK);
            var main = MenuCatalog.buttons(send(engine, "/start").screen());
            if (main.stream().noneMatch(b -> b.label().contains("Все операции")) || main.stream().noneMatch(b -> b.command().equals("/operations"))) throw new AssertionError("Main menu is missing edit/history shortcuts");
            contains(send(engine, "/report"), "Фактический баланс · 1200.00");
            select(engine, "legacy-row-2");
            send(engine, "/edit_amount");
            contains(send(engine, "0"), "больше нуля");
            contains(send(engine, "150"), "Станет · 150.00");
            if (!engine.exportState().entries().isEmpty()) throw new AssertionError("Preview applied a financial edit");
            engine = reload(engine, DATA, file);
            contains(send(engine, "/edit_confirm"), "Операция изменена");
            contains(send(engine, "/edit_confirm"), "Нет правки");
            contains(send(engine, "/report"), "Фактический баланс · 1150.00");
            send(engine, "/plan_fact_month");
            contains(send(engine, "2026-10"), "Факт · 150 ₽");
            engine = reload(engine, DATA, file);
            send(engine, "/undo");
            contains(send(engine, "/report"), "Фактический баланс · 1200.00");
            if (!engine.exportState().entries().isEmpty()) throw new AssertionError("Undo retained a legacy override");
            select(engine, "legacy-row-2");
            send(engine, "/edit_date");
            contains(send(engine, "2026-11-11"), "будущем");
            send(engine, "2026-11-05");
            send(engine, "/edit_confirm");
            contains(send(engine, "/report"), "Фактический баланс · 1200.00");
            send(engine, "/plan_fact_month");
            contains(send(engine, "2026-10"), "Факт · 0 ₽");
            send(engine, "/plan_fact_month");
            contains(send(engine, "2026-11"), "Факт · 100 ₽");
            send(engine, "/undo");
            select(engine, "legacy-row-2");
            send(engine, "/edit_comment");
            send(engine, "не сохранять");
            send(engine, "/edit_cancel");
            if (!engine.exportState().entries().isEmpty()) throw new AssertionError("Cancelled edit changed an operation");
            send(engine, "/pay_debt");
            send(engine, "Кредит; 100");
            String payment = engine.exportState().entries().getLast().entry().id();
            send(engine, "/fund_goal");
            send(engine, "Резерв; 50");
            String fund = engine.exportState().entries().getLast().entry().id();
            contains(send(engine, "/history_all"), "Пополнение цели");
            engine = reload(engine, DATA, file);
            if (engine.exportState().operationLinks().size() != 2) throw new AssertionError("Financial links were lost on restart");
            select(engine, payment);
            send(engine, "/edit_comment"); send(engine, "исправленный платёж"); send(engine, "/edit_confirm");
            select(engine, payment);
            send(engine, "/edit_amount"); send(engine, "150"); send(engine, "/edit_confirm");
            contains(send(engine, "/debt_list"), "осталось · 350.00");
            contains(send(engine, "/report"), "Фактический баланс · 1000.00");
            select(engine, fund);
            send(engine, "/edit_amount"); send(engine, "80"); send(engine, "/edit_confirm");
            contains(send(engine, "/goal_list"), "накоплено · 180.00");
            contains(send(engine, "/report"), "Фактический баланс · 970.00");
            engine = reload(engine, DATA, file);
            send(engine, "/undo");
            contains(send(engine, "/goal_list"), "накоплено · 150.00");
            send(engine, "/transfer"); send(engine, "30, внутренний");
            String transfer = engine.exportState().entries().getLast().entry().id();
            select(engine, transfer);
            send(engine, "/edit_amount"); send(engine, "60"); send(engine, "/edit_confirm");
            contains(send(engine, "/report"), "Фактический баланс · 1000.00");
            select(engine, "legacy-row-3");
            send(engine, "/edit_amount"); send(engine, "250"); send(engine, "/edit_confirm");
            contains(send(engine, "/report"), "Фактический баланс · 1050.00");
            send(engine, "/history_period"); send(engine, "2026-10-01..2026-10-31");
            engine = reload(engine, DATA, file);
            BotReply historyEdit = send(engine, "/history_edit");
            if (historyEdit.buttons().stream().filter(b -> b.command().startsWith("/choose/")).count() != 1) throw new AssertionError("History edit lost its date filter on restart");
            var paged = new LocalBotEngine(123, DATA, CLOCK);
            for (int i = 0; i < 12; i++) {
                send(paged, "/expense"); send(paged, "Еда"); send(paged, "1, запись " + i);
            }
            BotReply pageOne = send(paged, "/edit_operations");
            if (pageOne.buttons().stream().filter(b -> b.command().startsWith("/choose/")).count() != 5) throw new AssertionError("Editor does not show five records");
            String nextPage = pageOne.buttons().stream().filter(b -> b.label().equals("➡️ Следующие")).findFirst().orElseThrow().command();
            BotReply pageTwo = send(paged, nextPage);
            if (pageTwo.buttons().size() != 10 || pageTwo.buttons().stream().filter(b -> b.command().startsWith("/choose/")).count() != 5) throw new AssertionError("Middle editor page lost records or controls");
            var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(new VkKeyboardFactory().keyboard(pageTwo.buttons()));
            if (json.path("buttons").size() > 6) throw new AssertionError("Five-record editor exceeds row budget");
            String selected = pageTwo.buttons().stream().filter(b -> b.command().startsWith("/choose/")).findFirst().orElseThrow().command();
            paged = reload(paged, DATA, file);
            contains(send(paged, selected), "Выберите, что изменить");
            if (DATA.transactions().getFirst().entry().amount().compareTo(Money.of("100")) != 0) throw new AssertionError("Immutable base was modified");
        } finally { Files.deleteIfExists(file); Files.deleteIfExists(directory); }
        System.out.println("Operation edit UI, confirmation, restart, undo, filters and all operation kinds passed.");
    }
}
