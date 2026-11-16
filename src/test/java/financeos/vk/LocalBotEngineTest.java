package financeos.vk;

import financeos.core.BudgetPlan;
import financeos.core.Debt;
import financeos.core.Goal;
import financeos.core.Money;
import financeos.data.LegacyExtras;
import financeos.data.LegacyUserData;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class LocalBotEngineTest {
    private static long nextEvent = 1;

    private static BotReply send(LocalBotEngine engine, String text) {
        return engine.handle(new VkEvent("message_new", 99, 123, 123,
                Long.toString(nextEvent++), text)).orElseThrow();
    }

    private static void contains(BotReply reply, String fragment) {
        if (!reply.text().contains(fragment)) {
            throw new AssertionError("Missing '" + fragment + "' in: " + reply.text());
        }
    }

    private static LegacyUserData.TimedEntry timedExpense(String id, String timestamp, String amount) {
        var at = OffsetDateTime.parse(timestamp);
        return new LegacyUserData.TimedEntry(at, new financeos.core.LedgerEntry(id, at.toLocalDate(),
                financeos.core.EntryKind.EXPENSE, Money.of(amount), "Еда", "test", false));
    }

    public static void main(String[] args) {
        var data = new LegacyUserData(Money.of("100000"),
                OffsetDateTime.parse("2026-10-30T10:00:00+03:00"), List.of(),
                List.of(new BudgetPlan(YearMonth.of(2026, 10), "Еда", Money.of("25000"))),
                new LegacyExtras(List.of(new Debt("Кредит", Money.of("1500"),
                        Money.of("1000"), 31, true)),
                        List.of(new Goal("Резерв", Money.of("10000"), Money.of("2000"),
                                Money.of("1000"), true)), List.of("Зарплата"),
                        Map.of(YearMonth.of(2026, 10), Money.of("500")), Map.of()));
        var engine = new LocalBotEngine(123, data,
                Clock.fixed(Instant.parse("2026-10-30T09:00:00Z"), ZoneId.of("UTC")));
        var privateChatEngine = new LocalBotEngine(123, Set.of(123L, 2_000_000_001L),
                data, Clock.fixed(Instant.parse("2026-10-30T09:00:00Z"), ZoneId.of("UTC")));
        contains(privateChatEngine.handle(new VkEvent("message_new", 99, 123,
                2_000_000_001L, "chat-1", "/report")).orElseThrow(),
                "Свободные деньги · 74000.00 ₽");
        if (privateChatEngine.handle(new VkEvent("message_new", 99, 123,
                2_000_000_002L, "chat-2", "/report")).isPresent()) {
            throw new AssertionError("A different VK chat was accepted");
        }
        BotReply start = send(engine, "/start");
        contains(start, "Главное меню");
        if (start.text().contains("/report") || start.text().contains("Все команды")) {
            throw new AssertionError("Start displayed a command list");
        }
        MenuButton help = MenuCatalog.buttons(start.screen()).stream()
                .filter(b -> b.label().equals("❓ Помощь")).findFirst().orElseThrow();
        contains(send(engine, help.command()), "учёт финансов");
        contains(send(engine, "/commands"), "/report");
        contains(send(engine, "/start"), "Что хотите сделать?");
        contains(send(engine, "/report"), "Свободные деньги · 74000.00 ₽");
        contains(send(engine, "/report"), "Баланс минус остаток плана");
        send(engine, "/add");
        contains(send(engine, "/expense"), "Выберите категорию");
        contains(send(engine, " еда "), "Категория: Еда");
        contains(send(engine, "100, обед"), "🔴 −100.00 ₽");
        contains(send(engine, "/report"), "Свободные деньги · 74000.00 ₽");
        var chart = send(engine, "/chart_expenses");
        if (chart.imagePng() == null || chart.imagePng().length < 1000) {
            throw new AssertionError("Expense chart image was not attached to local reply");
        }
        contains(send(engine, "/daily_limit"), "Осталось сегодня · 400.00 ₽");
        contains(send(engine, "/morning_limit"), "На момент просмотра доступно 400.00 ₽");
        contains(send(engine, "/freedom"), "2026-11-30");
        contains(send(engine, "/health"), "Активный остаток долгов · 1500.00 ₽");
        contains(send(engine, "/insights"), "Крупнейшая статья расходов: Еда · 100.00 ₽");
        send(engine, "/transfer");
        contains(send(engine, "1500, на накопления"), "Общий баланс не изменился");
        contains(send(engine, "/report"), "Свободные деньги · 74000.00 ₽");
        contains(send(engine, "/history_all"), "Перевод · 1500.00 ₽");
        send(engine, "/undo");
        contains(send(engine, "/debt_list"), "Кредит · осталось · 1500.00 ₽");
        contains(send(engine, "/goal_list"), "Резерв · накоплено · 2000.00 ₽");
        var forecast = send(engine, "/forecast");
        contains(forecast, "2026-10 · 1000.00 ₽");
        contains(forecast, "2026-11 · 500.00 ₽");
        if (forecast.text().contains("2026-12")) {
            throw new AssertionError("Debt forecast continued after payoff");
        }
        send(engine, "/edit_debt");
        contains(send(engine, "Кредит; 1100; 600; 19"), "Баланс и история операций не изменились");
        var correctedForecast = send(engine, "/forecast");
        contains(correctedForecast, "2026-11 · 600.00 ₽");
        contains(correctedForecast, "2026-12 · 500.00 ₽");
        if (correctedForecast.text().contains("2027-01")) {
            throw new AssertionError("Corrected debt forecast added a third payment");
        }
        contains(send(engine, "/report"), "Свободные деньги · 75000.00 ₽");
        send(engine, "/undo");
        contains(send(engine, "/debt_list"), "Кредит · осталось · 1500.00 ₽");
        contains(send(engine, "/expense"), "Выберите категорию");
        contains(send(engine, "Такси"), "Категория: Такси");
        contains(send(engine, "300, поездка"), "🔴 −300.00 ₽");
        contains(send(engine, "/report"), "Свободные деньги · 73700.00 ₽");
        contains(send(engine, "/report"), "Незапланированные расходы за месяц · 300.00 ₽");
        contains(send(engine, "/report"), "300.00 ₽\nУже учтены в балансе");
        if (engine.handle(new VkEvent("message_new", 99, 123, 123,
                Long.toString(nextEvent - 1), "/report")).isPresent()) {
            throw new AssertionError("Repeated VK event was processed twice");
        }
        send(engine, "/history_date");
        contains(send(engine, "2026-10-30"), "поездка");
        contains(send(engine, "/undo"), "изменение отменено");
        contains(send(engine, "/report"), "Свободные деньги · 74000.00 ₽");
        send(engine, "/add_plan");
        contains(send(engine, "5000, ЕДА"), "Дубликат не создан");
        contains(send(engine, "5000, Развлечения"), "добавлен локально");
        contains(send(engine, "/report"), "Свободные деньги · 69000.00 ₽");
        send(engine, "/undo");
        contains(send(engine, "/report"), "Свободные деньги · 74000.00 ₽");
        send(engine, "/income");
        send(engine, "Зарплата");
        contains(send(engine, "12.345, неверно"), "Дробную часть пишите через точку");
        contains(send(engine, "1000.50, премия"), "🟢 +1000.50 ₽");
        contains(send(engine, "/report"), "Свободные деньги · 75000.50 ₽");
        send(engine, "/set_limit");
        contains(send(engine, "750"), "установлен локально");
        contains(send(engine, "/daily_limit"), "Осталось сегодня · 650.00 ₽");
        send(engine, "/undo");
        contains(send(engine, "/daily_limit"), "Осталось сегодня · 400.00 ₽");
        send(engine, "/pay_debt");
        contains(send(engine, "Кредит; 500"), "Фактический баланс уменьшился");
        contains(send(engine, "/report"), "Свободные деньги · 75000.50 ₽");
        contains(send(engine, "/debt_list"), "осталось · 1000.00 ₽");
        send(engine, "/undo");
        contains(send(engine, "/report"), "Свободные деньги · 75000.50 ₽");
        send(engine, "/fund_goal");
        contains(send(engine, "Резерв; 1000"), "Накоплено · 3000.00 ₽");
        contains(send(engine, "/report"), "Свободные деньги · 74000.50 ₽");
        send(engine, "/undo");
        contains(send(engine, "/goal_list"), "накоплено · 2000.00 ₽");
        contains(send(engine, "/history_all"), "[local-1]");
        send(engine, "/delete_operation");
        send(engine, "local-1");
        send(engine, "/edit_delete");
        contains(send(engine, "/edit_delete_confirm"), "Операция удалена");
        contains(send(engine, "/report"), "Свободные деньги · 75000.50 ₽");
        send(engine, "/undo");
        contains(send(engine, "/report"), "Свободные деньги · 75000.50 ₽");
        send(engine, "/report_date");
        var dated = send(engine, "2026-10-30");
        contains(dated, "Доходы · 🟢 +1000.50 ₽");
        contains(dated, "Расходы · 🔴 −100.00 ₽");
        contains(dated, "Баланс на конец периода · 100900.50 ₽");
        send(engine, "/report_period");
        contains(send(engine, "2026-10-29..2026-10-30"), "Свободные деньги на конец периода · 75000.50 ₽");
        send(engine, "/salary_rule");
        contains(send(engine, "2000; 30; оклад"), "Правило rule-1 создано");
        contains(send(engine, "/regular_pending"), "rule-1@2026-10-30");
        send(engine, "/regular_confirm");
        contains(send(engine, "rule-1@2026-10-30"), "подтверждена");
        contains(send(engine, "/report"), "Свободные деньги · 77000.50 ₽");
        send(engine, "/undo");
        contains(send(engine, "/report"), "Свободные деньги · 75000.50 ₽");
        contains(send(engine, "/regular_pending"), "rule-1@2026-10-30");
        send(engine, "/regular_skip");
        contains(send(engine, "rule-1@2026-10-30"), "пропущена");
        contains(send(engine, "/regular_pending"), "нет");
        send(engine, "/payment_rule");
        contains(send(engine, "600; 30; Аренда; проверка"), "Правило rule-2 создано");
        contains(send(engine, "/regular_pending"), "rule-2@2026-10-30");
        send(engine, "/regular_confirm");
        contains(send(engine, "rule-2@2026-10-30"), "подтверждена");
        if (engine.exportState().entries().stream().filter(timed ->
                timed.entry().category().equals("Аренда")).count() != 1) {
            throw new AssertionError("Monthly payment was not recorded exactly once");
        }
        contains(send(engine, "/report"), "Свободные деньги · 74400.50 ₽");
        contains(send(engine, "/regular_pending"), "нет");
        send(engine, "/undo");
        if (engine.exportState().entries().stream().anyMatch(timed ->
                timed.entry().category().equals("Аренда"))) {
            throw new AssertionError("Undo retained a confirmed monthly payment");
        }
        contains(send(engine, "/report"), "Свободные деньги · 75000.50 ₽");
        contains(send(engine, "/regular_pending"), "rule-2@2026-10-30");
        if (engine.handle(new VkEvent("message_new", 99, 456, 456, "foreign", "/report"))
                .isPresent()) throw new AssertionError("Other user received financial data");
        var historyEngine = new LocalBotEngine(123, data,
                Clock.fixed(Instant.parse("2026-10-30T09:00:00Z"), ZoneId.of("UTC")));
        for (int i = 0; i < 11; i++) {
            send(historyEngine, "/expense");
            send(historyEngine, "Еда");
            send(historyEngine, "1, запись " + i);
        }
        contains(send(historyEngine, "/history_all"), "страница 1 из 2");
        contains(send(historyEngine, "/history_next"), "страница 2 из 2");
        contains(send(historyEngine, "/history_next"), "последняя страница");
        contains(send(historyEngine, "/history_prev"), "страница 1 из 2");
        // Buttons survive state restoration, carry categories safely and reject stale selections.
        var picker = new LocalBotEngine(123, data,
                Clock.fixed(Instant.parse("2026-10-30T09:00:00Z"), ZoneId.of("UTC")));
        BotReply categoryMenu = send(picker, "/expense");
        var pendingBeforeHelp = picker.exportState().pending();
        contains(send(picker, "/commands"), "/expense");
        if (!picker.exportState().pending().equals(pendingBeforeHelp)) throw new AssertionError("Help lost input step");
        MenuButton food = categoryMenu.buttons().stream().filter(b -> b.label().equals("Еда")).findFirst().orElseThrow();
        var restoredPicker = new LocalBotEngine(123, data,
                Clock.fixed(Instant.parse("2026-10-30T09:00:00Z"), ZoneId.of("UTC")));
        restoredPicker.restoreState(picker.exportState());
        contains(send(restoredPicker, food.command()), "Категория: Еда");
        contains(send(restoredPicker, "2, кнопка"), "🔴 −2.00");
        contains(send(restoredPicker, food.command()), "устарела");
        if (restoredPicker.exportState().entries().size() != 1) throw new AssertionError("Stale button duplicated expense");
        var debtMenu = send(restoredPicker, "/pay_debt");
        MenuButton debt = debtMenu.buttons().stream().filter(b -> b.label().equals("Кредит")).findFirst().orElseThrow();
        contains(send(restoredPicker, debt.command()), "Введите сумму");
        contains(send(restoredPicker, "10"), "Платёж · 10.00");
        var factory = new VkKeyboardFactory();
        try {
            if (new com.fasterxml.jackson.databind.ObjectMapper().readTree(factory.keyboard(categoryMenu.buttons()))
                    .path("buttons").size() > 6) throw new AssertionError("Picker exceeded VK keyboard limits");
        } catch (java.io.IOException invalid) { throw new AssertionError(invalid); }
        var manyCategories = new LegacyUserData(data.startingBalance(), data.snapshotTime(), List.of(), List.of(),
                new LegacyExtras(List.of(), List.of(),
                        java.util.stream.IntStream.range(0, 20).mapToObj(i -> "Категория " + i).toList(), Map.of(), Map.of()));
        var pagedPicker = new LocalBotEngine(123, manyCategories, Clock.systemUTC());
        BotReply page = send(pagedPicker, "/expense");
        for (int n = 0; n < 4; n++) {
            if (page.buttons().size() > 10) throw new AssertionError("Picker exceeds inline button budget");
            var next = page.buttons().stream().filter(b -> b.label().equals("➡️ Следующие")).findFirst();
            if (next.isEmpty()) break;
            page = send(pagedPicker, next.get().command());
        }
        var monthlyData = new LegacyUserData(Money.of("1000"), data.snapshotTime(), List.of(
                timedExpense("jan", "2027-01-02T10:00:00+03:00", "100"),
                timedExpense("dec", "2026-12-31T10:00:00+03:00", "50"),
                timedExpense("old", "2024-02-29T10:00:00+03:00", "40"),
                timedExpense("future", "2027-02-02T10:00:00+03:00", "70")),
                List.of(new BudgetPlan(YearMonth.of(2027, 1), "Еда", Money.of("1000")),
                        new BudgetPlan(YearMonth.of(2026, 12), "Еда", Money.of("800")),
                        new BudgetPlan(YearMonth.of(2024, 2), "Еда", Money.of("300")),
                        new BudgetPlan(YearMonth.of(2027, 2), "Еда", Money.of("900"))), data.extras());
        var monthEngine = new LocalBotEngine(123, monthlyData,
                Clock.fixed(Instant.parse("2027-01-15T09:00:00Z"), ZoneId.of("UTC")));
        send(monthEngine, "/plans");
        BotReply monthMenu = send(monthEngine, "/plan_fact");
        var monthButtons = MenuCatalog.buttons(monthMenu.screen());
        for (String command : List.of("/plan_fact_current", "/plan_fact_previous", "/plan_fact_month", "/back")) {
            if (monthButtons.stream().noneMatch(b -> b.command().equals(command))) throw new AssertionError("Missing month action " + command);
        }
        if (monthMenu.text().contains("потрачено")) throw new AssertionError("Month chooser immediately rendered current month");
        contains(send(monthEngine, "/plan_fact_current"), "Еда\nПлан · 1 000 ₽\nФакт · 100 ₽\nОсталось по плану · 900 ₽");
        contains(send(monthEngine, "/plan_fact_previous"), "План / факт · декабрь 2026");
        contains(send(monthEngine, "/plan_fact_previous"), "Факт · 50 ₽\nОсталось по плану · 750 ₽");
        BotReply chooseMonth = send(monthEngine, "/plan_fact_month");
        contains(send(monthEngine, "2026-13"), "Неверный месяц");
        while (chooseMonth.buttons().stream().noneMatch(b -> b.label().equals("2024-02"))) {
            String next = chooseMonth.buttons().stream().filter(b -> b.label().equals("➡️ Следующие")).findFirst().orElseThrow().command();
            chooseMonth = send(monthEngine, next);
        }
        MenuButton archived = chooseMonth.buttons().stream().filter(b -> b.label().equals("2024-02")).findFirst().orElseThrow();
        var afterRestart = new LocalBotEngine(123, monthlyData,
                Clock.fixed(Instant.parse("2027-01-15T09:00:00Z"), ZoneId.of("UTC")));
        afterRestart.restoreState(monthEngine.exportState());
        contains(send(afterRestart, archived.command()), "Еда\nПлан · 300 ₽\nФакт · 40 ₽\nОсталось по плану · 260 ₽");
        if (send(afterRestart, "/back").screen() != financeos.core.Screen.PLANS) throw new AssertionError("Month selection broke back navigation");
        send(afterRestart, "/plan_fact_month");
        contains(send(afterRestart, "2027-02"), "Факт · 0 ₽\nОсталось по плану · 900 ₽");
        send(afterRestart, "/plan_fact_month");
        contains(send(afterRestart, "2023-01"), "Планов и расходов за этот месяц пока нет");
        if (!afterRestart.exportState().entries().isEmpty() || !afterRestart.exportState().plans().isEmpty()) {
            throw new AssertionError("Viewing another month mutated financial data");
        }
        String longCategory = "Очень длинное название категории для проверки читаемости";
        var longData = new LegacyUserData(data.startingBalance(), data.snapshotTime(), List.of(), List.of(),
                new LegacyExtras(List.of(), List.of(), List.of(longCategory), Map.of(), Map.of()));
        var longPicker = new LocalBotEngine(123, longData, Clock.systemUTC());
        BotReply longReply = send(longPicker, "/expense");
        contains(longReply, longCategory);
        var longButton = longReply.buttons().stream().filter(b -> b.command().startsWith("/choose/")).findFirst().orElseThrow();
        if (!longButton.label().equals(longCategory.substring(0,longCategory.offsetByCodePoints(0,39))+"…")) throw new AssertionError("Long option lost its readable name");
        try {
            var rows = new com.fasterxml.jackson.databind.ObjectMapper().readTree(new VkKeyboardFactory().keyboard(longReply.buttons())).path("buttons");
            if (rows.size() > 6) throw new AssertionError("Picker exceeds VK row budget");
        } catch (java.io.IOException invalid) { throw new AssertionError(invalid); }
        contains(send(longPicker, longButton.command()), longCategory);
        System.out.println("Local bot actions passed.");
    }
}
