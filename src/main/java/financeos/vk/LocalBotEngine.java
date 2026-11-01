package financeos.vk;

import financeos.core.BudgetPlan;
import financeos.core.ChartKind;
import financeos.core.ChartService;
import financeos.core.CategoryCatalog;
import financeos.core.Debt;
import financeos.core.EntryKind;
import financeos.core.LedgerEntry;
import financeos.core.Goal;
import financeos.core.Money;
import financeos.core.Navigation;
import financeos.core.OperationDraftParser;
import financeos.core.RecurringBook;
import financeos.core.Screen;
import financeos.core.UndoStack;
import financeos.data.LegacyUserData;
import financeos.data.LegacyExtras;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Map;
import java.util.Set;

/** One user's local, read-only-over-Google-Sheets bot session. Mutations stay in memory. */
public final class LocalBotEngine {
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private final long allowedVkUserId;
    private final Set<Long> allowedPeerIds;
    private final LegacyUserData original;
    private final Clock clock;
    private final Navigation navigation = new Navigation();
    private final UndoStack<BotUndoState> undo = new UndoStack<>(30);
    private final LinkedHashSet<String> seenEvents = new LinkedHashSet<>();
    private final List<LegacyUserData.TimedEntry> localEntries = new ArrayList<>();
    private final List<BudgetPlan> localPlans = new ArrayList<>();
    private final List<Debt> debtState = new ArrayList<>();
    private final List<Goal> goalState = new ArrayList<>();
    private final Map<YearMonth, BigDecimal> limitState = new LinkedHashMap<>();
    private final OperationDraftParser draftParser = new OperationDraftParser();
    private final RecurringBook recurring = new RecurringBook();
    private final ChartService chartService = new ChartService();
    private final ChartRenderer chartRenderer = new ChartRenderer();
    private Pending pending = Pending.NONE;
    private String pendingCategory = "";
    private long nextEntryId = 1;
    private LocalDate historyFrom;
    private LocalDate historyThrough;
    private int historyPage;

    private enum Pending { NONE, INCOME_CATEGORY, EXPENSE_CATEGORY, INCOME_AMOUNT,
        EXPENSE_AMOUNT, TRANSFER_AMOUNT, DELETE_OPERATION, PLAN, DELETE_PLAN, ADD_DEBT, EDIT_DEBT, PAY_DEBT, CLOSE_DEBT,
        ADD_GOAL, FUND_GOAL, CLOSE_GOAL, SET_LIMIT, HISTORY_DATE, HISTORY_PERIOD,
        REPORT_DATE, REPORT_PERIOD, SALARY_RULE, PAYMENT_RULE,
        CONFIRM_RULE, SKIP_RULE, DELETE_RULE }

    public LocalBotEngine(long allowedVkUserId, LegacyUserData original, Clock clock) {
        this(allowedVkUserId, Set.of(allowedVkUserId), original, clock);
    }

    public LocalBotEngine(long allowedVkUserId, Set<Long> allowedPeerIds,
                          LegacyUserData original, Clock clock) {
        if (allowedVkUserId <= 0) throw new IllegalArgumentException("VK user ID is required");
        this.allowedVkUserId = allowedVkUserId;
        if (allowedPeerIds == null || allowedPeerIds.isEmpty()
                || allowedPeerIds.stream().anyMatch(peerId -> peerId == null || peerId <= 0)) {
            throw new IllegalArgumentException("VK peer IDs are required");
        }
        this.allowedPeerIds = Set.copyOf(allowedPeerIds);
        this.original = java.util.Objects.requireNonNull(original);
        this.clock = java.util.Objects.requireNonNull(clock);
        debtState.addAll(original.extras().debts());
        goalState.addAll(original.extras().goals());
        limitState.putAll(original.extras().monthlyLimits());
    }

    public synchronized BotLocalState exportState() {
        return new BotLocalState(localEntries, localPlans, debtState, goalState, limitState,
                nextEntryId, List.copyOf(seenEvents), recurring.state(),
                pending.name(), pendingCategory, navigation.state(), undo.newestFirst());
    }

    public synchronized void restoreState(BotLocalState state) {
        localEntries.clear();
        localEntries.addAll(state.entries());
        localPlans.clear();
        localPlans.addAll(state.plans());
        debtState.clear();
        debtState.addAll(state.debts());
        goalState.clear();
        goalState.addAll(state.goals());
        limitState.clear();
        limitState.putAll(state.limits());
        nextEntryId = state.nextEntryId();
        seenEvents.clear();
        seenEvents.addAll(state.seenEvents());
        recurring.restore(state.recurring());
        pending = Pending.valueOf(state.pending());
        pendingCategory = state.pendingCategory();
        navigation.restore(state.navigation());
        undo.restoreNewestFirst(state.undoHistory());
    }

