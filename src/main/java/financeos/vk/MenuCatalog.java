package financeos.vk;

import financeos.core.Screen;
import java.util.List;
import java.util.Map;

/** VK text buttons avoid callback acknowledgements for long-running actions. */
public final class MenuCatalog {
    private static final Map<Screen, List<MenuButton>> MENUS = Map.ofEntries(
            Map.entry(Screen.MAIN, List.of(
                    new MenuButton("➕ Добавить", "/add"),
                    new MenuButton("📊 Отчёт", "/report"),
                    new MenuButton("📋 Планы", "/plans"),
                    new MenuButton("⏱ Лимиты", "/limits"),
                    new MenuButton("💳 Долги", "/debts"),
                    new MenuButton("🎯 Цели", "/goals"),
                    new MenuButton("📈 Аналитика", "/analytics"),
                    new MenuButton("⚙️ Настройки", "/settings"),
                    new MenuButton("🔁 Регулярные", "/regular"),
                    new MenuButton("📖 Список команд", "/help"))),
            Map.entry(Screen.ADD, List.of(
                    new MenuButton("💰 Доход", "/income"),
                    new MenuButton("💸 Расход", "/expense"),
                    new MenuButton("🔁 Перевод", "/transfer"),
                    new MenuButton("💳 Платёж по долгу", "/pay_debt"),
                    new MenuButton("🎯 Пополнить цель", "/fund_goal"),
                    new MenuButton("🗑 Удалить операцию", "/delete_operation"))),
            Map.entry(Screen.ANALYTICS, List.of(
                    new MenuButton("📈 Прогноз", "/forecast"),
                    new MenuButton("🕊 Долговая свобода", "/freedom"),
                    new MenuButton("💚 Финансовое здоровье", "/health"),
                    new MenuButton("📊 Диаграммы", "/charts"),
                    new MenuButton("🧠 Бизнес-выводы", "/insights"),
                    new MenuButton("📜 История операций", "/history"))),
            Map.entry(Screen.HISTORY, List.of(
                    new MenuButton("📋 Все операции", "/history_all"),
                    new MenuButton("📅 По дате", "/history_date"),
                    new MenuButton("🗓 За период", "/history_period"),
                    new MenuButton("⬅️ Раньше", "/history_prev"),
                    new MenuButton("➡️ Дальше", "/history_next"))),
            Map.entry(Screen.REPORT, List.of(
                    new MenuButton("📅 Отчёт за дату", "/report_date"),
                    new MenuButton("🗓 Отчёт за период", "/report_period"))),
            Map.entry(Screen.PLANS, List.of(
                    new MenuButton("📊 План / факт", "/plan_fact"),
                    new MenuButton("➕ Добавить план", "/add_plan"),
                    new MenuButton("🗑 Удалить план", "/delete_plan"))),
            Map.entry(Screen.LIMITS, List.of(
                    new MenuButton("📆 Дневной лимит", "/daily_limit"),
                    new MenuButton("🌅 Утренний лимит", "/morning_limit"),
                    new MenuButton("➕ Установить лимит", "/set_limit"))),
            Map.entry(Screen.DEBTS, List.of(
                    new MenuButton("💳 Список долгов", "/debt_list"),
                    new MenuButton("➕ Добавить долг", "/add_debt"),
                    new MenuButton("✏️ Уточнить долг", "/edit_debt"),
                    new MenuButton("💸 Платёж", "/pay_debt"),
                    new MenuButton("✅ Закрыть", "/close_debt"))),
            Map.entry(Screen.GOALS, List.of(
                    new MenuButton("🎯 Список целей", "/goal_list"),
                    new MenuButton("➕ Добавить цель", "/add_goal"),
                    new MenuButton("💰 Пополнить", "/fund_goal"),
                    new MenuButton("✅ Закрыть", "/close_goal"))),
            Map.entry(Screen.CHARTS, List.of(
                    new MenuButton("💸 Расходы", "/chart_expenses"),
                    new MenuButton("💰 Доходы", "/chart_income"),
                    new MenuButton("📊 План / факт", "/chart_plan"),
                    new MenuButton("💳 Долги", "/chart_debts"),
                    new MenuButton("🎯 Цели", "/chart_goals"))),
            Map.entry(Screen.RECURRING, List.of(
                    new MenuButton("💰 Зарплата", "/salary_rule"),
                    new MenuButton("💸 Ежемесячный платёж", "/payment_rule"),
                    new MenuButton("📋 Правила", "/regular_list"),
                    new MenuButton("⏳ Ждут подтверждения", "/regular_pending"),
                    new MenuButton("✅ Подтвердить", "/regular_confirm"),
                    new MenuButton("⏭ Пропустить", "/regular_skip"),
                    new MenuButton("🗑 Удалить правило", "/regular_delete")))
    );

    private MenuCatalog() {}

    public static String commandHelp() {
        var commands = new java.util.LinkedHashMap<String, String>();
        commands.put("/start", "Главное меню");
        for (Screen screen : Screen.values()) {
            for (MenuButton button : buttons(screen)) {
                commands.putIfAbsent(button.command(), button.label());
            }
        }
        var text = new StringBuilder("📖 Все команды FinanceOS\n\n");
        commands.forEach((command, label) -> text.append(command).append(" — ")
                .append(label).append('\n'));
        return text.append("\nДоход или расход: выберите категорию, затем введите «сумма, описание»."
                + " Для дробной суммы используйте точку: 1250.50, продукты."
                + "\n/back — назад, /undo — отменить последнее изменение."
                + "\nПросмотр списка команд сохраняет текущий шаг ввода.").toString();
    }

    public static List<MenuButton> buttons(Screen screen) {
        List<MenuButton> content = MENUS.getOrDefault(screen, List.of());
        var result = new java.util.ArrayList<>(content);
        result.add(new MenuButton("↩️ Отменить изменение", "/undo"));
        if (screen != Screen.MAIN) result.add(new MenuButton("⬅️ Назад", "/back"));
        return List.copyOf(result);
    }
}
