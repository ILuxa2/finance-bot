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
                "Свободные деньги: 75000.00 ₽");
        if (privateChatEngine.handle(new VkEvent("message_new", 99, 123,
                2_000_000_002L, "chat-2", "/report")).isPresent()) {
            throw new AssertionError("A different VK chat was accepted");
        }
        contains(send(engine, "/start"), "Главное меню");
        contains(send(engine, "/report"), "Свободные деньги: 75000.00 ₽");
        contains(send(engine, "/report"), "баланс минус остаток плана");
        send(engine, "/add");
        contains(send(engine, "/expense"), "Введите категорию");
        contains(send(engine, " еда "), "Категория: Еда");
        contains(send(engine, "100, обед"), "Расход 100.00 ₽");
        contains(send(engine, "/report"), "Свободные деньги: 75000.00 ₽");
        var chart = send(engine, "/chart_expenses");
        if (chart.imagePng() == null || chart.imagePng().length < 1000) {
            throw new AssertionError("Expense chart image was not attached to local reply");
        }
        contains(send(engine, "/daily_limit"), "Осталось сегодня: 400.00 ₽");
        contains(send(engine, "/morning_limit"), "На момент просмотра доступно 400.00 ₽");
        contains(send(engine, "/freedom"), "2026-11-30");
        contains(send(engine, "/health"), "Активный остаток долгов: 1500.00 ₽");
        contains(send(engine, "/insights"), "Крупнейшая статья расходов: Еда — 100.00 ₽");
        send(engine, "/transfer");
        contains(send(engine, "1500, на накопления"), "Общий баланс не изменился");
        contains(send(engine, "/report"), "Свободные деньги: 75000.00 ₽");
        contains(send(engine, "/history_all"), "↔1500.00 ₽");
        send(engine, "/undo");
        contains(send(engine, "/debt_list"), "Кредит: осталось 1500.00 ₽");
        contains(send(engine, "/goal_list"), "Резерв: накоплено 2000.00 ₽");
        var forecast = send(engine, "/forecast");
        contains(forecast, "2026-10 1000.00 ₽");
        contains(forecast, "2026-11 500.00 ₽");
        if (forecast.text().contains("2026-12")) {
            throw new AssertionError("Debt forecast continued after payoff");
        }
        send(engine, "/edit_debt");
        contains(send(engine, "Кредит; 1100; 600; 19"), "Баланс и история операций не изменились");
        var correctedForecast = send(engine, "/forecast");
        contains(correctedForecast, "2026-11 600.00 ₽");
        contains(correctedForecast, "2026-12 500.00 ₽");
        if (correctedForecast.text().contains("2027-01")) {
            throw new AssertionError("Corrected debt forecast added a third payment");
        }
        contains(send(engine, "/report"), "Свободные деньги: 75000.00 ₽");
        send(engine, "/undo");
        contains(send(engine, "/debt_list"), "Кредит: осталось 1500.00 ₽");
        contains(send(engine, "/expense"), "Введите категорию");
        contains(send(engine, "Такси"), "Категория: Такси");
        contains(send(engine, "300, поездка"), "Расход 300.00 ₽");
        contains(send(engine, "/report"), "Свободные деньги: 74700.00 ₽");
        contains(send(engine, "/report"), "Незапланированные расходы за месяц: 300.00 ₽");
        contains(send(engine, "/report"), "300.00 ₽ (уже учтены в балансе)");
        if (engine.handle(new VkEvent("message_new", 99, 123, 123,
                Long.toString(nextEvent - 1), "/report")).isPresent()) {
            throw new AssertionError("Repeated VK event was processed twice");
        }
        send(engine, "/history_date");
        contains(send(engine, "2026-10-30"), "поездка");
        contains(send(engine, "/undo"), "изменение отменено");
        contains(send(engine, "/report"), "Свободные деньги: 75000.00 ₽");
        send(engine, "/add_plan");
        contains(send(engine, "5000, ЕДА"), "Дубликат не создан");
        contains(send(engine, "5000, Развлечения"), "добавлен локально");
        contains(send(engine, "/report"), "Свободные деньги: 70000.00 ₽");
        send(engine, "/undo");
        contains(send(engine, "/report"), "Свободные деньги: 75000.00 ₽");
        send(engine, "/income");
        send(engine, "Зарплата");
        contains(send(engine, "12.345, неверно"), "Дробную часть пишите через точку");
        contains(send(engine, "1000.50, премия"), "Доход 1000.50 ₽");
        contains(send(engine, "/report"), "Свободные деньги: 76000.50 ₽");
        send(engine, "/set_limit");
        contains(send(engine, "750"), "установлен локально");
        contains(send(engine, "/daily_limit"), "Осталось сегодня: 650.00 ₽");
        send(engine, "/undo");
        contains(send(engine, "/daily_limit"), "Осталось сегодня: 400.00 ₽");
        send(engine, "/pay_debt");
        contains(send(engine, "Кредит; 500"), "Фактический баланс уменьшился");
        contains(send(engine, "/report"), "Свободные деньги: 75500.50 ₽");
        contains(send(engine, "/debt_list"), "осталось 1000.00 ₽");
        send(engine, "/undo");
        contains(send(engine, "/report"), "Свободные деньги: 76000.50 ₽");
        send(engine, "/fund_goal");
        contains(send(engine, "Резерв; 1000"), "Накоплено 3000.00 ₽");
        contains(send(engine, "/report"), "Свободные деньги: 76000.50 ₽");
        send(engine, "/undo");
        contains(send(engine, "/goal_list"), "накоплено 2000.00 ₽");
        contains(send(engine, "/history_all"), "[local-1]");
        send(engine, "/delete_operation");
        contains(send(engine, "local-1"), "удалена локально");
        contains(send(engine, "/report"), "Свободные деньги: 76000.50 ₽");
        send(engine, "/undo");
        contains(send(engine, "/report"), "Свободные деньги: 76000.50 ₽");
        send(engine, "/report_date");
        var dated = send(engine, "2026-10-30");
        contains(dated, "Доход: 1000.50 ₽");
        contains(dated, "Расход: 100.00 ₽");
        contains(dated, "Баланс на конец периода: 100900.50 ₽");
        send(engine, "/report_period");
        contains(send(engine, "2026-10-29..2026-10-30"), "Свободные деньги на конец периода: 76000.50 ₽");
        send(engine, "/salary_rule");
        contains(send(engine, "2000; 30; оклад"), "Правило rule-1 создано");
        contains(send(engine, "/regular_pending"), "rule-1@2026-10-30");
        send(engine, "/regular_confirm");
        contains(send(engine, "rule-1@2026-10-30"), "подтверждена");
        contains(send(engine, "/report"), "Свободные деньги: 78000.50 ₽");
        send(engine, "/undo");
        contains(send(engine, "/report"), "Свободные деньги: 76000.50 ₽");
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
        contains(send(engine, "/report"), "Свободные деньги: 75400.50 ₽");
        contains(send(engine, "/regular_pending"), "нет");
        send(engine, "/undo");
        if (engine.exportState().entries().stream().anyMatch(timed ->
                timed.entry().category().equals("Аренда"))) {
            throw new AssertionError("Undo retained a confirmed monthly payment");
        }
        contains(send(engine, "/report"), "Свободные деньги: 76000.50 ₽");
        contains(send(engine, "/regular_pending"), "rule-2@2026-10-30");
        if (engine.handle(new VkEvent("message_new", 99, 456, 456, "foreign", "/report"))
                .isPresent()) throw new AssertionError("Other user received financial data");
        var historyEngine = new LocalBotEngine(123, data,
                Clock.fixed(Instant.parse("2026-10-30T09:00:00Z"), ZoneId.of("UTC")));
        for (int i = 0; i < 21; i++) {
            send(historyEngine, "/expense");
            send(historyEngine, "Еда");
            send(historyEngine, "1, запись " + i);
        }
        contains(send(historyEngine, "/history_all"), "страница 1 из 2");
        contains(send(historyEngine, "/history_next"), "страница 2 из 2");
        contains(send(historyEngine, "/history_next"), "последняя страница");
        contains(send(historyEngine, "/history_prev"), "страница 1 из 2");
        System.out.println("Local bot actions passed.");
    }
}