    public synchronized Optional<BotReply> handle(VkEvent event) {
        if (!event.type().equals("message_new") && !event.type().equals("message_event")) {
            return Optional.empty();
        }
        if (event.userId() != allowedVkUserId || !allowedPeerIds.contains(event.peerId())) {
            return Optional.empty();
        }
        String eventKey = event.type() + ":" + event.groupId() + ":" + event.peerId()
                + ":" + event.eventId();
        if (!seenEvents.add(eventKey)) return Optional.empty();
        if (seenEvents.size() > 2000) seenEvents.remove(seenEvents.iterator().next());
        String text = event.text() == null ? "" : event.text().strip();
        if (text.equals("/start")) {
            pending = Pending.NONE;
            navigation.main();
            int due = recurring.pendingThrough(now().toLocalDate()).size();
            return answer("🏠 Главное меню. Выберите раздел."
                    + (due == 0 ? "" : "\nРегулярных операций ждут подтверждения: "
                    + due + ". Откройте /regular_pending."));
        }
        if (text.equals("/back")) {
            pending = Pending.NONE;
            pendingCategory = "";
            navigation.back();
            return answer("⬅️ Вернулись назад.");
        }
        if (text.equals("/undo")) {
            var previous = undo.undo();
            if (previous.isEmpty()) return answer("Пока нечего отменять.");
            localEntries.clear();
            localEntries.addAll(previous.get().entries());
            localPlans.clear();
            localPlans.addAll(previous.get().plans());
            debtState.clear();
            debtState.addAll(previous.get().debts());
            goalState.clear();
            goalState.addAll(previous.get().goals());
            limitState.clear();
            limitState.putAll(previous.get().limits());
            recurring.restore(previous.get().recurring());
            pending = Pending.NONE;
            return answer("Последнее локальное изменение отменено.");
        }
        if (text.equals("/help") || text.equals("/commands")) {
            return answer(MenuCatalog.commandHelp());
        }
        if (!text.startsWith("/") && pending != Pending.NONE) {
            return handlePending(text);
        }
        pending = Pending.NONE;
        return switch (text) {
            case "/add" -> open(Screen.ADD, "➕ Добавить операцию. Выберите тип.");
            case "/income" -> beginCategory(EntryKind.INCOME);
            case "/expense" -> beginCategory(EntryKind.EXPENSE);
            case "/transfer" -> begin(Pending.TRANSFER_AMOUNT, Screen.TRANSFER,
                    "Введите «сумма, описание перевода». Внутренний перевод не меняет общий баланс.");
            case "/delete_operation" -> begin(Pending.DELETE_OPERATION, Screen.ADD,
                    "Введите код локальной операции из истории, например local-1."
                    + " Операции исходной таблицы здесь не удаляются.");
            case "/report" -> report();
            case "/report_date" -> begin(Pending.REPORT_DATE, Screen.REPORT,
                    "Введите дату отчёта в формате ГГГГ-ММ-ДД.");
            case "/report_period" -> begin(Pending.REPORT_PERIOD, Screen.REPORT,
                    "Введите период отчёта: ГГГГ-ММ-ДД..ГГГГ-ММ-ДД.");
            case "/plans" -> open(Screen.PLANS, "📋 Планы. Выберите действие.");
            case "/plan_fact" -> planFact();
            case "/add_plan" -> beginPlan();
            case "/delete_plan" -> begin(Pending.DELETE_PLAN, Screen.PLANS,
                    "Введите категорию плана, добавленного в этом локальном запуске.");
            case "/analytics" -> open(Screen.ANALYTICS, "📈 Аналитика. Выберите действие.");
            case "/history" -> open(Screen.HISTORY, "📜 История операций. Выберите период.");
            case "/history_all" -> history(null, null);
            case "/history_date" -> beginHistoryDate();
            case "/history_period" -> beginHistoryPeriod();
            case "/history_next" -> historyPage(1);
            case "/history_prev" -> historyPage(-1);
            case "/charts" -> open(Screen.CHARTS, "📊 Диаграммы. Выберите вид.");
            case "/chart_expenses" -> chart(ChartKind.EXPENSES);
            case "/chart_income" -> chart(ChartKind.INCOME);
            case "/chart_plan" -> chart(ChartKind.PLAN);
            case "/chart_debts" -> chart(ChartKind.DEBTS);
            case "/chart_goals" -> chart(ChartKind.GOALS);
            case "/limits" -> open(Screen.LIMITS, "⏱ Лимиты. Выберите действие.");
            case "/daily_limit" -> dailyLimit();
            case "/morning_limit" -> morningLimit();
            case "/set_limit" -> begin(Pending.SET_LIMIT, Screen.LIMITS,
                    "Введите новый дневной лимит на текущий месяц. Для дробной суммы используйте точку.");
            case "/debts" -> open(Screen.DEBTS, "💳 Долги. Выберите действие.");
            case "/debt_list" -> debtList();
            case "/add_debt" -> begin(Pending.ADD_DEBT, Screen.DEBTS,
                    "Введите «Название; остаток; ежемесячный платёж; день платежа». Пример: Кредит; 15000; 5000; 19.");
            case "/edit_debt" -> begin(Pending.EDIT_DEBT, Screen.DEBTS,
                    "Уточните карточку долга: «Название; текущий остаток; ежемесячный платёж; день платежа»."
                    + " Баланс и история не изменятся. Пример: Заём; 1100; 600; 19.");
            case "/pay_debt" -> begin(Pending.PAY_DEBT, Screen.DEBTS,
                    "Введите «Название долга; сумма платежа». Пример: Кредит; 1000.");
            case "/close_debt" -> begin(Pending.CLOSE_DEBT, Screen.DEBTS,
                    "Введите название долга. Закрыть можно долг с нулевым остатком.");
            case "/goals" -> open(Screen.GOALS, "🎯 Цели. Выберите действие.");
            case "/goal_list" -> goalList();
            case "/add_goal" -> begin(Pending.ADD_GOAL, Screen.GOALS,
                    "Введите «Название; сумма цели; ежемесячная сумма». Пример: Резерв; 100000; 5000.");
            case "/fund_goal" -> begin(Pending.FUND_GOAL, Screen.GOALS,
                    "Введите «Название цели; сумма пополнения». Пример: Резерв; 1000.");
            case "/close_goal" -> begin(Pending.CLOSE_GOAL, Screen.GOALS,
                    "Введите название цели для закрытия.");
            case "/forecast" -> forecast();
            case "/freedom" -> open(Screen.ANALYTICS, LocalBotViews.debtFreedom(combined(), now()));
            case "/health" -> open(Screen.ANALYTICS, LocalBotViews.financialHealth(combined(), now()));
            case "/insights" -> open(Screen.ANALYTICS, LocalBotViews.insights(combined(), now()));
            case "/settings" -> open(Screen.SETTINGS, "⚙️ Настройки.");
            case "/regular" -> open(Screen.RECURRING,
                    "🔁 Регулярные операции. Создайте правило или проверьте подтверждения.");
            case "/salary_rule" -> begin(Pending.SALARY_RULE, Screen.RECURRING,
                    "Введите «сумма; день месяца; описание». Пример: 50000; 10; зарплата.");
            case "/payment_rule" -> begin(Pending.PAYMENT_RULE, Screen.RECURRING,
                    "Введите «сумма; день месяца; категория; описание»."
                    + " Пример: 15000; 5; Аренда; квартира.");
            case "/regular_list" -> regularList();
            case "/regular_pending" -> regularPending();
            case "/regular_confirm" -> begin(Pending.CONFIRM_RULE, Screen.RECURRING,
                    "Введите код ожидаемой операции из /regular_pending для подтверждения.");
            case "/regular_skip" -> begin(Pending.SKIP_RULE, Screen.RECURRING,
                    "Введите код ожидаемой операции из /regular_pending, чтобы пропустить этот месяц.");
            case "/regular_delete" -> begin(Pending.DELETE_RULE, Screen.RECURRING,
                    "Введите ID правила из /regular_list для удаления.");
            default -> answer("Этот пункт ещё не подключён в локальной версии. /start — главное меню.");
        };
    }

