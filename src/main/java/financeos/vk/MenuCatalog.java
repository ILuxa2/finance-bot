package financeos.vk;

import financeos.core.Screen;
import java.util.List;
import java.util.Map;

/** VK text buttons avoid callback acknowledgements for long-running actions. */
public final class MenuCatalog {
    private static final Map<Screen, List<MenuButton>> MENUS = Map.ofEntries(
            Map.entry(Screen.CATEGORY_SETUP,List.of(new MenuButton("✅ Создать стандартные","/defaults_confirm"),new MenuButton("Настрою сам","/defaults_skip"))),
            Map.entry(Screen.CATEGORY_PRUNE,List.of(new MenuButton("✅ Убрать неиспользуемые","/sections_prune_confirm"),new MenuButton("❌ Отмена","/sections"))),
            Map.entry(Screen.MAIN, List.of(
                    new MenuButton("💸 Расход", "/expense"), new MenuButton("💰 Доход", "/income"),
                    new MenuButton("📊 Отчёт", "/report_menu"), new MenuButton("📜 Все операции", "/operations"),
                    new MenuButton("📋 Планы", "/plans"), new MenuButton("☰ Ещё", "/settings"),
                    new MenuButton("✏️ Изменить", "/operation_filters"), new MenuButton("❓ Помощь", "/help"), new MenuButton("🧾 Чек", "/receipt"))),
            Map.entry(Screen.HISTORY_FILTER, List.of(
                    new MenuButton("Сегодня", "/filter_today"), new MenuButton("Вчера", "/filter_yesterday"),
                    new MenuButton("Этот месяц", "/filter_month"), new MenuButton("Выбрать период", "/filter_period"),
                    new MenuButton("Вся история", "/filter_all"))),
            Map.entry(Screen.HISTORY_SEARCH, List.of(
                    new MenuButton("Все категории", "/filter_apply"), new MenuButton("По категории", "/filter_categories"),
                    new MenuButton("По сумме", "/filter_amount"), new MenuButton("По комментарию", "/filter_comment"),
                    new MenuButton("Сбросить поиск", "/filter_clear"))),
            Map.entry(Screen.EDITOR_CARD, List.of(
                    new MenuButton("🔁 Повторить", "/repeat_operation"),
                    new MenuButton("📅 Дата", "/edit_date"),
                    new MenuButton("💰 Сумма", "/edit_amount"),
                    new MenuButton("💬 Комментарий", "/edit_comment"),
                    new MenuButton("🗑 Удалить", "/edit_delete"),
                    new MenuButton("📜 К списку", "/edit_return"))),
            Map.entry(Screen.EDITOR_DELETE_CONFIRM, List.of(
                    new MenuButton("🗑 Да, удалить", "/edit_delete_confirm"),
                    new MenuButton("❌ Отмена", "/edit_delete_cancel"))),
            Map.entry(Screen.EDITOR_CONFIRM, List.of(
                    new MenuButton("✅ Сохранить изменение", "/edit_confirm"),
                    new MenuButton("❌ Отменить правку", "/edit_cancel"))),
            Map.entry(Screen.SETTINGS, List.of(
                    new MenuButton("💳 Долги", "/debts"),
                    new MenuButton("📈 Аналитика", "/analytics"),
                    new MenuButton("⏱ Лимиты", "/limits"),
                    new MenuButton("🎯 Цели", "/goals"),
                    new MenuButton("🔁 Регулярные", "/regular"),
                    new MenuButton("🧹 Чат", "/chat_cleanup"),
                    new MenuButton("📂 Разделы", "/sections"))),
            Map.entry(Screen.ADD, List.of(
                    new MenuButton("💰 Доход", "/income"),
                    new MenuButton("💸 Расход", "/expense"),
                    new MenuButton("🔁 Перевод", "/transfer"),
                    new MenuButton("💳 Платёж по долгу", "/pay_debt"),
                    new MenuButton("🎯 Пополнить цель", "/fund_goal"),new MenuButton("🧾 Чек", "/receipt"))),
            Map.entry(Screen.ANALYTICS, List.of(
                    new MenuButton("📈 Прогноз", "/forecast"),
                    new MenuButton("🕊 Долговая свобода", "/freedom"),
                    new MenuButton("💚 Финансовое здоровье", "/health"),
                    new MenuButton("📊 Диаграммы", "/charts"),
                    new MenuButton("🧠 Выводы", "/insights"),
                    new MenuButton("📜 История", "/history"))),
            Map.entry(Screen.HISTORY, List.of(
                    new MenuButton("🔁 Повторить", "/history_repeat"),
                    new MenuButton("🔎 Фильтры", "/operations"),
                    new MenuButton("📅 По дате", "/history_date"),
                    new MenuButton("🗓 За период", "/history_period"),
                    new MenuButton("⬅️ Новее", "/history_prev"),
                    new MenuButton("Раньше ➡️", "/history_next"),
                    new MenuButton("✏️ Изменить операцию", "/history_edit"))),
            Map.entry(Screen.CHAT_CLEAN, List.of(
                    new MenuButton("🧹 Очистить сейчас", "/chat_clear"),
                    new MenuButton("⏱ Автоочистка", "/chat_auto"))),
            Map.entry(Screen.CHAT_CLEAR_CONFIRM, List.of(
                    new MenuButton("🗑 Да, очистить чат", "/chat_clear_confirm"),
                    new MenuButton("❌ Отмена", "/chat_cleanup"))),
            Map.entry(Screen.CHAT_CLEAR_RESULT, List.of(
                    new MenuButton("🧹 Настройки чата", "/chat_cleanup"))),
            Map.entry(Screen.CHAT_AUTO, List.of(
                    new MenuButton("1 день", "/chat_auto_1"), new MenuButton("7 дней", "/chat_auto_7"),
                    new MenuButton("30 дней", "/chat_auto_30"), new MenuButton("Выключить", "/chat_auto_off"))),
            Map.entry(Screen.CHAT_AUTO_CONFIRM, List.of(
                    new MenuButton("✅ Включить", "/chat_auto_confirm"), new MenuButton("❌ Отмена", "/chat_cleanup"))),
            Map.entry(Screen.SECTIONS,List.of(new MenuButton("Разделы","/section_pick"),new MenuButton("Новый раздел","/section_new"),new MenuButton("Архив","/category_archive_list"),new MenuButton("Убрать пустые","/sections_prune"),new MenuButton("Стандартный набор","/defaults_setup"))),
            Map.entry(Screen.SECTION_CARD,List.of(new MenuButton("Категории","/section_categories"),new MenuButton("Новая категория","/category_new"),new MenuButton("Переименовать","/section_rename"),new MenuButton("Удалить раздел","/section_delete"))),
            Map.entry(Screen.SECTION_CATEGORY_CARD,List.of(new MenuButton("Переименовать","/category_rename"),new MenuButton("Перенести","/category_move"),new MenuButton("В архив","/category_delete"))),
            Map.entry(Screen.SECTION_CONFIRM,List.of(new MenuButton("✅ Подтвердить","/section_confirm"),new MenuButton("❌ Отмена","/sections"))),
            Map.entry(Screen.PLAN_SECTION_DETAIL,List.of(new MenuButton("Разделы","/plan_sections"),new MenuButton("Категории","/plan_categories"),new MenuButton("Изменить план","/plan_edit"))),
            Map.entry(Screen.CHART_LEVEL,List.of(new MenuButton("По разделам","/chart_section_level"),new MenuButton("По категориям","/chart_category_level"))),
            Map.entry(Screen.REPORT_OPTIONS, List.of(
                    new MenuButton("📊 С диаграммой", "/report_chart"),
                    new MenuButton("📄 Без диаграммы", "/report"))),
            Map.entry(Screen.CHART_THEME, List.of(
                    new MenuButton("🌙 Тёмная", "/chart_theme_dark"),
                    new MenuButton("☀️ Светлая", "/chart_theme_light"))),
            Map.entry(Screen.PLAN_COPY_MONTH, List.of(
                    new MenuButton("📅 Текущий месяц", "/plan_copy_current"),
                    new MenuButton("➡️ Следующий месяц", "/plan_copy_next"))),
            Map.entry(Screen.REPORT, List.of(new MenuButton("📂 Разделы", "/report_sections"),new MenuButton("📋 Категории", "/report_categories"),
                    new MenuButton("📅 Отчёт за дату", "/report_date"),
                    new MenuButton("🗓 За период", "/report_period"),
                    new MenuButton("📊 Сравнить месяцы", "/compare_months"))),
            Map.entry(Screen.PLANS, List.of(
                    new MenuButton("📊 План / факт", "/plan_fact"),
                    new MenuButton("➕ Добавить план", "/add_plan"),
                    new MenuButton("🗑 Удалить план", "/delete_plan"),
                    new MenuButton("📋 Копировать план", "/plan_copy_month"), new MenuButton("✏️ Изменить", "/plan_edit_current"))),
            Map.entry(Screen.PLAN_FACT, List.of(
                    new MenuButton("📅 Текущий", "/plan_fact_current"),
                    new MenuButton("◀️ Прошлый", "/plan_fact_previous"),
                    new MenuButton("🗓 Другой", "/plan_fact_month"),
                    new MenuButton("📋 Копировать", "/plan_copy_previous"), new MenuButton("✏️ Изменить", "/plan_edit"),new MenuButton("📂 Разделы", "/plan_sections"),new MenuButton("📋 Категории", "/plan_categories"))),
            Map.entry(Screen.PLAN_EDIT_CARD,List.of(new MenuButton("Название","/plan_edit_name"),new MenuButton("Сумма","/plan_edit_amount"),new MenuButton("К категориям","/plan_edit"))),
            Map.entry(Screen.PLAN_EDIT_CONFIRM,List.of(new MenuButton("✅ Сохранить","/plan_edit_confirm"),new MenuButton("❌ Отмена","/plan_edit_cancel"))),
            Map.entry(Screen.PLAN_COPY_CONFIRM, List.of(
                    new MenuButton("✅ Копировать", "/plan_copy_confirm"),
                    new MenuButton("✏️ Изменить категории", "/plan_copy_edit"),
                    new MenuButton("🗓 Другой месяц", "/plan_copy_month"),
                    new MenuButton("❌ Отмена", "/plan_copy_cancel"))),
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
                    new MenuButton("🎯 Цели", "/chart_goals"),new MenuButton("🎨 Тема", "/chart_theme"))),
            Map.entry(Screen.RECURRING, List.of(
                    new MenuButton("💰 Зарплата", "/salary_rule"),
                    new MenuButton("💸 Платёж", "/payment_rule"),
                    new MenuButton("📋 Правила", "/regular_list"),
                    new MenuButton("⏳ Ожидают", "/regular_pending"),
                    new MenuButton("✅ Подтвердить", "/regular_confirm"),
                    new MenuButton("⏭ Пропуск", "/regular_skip"),
                    new MenuButton("🗑 Удалить", "/regular_delete")))
    );

    private MenuCatalog() {}

    public static String commandHelp() {
        var commands = new java.util.LinkedHashMap<String, String>();
        commands.put("/start", "Главное меню");
        commands.put("/history_all", "Вся история без фильтров");
        commands.put("/edit_operations", "Изменение операций без фильтров");
        commands.put("/delete_operation", "Открыть список для изменения и удаления");
        for (Screen screen : Screen.values()) {
            for (MenuButton button : buttons(screen)) {
                commands.putIfAbsent(button.command(), button.label());
            }
        }
        var text = new StringBuilder("❓ Помощь FinanceOS · команды\n\n");
        commands.forEach((command, label) -> text.append(command).append(": ")
                .append(label).append('\n'));
        return text.append("\nВвод: 1250.50, продукты. Дробная часть через точку."
                + "\nБыстрый ввод: −450.50, кофе / +50000, зарплата → категория. Повтор: история → операция → «Повторить».\n/back — назад; /undo — отмена. Справка не сбрасывает ввод.").toString();
    }

    public static List<MenuButton> buttons(Screen screen) {
        List<MenuButton> content = MENUS.getOrDefault(screen, List.of());
        var result = new java.util.ArrayList<>(content);
        if (!java.util.Set.of(Screen.CHAT_CLEAN, Screen.CHAT_CLEAR_CONFIRM, Screen.CHAT_CLEAR_RESULT, Screen.CHAT_AUTO, Screen.CHAT_AUTO_CONFIRM).contains(screen)) {
            result.add(new MenuButton("↩️ Отменить", "/undo"));
        }
        if (screen != Screen.MAIN) {
            result.add(new MenuButton("⬅️ Назад", "/back"));
            result.add(new MenuButton("🏠 Меню", "/start"));
        }
        return List.copyOf(result);
    }
}