    private Optional<BotReply> beginCategory(EntryKind kind) {
        navigation.open(kind == EntryKind.INCOME ? Screen.INCOME : Screen.EXPENSE);
        pending = kind == EntryKind.INCOME ? Pending.INCOME_CATEGORY : Pending.EXPENSE_CATEGORY;
        var names = categories();
        String options = names.isEmpty() ? "" : "\nСуществующие категории: " + String.join(", ", names);
        return answer("Введите категорию одним сообщением. Новую можно написать здесь же." + options);
    }

    private Optional<BotReply> beginPlan() {
        navigation.open(Screen.PLANS);
        pending = Pending.PLAN;
        return answer("План на " + YearMonth.from(now())
                + ": введите «сумма, категория». Для дробной суммы используйте точку.");
    }

    private Optional<BotReply> begin(Pending next, Screen screen, String prompt) {
        navigation.open(screen);
        pending = next;
        return answer(prompt);
    }

    private Optional<BotReply> beginHistoryDate() {
        navigation.open(Screen.HISTORY);
        pending = Pending.HISTORY_DATE;
        return answer("Введите дату в формате ГГГГ-ММ-ДД.");
    }

    private Optional<BotReply> beginHistoryPeriod() {
        navigation.open(Screen.HISTORY);
        pending = Pending.HISTORY_PERIOD;
        return answer("Введите начало и конец: ГГГГ-ММ-ДД..ГГГГ-ММ-ДД.");
    }

    private Optional<BotReply> handlePending(String text) {
        return switch (pending) {
            case INCOME_CATEGORY, EXPENSE_CATEGORY -> {
                if (text.isBlank() || text.length() > 80) {
                    yield answer("Название категории должно содержать от 1 до 80 символов.");
                }
                var catalog = categoryCatalog();
                pendingCategory = catalog.ensure(text);
                pending = pending == Pending.INCOME_CATEGORY
                        ? Pending.INCOME_AMOUNT : Pending.EXPENSE_AMOUNT;
                yield answer("Категория: " + pendingCategory
                        + ". Введите «сумма, описание». Для дробной суммы используйте точку.");
            }
            case INCOME_AMOUNT, EXPENSE_AMOUNT -> addOperation(text);
            case TRANSFER_AMOUNT -> addTransfer(text);
            case DELETE_OPERATION -> deleteOperation(text);
            case PLAN -> addPlan(text);
            case DELETE_PLAN -> deletePlan(text);
            case ADD_DEBT -> addDebt(text);
            case EDIT_DEBT -> editDebt(text);
            case PAY_DEBT -> payDebt(text);
            case CLOSE_DEBT -> closeDebt(text);
            case ADD_GOAL -> addGoal(text);
            case FUND_GOAL -> fundGoal(text);
            case CLOSE_GOAL -> closeGoal(text);
            case SET_LIMIT -> setLimit(text);
            case SALARY_RULE -> addRecurring(text, EntryKind.INCOME);
            case PAYMENT_RULE -> addRecurring(text, EntryKind.EXPENSE);
            case CONFIRM_RULE -> decideRecurring(text, true);
            case SKIP_RULE -> decideRecurring(text, false);
            case DELETE_RULE -> deleteRecurring(text);
            case HISTORY_DATE -> {
                try {
                    LocalDate date = LocalDate.parse(text);
                    pending = Pending.NONE;
                    yield history(date, date);
                } catch (RuntimeException invalid) {
                    yield answer("Неверная дата. Пример: 2026-10-30.");
                }
            }
            case HISTORY_PERIOD -> {
                try {
                    String[] dates = text.split("\\.\\.", -1);
                    if (dates.length != 2) throw new IllegalArgumentException();
                    LocalDate from = LocalDate.parse(dates[0].strip());
                    LocalDate through = LocalDate.parse(dates[1].strip());
                    if (from.isAfter(through)) throw new IllegalArgumentException();
                    pending = Pending.NONE;
                    yield history(from, through);
                } catch (RuntimeException invalid) {
                    yield answer("Неверный период. Пример: 2026-10-01..2026-10-30.");
                }
            }
            case REPORT_DATE -> {
                try {
                    LocalDate date = LocalDate.parse(text);
                    if (date.isAfter(now().toLocalDate())) throw new IllegalArgumentException();
                    pending = Pending.NONE;
                    yield open(Screen.REPORT, LocalBotViews.periodReport(combined(), now(), date, date));
                } catch (RuntimeException invalid) {
                    yield answer("Неверная дата или дата в будущем. Пример: 2026-10-30.");
                }
            }
            case REPORT_PERIOD -> {
                try {
                    String[] dates = text.split("\\.\\.", -1);
                    if (dates.length != 2) throw new IllegalArgumentException();
                    LocalDate from = LocalDate.parse(dates[0].strip());
                    LocalDate through = LocalDate.parse(dates[1].strip());
                    if (from.isAfter(through) || through.isAfter(now().toLocalDate())) {
                        throw new IllegalArgumentException();
                    }
                    pending = Pending.NONE;
                    yield open(Screen.REPORT, LocalBotViews.periodReport(combined(), now(), from, through));
                } catch (RuntimeException invalid) {
                    yield answer("Неверный период или конец в будущем."
                            + " Пример: 2026-10-01..2026-10-30.");
                }
            }
            case NONE -> answer("/start — главное меню.");
        };
    }

    private Optional<BotReply> addOperation(String text) {
        try {
            var draft = draftParser.parse(text);
            EntryKind kind = pending == Pending.INCOME_AMOUNT ? EntryKind.INCOME : EntryKind.EXPENSE;
            undo.remember(snapshot());
            OffsetDateTime at = now();
            localEntries.add(new LegacyUserData.TimedEntry(at,
                    new LedgerEntry("local-" + nextEntryId++, at.toLocalDate(), kind,
                            draft.amount(), pendingCategory, draft.description(), false)));
            pending = Pending.NONE;
            navigation.back();
            return answer((kind == EntryKind.INCOME ? "Доход" : "Расход") + " "
                    + money(draft.amount()) + " ₽ записан локально в категории «" + pendingCategory
                    + "». /undo — отменить.");
        } catch (IllegalArgumentException invalid) {
            return answer("Введите «сумма, описание». Например: 1250.50, продукты."
                    + " Дробную часть пишите через точку.");
        }
    }

    private Optional<BotReply> addTransfer(String text) {
        try {
            var draft = draftParser.parse(text);
            undo.remember(snapshot());
            OffsetDateTime at = now();
            localEntries.add(new LegacyUserData.TimedEntry(at,
                    new LedgerEntry("local-" + nextEntryId++, at.toLocalDate(),
                            EntryKind.TRANSFER, draft.amount(), "Внутренний перевод",
                            draft.description(), false)));
            pending = Pending.NONE;
            navigation.back();
            return answer("Внутренний перевод " + money(draft.amount())
                    + " ₽ записан локально. Общий баланс не изменился. /undo — отменить.");
        } catch (IllegalArgumentException invalid) {
            return answer("Введите «сумма, описание перевода». Например: 1000.50, на накопления."
                    + " Дробную часть пишите через точку.");
        }
    }

    private Optional<BotReply> deleteOperation(String text) {
        int index = -1;
        for (int i = 0; i < localEntries.size(); i++) {
            if (localEntries.get(i).entry().id().equals(text)) {
                index = i;
                break;
            }
        }
        if (index < 0) return answer("Локальная операция с таким кодом не найдена.");
        var entry = localEntries.get(index).entry();
        if (entry.description().equals("Платёж по долгу")) {
            return answer("Платёж по долгу удаляйте через /undo сразу после его внесения,"
                    + " чтобы остаток долга и баланс совпадали.");
        }
        undo.remember(snapshot());
        localEntries.remove(index);
        pending = Pending.NONE;
        return answer("Операция " + text + " удалена локально. /undo — отменить.");
    }

    private Optional<BotReply> addPlan(String text) {
        try {
            var draft = draftParser.parse(text);
            if (draft.description().length() > 80) throw new IllegalArgumentException();
            YearMonth month = YearMonth.from(now());
            String key = categoryKey(draft.description());
            boolean exists = combined().plans().stream().anyMatch(plan ->
                    plan.month().equals(month) && categoryKey(plan.category()).equals(key));
            if (exists) return answer("Для этой категории план на месяц уже есть. Дубликат не создан.");
            String category = categoryCatalog().ensure(draft.description());
            undo.remember(snapshot());
            localPlans.add(new BudgetPlan(month, category, draft.amount()));
            pending = Pending.NONE;
            return answer("План «" + category + "» на " + money(draft.amount())
                    + " ₽ добавлен локально. /undo — отменить.");
        } catch (IllegalArgumentException invalid) {
            return answer("Введите «сумма, категория». Например: 15000, Еда."
                    + " Дробную часть пишите через точку.");
        }
    }

    private Optional<BotReply> deletePlan(String text) {
        String key = categoryKey(text);
        YearMonth month = YearMonth.from(now());
        int index = -1;
        for (int i = 0; i < localPlans.size(); i++) {
            var plan = localPlans.get(i);
            if (plan.month().equals(month) && categoryKey(plan.category()).equals(key)) {
                index = i;
                break;
            }
        }
        if (index < 0) return answer("Локальный план с этой категорией не найден."
                + " Планы из исходной таблицы здесь не удаляются.");
        undo.remember(snapshot());
        var removed = localPlans.remove(index);
        pending = Pending.NONE;
        return answer("Локальный план «" + removed.category() + "» удалён. /undo — отменить.");
    }

    private Optional<BotReply> addDebt(String text) {
        try {
            String[] fields = parts(text, 4);
            if (debtIndex(fields[0]) >= 0) return answer("Долг с этим названием уже есть.");
            BigDecimal remaining = positiveMoney(fields[1]);
            BigDecimal monthly = positiveMoney(fields[2]);
            int day = Integer.parseInt(fields[3]);
            var debt = new Debt(fields[0], remaining, monthly, day, true);
            undo.remember(snapshot());
            debtState.add(debt);
            pending = Pending.NONE;
            return answer("Долг «" + debt.name() + "» добавлен локально. /undo — отменить.");
        } catch (RuntimeException invalid) {
            return answer("Введите «Название; остаток; ежемесячный платёж; день платежа»."
                    + " Суммы больше нуля, день от 1 до 31.");
        }
    }

    private Optional<BotReply> editDebt(String text) {
        try {
            String[] fields = parts(text, 4);
            int index = debtIndex(fields[0]);
            if (index < 0) return answer("Долг не найден. Откройте /debt_list и проверьте название.");
            BigDecimal remaining = Money.of(fields[1]);
            if (remaining.signum() < 0) throw new IllegalArgumentException("Negative balance");
            BigDecimal monthly = positiveMoney(fields[2]);
            int day = Integer.parseInt(fields[3]);
            var before = debtState.get(index);
            var after = new Debt(before.name(), remaining, monthly, day, remaining.signum() > 0);
            undo.remember(snapshot());
            debtState.set(index, after);
            pending = Pending.NONE;
            return answer("Долг «" + after.name() + "» уточнён: остаток "
                    + money(after.remaining()) + " ₽, платёж " + money(after.monthlyPayment())
                    + " ₽, день " + after.paymentDay() + ". Баланс и история операций не изменились."
                    + " /undo — отменить.");
        } catch (RuntimeException invalid) {
            return answer("Введите «Название; текущий остаток; ежемесячный платёж; день платежа»."
                    + " Суммы через точку, платёж больше нуля, день от 1 до 31.");
        }
    }

    private Optional<BotReply> payDebt(String text) {
        try {
            String[] fields = parts(text, 2);
            int index = debtIndex(fields[0]);
            if (index < 0) return answer("Долг не найден.");
            BigDecimal amount = positiveMoney(fields[1]);
            var before = debtState.get(index);
            var after = before.pay(amount);
            undo.remember(snapshot());
            debtState.set(index, after);
            OffsetDateTime at = now();
            localEntries.add(new LegacyUserData.TimedEntry(at,
                    new LedgerEntry("local-" + nextEntryId++, at.toLocalDate(), EntryKind.EXPENSE,
                            amount, before.name(), "Платёж по долгу", false)));
            pending = Pending.NONE;
            return answer("Платёж " + money(amount) + " ₽ учтён локально. Остаток долга «"
                    + before.name() + "»: " + money(after.remaining()) + " ₽."
                    + " Фактический баланс уменьшился на сумму платежа. /undo — отменить.");
        } catch (RuntimeException invalid) {
            return answer("Введите «Название долга; сумма платежа»."
                    + " Сумма должна быть больше нуля и не превышать остаток долга.");
        }
    }

    private Optional<BotReply> closeDebt(String text) {
        int index = debtIndex(text);
        if (index < 0) return answer("Долг не найден.");
        var debt = debtState.get(index);
        if (debt.remaining().signum() > 0) {
            return answer("У долга ещё есть остаток " + money(debt.remaining())
                    + " ₽. Сначала внесите платёж.");
        }
        if (debt.active()) {
            undo.remember(snapshot());
            debtState.set(index, new Debt(debt.name(), debt.remaining(), debt.monthlyPayment(),
                    debt.paymentDay(), false));
            pending = Pending.NONE;
            return answer("Долг «" + debt.name() + "» закрыт локально. /undo — отменить.");
        }
        pending = Pending.NONE;
        return answer("Долг «" + debt.name() + "» уже закрыт.");
    }

    private Optional<BotReply> addGoal(String text) {
        try {
            String[] fields = parts(text, 3);
            if (goalIndex(fields[0]) >= 0) return answer("Цель с этим названием уже есть.");
            BigDecimal target = positiveMoney(fields[1]);
            BigDecimal monthly = Money.of(fields[2]);
            var goal = new Goal(fields[0], target, Money.of("0"), monthly, true);
            undo.remember(snapshot());
            goalState.add(goal);
            pending = Pending.NONE;
            return answer("Цель «" + goal.name() + "» добавлена локально. /undo — отменить.");
        } catch (RuntimeException invalid) {
            return answer("Введите «Название; сумма цели; ежемесячная сумма»."
                    + " Сумму цели укажите больше нуля.");
        }
    }

    private Optional<BotReply> fundGoal(String text) {
        try {
            String[] fields = parts(text, 2);
            int index = goalIndex(fields[0]);
            if (index < 0) return answer("Цель не найдена.");
            BigDecimal amount = positiveMoney(fields[1]);
            var before = goalState.get(index);
            var after = before.contribute(amount);
            undo.remember(snapshot());
            goalState.set(index, after);
            pending = Pending.NONE;
            return answer("Цель «" + before.name() + "» пополнена локально на "
                    + money(amount) + " ₽. Накоплено " + money(after.current())
                    + " ₽. Общий баланс не изменился. /undo — отменить.");
        } catch (RuntimeException invalid) {
            return answer("Введите «Название цели; сумма пополнения»."
                    + " Сумма должна быть больше нуля.");
        }
    }

    private Optional<BotReply> closeGoal(String text) {
        int index = goalIndex(text);
        if (index < 0) return answer("Цель не найдена.");
        var before = goalState.get(index);
        if (!before.active()) return answer("Цель уже закрыта.");
        undo.remember(snapshot());
        goalState.set(index, before.close());
        pending = Pending.NONE;
        return answer("Цель «" + before.name() + "» закрыта локально. /undo — отменить.");
    }

    private Optional<BotReply> setLimit(String text) {
        try {
            BigDecimal amount = Money.of(text);
            if (amount.signum() < 0) throw new IllegalArgumentException();
            undo.remember(snapshot());
            limitState.put(YearMonth.from(now()), amount);
            pending = Pending.NONE;
            return answer("Дневной лимит на " + YearMonth.from(now()) + " установлен локально: "
                    + money(amount) + " ₽. /undo — отменить.");
        } catch (RuntimeException invalid) {
            return answer("Введите сумму дневного лимита числом, дробную часть через точку.");
        }
    }

    private Optional<BotReply> addRecurring(String text, EntryKind kind) {
        try {
            String[] fields = parts(text, kind == EntryKind.INCOME ? 3 : 4);
            BigDecimal amount = positiveMoney(fields[0]);
            int day = Integer.parseInt(fields[1]);
            String category = kind == EntryKind.INCOME ? "Зарплата"
                    : categoryCatalog().ensure(fields[2]);
            String description = kind == EntryKind.INCOME ? fields[2] : fields[3];
            BotUndoState before = snapshot();
            var rule = recurring.add(kind, amount, day, now().toLocalDate(),
                    category, description);
            undo.remember(before);
            pending = Pending.NONE;
            return answer("Правило " + rule.id() + " создано локально: "
                    + money(rule.amount()) + " ₽ каждого " + rule.dayOfMonth()
                    + "-го числа. Запись появится только после подтверждения в день платежа."
                    + " /undo — отменить.");
        } catch (RuntimeException invalid) {
            if ("This monthly rule already exists".equals(invalid.getMessage())) {
                return answer("Такое ежемесячное правило уже есть. Дубликат не создан.");
            }
            return answer(kind == EntryKind.INCOME
                    ? "Введите «сумма; день месяца; описание», день от 1 до 31."
                    : "Введите «сумма; день месяца; категория; описание», день от 1 до 31.");
        }
    }

    private Optional<BotReply> regularList() {
        navigation.open(Screen.RECURRING);
        var rules = recurring.state().rules();
        if (rules.isEmpty()) return answer("Ежемесячных правил пока нет.");
        var result = new StringBuilder("🔁 Ежемесячные правила\n");
        for (var rule : rules) {
            result.append(rule.id()).append(" | ")
                    .append(rule.kind() == EntryKind.INCOME ? "доход" : "расход")
                    .append(" ").append(money(rule.amount())).append(" ₽ | день ")
                    .append(rule.dayOfMonth()).append(" | ").append(rule.category())
                    .append(" | ").append(rule.description()).append("\n");
        }
        return answer(result.toString().strip());
    }

    private Optional<BotReply> regularPending() {
        navigation.open(Screen.RECURRING);
        var due = recurring.pendingThrough(now().toLocalDate());
        if (due.isEmpty()) return answer("Регулярных операций для подтверждения нет.");
        var result = new StringBuilder("⏳ Ждут подтверждения\n");
        for (var occurrence : due.stream().limit(15).toList()) {
            var rule = occurrence.rule();
            result.append(occurrence.code()).append(" | дата ")
                    .append(occurrence.date()).append(" | ")
                    .append(rule.kind() == EntryKind.INCOME ? "доход" : "расход")
                    .append(" ").append(money(rule.amount())).append(" ₽ | ")
                    .append(rule.category()).append("\n");
        }
        if (due.size() > 15) result.append("Показаны 15 из ").append(due.size())
                .append(" ожидающих операций. Подтвердите их, чтобы увидеть следующие.\n");
        result.append("Подтвердите через /regular_confirm или пропустите через /regular_skip.");
        return answer(result.toString());
    }

    private Optional<BotReply> decideRecurring(String code, boolean confirmed) {
        boolean exists = recurring.pendingThrough(now().toLocalDate()).stream()
                .anyMatch(item -> item.code().equals(code));
        if (!exists) return answer("Ожидаемая операция с таким кодом не найдена.");
        undo.remember(snapshot());
        var occurrence = recurring.decide(code, now().toLocalDate(), confirmed);
        if (confirmed) {
            var rule = occurrence.rule();
            OffsetDateTime at = now();
            localEntries.add(new LegacyUserData.TimedEntry(at,
                    new LedgerEntry("local-" + nextEntryId++, at.toLocalDate(),
                            rule.kind(), rule.amount(), rule.category(), rule.description(), false)));
        }
        pending = Pending.NONE;
        return answer(confirmed ? "Регулярная операция подтверждена и записана локально."
                + " /undo — отменить." : "Операция этого месяца пропущена. /undo — отменить.");
    }

    private Optional<BotReply> deleteRecurring(String id) {
        boolean exists = recurring.state().rules().stream().anyMatch(rule -> rule.id().equals(id));
        if (!exists) return answer("Правило с таким ID не найдено.");
        undo.remember(snapshot());
        recurring.delete(id);
        pending = Pending.NONE;
        return answer("Ежемесячное правило " + id + " удалено локально. /undo — отменить.");
    }

    private String[] parts(String text, int expected) {
        String[] fields = text.split(";", -1);
        if (fields.length != expected) throw new IllegalArgumentException("Wrong field count");
        for (int i = 0; i < fields.length; i++) {
            fields[i] = fields[i].strip();
            if (fields[i].isBlank()) throw new IllegalArgumentException("Empty field");
        }
        return fields;
    }

    private BigDecimal positiveMoney(String text) {
        BigDecimal amount = Money.of(text);
        if (amount.signum() <= 0) throw new IllegalArgumentException("Amount must be positive");
        return amount;
    }

    private int debtIndex(String name) {
        String key = categoryKey(name);
        for (int i = 0; i < debtState.size(); i++) {
            if (categoryKey(debtState.get(i).name()).equals(key)) return i;
        }
        return -1;
    }

    private int goalIndex(String name) {
        String key = categoryKey(name);
        for (int i = 0; i < goalState.size(); i++) {
            if (categoryKey(goalState.get(i).name()).equals(key)) return i;
        }
        return -1;
    }

    private Optional<BotReply> report() {
        return open(Screen.REPORT, LocalBotViews.report(combined(), now()));
    }

    private Optional<BotReply> planFact() {
        return open(Screen.PLANS, LocalBotViews.planFact(combined(), now()));
    }

    private Optional<BotReply> debtList() {
        return open(Screen.DEBTS, LocalBotViews.debtList(combined()));
    }

    private Optional<BotReply> goalList() {
        return open(Screen.GOALS, LocalBotViews.goalList(combined()));
    }

    private Optional<BotReply> dailyLimit() {
        return open(Screen.LIMITS, LocalBotViews.dailyLimit(combined(), now()));
    }

    private Optional<BotReply> morningLimit() {
        return open(Screen.LIMITS, LocalBotViews.morningLimit(combined(), now()));
    }

    public synchronized String morningLimitText() {
        return LocalBotViews.morningLimit(combined(), now());
    }

    private Optional<BotReply> forecast() {
        return open(Screen.FORECAST, LocalBotViews.forecast(combined(), now()));
    }

    private Optional<BotReply> chart(ChartKind kind) {
        navigation.open(Screen.CHARTS);
        var data = chartService.prepare(kind, combined(), now());
        if (data.labels().isEmpty()) return answer("Для этой диаграммы пока нет данных.");
        return Optional.of(new BotReply(data.title() + ". Диаграмма готова.",
                navigation.current(), chartRenderer.png(data)));
    }

    private Optional<BotReply> history(LocalDate from, LocalDate through) {
        historyFrom = from;
        historyThrough = through;
        historyPage = 0;
        return open(Screen.HISTORY, LocalBotViews.history(combined(), now(), from, through, historyPage));
    }

    private Optional<BotReply> historyPage(int delta) {
        if (navigation.current() != Screen.HISTORY) {
            return answer("Откройте /history и выберите историю для просмотра.");
        }
        int next = Math.max(0, historyPage + delta);
        int count = LocalBotViews.historyCount(combined(), now(), historyFrom, historyThrough);
        if (next * 20 >= count && next != 0) {
            return answer("Это последняя страница истории.");
        }
        historyPage = next;
        return answer(LocalBotViews.history(combined(), now(), historyFrom, historyThrough, historyPage));
    }
    private List<String> categories() { return categoryCatalog().all(); }

    private CategoryCatalog categoryCatalog() {
        var catalog = new CategoryCatalog();
        for (var category : original.extras().categories()) catalog.ensure(category);
        for (var plan : combined().plans()) catalog.ensure(plan.category());
        for (var entry : combined().transactions()) {
            if (!entry.entry().category().isBlank()) catalog.ensure(entry.entry().category());
        }
        return catalog;
    }

    private String categoryKey(String value) {
        return Normalizer.normalize(value.strip().replaceAll("\\s+", " "),
                Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private LegacyUserData combined() {
        var entries = new ArrayList<>(original.transactions());
        entries.addAll(localEntries);
        var plans = new ArrayList<>(original.plans());
        plans.addAll(localPlans);
        var extras = new LegacyExtras(debtState, goalState, original.extras().categories(),
                limitState, original.extras().dailyOverrides());
        return new LegacyUserData(original.startingBalance(), original.snapshotTime(),
                List.copyOf(entries), List.copyOf(plans), extras);
    }

    private BotUndoState snapshot() { return new BotUndoState(List.copyOf(localEntries), List.copyOf(localPlans),
            List.copyOf(debtState), List.copyOf(goalState), Map.copyOf(limitState),
            recurring.state()); }
    private OffsetDateTime now() { return OffsetDateTime.now(clock.withZone(MOSCOW)); }
    private String money(BigDecimal value) { return value.setScale(2).toPlainString(); }
    private Optional<BotReply> open(Screen screen, String text) {
        navigation.open(screen);
        return answer(text);
    }
    private Optional<BotReply> answer(String text) {
        return Optional.of(new BotReply(text, navigation.current()));
    }
}

