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
    private final Set<Long> allowedVkUsers;
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
    private final financeos.core.OperationEditor operationEditor = new financeos.core.OperationEditor();
    private final Map<String, financeos.core.OperationLink> operationLinks = new LinkedHashMap<>();
    private ReceiptReader receiptReader=YandexReceiptReader.local();
    public LocalBotEngine receiptReader(ReceiptReader reader){this.receiptReader=java.util.Objects.requireNonNull(reader);return this;}
    private Pending pending = Pending.NONE;
    private String pendingCategory = "";
    private long nextEntryId = 1;
    private LocalDate historyFrom;
    private LocalDate historyThrough;
    private int historyPage;
    private financeos.core.CategorySections categorySections = financeos.core.CategorySections.empty();
    private final List<financeos.core.PlanEdit> planEdits = new ArrayList<>();
    private final financeos.core.PlanEditService planEditor = new financeos.core.PlanEditService();
    private String chartTheme = "dark";
    private ChatCleanupSettings chatCleanup = ChatCleanupSettings.off();
    private final java.util.Set<String> deletedIds = new java.util.HashSet<>();
    private String historyCategory = "", historyQuery = "", historyAmount = "", historyKind = "";
    private int editorPage;
    private boolean editorContext;
    private List<String> cachedChoiceValues;
    private List<String> cachedIncomeSources;

    private enum Pending { NONE, INCOME_CATEGORY, EXPENSE_CATEGORY, INCOME_AMOUNT,
        EXPENSE_AMOUNT, TRANSFER_AMOUNT, DELETE_OPERATION, PLAN, DELETE_PLAN, ADD_DEBT, EDIT_DEBT, PAY_DEBT, CLOSE_DEBT,
        ADD_GOAL, FUND_GOAL, CLOSE_GOAL, SET_LIMIT, HISTORY_DATE, HISTORY_PERIOD,
        REPORT_DATE, REPORT_PERIOD, SALARY_RULE, PAYMENT_RULE,
        CONFIRM_RULE, SKIP_RULE, DELETE_RULE, PAY_DEBT_AMOUNT, FUND_GOAL_AMOUNT, EDIT_DEBT_VALUES, PLAN_AMOUNT, PLAN_FACT_MONTH, EDIT_SELECT, HISTORY_EDIT_SELECT, EDIT_DATE, EDIT_AMOUNT, EDIT_COMMENT, EDIT_OPERATION_SECTION, EDIT_OPERATION_CATEGORY, EDIT_CONFIRM, PLAN_COPY_CONFIRM, PLAN_COPY_MONTH, PLAN_COPY_ITEM, PLAN_COPY_VALUES, CHAT_CLEAR_CONFIRM, CHAT_AUTO_CONFIRM, PLAN_EDIT_SELECT, PLAN_EDIT_NAME, PLAN_EDIT_AMOUNT, PLAN_EDIT_CONFIRM, INCOME_SECTION, EXPENSE_SECTION, SECTION_PICK, SECTION_CATEGORY_PICK, SECTION_NEW_NAME, SECTION_RENAME_NAME, CATEGORY_NEW_NAME, CATEGORY_RENAME_NAME, CATEGORY_MOVE_TARGET, ARCHIVED_CATEGORY_PICK, SECTION_CONFIRM, PLAN_SECTION_PICK, FILTER_PERIOD, FILTER_CATEGORY, FILTER_AMOUNT, FILTER_COMMENT, EDIT_DELETE_CONFIRM, QUICK_CATEGORY, QUICK_ALL_CATEGORY, REPEAT_AMOUNT, REPORT_EXPENSE_CATEGORY, NEW_EXPENSE_PLAN, NEW_EXPENSE_INCREASE, RECEIPT_INPUT }

    public LocalBotEngine(long allowedVkUserId, LegacyUserData original, Clock clock) {
        this(allowedVkUserId, Set.of(allowedVkUserId), original, clock);
    }

    public LocalBotEngine(long allowedVkUserId, Set<Long> allowedPeerIds,
                          LegacyUserData original, Clock clock) {
        this(allowedVkUserId,allowedPeerIds,original,clock,Set.of(allowedVkUserId));
    }
    public LocalBotEngine(long allowedVkUserId,Set<Long> allowedPeerIds,LegacyUserData original,Clock clock,Set<Long> users){
        this.allowedVkUsers=Set.copyOf(users);
        if(!this.allowedVkUsers.contains(allowedVkUserId))throw new IllegalArgumentException("Owner required");
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
                pending.name(), pendingCategory, navigation.state(), undo.newestFirst(), operationLinks,
                new HistoryCursor(historyFrom, historyThrough, historyPage, historyCategory, historyQuery, historyAmount, editorPage, editorContext, historyKind), deletedIds, chartTheme, chatCleanup, planEdits, categorySections);
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
        operationLinks.clear();
        operationLinks.putAll(state.operationLinks());
        deletedIds.clear(); deletedIds.addAll(state.deletedIds());
        chartTheme = state.chartTheme();
        chatCleanup = state.chatCleanup();
        planEdits.clear(); planEdits.addAll(state.planEdits());
        categorySections = state.categorySections();
        historyFrom = state.history().from();
        historyThrough = state.history().through();
        historyPage = state.history().page();
        historyKind = state.history().kind(); historyCategory = state.history().category(); historyQuery = state.history().query(); historyAmount = state.history().amount();
        editorPage = state.history().editorPage(); editorContext = state.history().editorContext();
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
        if (!allowedVkUsers.contains(event.userId()) || !allowedPeerIds.contains(event.peerId()) || (event.peerId()==allowedVkUserId && event.userId()!=allowedVkUserId)) {
            return Optional.empty();
        }
        String eventKey = event.type() + ":" + event.groupId() + ":" + event.peerId()
                + ":" + event.eventId();
        if (!seenEvents.add(eventKey)) return Optional.empty();
        if (seenEvents.size() > 2000) seenEvents.remove(seenEvents.iterator().next());
        cachedChoiceValues = null;cachedIncomeSources=null;
        String text = event.text() == null ? "" : event.text().strip();
        if(!event.photoUrls().isEmpty())return receiveReceipt(event);
        if(text.equals("/receipt")){return receiptResult(ReceiptFlow.render(ReceiptFlow.blank(now().toLocalDate()),receiptCategories(),""));}
        if(pending==Pending.RECEIPT_INPUT && !java.util.Set.of("/start","/help","/commands","/undo").contains(text)){
            if(text.equals("/back"))text="/receipt/"+receiptDraft().nonce()+"/review";
            return receiptInput(text);
        }
        if(text.startsWith("/receipt/"))return answer("Кнопка чека устарела. Откройте новый чек.");
        if(pending==Pending.NEW_EXPENSE_PLAN || pending==Pending.NEW_EXPENSE_INCREASE){
            try {
                String nonce=newExpenseDraft().nonce();
                if(nonce==null || nonce.isBlank())throw new IllegalArgumentException("Legacy draft");
            } catch(IllegalArgumentException expired) {
                pending=Pending.NONE;pendingCategory="";navigation.main();
                return answer("Черновик расхода устарел после обновления. Введите расход заново. Деньги не списаны.");
            }
        }
        if (text.startsWith("/new_plan_")) {
            String[] parts = text.split("/", -1);
            if (parts.length != 3 || !java.util.Set.of("new_plan_keep","new_plan_expense","new_plan_custom","new_plan_cancel","new_plan_unplanned").contains(parts[1]) || (pending != Pending.NEW_EXPENSE_PLAN && pending != Pending.NEW_EXPENSE_INCREASE)
                    || !parts[2].equals(newExpenseDraft().nonce()) || parts[2].isBlank()) {
                return answer("Эта кнопка устарела. Используйте кнопки текущего расхода.");
            }
            text = "/" + parts[1];
        }
        if(text.startsWith("/expense_chart_page/"))return expenseChartPage(text);
        if(text.startsWith("/plan_page/"))return planPage(text);
        if (text.startsWith("/choose/")) return choose(text);
        if (text.startsWith("/choices/")) {
            try {
                String[] fields = text.split("/");
                if (!pending.name().equals(fields[2])) return answer("Эта кнопка устарела. Выберите действие заново.");
                return choices("Выберите вариант:", Integer.parseInt(fields[3]));
            } catch (RuntimeException invalid) { return answer("Выберите действие в текущем меню."); }
        }
        if (text.equals("/start")) {
            pending = Pending.NONE;
            navigation.main();
            if(needsCategorySetup())return offerCategorySetup();
            int due = recurring.pendingThrough(now().toLocalDate()).size();
            return answer("🏠 Главное меню\nЧто хотите сделать?\nБыстрый ввод: −450, кофе или +50000, зарплата."
                    + (due == 0 ? "" : "\nРегулярных операций ждут подтверждения: "
                    + due + ". Нажмите «⏳ Ожидают» в главном меню."));
        }
        if (text.equals("/back")) {
            if(pending==Pending.EDIT_OPERATION_CATEGORY) {
                var draft=moveDraft();saveMoveDraft(new MoveDraft(draft.id(),""));pending=Pending.EDIT_OPERATION_SECTION;
                return answer("Выберите раздел кнопкой или напишите название категории.");
            }
            if (quickPending()) return cancelQuick();
            if(navigation.current()==Screen.HISTORY && historyKind.equals("EXPENSE") && !historyCategory.isBlank())return reportExpenseCategories();
            if(pending==Pending.INCOME_AMOUNT)return beginCategory(EntryKind.INCOME);
            if(pending==Pending.INCOME_CATEGORY||pending==Pending.INCOME_SECTION){pending=Pending.NONE;pendingCategory="";navigation.main();return answer("🏠 Главное меню\nЧто хотите сделать?");}
            if(categorySections.enabled()&&(pending==Pending.INCOME_AMOUNT||pending==Pending.EXPENSE_AMOUNT)) {
                var d=sectionDraft();if(!d.section().isBlank()){pending=d.kind().equals("INCOME")?Pending.INCOME_CATEGORY:Pending.EXPENSE_CATEGORY;saveSectionDraft(new SectionDraft(d.section(),"","","",d.month(),d.kind(),""));return answer("Выберите категорию раздела «"+d.section()+"».");}
            }
            if(categorySections.enabled()&&(pending==Pending.INCOME_CATEGORY||pending==Pending.EXPENSE_CATEGORY)) {var kind=pending==Pending.INCOME_CATEGORY?EntryKind.INCOME:EntryKind.EXPENSE;navigation.back();return beginCategory(kind);}

            if (navigation.current() == Screen.EDITOR_DELETE_CONFIRM) return cancelOperationDelete();
            if (navigation.current() == Screen.EDITOR_INPUT || navigation.current() == Screen.EDITOR_CONFIRM) {
                return returnToOperationCard();
            }
            pending = Pending.NONE;
            pendingCategory = "";
            navigation.back();
            if (navigation.current() == Screen.EDITOR_LIST || navigation.current() == Screen.HISTORY_EDITOR_LIST) {
                return beginEditOperations(navigation.current() == Screen.HISTORY_EDITOR_LIST);
            }
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
            operationLinks.clear();
            operationLinks.putAll(previous.get().operationLinks());
            deletedIds.clear(); deletedIds.addAll(previous.get().deletedIds());
            planEdits.clear(); planEdits.addAll(previous.get().planEdits());
            categorySections = previous.get().categorySections();
            pending = Pending.NONE;
            var actions=new ArrayList<MenuButton>();
            if(!undo.newestFirst().isEmpty())actions.add(new MenuButton("↩️ Ещё отменить","/undo"));
            actions.add(new MenuButton("🏠 Меню","/start"));
            return Optional.of(new BotReply("Последнее изменение отменено.",navigation.current(),null,actions));
        }
        if (text.equals("/help")) {
            return Optional.of(new BotReply(MenuCatalog.welcomeHelp(), Screen.HELP));
        }
        if (text.equals("/commands")) return answer(MenuCatalog.commandHelp());
        if (financeos.core.QuickOperationParser.signed(text) && (text.contains(",") || pending == Pending.NONE)) return beginQuick(text);
        if (!text.startsWith("/") && pending != Pending.NONE) {
            return handlePending(text);
        }
        Pending beforeCommand = pending;
        pending = Pending.NONE;
        return switch (text) {
            case "/quick_all" -> quickCategoryMode(beforeCommand, true);
            case "/quick_recent" -> quickCategoryMode(beforeCommand, false);
            case "/quick_cancel" -> { if (isQuick(beforeCommand)) yield cancelQuick(); yield answer("Нет быстрого ввода для отмены."); }
            case "/repeat_operation" -> beginRepeat();
            case "/history_repeat" -> { editorPage = historyPage * 2; yield beginEditOperations(true); }
            case "/new_plan_unplanned" -> completeNewExpense(beforeCommand,BigDecimal.ZERO,false);
            case "/new_plan_keep" -> completeNewExpense(beforeCommand,BigDecimal.ZERO);
            case "/new_plan_expense" -> {if(beforeCommand!=Pending.NEW_EXPENSE_PLAN && beforeCommand!=Pending.NEW_EXPENSE_INCREASE)yield answer("Кнопка устарела.");yield completeNewExpense(beforeCommand,Money.of(newExpenseDraft().amount()));}
            case "/new_plan_custom" -> { if(beforeCommand!=Pending.NEW_EXPENSE_PLAN)yield answer("Кнопка устарела.");pending=Pending.NEW_EXPENSE_INCREASE;yield answer("На сколько увеличить план раздела? Введите положительную сумму, дробную часть через точку."); }
            case "/new_plan_cancel" -> cancelQuick();
            case "/operations" -> operationFilters(false);
            case "/operation_filters" -> operationFilters(true);
            case "/filter_today" -> filterDates(now().toLocalDate(), now().toLocalDate());
            case "/filter_yesterday" -> filterDates(now().toLocalDate().minusDays(1), now().toLocalDate().minusDays(1));
            case "/filter_month" -> filterDates(now().toLocalDate().withDayOfMonth(1), now().toLocalDate());
            case "/filter_all" -> filterDates(null, null);
            case "/filter_period" -> begin(Pending.FILTER_PERIOD, Screen.HISTORY_FILTER, "Введите период ГГГГ-ММ-ДД..ГГГГ-ММ-ДД.");
            case "/filter_categories" -> begin(Pending.FILTER_CATEGORY, Screen.HISTORY_CATEGORY, "Выберите категорию.");
            case "/filter_amount" -> begin(Pending.FILTER_AMOUNT, Screen.HISTORY_SEARCH, "Введите сумму операции. Дробная часть через точку.");
            case "/filter_comment" -> begin(Pending.FILTER_COMMENT, Screen.HISTORY_SEARCH, "Введите слово из комментария.");
            case "/filter_clear" -> { historyKind = ""; historyCategory = ""; historyQuery = ""; historyAmount = ""; historyPage = editorPage = 0; yield showFiltered(); }
            case "/filter_apply" -> showFiltered();
            case "/edit_return" -> returnToOperationList("📜 Выберите операцию.");
            case "/compare_months" -> open(Screen.REPORT, LocalBotViews.compareMonths(combined(), now()));
            case "/edit_operations" -> { editorPage = 0; editorContext = true; yield beginEditOperations(false); }
            case "/history_edit" -> { editorPage = historyPage * 2; yield beginEditOperations(true); }
            case "/edit_date" -> beginEditField(financeos.core.OperationEditor.Field.DATE);
            case "/edit_amount" -> beginEditField(financeos.core.OperationEditor.Field.AMOUNT);
            case "/edit_category" -> beginOperationMove();
            case "/edit_comment" -> beginEditField(financeos.core.OperationEditor.Field.COMMENT);
            case "/edit_confirm" -> confirmOperationEdit();
            case "/edit_cancel" -> returnToOperationCard();
            case "/add" -> open(Screen.ADD, "➕ Добавить операцию. Выберите тип.");
            case "/income" -> beginCategory(EntryKind.INCOME);
            case "/expense" -> beginCategory(EntryKind.EXPENSE);
            case "/transfer" -> begin(Pending.TRANSFER_AMOUNT, Screen.TRANSFER,
                    "Введите «сумма, описание перевода». Внутренний перевод не меняет общий баланс.");
            case "/delete_operation" -> { editorPage = 0; yield beginEditOperations(false); }
            case "/edit_delete" -> prepareOperationDelete();
            case "/edit_delete_confirm" -> confirmOperationDelete();
            case "/edit_delete_cancel" -> cancelOperationDelete();
            case "/chat_cleanup" -> { pending = Pending.NONE; pendingCategory = ""; yield open(Screen.CHAT_CLEAN,
                    "🧹 Очистка чата\nУдаляются сообщения VK, включая ваши. Баланс, операции, планы и резервные копии сохраняются.\nАвтоочистка · " + (chatCleanup.days() == 0 ? "выключена" : "старше " + chatCleanup.days() + " дн.") + "\nУдаление сообщений нельзя отменить кнопкой «Отменить»."); }
            case "/chat_clear" -> begin(Pending.CHAT_CLEAR_CONFIRM, Screen.CHAT_CLEAR_CONFIRM,
                    "🗑 Удалить ваши сообщения и ответы бота для всех участников этой беседы?\nУдаление необратимо. Финансовые данные останутся. Последние служебные сообщения и ещё не обработанные запросы сохранятся. VK может запретить удаление отдельных сообщений.");
            case "/chat_clear_confirm" -> {
                if (beforeCommand != Pending.CHAT_CLEAR_CONFIRM || navigation.current() != Screen.CHAT_CLEAR_CONFIRM) yield open(Screen.CHAT_CLEAN, "Сначала нажмите «Очистить сейчас» и подтвердите удаление.");
                pending = Pending.NONE; pendingCategory = "";
                yield open(Screen.CHAT_CLEAR_RESULT, "⏳ Запрос очистки подтверждён. Финансовые данные сохраняются.");
            }
            case "/chat_auto" -> { pending = Pending.NONE; yield open(Screen.CHAT_AUTO,
                    "⏱ Удалять ваши сообщения и ответы бота старше выбранного срока.\nПроверка — при обращении к боту и существующем утреннем запуске, не чаще раза в день. Это не точный таймер удаления. Финансовая история сохраняется."); }
            case "/chat_auto_1", "/chat_auto_7", "/chat_auto_30" -> {
                pendingCategory = text.substring("/chat_auto_".length()); pending = Pending.CHAT_AUTO_CONFIRM;
                yield open(Screen.CHAT_AUTO_CONFIRM, "Включить необратимое удаление ваших сообщений и ответов бота старше " + pendingCategory + " дн.?\nПосле включения старые сообщения могут быть удалены при ближайшей обработке. Финансовые записи сохраняются. VK может отказать в удалении отдельных сообщений.");
            }
            case "/chat_auto_confirm" -> {
                if (beforeCommand != Pending.CHAT_AUTO_CONFIRM || navigation.current() != Screen.CHAT_AUTO_CONFIRM) yield open(Screen.CHAT_CLEAN, "Сначала выберите срок автоочистки.");
                chatCleanup = new ChatCleanupSettings(Integer.parseInt(pendingCategory), ""); pending = Pending.NONE; pendingCategory = "";
                yield open(Screen.CHAT_CLEAN, "✅ Автоочистка включена · старше " + chatCleanup.days() + " дн. Можно выключить в настройках чата.");
            }
            case "/chat_auto_off" -> { chatCleanup = ChatCleanupSettings.off(); pending = Pending.NONE; pendingCategory = ""; yield open(Screen.CHAT_CLEAN, "✅ Автоочистка выключена. Уже удалённые сообщения не восстанавливаются."); }
            case "/sections" -> sectionsHome();
            case "/defaults_setup" -> offerCategorySetup();
            case "/defaults_confirm" -> confirmCategorySetup(false);
            case "/defaults_skip" -> confirmCategorySetup(true);
            case "/sections_prune" -> previewUnusedCategories();
            case "/sections_prune_confirm" -> confirmUnusedCategories();
            case "/section_pick" -> {pending=Pending.SECTION_PICK;navigation.open(Screen.OPERATION_SECTIONS);yield answer("Выберите раздел для управления.");}
            case "/section_new" -> {saveSectionDraft(new SectionDraft("","","","",YearMonth.from(now()).toString(),"",""));yield sectionInput(Pending.SECTION_NEW_NAME,"Введите название нового раздела, до 40 символов.");}
            case "/section_rename" -> sectionInput(Pending.SECTION_RENAME_NAME,"Введите новое название раздела.");
            case "/section_delete" -> prepareSectionChange("DELETE_SECTION","");
            case "/section_categories" -> {pending=Pending.SECTION_CATEGORY_PICK;navigation.open(Screen.OPERATION_SECTIONS);yield answer("Выберите категорию раздела «"+sectionDraft().section()+"».");}
            case "/category_new" -> sectionInput(Pending.CATEGORY_NEW_NAME,"Введите название новой категории в разделе «"+sectionDraft().section()+"».");
            case "/category_rename" -> sectionInput(Pending.CATEGORY_RENAME_NAME,"Введите новое название категории. История будет показана под новым названием.");
            case "/category_move" -> {pending=Pending.CATEGORY_MOVE_TARGET;navigation.open(Screen.OPERATION_SECTIONS);yield answer("Выберите новый раздел категории.");}
            case "/category_delete" -> prepareSectionChange("ARCHIVE_CATEGORY","");
            case "/category_archive_list" -> {pending=Pending.ARCHIVED_CATEGORY_PICK;navigation.open(Screen.OPERATION_SECTIONS);yield answer("Выберите категорию для восстановления из архива.");}
            case "/section_confirm" -> confirmSectionChange(beforeCommand);
            case "/plan_sections" -> planSections();
            case "/plan_categories" -> {var month=selectedPlanMonth();savePlanDraft(new PlanDraft(month.toString(),"","","",""));yield open(Screen.PLAN_FACT,withMonthEnd(LocalBotViews.planFact(new financeos.core.DebtBudgetService().project(manualData(),operationLinks,now(),month),now(),month),month));}
            case "/report_sections" -> open(Screen.REPORT,SectionViews.report(combined(),categorySections,planEdits,now(),true));
            case "/report_categories" -> reportExpenseCategories();
            case "/chart_section_level", "/chart_category_level" -> {try{if(navigation.current()!=Screen.CHART_LEVEL)throw new IllegalArgumentException();yield chartAtLevel(ChartKind.valueOf(sectionDraft().kind()),text.equals("/chart_section_level"));}catch(RuntimeException invalid){yield open(Screen.CHARTS,"Выберите вид диаграммы заново.");}}
            case "/report_menu" -> { pending = Pending.NONE; yield open(Screen.REPORT_OPTIONS, "📊 Отчёт. С диаграммой расходов или только текст?"); }
            case "/report_chart" -> reportWithChart();
            case "/chart_theme" -> { pending = Pending.NONE; yield open(Screen.CHART_THEME, "🎨 Тема диаграмм · " + (chartTheme.equals("dark") ? "тёмная" : "светлая") + "\nVK не передаёт тему приложения обычному боту. Выберите фон кнопкой."); }
            case "/chart_theme_dark", "/chart_theme_light" -> { pending = Pending.NONE; chartTheme = text.endsWith("dark") ? "dark" : "light"; yield open(Screen.CHART_THEME, "✅ Тема диаграмм сохранена · " + (chartTheme.equals("dark") ? "тёмная" : "светлая")); }
            case "/report" -> report();
            case "/report_date" -> begin(Pending.REPORT_DATE, Screen.REPORT,
                    "Введите дату отчёта в формате ГГГГ-ММ-ДД.");
            case "/report_period" -> begin(Pending.REPORT_PERIOD, Screen.REPORT,
                    "Введите период отчёта: ГГГГ-ММ-ДД..ГГГГ-ММ-ДД.");
            case "/plans" -> open(Screen.PLANS, "📋 Планы. Выберите действие.");
            case "/plan_fact" -> { pendingCategory = ""; yield open(Screen.PLAN_FACT, "📋 План / факт. Выберите месяц."); }
            case "/plan_copy_previous", "/plan_copy_current" -> beginPlanCopy();
            case "/plan_copy_next" -> beginPlanCopy(YearMonth.from(now()).plusMonths(1));
            case "/plan_copy_month" -> begin(Pending.PLAN_COPY_MONTH, Screen.PLAN_COPY_MONTH, "Выберите месяц кнопкой или введите ГГГГ-ММ. Можно текущий или будущий месяц.");
            case "/plan_copy_edit" -> editPlanCopy();
            case "/plan_copy_confirm" -> confirmPlanCopy();
            case "/plan_copy_cancel" -> cancelPlanCopy();
            case "/plan_edit" -> beginPlanEdit(selectedPlanMonth());
            case "/plan_edit_current" -> beginPlanEdit(YearMonth.from(now()));
            case "/plan_edit_name" -> planEditInput(Pending.PLAN_EDIT_NAME);
            case "/plan_edit_amount" -> planEditInput(Pending.PLAN_EDIT_AMOUNT);
            case "/plan_edit_cancel" -> planEditCard();
            case "/plan_edit_confirm" -> confirmPlanEdit(beforeCommand);
            case "/plan_fact_current" -> planFact(YearMonth.from(now()));
            case "/plan_fact_previous" -> planFact(YearMonth.from(now()).minusMonths(1));
            case "/plan_fact_month" -> begin(Pending.PLAN_FACT_MONTH, Screen.PLAN_FACT_MONTH,
                    "Выберите месяц кнопкой или введите любой месяц в формате ГГГГ-ММ, например 2026-10.");
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
            case "/chart_expenses" -> expenseChartBundle(Screen.CHARTS);
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
            case "/settings" -> open(Screen.SETTINGS, "⚙️ Ещё · выберите раздел.");
            case "/preferences" -> open(Screen.PREFERENCES, "⚙️ Настройки · выберите раздел.");
            case "/feedback" -> answer("Обратная связь будет доступна после подключения приёма обращений. Сейчас обращение не отправлено.");
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
                    "Выберите ожидаемую операцию кнопкой для подтверждения.");
            case "/regular_skip" -> begin(Pending.SKIP_RULE, Screen.RECURRING,
                    "Выберите ожидаемую операцию кнопкой, чтобы пропустить этот месяц.");
            case "/regular_delete" -> begin(Pending.DELETE_RULE, Screen.RECURRING,
                    "Выберите правило кнопкой для удаления.");
            default -> answer("Выберите действие кнопкой. Для возврата в главное меню отправьте /start.");
        };
    }

    private record QuickDraft(String nonce, String kind, String amount, String comment,
                              String category, String sourceId, String expected) {}
    private static boolean isQuick(Pending p) { return p==Pending.QUICK_CATEGORY || p==Pending.QUICK_ALL_CATEGORY || p==Pending.REPEAT_AMOUNT; }
    private boolean quickPending() { return isQuick(pending); }
    private QuickDraft quickDraft() {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().readValue(pendingCategory, QuickDraft.class); }
        catch (Exception invalid) { throw new IllegalArgumentException("Быстрый ввод устарел. Введите сумму и комментарий заново."); }
    }
    private void saveQuickDraft(QuickDraft draft) {
        try { pendingCategory = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(draft); }
        catch (java.io.IOException invalid) { throw new IllegalStateException(invalid); }
        cachedChoiceValues = null;
    }
    private Optional<BotReply> beginQuick(String text) {
        try {
            var draft = new financeos.core.QuickOperationParser().parse(text);
            saveQuickDraft(new QuickDraft(java.util.UUID.randomUUID().toString(), draft.kind().name(),
                    draft.amount().toPlainString(), draft.comment(), "", "", ""));
            pending = Pending.QUICK_CATEGORY;
            navigation.open(Screen.ADD);
            return answer(quickPrompt());
        } catch (IllegalArgumentException invalid) {
            return answer("Быстрый ввод: −450, кофе или +50000, зарплата. Дробная часть через точку: −450.50, кофе. Сумма больше нуля, комментарий до 500 символов. Операция не сохранена.");
        }
    }
    private String quickPrompt() {
        var d = quickDraft();
        return (d.kind().equals("INCOME") ? "💰 Доход" : "💸 Расход") + " · " + PlanFactView.money(Money.of(d.amount()))
                + "\n" + d.comment() + "\n\nВыберите категорию. "
                + (pending==Pending.QUICK_ALL_CATEGORY ? "Все категории." : "Сначала недавние; остальные — кнопкой «Все категории».");
    }
    private List<String> quickCategories(boolean recent) {
        var kind = EntryKind.valueOf(quickDraft().kind());
        var all = kind==EntryKind.INCOME ? incomeSources() : categories().stream().filter(c -> !incomeSource(c) && !debtCategory(c)).toList();
        if (!recent) return all;
        var chosen = new LinkedHashMap<String,String>();
        manualData().transactions().stream().filter(t -> !t.timestamp().isAfter(now()) && t.entry().kind()==kind && !operationLinks.containsKey(t.entry().id()))
                .sorted(java.util.Comparator.comparing(LegacyUserData.TimedEntry::timestamp).reversed()).forEach(t -> {
                    String name = effectiveCategory(t.entry().category(), YearMonth.from(now()));
                    all.stream().filter(c -> categoryKey(c).equals(categoryKey(name))).findFirst().ifPresent(c -> chosen.putIfAbsent(categoryKey(c), c));
                });
        return (chosen.isEmpty() ? all : List.copyOf(chosen.values())).stream().limit(5).toList();
    }
    private Optional<BotReply> quickCategoryMode(Pending previous, boolean all) {
        if (previous!=Pending.QUICK_CATEGORY && previous!=Pending.QUICK_ALL_CATEGORY) return answer("Эта кнопка устарела. Введите новую операцию.");
        pending = all ? Pending.QUICK_ALL_CATEGORY : Pending.QUICK_CATEGORY;
        return answer(quickPrompt());
    }
    private Optional<BotReply> cancelQuick() {
        pending = Pending.NONE; pendingCategory = ""; navigation.main();
        return answer("Ввод отменён. Финансовые данные не изменены.");
    }
    private Optional<BotReply> saveQuick(String category) {
        var d = quickDraft(); var kind = EntryKind.valueOf(d.kind());
        var match = quickCategories(false).stream().filter(c -> categoryKey(c).equals(categoryKey(category))).findFirst();
        if (match.isEmpty()) return answer("Выберите существующую категорию кнопкой. Новые категории создаются через «Ещё» → «Разделы».");
        pending = kind==EntryKind.INCOME ? Pending.INCOME_CATEGORY : Pending.EXPENSE_CATEGORY;
        pendingCategory = "";
        if (categorySections.enabled()) saveSectionDraft(new SectionDraft(kind==EntryKind.INCOME ? "Доходы" : sectionOf(match.get(),YearMonth.from(now())), "", "", "", YearMonth.from(now()).toString(),kind.name(),""));
        selectOperationCategory(match.get());
        if (pending!=Pending.INCOME_AMOUNT && pending!=Pending.EXPENSE_AMOUNT) return answer("Не удалось выбрать категорию. Начните ввод заново.");
        var result = addOperation(d.amount() + ", " + d.comment());
        if (pending==Pending.NONE) { navigation.main(); return answer(result.orElseThrow().text()); }
        return result;
    }
    private boolean repeatable(String id) {
        try { var e=findOperation(id).entry(); return !operationLinks.containsKey(id) && (e.kind()==EntryKind.INCOME || e.kind()==EntryKind.EXPENSE) && !debtCategory(e.category()) && !categorySections.hidden(e.category()); }
        catch (IllegalArgumentException missing) { return false; }
    }
    private Optional<BotReply> beginRepeat() {
        if (navigation.current()!=Screen.EDITOR_CARD || !repeatable(pendingCategory)) return answer("Повтор доступен для обычных доходов и расходов. Платежи по долгам и целям добавляются через их разделы.");
        String id=pendingCategory; var e=findOperation(id).entry();
        saveQuickDraft(new QuickDraft(java.util.UUID.randomUUID().toString(),e.kind().name(),"",e.description(),effectiveCategory(e.category(),YearMonth.from(now())),id,editFingerprint(id)));
        pending=Pending.REPEAT_AMOUNT;
        return answer("🔁 Повтор операции\nКатегория · " + e.category() + "\nКомментарий · " + (e.description().isBlank()?"—":e.description()) + "\nВведите новую сумму без знака, дробную часть через точку. Дата новой операции — сегодня. Можно изменить комментарий: сумма, комментарий.");
    }
    private Optional<BotReply> repeatAmount(String text) {
        var d=quickDraft();
        if (!repeatable(d.sourceId()) || !editFingerprint(d.sourceId()).equals(d.expected())) return cancelQuick();
        try {
            var amount = text.contains(",") ? draftParser.parse(text).amount() : positiveMoney(text.strip());
            String comment=text.contains(",") ? draftParser.parse(text).description() : d.comment();
            if(comment.length()>500 || amount.precision()>17)throw new IllegalArgumentException();
            // Existing amount/comment parser requires text. A blank original comment stays blank below.
            String category=quickCategoriesForRepeat(d);
            pending=Pending.QUICK_CATEGORY;
            saveQuickDraft(new QuickDraft(d.nonce(),d.kind(),amount.toPlainString(),comment,category,"",""));
            if(comment.isBlank()) return saveBlankRepeat(category,amount,EntryKind.valueOf(d.kind()));
            return saveQuick(category);
        } catch(IllegalArgumentException invalid) { return answer("Введите положительную сумму, дробную часть через точку. Либо сумма, новый комментарий (до 500 символов)."); }
    }
    private String quickCategoriesForRepeat(QuickDraft d) {
        return quickCategories(false).stream().filter(c->categoryKey(c).equals(categoryKey(d.category()))).findFirst().orElseThrow(()->new IllegalArgumentException("Категория недоступна"));
    }
    private Optional<BotReply> saveBlankRepeat(String category,BigDecimal amount,EntryKind kind) {
        undo.remember(snapshot());var at=now();localEntries.add(new LegacyUserData.TimedEntry(at,new LedgerEntry("local-"+nextEntryId++,at.toLocalDate(),kind,amount,category,"",false)));
        pending=Pending.NONE;pendingCategory="";navigation.main();return answer("✅ "+(kind==EntryKind.INCOME?"Доход":"Расход")+" сохранён\n"+UiText.flow(kind,amount)+" · "+category+"\nНажмите «Отменить», чтобы отменить.");
    }

    private Optional<BotReply> beginCategory(EntryKind kind) {
        if(kind==EntryKind.INCOME){
            if(categorySections.enabled())saveSectionDraft(new SectionDraft("Доходы","","","",YearMonth.from(now()).toString(),"INCOME",""));
            else pendingCategory="";
            pending=Pending.INCOME_CATEGORY;navigation.open(Screen.INCOME);
            return answer("💰 Выберите источник дохода. Новый источник можно написать сообщением.");
        }
        if(categorySections.enabled()) {
            saveSectionDraft(new SectionDraft("","","","",YearMonth.from(now()).toString(),kind.name(),""));
            pending=kind==EntryKind.INCOME?Pending.INCOME_SECTION:Pending.EXPENSE_SECTION;
            navigation.open(Screen.OPERATION_SECTIONS);return answer("📂 Выберите раздел.");
        }
        navigation.open(kind == EntryKind.INCOME ? Screen.INCOME : Screen.EXPENSE);
        pending = kind == EntryKind.INCOME ? Pending.INCOME_CATEGORY : Pending.EXPENSE_CATEGORY;
        return answer("Выберите категорию кнопкой. Новую можно написать сообщением.");
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
            case QUICK_CATEGORY, QUICK_ALL_CATEGORY -> saveQuick(text);
            case REPEAT_AMOUNT -> repeatAmount(text);
            case INCOME_CATEGORY, EXPENSE_CATEGORY -> selectOperationCategory(text);
            case INCOME_SECTION, EXPENSE_SECTION -> selectOperationSection(text);
            case SECTION_PICK -> sectionCard(text);
            case SECTION_CATEGORY_PICK -> categoryCard(text);
            case CATEGORY_MOVE_TARGET -> prepareSectionChange("MOVE_CATEGORY",text);
            case ARCHIVED_CATEGORY_PICK -> {if(text.length()>80 || !categorySections.archived().contains(text))yield answer("Выберите категорию из архива кнопкой.");saveSectionDraft(new SectionDraft(categorySections.section(text),text,"","","","",""));yield prepareSectionChange("RESTORE_CATEGORY",text);}
            case SECTION_NEW_NAME -> prepareSectionChange("CREATE_SECTION",text);
            case SECTION_RENAME_NAME -> prepareSectionChange("RENAME_SECTION",text);
            case CATEGORY_NEW_NAME -> prepareSectionChange("CREATE_CATEGORY",text);
            case CATEGORY_RENAME_NAME -> prepareSectionChange("RENAME_CATEGORY",text);
            case SECTION_CONFIRM -> answer("Подтвердите изменение кнопкой или отмените его.");
            case PLAN_SECTION_PICK -> {
                var context=sectionDraft();var month=YearMonth.parse(context.month());
                if(context.kind().equals("EDIT_PLAN"))yield planEditCategoryPicker(month,text);
                yield planSection(month,text);
            }
            case PAY_DEBT_AMOUNT -> payDebt(pendingCategory + ";" + text);
            case FUND_GOAL_AMOUNT -> fundGoal(pendingCategory + ";" + text);
            case EDIT_DEBT_VALUES -> editDebt(pendingCategory + ";" + text);
            case PLAN_AMOUNT -> addPlan(text + "," + pendingCategory);
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
            case EDIT_SELECT, HISTORY_EDIT_SELECT -> operationCard(text);
            case FILTER_PERIOD -> {
                try {
                    String[] dates = text.split("\\.\\.", -1);
                    if (dates.length != 2) throw new IllegalArgumentException();
                    LocalDate from = LocalDate.parse(dates[0].strip()), through = LocalDate.parse(dates[1].strip());
                    if (from.isAfter(through)) throw new IllegalArgumentException();
                    yield filterDates(from, through);
                } catch (RuntimeException invalid) { yield answer("Неверный период. Пример: 2026-11-01..2026-11-05."); }
            }
            case NEW_EXPENSE_PLAN -> answer("Выберите, планировать ли расход: можно сохранить его без изменения плана.");
            case NEW_EXPENSE_INCREASE -> {try{yield completeNewExpense(pending,positiveMoney(text));}catch(IllegalArgumentException invalid){yield answer("Введите положительную сумму через точку.");}}
            case REPORT_EXPENSE_CATEGORY -> expenseCategoryHistory(text);
            case FILTER_CATEGORY -> { historyCategory = text; historyPage = editorPage = 0; yield showFiltered(); }
            case FILTER_AMOUNT -> {
                try { BigDecimal amount = positiveMoney(text); historyAmount = amount.toPlainString(); historyPage = editorPage = 0; yield showFiltered(); }
                catch (RuntimeException invalid) { yield answer("Введите положительную сумму числом, дробную часть через точку."); }
            }
            case FILTER_COMMENT -> {
                if (text.isBlank() || text.length() > 100) yield answer("Введите от 1 до 100 символов комментария.");
                historyQuery = text; historyPage = editorPage = 0; yield showFiltered();
            }
            case EDIT_OPERATION_SECTION, EDIT_OPERATION_CATEGORY -> selectOperationMove(text);
            case EDIT_DATE -> prepareOperationEdit(financeos.core.OperationEditor.Field.DATE, text);
            case EDIT_AMOUNT -> prepareOperationEdit(financeos.core.OperationEditor.Field.AMOUNT, text);
            case EDIT_COMMENT -> prepareOperationEdit(financeos.core.OperationEditor.Field.COMMENT, text);
            case PLAN_COPY_MONTH -> { try { yield beginPlanCopy(YearMonth.parse(text.strip())); } catch (RuntimeException invalid) { yield answer("Введите месяц в формате ГГГГ-ММ, текущий или будущий."); } }
            case PLAN_COPY_ITEM -> selectPlanCopyItem(text);
            case PLAN_COPY_VALUES -> changePlanCopyItem(text);
            case PLAN_EDIT_SELECT -> selectPlanEdit(text);
            case PLAN_EDIT_NAME -> preparePlanEdit(text, true);
            case PLAN_EDIT_AMOUNT -> preparePlanEdit(text, false);
            case PLAN_EDIT_CONFIRM -> answer("Подтвердите правку кнопкой «Сохранить» или отмените её.");
            case CHAT_CLEAR_CONFIRM, CHAT_AUTO_CONFIRM -> answer("Используйте кнопки подтверждения или отмены.");
            case PLAN_COPY_CONFIRM -> answer("Проверьте план и нажмите «Копировать» или «Отмена».");
            case EDIT_DELETE_CONFIRM -> answer("Проверьте операцию и нажмите «Да, удалить» или «Отмена».");
            case EDIT_CONFIRM -> answer("Проверьте изменение и нажмите «Сохранить изменение» или «Отменить правку».");
            case PLAN_FACT_MONTH -> {
                try {
                    if (!text.matches("[0-9]{4}-[0-9]{2}")) throw new IllegalArgumentException();
                    YearMonth month = YearMonth.parse(text);
                    if (month.getYear() < 1) throw new IllegalArgumentException();
                    pending = Pending.NONE;
                    // Return from the input screen without adding duplicate navigation levels.
                    navigation.back();
                    yield planFact(month);
                } catch (java.time.DateTimeException | IllegalArgumentException invalid) {
                    yield answer("Неверный месяц. Выберите кнопку или введите ГГГГ-ММ, например 2026-10.");
                }
            }
            case RECEIPT_INPUT -> receiptInput(text);
            case NONE -> answer("/start — главное меню.");
        };
    }

    private List<String> receiptCategories(){return categories().stream().filter(c->!incomeSource(c)&&!debtCategory(c)&&!categorySections.hidden(c)).toList();}
    private ReceiptFlow.Draft receiptDraft(){try{return new com.fasterxml.jackson.databind.ObjectMapper().readValue(pendingCategory,ReceiptFlow.Draft.class);}catch(java.io.IOException invalid){throw new IllegalArgumentException("Receipt draft expired");}}
    private Optional<BotReply> receiveReceipt(VkEvent event){
        if(event.photoUrls().size()!=1)return receiptResult(ReceiptFlow.render(ReceiptFlow.blank(now().toLocalDate()),receiptCategories(),"Пришлите одно фото одного чека. Коллажи и несколько фотографий пока не поддерживаются."));
        try{return receiptText(receiptReader.read(event));}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();return receiptResult(ReceiptFlow.render(ReceiptFlow.blank(now().toLocalDate()),receiptCategories(),"Распознавание прервано. Введите итог чека вручную или пришлите фото заново."));}
        catch(java.io.IOException|IllegalArgumentException failure){return receiptResult(ReceiptFlow.render(ReceiptFlow.blank(now().toLocalDate()),receiptCategories(),"Не удалось распознать чек. Проверьте подключение OCR или введите итог вручную. Деньги не списаны."));}
    }
    private Optional<BotReply> receiptText(String text){
        try{var parsed=new financeos.core.ReceiptTextParser().parse(text,now().toLocalDate());return receiptResult(ReceiptFlow.render(ReceiptFlow.parsed(parsed),receiptCategories(),"Проверьте распознанные данные."));}
        catch(IllegalArgumentException invalid){return receiptResult(ReceiptFlow.render(ReceiptFlow.blank(now().toLocalDate()),receiptCategories(),"Итог или дата чека не определены однозначно. Введите сумму вручную."));}
    }
    private Optional<BotReply> receiptInput(String text){
        var draft=receiptDraft();if(draft.phase().equals("INPUT")&&!text.startsWith("/")&&(text.contains("\n")||text.matches("(?isu).*(?:итог|к\\s+оплате|сумма|total).*")))return receiptText(text);
        return receiptResult(ReceiptFlow.step(draft,text,receiptCategories()));
    }
    private Optional<BotReply> receiptResult(ReceiptFlow.Result result){
        var d=result.draft();
        if(result.commit()){
            String prefix="receipt-"+d.fingerprint()+"-";
            if(localEntries.stream().anyMatch(t->t.entry().id().startsWith(prefix))){pending=Pending.NONE;pendingCategory="";navigation.main();return answer("Этот чек уже был сохранён. Повторные расходы не добавлены.");}
            if(ReceiptFlow.remaining(d).signum()!=0||d.parts().isEmpty()||d.parts().stream().anyMatch(p->Money.of(p.amount()).signum()<=0||!receiptCategories().contains(p.category())))throw new IllegalArgumentException("Invalid receipt allocation");
            var date=LocalDate.parse(d.date());if(date.isAfter(now().toLocalDate())||date.getYear()<2000)return receiptResult(ReceiptFlow.render(ReceiptFlow.change(d,"DATE","",false,false,0),receiptCategories(),"Проверьте дату: расход по чеку не может быть в будущем или ранее 2000 года."));var timestamp=date.atTime(12,0).atZone(MOSCOW).toOffsetDateTime();if(date.equals(now().toLocalDate()))timestamp=now();
            var entries=new ArrayList<LegacyUserData.TimedEntry>();int index=0;
            for(var part:d.parts())entries.add(new LegacyUserData.TimedEntry(timestamp,new LedgerEntry(prefix+(++index),date,EntryKind.EXPENSE,Money.of(part.amount()),part.category(),"Чек · "+d.store()+" · "+d.fingerprint().substring(0,8),false)));
            undo.remember(snapshot());localEntries.addAll(entries);pending=Pending.NONE;pendingCategory="";navigation.main();
            return answer("✅ Чек сохранён · "+PlanFactView.money(Money.of(d.total()))+"\nРасходов по категориям · "+entries.size()+"\nОперации связаны номером чека. «Отменить» отменит весь ввод чека одним действием.");
        }
        if(result.cancelled()){pending=Pending.NONE;pendingCategory="";navigation.main();}
        else {try{pendingCategory=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(d);}catch(java.io.IOException impossible){throw new IllegalStateException(impossible);}pending=Pending.RECEIPT_INPUT;}
        return Optional.of(new BotReply(result.text(),navigation.current(),null,result.buttons()));
    }

    private record NewExpenseDraft(String section,String category,String amount,String comment,String fingerprint,String month,String nonce) {}
    private NewExpenseDraft newExpenseDraft(){try{return new com.fasterxml.jackson.databind.ObjectMapper().readValue(pendingCategory,NewExpenseDraft.class);}catch(Exception bad){throw new IllegalArgumentException("Черновик устарел");}}
    private BigDecimal sectionPlan(String section){return manualData().plans().stream().filter(p->p.month().equals(YearMonth.from(now()))&&sectionOf(p.category(),p.month()).equals(section)).map(BudgetPlan::amount).reduce(BigDecimal.ZERO,BigDecimal::add);}
    private Optional<BotReply> completeNewExpense(Pending previous,BigDecimal increase){return completeNewExpense(previous,increase,true);}
    private void recordNewCategoryExpense(NewExpenseDraft draft){
        categorySections=categorySections.assign(draft.category(),draft.section());
        var at=now();localEntries.add(new LegacyUserData.TimedEntry(at,new LedgerEntry("local-"+nextEntryId++,at.toLocalDate(),EntryKind.EXPENSE,Money.of(draft.amount()),draft.category(),draft.comment(),false)));
        pending=Pending.NONE;pendingCategory="";navigation.main();
    }
    private Optional<BotReply> completeNewExpense(Pending previous,BigDecimal increase,boolean planned){
        if(previous!=Pending.NEW_EXPENSE_PLAN && previous!=Pending.NEW_EXPENSE_INCREASE)return answer("Кнопка устарела.");
        var d=newExpenseDraft();var month=YearMonth.from(now());
        if(!month.toString().equals(d.month())||!planEditor.fingerprint(manualData().plans(),month).equals(d.fingerprint())){pending=Pending.NONE;return answer("План изменился. Введите расход заново, чтобы проверить суммы.");}
        if(!planned){
            if(categorySections.hidden(d.category())||!categorySections.allSections().contains(d.section())
                    ||!categoryKey(categorySections.canonical(d.category())).equals(categoryKey(d.category()))
                    ||(categoryKnown(d.category())&&!sectionOf(d.category(),month).equals(d.section()))){
                pending=Pending.NONE;pendingCategory="";navigation.main();return answer("Категория или раздел изменились. Введите расход заново.");
            }
            undo.remember(snapshot());recordNewCategoryExpense(d);
            return answer("✅ Незапланированный расход сохранён · "+PlanFactView.money(Money.of(d.amount()))+" · "+d.category()+"\nПланы остались прежними. Расход учтён в балансе и незапланированных расходах.\nДля отмены нажмите «Отменить».");
        }
        BigDecimal amount=Money.of(d.amount()),need=amount.subtract(increase).max(BigDecimal.ZERO),left=need;
        var changes=new ArrayList<financeos.core.PlanEdit>();
        for(var line:new financeos.core.BudgetService().breakdown(month,manualData().plans(),PlanFactView.entries(manualData(),now())))if(sectionOf(line.category(),month).equals(d.section())&&!debtCategory(line.category())&&left.signum()>0){var take=line.remaining().min(left);if(take.signum()>0){changes.add(new financeos.core.PlanEdit(month,line.category(),line.category(),line.planned().subtract(take),true));left=left.subtract(take);}}
        if(left.signum()>0){pending=Pending.NEW_EXPENSE_PLAN;return answer("В плане раздела недостаточно свободного остатка: не хватает "+PlanFactView.money(left)+". Увеличьте план или выберите «Не планировать». Расход пока не сохранён.");}
        BigDecimal before=sectionPlan(d.section());undo.remember(snapshot());planEdits.addAll(changes);localPlans.add(new BudgetPlan(month,d.category(),amount.max(increase)));
        recordNewCategoryExpense(d);
        return answer("✅ Расход сохранён · "+PlanFactView.money(amount)+" · "+d.category()+"\nПлан раздела был · "+PlanFactView.money(before)+"\nПрибавка · "+PlanFactView.money(increase)+"\nПлан стал · "+PlanFactView.money(before.add(increase))+"\nПри включении в текущий план сумма перераспределена из свободных остатков категорий раздела.\nДля отмены нажмите «Отменить».");
    }

    private Optional<BotReply> addOperation(String text) {
        try {
            var draft = draftParser.parse(text);
            EntryKind kind = pending == Pending.INCOME_AMOUNT ? EntryKind.INCOME : EntryKind.EXPENSE;
            String selectedCategory=operationCategory();String selectedSection=operationSection();
            if(kind==EntryKind.EXPENSE && !selectedSection.isBlank()) {
                var context=sectionDraft();
                var saved=new NewExpenseDraft(selectedSection,selectedCategory,draft.amount().toPlainString(),draft.description(),planEditor.fingerprint(manualData().plans(),YearMonth.from(now())),YearMonth.from(now()).toString(),java.util.UUID.randomUUID().toString());
                try{pendingCategory=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(saved);}catch(java.io.IOException bad){throw new IllegalStateException(bad);}pending=Pending.NEW_EXPENSE_PLAN;
                return answer("Новая категория · "+selectedCategory+"\nРасход · "+PlanFactView.money(draft.amount())+"\nРаздел · "+selectedSection+"\nПлан сейчас · "+PlanFactView.money(sectionPlan(selectedSection))+"\nКак учесть расход? Можно сохранить его без планирования.");
            }
            undo.remember(snapshot());
            if(!selectedSection.isBlank()){
                if(selectedSection.equals("Доходы")&&categorySections.allSections().stream().noneMatch(s->categoryKey(s).equals(categoryKey("Доходы"))))categorySections=categorySections.create("Доходы");
                categorySections=categorySections.assign(selectedCategory,selectedSection);
            }
            pendingCategory=selectedCategory;
            OffsetDateTime at = now();
            localEntries.add(new LegacyUserData.TimedEntry(at,
                    new LedgerEntry("local-" + nextEntryId++, at.toLocalDate(), kind,
                            draft.amount(), selectedCategory, draft.description(), false)));
            pending = Pending.NONE;
            if(categorySections.enabled())navigation.main();else navigation.back();
            return answer("✅ " + (kind == EntryKind.INCOME ? "Доход" : "Расход") + " сохранён\n"
                    + UiText.flow(kind, draft.amount()) + " · " + pendingCategory + "\n" + draft.description()
                    + "\nНажмите «Отменить», чтобы отменить.");
        } catch (IllegalArgumentException invalid) {
            return answer("Введите «сумма, описание». Например: 1250.50, продукты."
                    + " Дробную часть пишите через точку. Комментарий — до 500 символов.");
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
            return answer("Внутренний перевод · " + money(draft.amount())
                    + " ₽ записан локально. Общий баланс не изменился. Нажмите «Отменить», чтобы отменить.");
        } catch (IllegalArgumentException invalid) {
            return answer("Введите «сумма, описание перевода». Например: 1000.50, на накопления."
                    + " Дробную часть пишите через точку.");
        }
    }

    private Optional<BotReply> deleteOperation(String text) {
        // Compatibility with an old persisted input step: still require confirmation.
        operationCard(text);
        return prepareOperationDelete();
    }

    private Optional<BotReply> addPlan(String text) {
        try {
            var draft = draftParser.parse(text);
            if (draft.description().length() > 80) throw new IllegalArgumentException();
            YearMonth month = YearMonth.from(now());
            String key = categoryKey(effectiveCategory(draft.description(), month));
            boolean exists = combined().plans().stream().anyMatch(plan ->
                    plan.month().equals(month) && categoryKey(plan.category()).equals(key));
            if (exists) return answer("Для этой категории план на месяц уже есть. Дубликат не создан.");
            String category = categoryCatalog().ensure(effectiveCategory(draft.description(), month));
            undo.remember(snapshot());
            localPlans.add(new BudgetPlan(month, category, draft.amount()));
            pending = Pending.NONE;
            return answer("План «" + category + "» · " + money(draft.amount())
                    + " ₽ добавлен локально. Нажмите «Отменить», чтобы отменить.");
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
            if (plan.month().equals(month) && (categoryKey(plan.category()).equals(key) || categoryKey(effectiveCategory(plan.category(), month)).equals(key))) {
                index = i;
                break;
            }
        }
        if (index < 0) return answer("Локальный план с этой категорией не найден."
                + " Планы из исходной таблицы здесь не удаляются.");
        String displayed = effectiveCategory(localPlans.get(index).category(), month);
        if (debtCategory(displayed)) return answer("Кредитная строка управляется карточкой долга.");
        undo.remember(snapshot());
        var removed = localPlans.remove(index);
        String source = removed.category();
        var retained = new ArrayList<financeos.core.PlanEdit>();
        for (var edit : planEdits) {
            if (edit.month().equals(month) && categoryKey(edit.source()).equals(categoryKey(source))) source = edit.category();
            else retained.add(edit);
        }
        planEdits.clear(); planEdits.addAll(retained);
        pending = Pending.NONE;
        return answer("Локальный план «" + displayed + "» удалён. Нажмите «Отменить», чтобы отменить.");
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
            return answer("Долг «" + debt.name() + "» добавлен локально. Нажмите «Отменить», чтобы отменить.");
        } catch (RuntimeException invalid) {
            return answer("Введите «Название; остаток; ежемесячный платёж; день платежа»."
                    + " Суммы больше нуля, день от 1 до 31.");
        }
    }

    private Optional<BotReply> editDebt(String text) {
        try {
            String[] fields = parts(text, 4);
            int index = debtIndex(fields[0]);
            if (index < 0) return answer("Долг не найден. Нажмите «Список долгов» и проверьте название.");
            BigDecimal remaining = Money.of(fields[1]);
            if (remaining.signum() < 0) throw new IllegalArgumentException("Negative balance");
            BigDecimal monthly = positiveMoney(fields[2]);
            int day = Integer.parseInt(fields[3]);
            var before = debtState.get(index);
            var after = new Debt(before.name(), remaining, monthly, day, remaining.signum() > 0);
            undo.remember(snapshot());
            debtState.set(index, after);
            pending = Pending.NONE;
            return answer("Долг «" + after.name() + "» уточнён · остаток · "
                    + money(after.remaining()) + " ₽ · платёж · " + money(after.monthlyPayment())
                    + " ₽, день " + after.paymentDay() + ". Баланс и история операций не изменились."
                    + " Нажмите «Отменить», чтобы отменить.");
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
            String operationId = "local-" + nextEntryId++;
            localEntries.add(new LegacyUserData.TimedEntry(at,
                    new LedgerEntry(operationId, at.toLocalDate(), EntryKind.EXPENSE,
                            amount, before.name(), "Платёж по долгу", false)));
            operationLinks.put(operationId, new financeos.core.OperationLink(
                    financeos.core.OperationLink.Type.DEBT_PAYMENT, before.name()));
            pending = Pending.NONE;
            return answer("Платёж · " + money(amount) + " ₽ учтён локально. Остаток долга «"
                    + before.name() + "» · " + money(after.remaining()) + " ₽."
                    + " Фактический баланс уменьшился на сумму платежа. Нажмите «Отменить», чтобы отменить.");
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
            return answer("Долг «" + debt.name() + "» закрыт локально. Нажмите «Отменить», чтобы отменить.");
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
            return answer("Цель «" + goal.name() + "» добавлена локально. Нажмите «Отменить», чтобы отменить.");
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
            OffsetDateTime at = now();
            String operationId = "local-" + nextEntryId++;
            localEntries.add(new LegacyUserData.TimedEntry(at, new LedgerEntry(operationId,
                    at.toLocalDate(), EntryKind.EXPENSE, amount, before.name(), "Пополнение цели", false)));
            operationLinks.put(operationId, new financeos.core.OperationLink(
                    financeos.core.OperationLink.Type.GOAL_FUND, before.name()));
            pending = Pending.NONE;
            return answer("Цель «" + before.name() + "» · пополнение · "
                    + money(amount) + " ₽. Накоплено · " + money(after.current())
                    + " ₽. Фактический баланс уменьшился на сумму пополнения. Нажмите «Отменить», чтобы отменить.");
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
        return answer("Цель «" + before.name() + "» закрыта локально. Нажмите «Отменить», чтобы отменить.");
    }

    private Optional<BotReply> setLimit(String text) {
        try {
            BigDecimal amount = Money.of(text);
            if (amount.signum() < 0) throw new IllegalArgumentException();
            undo.remember(snapshot());
            limitState.put(YearMonth.from(now()), amount);
            pending = Pending.NONE;
            return answer("Дневной лимит на " + YearMonth.from(now()) + " установлен локально: "
                    + money(amount) + " ₽. Нажмите «Отменить», чтобы отменить.");
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
                    : categoryCatalog().ensure(effectiveCategory(fields[2],YearMonth.from(now())));
            String description = kind == EntryKind.INCOME ? fields[2] : fields[3];
            BotUndoState before = snapshot();
            var rule = recurring.add(kind, amount, day, now().toLocalDate(),
                    category, description);
            undo.remember(before);
            pending = Pending.NONE;
            return answer("Правило " + rule.id() + " создано локально: "
                    + money(rule.amount()) + " ₽ каждого " + rule.dayOfMonth()
                    + "-го числа. Запись появится только после подтверждения в день платежа."
                    + " Нажмите «Отменить», чтобы отменить.");
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
            result.append(rule.id()).append(" · ")
                    .append(rule.kind() == EntryKind.INCOME ? "доход" : "расход")
                    .append(" · ").append(money(rule.amount())).append(" ₽ · день · ")
                    .append(rule.dayOfMonth()).append(" · ").append(rule.category())
                    .append(" · ").append(rule.description()).append("\n");
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
            result.append(occurrence.code()).append(" · дата · ")
                    .append(occurrence.date()).append(" · ")
                    .append(rule.kind() == EntryKind.INCOME ? "доход" : "расход")
                    .append(" · ").append(money(rule.amount())).append(" ₽ · ")
                    .append(rule.category()).append("\n");
        }
        if (due.size() > 15) result.append("Показаны 15 из ").append(due.size())
                .append(" ожидающих операций. Подтвердите их, чтобы увидеть следующие.\n");
        result.append("Нажмите «Подтвердить» или «Пропуск», затем выберите операцию.");
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
                + " Нажмите «Отменить», чтобы отменить." : "Операция этого месяца пропущена. Нажмите «Отменить», чтобы отменить.");
    }

    private Optional<BotReply> deleteRecurring(String id) {
        boolean exists = recurring.state().rules().stream().anyMatch(rule -> rule.id().equals(id));
        if (!exists) return answer("Правило с таким ID не найдено.");
        undo.remember(snapshot());
        recurring.delete(id);
        pending = Pending.NONE;
        return answer("Ежемесячное правило " + id + " удалено локально. Нажмите «Отменить», чтобы отменить.");
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

    private Map<String,BigDecimal> reportExpenseTotals() {
        var totals=new java.util.TreeMap<String,BigDecimal>();var month=YearMonth.from(now());
        for(var t:combined().transactions()) if(!t.timestamp().isAfter(now()) && t.entry().kind()==EntryKind.EXPENSE && YearMonth.from(t.entry().date()).equals(month)) totals.merge(t.entry().category(),t.entry().amount(),BigDecimal::add);
        return totals;
    }
    private Optional<BotReply> reportExpenseCategories() {
        pending=Pending.REPORT_EXPENSE_CATEGORY;navigation.open(Screen.REPORT);
        String title=LocalBotViews.report(combined(),now())+"\n\n📋 Расходы по категориям · "+YearMonth.from(now());
        return answer(title+(reportExpenseTotals().isEmpty()?"\nРасходов за текущий месяц нет.":"\nВыберите категорию, чтобы открыть её операции за текущий месяц."));
    }
    private Optional<BotReply> expenseCategoryHistory(String category) {
        var match=reportExpenseTotals().keySet().stream().filter(c->categoryKey(c).equals(categoryKey(category))).findFirst();
        if(match.isEmpty())return answer("В этой категории больше нет расходов за текущий месяц. Выберите другую.");
        historyFrom=now().toLocalDate().withDayOfMonth(1);historyThrough=now().toLocalDate();
        historyCategory=match.get();historyKind="EXPENSE";historyQuery=historyAmount="";historyPage=editorPage=0;editorContext=false;
        return showFiltered();
    }

    private Optional<BotReply> report() {
        pending = Pending.NONE;
        return open(Screen.REPORT, categorySections.enabled()?SectionViews.report(combined(),categorySections,planEdits,now(),true):LocalBotViews.report(combined(), now()));
    }

    private Optional<BotReply> reportWithChart() {
        pending = Pending.NONE;
        return expenseChartBundle(Screen.REPORT);
    }

    private Optional<BotReply> planFact(YearMonth month) {
        pending=Pending.NONE;
        if(categorySections.enabled())return planDetail(month,"",0,java.util.UUID.randomUUID().toString());
        savePlanDraft(new PlanDraft(month.toString(),"","","",""));
        var data=new financeos.core.DebtBudgetService().project(manualData(),operationLinks,now(),month);
        return open(Screen.PLAN_FACT,withMonthEnd(LocalBotViews.planFact(data,now(),month),month));
    }
    private Optional<BotReply> planPage(String command){
        try{
            var draft=sectionDraft();var parts=command.split("/",-1);int page=Integer.parseInt(parts[3]),current=Integer.parseInt(draft.value());
            if(parts.length!=4||pending!=Pending.NONE||!draft.kind().equals("PLAN_VIEW")||!draft.action().equals("DETAIL")||!parts[2].equals(draft.expected())||Math.abs((long)page-current)!=1||page<0||!(navigation.current()==Screen.PLAN_FACT||navigation.current()==Screen.PLAN_SECTION_DETAIL))return answer("Кнопка страницы устарела. Откройте план/факт заново.");
            return planDetail(YearMonth.parse(draft.month()),draft.section(),page,draft.expected());
        }catch(IllegalArgumentException|IndexOutOfBoundsException bad){return answer("Откройте план/факт заново.");}
    }
    private Optional<BotReply> planDetail(YearMonth month,String section,int requestedPage,String token){
        var data=new financeos.core.DebtBudgetService().project(manualData(),operationLinks,now(),month);
        var pages=SectionPlanFactView.pages(data,categorySections,planEdits,now(),month,section);int page=Math.max(0,Math.min(requestedPage,pages.size()-1));
        pending=Pending.NONE;saveSectionDraft(new SectionDraft(section,"","DETAIL",Integer.toString(page),month.toString(),"PLAN_VIEW",token));
        Screen screen=section.isBlank()?Screen.PLAN_FACT:Screen.PLAN_SECTION_DETAIL;if(navigation.current()!=screen)navigation.open(screen);
        var buttons=new ArrayList<MenuButton>();if(page>0)buttons.add(new MenuButton("⬅️ Предыдущие","/plan_page/"+token+"/"+(page-1)));if(page+1<pages.size())buttons.add(new MenuButton("➡️ Следующие","/plan_page/"+token+"/"+(page+1)));
        buttons.add(new MenuButton("📂 Разделы","/plan_sections"));buttons.add(new MenuButton("📋 Категории","/plan_categories"));buttons.add(new MenuButton("✏️ Изменить","/plan_edit"));buttons.add(new MenuButton("📋 Копировать","/plan_copy_previous"));buttons.add(new MenuButton("📅 Месяц","/plan_fact"));buttons.add(new MenuButton("↩️ Отменить","/undo"));buttons.add(new MenuButton("⬅️ Назад","/back"));buttons.add(new MenuButton("🏠 Меню","/start"));
        return Optional.of(new BotReply(withMonthEnd(pages.get(page),month),screen,null,buttons));
    }

    private String withMonthEnd(String text, YearMonth month) {
        String summary;
        try {
            var result = new financeos.core.MonthEndBalanceService().calculate(manualData(), recurring.state(), now(), month, operationLinks);
            summary = result.historical()
                    ? "Баланс на конец месяца · " + PlanFactView.money(result.endingBalance())
                    : "Баланс на конец месяца по плану · " + PlanFactView.money(result.endingBalance())
                      + "\nФактический баланс · " + PlanFactView.money(result.balance())
                      + "\nОжидаемые регулярные доходы · " + PlanFactView.money(result.expectedIncome())
                      + "\nОсталось по плану расходов · " + PlanFactView.money(result.remainingExpenses())
                      + (month.isAfter(YearMonth.from(now())) ? "\nУчитываются месяцы от текущего до выбранного." : "");
        } catch (IllegalArgumentException unavailable) {
            summary = "Баланс на конец месяца · нет данных до начального снимка.";
        }
        int position = text.indexOf("\n\n");
        if (position < 0) return text + "\n\n" + summary;
        return text.substring(0, position) + "\n\n" + summary + text.substring(position);
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
        if(categorySections.enabled()&&(kind==ChartKind.EXPENSES||kind==ChartKind.INCOME||kind==ChartKind.PLAN)) {
            saveSectionDraft(new SectionDraft("","","","",YearMonth.from(now()).toString(),kind.name(),""));
            return open(Screen.CHART_LEVEL,"📊 Выберите уровень диаграммы.");
        }
        return chartAtLevel(kind,false);
    }
    private Optional<BotReply> chartAtLevel(ChartKind kind,boolean grouped) {
        if(grouped&&kind==ChartKind.EXPENSES)return expenseChartBundle(Screen.CHART_LEVEL);
        navigation.open(categorySections.enabled()&&(kind==ChartKind.EXPENSES||kind==ChartKind.INCOME||kind==ChartKind.PLAN)?Screen.CHART_LEVEL:Screen.CHARTS);
        var data = grouped?SectionViews.chart(combined(),categorySections,planEdits,now(),kind):chartService.prepare(kind, combined(), now());
        if (data.labels().isEmpty()) return answer("Для этой диаграммы пока нет данных.");
        return Optional.of(new BotReply(data.title() + ". Диаграмма готова.",
                navigation.current(), chartRenderer.png(data, chartTheme.equals("dark"))));
    }

    private Optional<BotReply> expenseChartPage(String command){return answer("Откройте отчёт заново. Теперь все диаграммы приходят сразу.");}
    private Optional<BotReply> expenseChartBundle(Screen screen){
        var data=combined();var month=YearMonth.from(now());var pages=ExpenseChartPages.build(data,categorySections,planEdits,now(),month);pending=Pending.NONE;
        if(navigation.current()!=screen)navigation.open(screen);
        String text=screen==Screen.REPORT?(categorySections.enabled()?SectionViews.report(data,categorySections,planEdits,now(),true):LocalBotViews.report(data,now())):"Расходы по разделам · "+month+". Диаграммы готовы.";
        var images=new ArrayList<byte[]>();
        if(!pages.isEmpty())images.add(new GroupedExpenseChart().png(pages,chartTheme.equals("dark")));
        if(screen==Screen.REPORT) {
            var income=chartService.prepare(ChartKind.INCOME,data,now());
            if(!income.labels().isEmpty())images.add(chartRenderer.png(income,chartTheme.equals("dark")));
            images.add(chartRenderer.png(monthComparison(data),chartTheme.equals("dark")));
        }
        if(images.isEmpty())return Optional.of(new BotReply(text+"\nРасходов для диаграммы пока нет.",screen));
        return Optional.of(new BotReply(text,screen,images.getFirst(),null,images.subList(1,images.size())));
    }

    private financeos.core.ChartData monthComparison(LegacyUserData data) {
        var month=YearMonth.from(now());
        var totals=new java.util.TreeMap<LocalDate,BigDecimal[]>();
        for(var timed:data.transactions()) {
            var e=timed.entry();
            if(timed.timestamp().isAfter(now()) || !YearMonth.from(e.date()).equals(month)
                    || (e.kind()!=EntryKind.INCOME && e.kind()!=EntryKind.EXPENSE))continue;
            var pair=totals.computeIfAbsent(e.date(),key->new BigDecimal[]{BigDecimal.ZERO,BigDecimal.ZERO});
            int i=e.kind()==EntryKind.INCOME?0:1;pair[i]=pair[i].add(e.amount());
        }
        if(totals.isEmpty())totals.put(now().toLocalDate(),new BigDecimal[]{BigDecimal.ZERO,BigDecimal.ZERO});
        return new financeos.core.ChartData("Доходы и расходы · "+month,
                totals.keySet().stream().map(LocalDate::toString).toList(),
                List.of(new financeos.core.ChartSeries("Доходы, ₽",totals.values().stream().map(v->v[0]).toList()),
                        new financeos.core.ChartSeries("Расходы, ₽",totals.values().stream().map(v->v[1]).toList())));
    }

    private Optional<BotReply> history(LocalDate from, LocalDate through) {
        historyKind = ""; historyCategory = historyQuery = historyAmount = "";
        historyFrom = from;
        historyThrough = through;
        historyPage = 0;
        return open(Screen.HISTORY, LocalBotViews.history(filteredData(), now(), from, through, historyPage, operationLinks));
    }

    private Optional<BotReply> historyPage(int delta) {
        if (navigation.current() != Screen.HISTORY) {
            return answer("Нажмите «История операций» и выберите период.");
        }
        int next = Math.max(0, historyPage + delta);
        int count = LocalBotViews.historyCount(filteredData(), now(), historyFrom, historyThrough);
        if (next * 10 >= count && next != 0) {
            return answer("Это последняя страница истории.\n\n"+LocalBotViews.history(filteredData(),now(),historyFrom,historyThrough,historyPage,operationLinks));
        }
        if(historyPage==0&&delta<0)return answer("Это самые новые операции.\n\n"+LocalBotViews.history(filteredData(),now(),historyFrom,historyThrough,historyPage,operationLinks));
        historyPage = next;
        return answer(LocalBotViews.history(filteredData(), now(), historyFrom, historyThrough, historyPage, operationLinks));
    }
    private List<String> categories() { return categoryCatalog().all().stream().filter(c->!categorySections.hidden(c)).toList(); }

    private List<String> incomeSources(){
        if(cachedIncomeSources!=null)return cachedIncomeSources;
        var catalog=new CategoryCatalog();var month=YearMonth.from(now());
        for(var source:List.of("Зарплата","Премия","Подработка","Проценты","Возврат","Подарок"))catalog.ensure(effectiveCategory(source,month));
        categorySections.assignments().forEach((category,section)->{if(categoryKey(section).equals(categoryKey("Доходы")))catalog.ensure(effectiveCategory(category,month));});
        var data=manualData();
        for(var timed:data.transactions())if(timed.entry().kind()==EntryKind.INCOME){String name=timed.entry().category();
            boolean expense=data.plans().stream().anyMatch(p->categoryKey(p.category()).equals(categoryKey(name)))||data.transactions().stream().anyMatch(t->t.entry().kind()==EntryKind.EXPENSE&&categoryKey(t.entry().category()).equals(categoryKey(name)));
            if(!expense)catalog.ensure(name);
        }
        cachedIncomeSources=catalog.all().stream().filter(c->!categorySections.hidden(c)).toList();return cachedIncomeSources;
    }
    private boolean incomeSource(String name){return incomeSources().stream().anyMatch(c->categoryKey(c).equals(categoryKey(name)));}
    private List<String> expenseSections(){return categorySections.allSections().stream().filter(s->!categoryKey(s).equals(categoryKey("Доходы"))).filter(s->categories().stream().anyMatch(c->!incomeSource(c)&&sectionOf(c,YearMonth.from(now())).equals(s))).toList();}

    private CategoryCatalog categoryCatalog() {
        var catalog=new CategoryCatalog();
        original.extras().categories().forEach(c->catalog.ensure(effectiveCategory(c,YearMonth.from(now()))));
        manualData().plans().forEach(p->catalog.ensure(p.category()));
        combined().plans().forEach(p->catalog.ensure(p.category()));
        combined().transactions().stream().filter(t->t.entry().kind()!=EntryKind.TRANSFER&&!t.entry().category().isBlank()).forEach(t->catalog.ensure(t.entry().category()));
        categorySections.assignments().keySet().forEach(c->catalog.ensure(categorySections.canonical(c)));
        return catalog;
    }
    private String effectiveCategory(String category,YearMonth month) {
        String value=planEditor.resolve(planEdits.stream().filter(e->!e.canonical()).toList(),month,category);
        value=categorySections.canonical(value);
        return planEditor.resolve(planEdits.stream().filter(financeos.core.PlanEdit::canonical).map(e->new financeos.core.PlanEdit(e.month(),categorySections.canonical(e.source()),categorySections.canonical(e.category()),e.amount(),true)).toList(),month,value);
    }

    private String categoryKey(String value) {
        return Normalizer.normalize(value.strip().replaceAll("\\s+", " "),
                Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private record SectionDraft(String section,String category,String action,String value,String month,String kind,String expected) {
        private SectionDraft {section=section==null?"":section;category=category==null?"":category;action=action==null?"":action;value=value==null?"":value;month=month==null?"":month;kind=kind==null?"":kind;expected=expected==null?"":expected;}
    }
    private SectionDraft sectionDraft(){try{return new com.fasterxml.jackson.databind.ObjectMapper().readValue(pendingCategory,SectionDraft.class);}catch(Exception ignored){return new SectionDraft("","","","",YearMonth.from(now()).toString(),"","");}}
    private void saveSectionDraft(SectionDraft d){try{pendingCategory=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(d);}catch(java.io.IOException e){throw new IllegalStateException(e);}}
    private String sectionsFingerprint(){try{String text=categorySections.setup()+"|"+categorySections.names()+"|"+new java.util.TreeMap<>(categorySections.assignments())+"|"+new java.util.TreeMap<>(categorySections.aliases())+"|"+new java.util.TreeSet<>(categorySections.archived());return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private String sectionOf(String name,YearMonth month){return SectionViews.section(categorySections,planEdits,name,month);}
    private boolean categoryKnown(String name){String key=categoryKey(categorySections.canonical(name));return categoryCatalog().all().stream().anyMatch(n->categoryKey(n).equals(key));}
    private String operationCategory(){var d=sectionDraft();return effectiveCategory(d.category().isBlank()?pendingCategory:d.category(),YearMonth.from(now()));}
    private String operationSection(){
        var d=sectionDraft();
        if(d.action().equals("NEW_CATEGORY"))return d.section();
        if(!categorySections.enabled() || !d.kind().equals("EXPENSE") || d.category().isBlank())return "";
        String key=categoryKey(operationCategory());
        boolean planned=manualData().plans().stream().anyMatch(p->p.month().equals(YearMonth.from(now())) && categoryKey(p.category()).equals(key));
        boolean used=manualData().transactions().stream().anyMatch(t->t.entry().kind()==EntryKind.EXPENSE && categoryKey(effectiveCategory(t.entry().category(),YearMonth.from(now()))).equals(key));
        return !planned && !used ? sectionOf(operationCategory(),YearMonth.from(now())) : "";
    }
    private Optional<BotReply> selectOperationSection(String text){
        String kind=pending==Pending.INCOME_SECTION?"INCOME":"EXPENSE";
        if(kind.equals("INCOME")){beginCategory(EntryKind.INCOME);if(categoryKey(text).equals(categoryKey("Доходы")))return answer("💰 Выберите источник дохода.");return selectOperationCategory(text);}
        var selected=expenseSections().stream().filter(n->categoryKey(n).equals(categoryKey(text))).findFirst();
        if(selected.isEmpty()){
            if(categoryKnown(text)){String category=effectiveCategory(text,YearMonth.from(now()));saveSectionDraft(new SectionDraft(sectionOf(category,YearMonth.from(now())),"","","",YearMonth.from(now()).toString(),kind,""));pending=kind.equals("INCOME")?Pending.INCOME_CATEGORY:Pending.EXPENSE_CATEGORY;return selectOperationCategory(category);}
            return answer("Выберите раздел кнопкой. Создать раздел можно через «Ещё» → «Разделы».");
        }
        saveSectionDraft(new SectionDraft(selected.get(),"","","",YearMonth.from(now()).toString(),kind,""));pending=kind.equals("INCOME")?Pending.INCOME_CATEGORY:Pending.EXPENSE_CATEGORY;navigation.open(kind.equals("INCOME")?Screen.INCOME:Screen.EXPENSE);
        return answer("📂 "+selected.get()+"\nВыберите категорию или напишите название новой категории в этом разделе.");
    }
    private Optional<BotReply> selectOperationCategory(String text){
        if(text.isBlank()||text.length()>80)return answer("Введите название категории от 1 до 80 символов.");
        String name=effectiveCategory(text,YearMonth.from(now()));var context=sectionDraft();boolean known=categoryKnown(name);
        boolean income=pending==Pending.INCOME_CATEGORY;String inputNameKey=categoryKey(name);
        if(income&&categoryKey(name).equals(categoryKey("Доходы")))return beginCategory(EntryKind.INCOME);
        if(income&&categorySections.allSections().stream().anyMatch(section->categoryKey(section).equals(inputNameKey))&&!incomeSource(name))return answer("Это раздел расходов. Выберите источник дохода.");
        if(income&&categorySections.enabled()&&categoryKnown(name)&&!incomeSource(name))return answer("Это категория расходов. Выберите источник дохода или напишите новое название.");
        if(!income&&categorySections.enabled()&&incomeSource(name))return answer("Это источник дохода. Добавьте поступление через кнопку «Доход».");
        if(categorySections.hidden(name))return answer("Категория в архиве. Восстановите её через «Ещё» → «Разделы» → «Архив».");
        if(categorySections.enabled()&&!income&&known&&!context.section().isBlank()&&!sectionOf(name,YearMonth.from(now())).equals(context.section()))return answer("Эта категория находится в разделе «"+sectionOf(name,YearMonth.from(now()))+"». Для переноса используйте управление категориями.");
        String kind=pending==Pending.INCOME_CATEGORY?"INCOME":"EXPENSE";name=categoryCatalog().ensure(name);
        pending=kind.equals("INCOME")?Pending.INCOME_AMOUNT:Pending.EXPENSE_AMOUNT;
        if(categorySections.enabled())saveSectionDraft(new SectionDraft(context.section(),name,known?"":"NEW_CATEGORY","",YearMonth.from(now()).toString(),kind,""));else pendingCategory=name;
        return answer("Категория: "+name+". Введите «сумма, описание». Для дробной суммы используйте точку.");
    }
    private boolean needsCategorySetup(){return categorySections.setup().equals("UNSEEN")&&!categorySections.enabled()&&manualData().transactions().isEmpty()&&manualData().plans().isEmpty()&&original.extras().categories().isEmpty()&&debtState.isEmpty()&&goalState.isEmpty()&&recurring.state().rules().isEmpty();}
    private Optional<BotReply> offerCategorySetup(){pending=Pending.NONE;saveSectionDraft(new SectionDraft("","","DEFAULTS","",YearMonth.from(now()).toString(),"",sectionsFingerprint()));return open(Screen.CATEGORY_SETUP,"🏠 Главное меню\nСоздать стандартные разделы и категории расходов? Питание, Жильё, Поездки, Машина, Здоровье, Досуг и Прочее. Источники дохода тоже будут готовы.\nМожно отказаться и настроить всё самостоятельно. В общей беседе набор создаётся один раз для общего бюджета.");}
    private Optional<BotReply> confirmCategorySetup(boolean skip){
      if(navigation.current()!=Screen.CATEGORY_SETUP||!sectionDraft().action().equals("DEFAULTS")||!sectionDraft().expected().equals(sectionsFingerprint()))return answer("Настройка изменилась. Откройте меню заново.");
      if(skip)categorySections=categorySections.skipSetup();else {undo.remember(snapshot());categorySections=financeos.core.CategoryMaintenance.defaults(categorySections);}
      pending=Pending.NONE;navigation.main();return answer(skip?"🏠 Главное меню\nСтандартный набор не создан. Создайте разделы через «Ещё» → «Разделы».":"🏠 Главное меню\nСтандартные разделы и категории созданы. Их можно переименовывать и менять.");
    }
    private Set<String> usedExpenseCategories(){var used=new java.util.HashSet<String>();var data=manualData();data.transactions().stream().filter(t->t.entry().kind()==EntryKind.EXPENSE).forEach(t->used.add(t.entry().category()));data.plans().forEach(p->used.add(p.category()));debtState.stream().filter(Debt::active).forEach(d->used.add(financeos.core.DebtBudgetService.category(d.name())));recurring.state().rules().stream().filter(r->r.kind()==EntryKind.EXPENSE).forEach(r->used.add(effectiveCategory(r.category(),YearMonth.from(now()))));return Set.copyOf(used);}
    private financeos.core.CategorySections prunedCategories(){return financeos.core.CategoryMaintenance.prune(categorySections,categoryCatalog().all().stream().filter(c->!incomeSource(c)).toList(),usedExpenseCategories());}
    private String pruneFingerprint(){return sectionsFingerprint()+"|"+new java.util.TreeSet<>(usedExpenseCategories());}
    private Optional<BotReply> previewUnusedCategories(){var next=prunedCategories();long count=next.archived().size()-categorySections.archived().size();saveSectionDraft(new SectionDraft("","","PRUNE","",YearMonth.from(now()).toString(),"",pruneFingerprint()));pending=Pending.NONE;return open(Screen.CATEGORY_PRUNE,"Убрать неиспользуемые категории · "+count+"?\nПроверены расходы за всё время, планы всех месяцев, активные долги и регулярные платежи. История и суммы сохранятся; категории попадут в архив, пустые разделы будут убраны.");}
    private Optional<BotReply> confirmUnusedCategories(){if(navigation.current()!=Screen.CATEGORY_PRUNE||!sectionDraft().action().equals("PRUNE")||!sectionDraft().expected().equals(pruneFingerprint()))return answer("Данные изменились. Запустите проверку заново.");var next=prunedCategories();if(!next.equals(categorySections)){undo.remember(snapshot());categorySections=next;}return sectionsHome();}
    private Optional<BotReply> sectionsHome(){pending=Pending.NONE;saveSectionDraft(new SectionDraft("","","","",YearMonth.from(now()).toString(),"",""));var text=new StringBuilder("📂 Разделы и категории");for(var name:categorySections.allSections()){long count=categories().stream().filter(c->sectionOf(c,YearMonth.from(now())).equals(name)).count();text.append("\n").append(name).append(" · ").append(count);}return open(Screen.SECTIONS,text.toString());}
    private Optional<BotReply> sectionCard(String name){var chosen=categorySections.allSections().stream().filter(n->categoryKey(n).equals(categoryKey(name))).findFirst();if(chosen.isEmpty())return answer("Выберите существующий раздел.");saveSectionDraft(new SectionDraft(chosen.get(),"","","",YearMonth.from(now()).toString(),"",""));pending=Pending.NONE;return open(Screen.SECTION_CARD,"📂 "+chosen.get()+"\nСоздавайте категории, переименовывайте раздел или удаляйте его с подтверждением.");}
    private Optional<BotReply> categoryCard(String name){String category=categorySections.canonical(name);var d=sectionDraft();if(!categoryKnown(category)||categorySections.hidden(category)||!sectionOf(category,YearMonth.from(now())).equals(d.section()))return answer("Выберите категорию этого раздела.");saveSectionDraft(new SectionDraft(d.section(),category,"","",d.month(),"",""));pending=Pending.NONE;return open(Screen.SECTION_CATEGORY_CARD,"Категория · "+category+"\nРаздел · "+d.section());}
    private Optional<BotReply> sectionInput(Pending input,String prompt){return begin(input,Screen.SECTION_INPUT,prompt);}
    private Optional<BotReply> prepareSectionChange(String action,String value){try{
        var d=sectionDraft();value=value.strip().replaceAll("\\s+"," ");if(!action.equals("CREATE_SECTION")&&!action.equals("RESTORE_CATEGORY")&&(d.section().isBlank()||!categorySections.allSections().contains(d.section())))return answer("Сначала выберите раздел.");
        if(java.util.Set.of("RENAME_CATEGORY","MOVE_CATEGORY","ARCHIVE_CATEGORY").contains(action)&&(d.category().isBlank()||!categoryKnown(d.category())))return answer("Сначала выберите категорию.");if((action.contains("CREATE")||action.contains("RENAME"))&&(value.isBlank()||value.codePointCount(0,value.length())>(action.endsWith("SECTION")?40:80)))return answer("Название слишком длинное или пустое.");
        if((action.equals("CREATE_CATEGORY")||action.equals("RENAME_CATEGORY"))&&categoryKnown(value)&&!categoryKey(categorySections.canonical(value)).equals(categoryKey(d.category())))return answer("Такая категория уже существует, в том числе возможно в архиве. Выберите другое название.");
        saveSectionDraft(new SectionDraft(d.section(),d.category(),action,value,d.month(),d.kind(),sectionsFingerprint()));pending=Pending.SECTION_CONFIRM;
        String detail=switch(action){case "DELETE_SECTION"->"Удалить раздел «"+d.section()+"»? Категории перейдут в Прочее.";case "ARCHIVE_CATEGORY"->"Убрать «"+d.category()+"» из выбора новых операций? История и планы сохранятся.";case "MOVE_CATEGORY"->"Перенести «"+d.category()+"» в раздел «"+value+"»?";case "RESTORE_CATEGORY"->"Восстановить категорию «"+d.category()+"»?";default->"Сохранить изменение · "+(d.category().isBlank()?d.section():d.category())+" → "+value+"?";};
        return open(Screen.SECTION_CONFIRM,detail+"\nСуммы и даты операций не меняются. Изменение можно отменить.");
    }catch(RuntimeException invalid){return answer("Откройте управление разделами заново.");}}
    private Optional<BotReply> confirmSectionChange(Pending before){if(before!=Pending.SECTION_CONFIRM||navigation.current()!=Screen.SECTION_CONFIRM)return answer("Нет изменения для подтверждения.");try{
        var d=sectionDraft();if(!d.expected().equals(sectionsFingerprint()))return answer("Разделы изменились. Откройте изменение заново.");
        if((d.action().equals("CREATE_CATEGORY")||d.action().equals("RENAME_CATEGORY"))&&categoryKnown(d.value())&&!categoryKey(categorySections.canonical(d.value())).equals(categoryKey(d.category())))return answer("Категория уже существует. Изменение не сохранено.");
        var next=switch(d.action()){case "CREATE_SECTION"->categorySections.create(d.value());case "RENAME_SECTION"->categorySections.renameSection(d.section(),d.value());case "DELETE_SECTION"->categorySections.deleteSection(d.section());case "CREATE_CATEGORY"->categorySections.assign(d.value(),d.section());case "RENAME_CATEGORY"->categorySections.renameCategory(d.category(),d.value(),d.section());case "MOVE_CATEGORY"->categorySections.assign(d.category(),d.value());case "ARCHIVE_CATEGORY"->categorySections.archive(d.category());case "RESTORE_CATEGORY"->categorySections.assign(d.category(),categorySections.section(d.category()));default->throw new IllegalArgumentException("Неизвестное действие.");};
        if(next.equals(categorySections))return sectionsHome();undo.remember(snapshot());categorySections=next;return sectionsHome().map(r->{var actions=new ArrayList<MenuButton>(r.buttons()==null?MenuCatalog.buttons(r.screen()):r.buttons());actions.add(new MenuButton("↩️ Отменить изменение","/undo"));return new BotReply("✅ Изменение сохранено. Можно отменить.\n"+r.text(),r.screen(),r.imagePng(),actions);});
    }catch(IllegalArgumentException invalid){return answer("Изменение не сохранено. "+invalid.getMessage());}}
    private Optional<BotReply> planSections(){var month=selectedPlanMonth();saveSectionDraft(new SectionDraft("","","","",month.toString(),"PLAN_VIEW",""));pending=Pending.PLAN_SECTION_PICK;navigation.open(Screen.OPERATION_SECTIONS);return answer("📂 Выберите раздел · "+month);}
    private Optional<BotReply> planSection(YearMonth month,String name){if(!categorySections.allSections().contains(name))return answer("Выберите раздел кнопкой.");return planDetail(month,name,0,java.util.UUID.randomUUID().toString());}
    private Optional<BotReply> planEditCategoryPicker(YearMonth month,String section){if(month.isBefore(YearMonth.from(now())))return answer("Изменять можно текущий или будущий месяц.");saveSectionDraft(new SectionDraft(section,"","","",month.toString(),"EDIT_PLAN",""));pending=Pending.PLAN_EDIT_SELECT;navigation.open(Screen.PLAN_EDIT_SELECT);return answer("✏️ Выберите категорию плана · "+month+" · "+section);}

    private record PlanDraft(String month, String source, String category, String amount, String expected) {}
    private PlanDraft planDraft() {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().readValue(pendingCategory, PlanDraft.class); }
        catch (java.io.IOException invalid) { throw new IllegalArgumentException("Нет выбранной категории плана"); }
    }
    private void savePlanDraft(PlanDraft draft) {
        try { pendingCategory = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(draft); }
        catch (java.io.IOException invalid) { throw new IllegalStateException(invalid); }
    }
    private YearMonth selectedPlanMonth() { try { return YearMonth.parse(new com.fasterxml.jackson.databind.ObjectMapper().readTree(pendingCategory).path("month").asText()); } catch (Exception invalid) { return YearMonth.from(now()); } }
    private boolean debtCategory(String name) { return debtState.stream().anyMatch(d -> categoryKey(name).equals(categoryKey(d.name())) || categoryKey(name).equals(categoryKey(financeos.core.DebtBudgetService.category(d.name())))); }
    private List<BudgetPlan> editablePlans(YearMonth month) {
        return new financeos.core.BudgetService().breakdown(month, manualData().plans(), List.of()).stream()
                .filter(p -> !debtCategory(p.category())).map(p -> new BudgetPlan(month, p.category(), p.planned())).toList();
    }
    private Optional<BotReply> beginPlanEdit(YearMonth month) {
        if (month.isBefore(YearMonth.from(now()))) return answer("Изменять план можно за текущий или будущий месяц. Выберите месяц в план/факте.");
        if (editablePlans(month).isEmpty()) return open(Screen.PLANS, "На " + month + " нет обычных категорий плана для изменения. Кредитные строки меняются через карточки долгов.");
        if(categorySections.enabled()) {
            saveSectionDraft(new SectionDraft("","","","",month.toString(),"EDIT_PLAN",""));pending=Pending.PLAN_SECTION_PICK;navigation.open(Screen.OPERATION_SECTIONS);return answer("📂 Выберите раздел плана · "+month);
        }
        savePlanDraft(new PlanDraft(month.toString(), "", "", "", ""));
        pending = Pending.PLAN_EDIT_SELECT; navigation.open(Screen.PLAN_EDIT_SELECT);
        return answer("✏️ Изменить существующий план · " + month + "\nВыберите категорию. Кредитные строки управляются через карточки долгов.");
    }
    private Optional<BotReply> selectPlanEdit(String category) {
        YearMonth month = selectedPlanMonth();
        var selected = editablePlans(month).stream().filter(p -> categoryKey(p.category()).equals(categoryKey(category))).findFirst();
        if (selected.isEmpty()) return answer("Выберите существующую категорию кнопкой.");
        var plan = selected.get();
        savePlanDraft(new PlanDraft(month.toString(), plan.category(), plan.category(), plan.amount().toPlainString(), planEditor.fingerprint(manualData().plans(), month)));
        return planEditCard();
    }
    private Optional<BotReply> planEditCard() {
        try {
            var draft = planDraft(); YearMonth month = YearMonth.parse(draft.month());
            var plan = editablePlans(month).stream().filter(p -> categoryKey(p.category()).equals(categoryKey(draft.source()))).findFirst().orElseThrow();
            savePlanDraft(new PlanDraft(month.toString(), plan.category(), plan.category(), plan.amount().toPlainString(), planEditor.fingerprint(manualData().plans(), month)));
            pending = Pending.NONE;
            return open(Screen.PLAN_EDIT_CARD, "✏️ План · " + month + "\nКатегория · " + plan.category() + "\nСумма · " + money(plan.amount()) + " ₽\nВыберите название или сумму. Факт берётся из истории операций.");
        } catch (RuntimeException invalid) { return answer("Выберите категорию плана заново."); }
    }
    private Optional<BotReply> planEditInput(Pending field) {
        if (navigation.current() != Screen.PLAN_EDIT_CARD) return answer("Сначала выберите категорию в изменении плана.");
        return begin(field, Screen.PLAN_EDIT_INPUT, field == Pending.PLAN_EDIT_NAME ? "Введите новое название категории." : "Введите новую плановую сумму. Дробная часть через точку; ноль допустим.");
    }
    private Optional<BotReply> preparePlanEdit(String text, boolean rename) {
        try {
            var draft = planDraft(); YearMonth month = YearMonth.parse(draft.month());
            String name = rename ? effectiveCategory(text.strip().replaceAll("\\s+", " "),month) : draft.source();
            BigDecimal amount = rename ? Money.of(draft.amount()) : Money.of(text);
            if (name.isBlank() || name.length() > 80 || amount.signum() < 0) throw new IllegalArgumentException();
            if (debtCategory(name)) return answer("Кредитные строки меняются через карточку долга. Выберите другое название.");
            if (editablePlans(month).stream().anyMatch(p -> !categoryKey(p.category()).equals(categoryKey(draft.source())) && categoryKey(p.category()).equals(categoryKey(name)))) return answer("Такая категория в этом месяце уже есть. Дубликат не создан.");
            savePlanDraft(new PlanDraft(draft.month(), draft.source(), name, amount.toPlainString(), draft.expected()));
            pending = Pending.PLAN_EDIT_CONFIRM; navigation.open(Screen.PLAN_EDIT_CONFIRM);
            return answer("Сохранить изменение плана · " + month + "?\nКатегория · " + draft.source() + " → " + name + "\nСумма · " + money(Money.of(draft.amount())) + " → " + money(amount) + " ₽\nФактические операции сохранятся. Расходы этой категории за выбранный месяц будут показаны под новым названием.");
        } catch (RuntimeException invalid) { return answer(rename ? "Введите название от 1 до 80 символов." : "Введите неотрицательную сумму, дробную часть через точку."); }
    }
    private Optional<BotReply> confirmPlanEdit(Pending before) {
        if (before != Pending.PLAN_EDIT_CONFIRM || navigation.current() != Screen.PLAN_EDIT_CONFIRM) return answer("Нет изменения плана для подтверждения.");
        try {
            var draft = planDraft(); YearMonth month = YearMonth.parse(draft.month());
            if (month.isBefore(YearMonth.from(now())) || !draft.expected().equals(planEditor.fingerprint(manualData().plans(), month))) return answer("План или месяц изменился. Отмените правку и выберите категорию заново.");
            if (debtCategory(draft.source()) || debtCategory(draft.category())) return answer("Кредитная категория управляется карточкой долга.");
            if (editablePlans(month).stream().noneMatch(p -> categoryKey(p.category()).equals(categoryKey(draft.source())))) return answer("Исходная категория уже изменилась. Выберите её заново.");
            if (editablePlans(month).stream().anyMatch(p -> !categoryKey(p.category()).equals(categoryKey(draft.source())) && categoryKey(p.category()).equals(categoryKey(draft.category())))) return answer("Такая категория уже есть. Изменение не сохранено.");
            var edit = new financeos.core.PlanEdit(month, draft.source(), draft.category(), Money.of(draft.amount()), !categorySections.aliases().isEmpty());
            undo.remember(snapshot()); planEdits.add(edit); pending = Pending.NONE;
            return planFact(month).map(r -> new BotReply("✅ План изменён. Остаток и свободные деньги пересчитаны. Можно отменить последнее изменение.\n\n" + r.text(), r.screen(), r.imagePng(), r.buttons()));
        } catch (RuntimeException invalid) { return answer("Изменение не сохранено. Выберите категорию плана заново."); }
    }

    private List<BudgetPlan> copyablePlans() {
        return manualData().plans().stream().filter(p -> debtState.stream().noneMatch(d ->
                categoryKey(p.category()).equals(categoryKey(d.name())) || categoryKey(p.category()).equals(categoryKey(financeos.core.DebtBudgetService.category(d.name()))))).toList();
    }

    private record PlanCopyItem(String category, String amount) {}
    private record PlanCopyDraft(String source, String target, String expected, List<PlanCopyItem> items, int selected) {}

    private Optional<BotReply> beginPlanCopy() { return beginPlanCopy(YearMonth.from(now())); }
    private Optional<BotReply> beginPlanCopy(YearMonth target) {
        if (target.isBefore(YearMonth.from(now()))) return answer("Копировать можно в текущий или будущий месяц.");
        pendingCategory = "";
        var service = new financeos.core.PlanCopyService();
        var preview = service.preview(copyablePlans(), target);
        if (preview.additions().isEmpty()) return open(Screen.PLAN_FACT,
                preview.retained() == 0 ? "За " + preview.source() + " плана нет. Копировать нечего."
                        : "Все категории прошлого месяца уже есть в текущем. Ничего не скопировано.");
        var text = new StringBuilder("📋 Копирование плана: ").append(preview.source()).append(" → ").append(target)
                .append("\nБудут добавлены категории: ").append(preview.additions().size());
        BigDecimal total = BigDecimal.ZERO;
        for (var plan : preview.additions()) total = total.add(plan.amount());
        text.append(" на ").append(money(total)).append(" ₽.");
        for (var plan : preview.additions().stream().limit(20).toList()) {
            text.append("\n").append(plan.category()).append(" · ").append(money(plan.amount())).append(" ₽");
        }
        if (preview.additions().size() > 20) text.append("\nПоказаны первые 20 категорий из ").append(preview.additions().size()).append('.');
        text.append("\nУже существующих категорий оставлено без изменений: ").append(preview.retained())
                .append(".\nКопируются полные плановые суммы, расходы не переносятся. Подтвердить?");
        try {
            pendingCategory = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                    new PlanCopyDraft(preview.source().toString(), target.toString(), service.fingerprint(copyablePlans(), target), preview.additions().stream().map(p -> new PlanCopyItem(p.category(), p.amount().toPlainString())).toList(), -1));
        } catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalStateException(invalid); }
        pending = Pending.PLAN_COPY_CONFIRM;
        navigation.open(Screen.PLAN_COPY_CONFIRM);
        return answer(text.toString());
    }

    private Optional<BotReply> confirmPlanCopy() {
        if (navigation.current() != Screen.PLAN_COPY_CONFIRM) return answer("Нет копирования для подтверждения.");
        try {
            var draft = new com.fasterxml.jackson.databind.ObjectMapper().readValue(pendingCategory, PlanCopyDraft.class);
            YearMonth target = YearMonth.parse(draft.target());
            var service = new financeos.core.PlanCopyService();
            if (target.isBefore(YearMonth.from(now())) || !draft.source().equals(target.minusMonths(1).toString())) {
                return answer("Месяц изменился. Отмените и откройте копирование заново.");
            }
            if (!draft.expected().equals(service.fingerprint(copyablePlans(), target))) {
                return answer("План уже изменился. Отмените и откройте копирование заново, чтобы проверить новые суммы.");
            }
            var preview = service.preview(copyablePlans(), target);
            if (preview.additions().isEmpty()) return answer("Новых категорий для копирования нет.");
            var catalog = categoryCatalog();
            var chosen = draft.items() == null ? preview.additions().stream().map(p -> new PlanCopyItem(p.category(), p.amount().toPlainString())).toList() : draft.items();
            var used = new java.util.HashSet<String>();
            manualData().plans().stream().filter(p -> p.month().equals(target)).forEach(p -> used.add(categoryKey(p.category())));
            var additions = new ArrayList<BudgetPlan>();
            for (var item : chosen) {
                if (!used.add(categoryKey(item.category()))) return answer("Категория уже есть в целевом месяце. Измените название, чтобы не создавать дубликат.");
                additions.add(new BudgetPlan(target, catalog.ensure(item.category()), Money.of(item.amount())));
            }
            undo.remember(snapshot());
            localPlans.addAll(additions);
            pending = Pending.NONE;
            pendingCategory = "";
            navigation.back();
            savePlanDraft(new PlanDraft(target.toString(), "", "", "", ""));
            return open(Screen.PLAN_FACT, "✅ План скопирован: " + preview.source() + " → " + target
                    + ". Добавлено категорий: " + additions.size() + "."
                    + " Существующие категории не изменены. Для отмены нажмите «Отменить».\n\n"
                    + withMonthEnd(LocalBotViews.planFact(combined(), now(), target), target));
        } catch (java.io.IOException | IllegalArgumentException | java.time.DateTimeException invalid) {
            return answer("Копирование не выполнено. Отмените и подготовьте его заново.");
        }
    }

    private PlanCopyDraft planCopyDraft() {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().readValue(pendingCategory, PlanCopyDraft.class); }
        catch (java.io.IOException invalid) { throw new IllegalArgumentException("Нет подготовленного копирования"); }
    }
    private void savePlanCopyDraft(PlanCopyDraft draft) {
        try { pendingCategory = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(draft); }
        catch (java.io.IOException invalid) { throw new IllegalStateException(invalid); }
    }
    private Optional<BotReply> editPlanCopy() {
        try { var draft = planCopyDraft(); if (draft.items() == null || draft.items().isEmpty()) return answer("Подготовьте копирование заново.");
            pending = Pending.PLAN_COPY_ITEM; navigation.open(Screen.PLAN_COPY_EDIT);
            return answer("Выберите категорию для изменения названия и суммы. До подтверждения план не меняется.");
        } catch (IllegalArgumentException invalid) { return answer("Сначала подготовьте копирование."); }
    }
    private Optional<BotReply> selectPlanCopyItem(String category) {
        var draft = planCopyDraft();
        int index = -1;
        for (int i = 0; i < draft.items().size(); i++) if (draft.items().get(i).category().equals(category)) index = i;
        if (index < 0) return answer("Выберите категорию кнопкой.");
        savePlanCopyDraft(new PlanCopyDraft(draft.source(), draft.target(), draft.expected(), draft.items(), index));
        pending = Pending.PLAN_COPY_VALUES;
        return answer("Введите «название; сумма». Сейчас · " + category + " · " + draft.items().get(index).amount() + " ₽. Дробная часть через точку.");
    }
    private Optional<BotReply> changePlanCopyItem(String text) {
        try {
            var draft = planCopyDraft(); var fields = parts(text, 2);
            String name = effectiveCategory(fields[0].strip().replaceAll("\\s+", " "),YearMonth.parse(draft.target()));
            BigDecimal amount = Money.of(fields[1]);
            if (name.isBlank() || name.length() > 80 || amount.signum() < 0 || draft.selected() < 0) throw new IllegalArgumentException();
            if (debtState.stream().anyMatch(d -> categoryKey(name).equals(categoryKey(d.name())) || categoryKey(name).equals(categoryKey(financeos.core.DebtBudgetService.category(d.name()))))) return answer("План кредита формируется из карточки долга автоматически. Выберите другое название обычной категории.");
            for (int i = 0; i < draft.items().size(); i++) if (i != draft.selected() && categoryKey(draft.items().get(i).category()).equals(categoryKey(name))) return answer("Такая категория уже есть в копии. Выберите уникальное название.");
            YearMonth target = YearMonth.parse(draft.target());
            if (manualData().plans().stream().anyMatch(p -> p.month().equals(target) && categoryKey(p.category()).equals(categoryKey(name)))) return answer("Такая категория уже есть в целевом месяце. Дубликат не создан.");
            var items = new ArrayList<>(draft.items()); items.set(draft.selected(), new PlanCopyItem(name, amount.toPlainString()));
            savePlanCopyDraft(new PlanCopyDraft(draft.source(), draft.target(), draft.expected(), items, -1));
            pending = Pending.PLAN_COPY_CONFIRM; navigation.open(Screen.PLAN_COPY_CONFIRM);
            var view = new StringBuilder("📋 Предпросмотр · ").append(draft.source()).append(" → ").append(draft.target());
            for (var item : items) view.append("\n").append(item.category()).append(" · ").append(item.amount()).append(" ₽");
            return answer(view.append("\nИзменения ещё не сохранены. Подтвердите копирование.").toString());
        } catch (RuntimeException invalid) { return answer("Введите «название; сумма», например «Продукты; 12000.50». Сумма не может быть отрицательной."); }
    }

    private Optional<BotReply> cancelPlanCopy() {
        pending = Pending.NONE;
        pendingCategory = "";
        if (navigation.current() == Screen.PLAN_COPY_CONFIRM) navigation.back();
        return open(Screen.PLAN_FACT, "Копирование отменено. План не изменён. Выберите месяц.");
    }

    private Optional<BotReply> operationFilters(boolean edit) {
        editorContext = edit; historyPage = editorPage = 0;
        historyKind = ""; historyCategory = historyQuery = historyAmount = "";
        pending = Pending.NONE;
        return open(Screen.HISTORY_FILTER, "📅 За какой период показать операции?");
    }
    private Optional<BotReply> filterDates(LocalDate from, LocalDate through) {
        historyFrom = from; historyThrough = through; historyPage = editorPage = 0;
        pending = Pending.NONE;
        return open(Screen.HISTORY_SEARCH, "🔎 Как найти операцию?\nМожно выбрать категорию или искать по сумме и комментарию.");
    }
    private Optional<BotReply> showFiltered() {
        pending = Pending.NONE; cachedChoiceValues = null;
        if (editorContext) return beginEditOperations(true);
        return open(Screen.HISTORY, LocalBotViews.history(filteredData(), now(), historyFrom, historyThrough, historyPage, operationLinks));
    }
    private java.util.List<LedgerEntry> filteredOperations(boolean useFilters) {
        var entries = LocalBotViews.historyEntries(combined(), now(), useFilters ? historyFrom : null, useFilters ? historyThrough : null);
        if (!useFilters) return entries;
        return entries.stream().filter(e -> historyKind.isBlank() || e.kind().name().equals(historyKind)).filter(e -> historyCategory.isBlank() || categoryKey(e.category()).equals(categoryKey(historyCategory)))
                .filter(e -> historyQuery.isBlank() || categoryKey(e.description()).contains(categoryKey(historyQuery)))
                .filter(e -> historyAmount.isBlank() || e.amount().compareTo(new BigDecimal(historyAmount)) == 0).toList();
    }
    private LegacyUserData filteredData() {
        var data = combined();
        var ids = filteredOperations(true).stream().map(LedgerEntry::id).collect(java.util.stream.Collectors.toSet());
        return new LegacyUserData(data.startingBalance(), data.snapshotTime(), data.transactions().stream()
                .filter(e -> ids.contains(e.entry().id())).toList(), data.plans(), data.extras());
    }
    private Optional<BotReply> returnToOperationList(String message) {
        while (navigation.current() == Screen.EDITOR_CARD || navigation.current() == Screen.EDITOR_INPUT || navigation.current() == Screen.EDITOR_CONFIRM || navigation.current() == Screen.EDITOR_DELETE_CONFIRM) navigation.back();
        pending = editorContext ? Pending.HISTORY_EDIT_SELECT : Pending.EDIT_SELECT;
        cachedChoiceValues = null; pendingCategory = "";
        navigation.open(editorContext ? Screen.HISTORY_EDITOR_LIST : Screen.EDITOR_LIST);
        return answer(message + "\nФильтры и страница сохранены.");
    }

    private record EditDraft(String id, financeos.core.OperationEditor.Field field, String value, String expected) {}

    private Optional<BotReply> beginEditOperations(boolean fromHistory) {
        cachedChoiceValues = null;
        pending = fromHistory ? Pending.HISTORY_EDIT_SELECT : Pending.EDIT_SELECT;
        pendingCategory = "";
        editorContext = fromHistory;
        navigation.open(fromHistory ? Screen.HISTORY_EDITOR_LIST : Screen.EDITOR_LIST);
        return answer(choiceValues().isEmpty() ? "Операций для изменения пока нет."
                : "✏️ Изменение операций. Выберите запись. Перед сохранением можно проверить правку.");
    }

    private LegacyUserData.TimedEntry findOperation(String id) {
        return operationEditor.operations(original, localEntries).stream()
                .filter(item -> item.entry().id().equals(id) && !deletedIds.contains(id) && !item.timestamp().isAfter(now())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Операция не найдена. Выберите запись заново."));
    }

    private EntryKind displayKind(LedgerEntry entry) {
        var link = operationLinks.get(entry.id());
        return link != null && link.type() == financeos.core.OperationLink.Type.GOAL_FUND ? EntryKind.EXPENSE : entry.kind();
    }

    private String operationType(String id) {
        var link = operationLinks.get(id);
        if (link != null) return link.type() == financeos.core.OperationLink.Type.DEBT_PAYMENT
                ? "Платёж по долгу «" + link.target() + "»" : "Пополнение цели «" + link.target() + "»";
        return switch (findOperation(id).entry().kind()) {
            case INCOME -> "Доход";
            case EXPENSE -> "Расход";
            case TRANSFER -> "Внутренний перевод";
        };
    }

    private Optional<BotReply> operationCard(String id) {
        try {
            var entry = findOperation(id).entry();
            pending = Pending.NONE;
            cachedChoiceValues = null;
            pendingCategory = id;
            navigation.open(Screen.EDITOR_CARD);
            return answer("✏️ " + operationType(id) + "\nДата · " + entry.date() + "\nСумма · "
                    + UiText.flow(displayKind(entry), entry.amount()) + "\nРаздел · " + sectionOf(entry.category(),YearMonth.from(entry.date())) + "\nКатегория · " + entry.category()
                    + "\nКомментарий: " + (entry.description().isBlank() ? "—" : entry.description())
                    + "\nВыберите, что изменить.");
        } catch (IllegalArgumentException invalid) { return answer(invalid.getMessage()); }
    }

    private record MoveDraft(String id,String section) {}
    private MoveDraft moveDraft() {
        try{return new com.fasterxml.jackson.databind.ObjectMapper().readValue(pendingCategory,MoveDraft.class);}
        catch(java.io.IOException invalid){throw new IllegalArgumentException("Перенос устарел. Выберите операцию заново.");}
    }
    private void saveMoveDraft(MoveDraft draft) {
        try{pendingCategory=new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(draft);}
        catch(java.io.IOException invalid){throw new IllegalStateException(invalid);}
    }
    private List<String> moveCategories() {
        var operation=findOperation(moveDraft().id()).entry();
        return categories().stream().filter(c->operation.kind()==EntryKind.TRANSFER
                || (operation.kind()==EntryKind.INCOME?incomeSource(c):!incomeSource(c))).toList();
    }
    private Optional<BotReply> beginOperationMove() {
        if(navigation.current()!=Screen.EDITOR_CARD)return beginEditOperations(editorContext);
        var id=pendingCategory;findOperation(id);saveMoveDraft(new MoveDraft(id,""));
        pending=Pending.EDIT_OPERATION_SECTION;navigation.open(Screen.EDITOR_INPUT);
        return answer("📂 Перенести в категорию\nВыберите раздел кнопкой или сразу напишите название категории.");
    }
    private Optional<BotReply> selectOperationMove(String text) {
        var draft=moveDraft();
        var category=moveCategories().stream().filter(c->categoryKey(c).equals(categoryKey(text))).findFirst();
        if(category.isPresent())return prepareOperationEdit(financeos.core.OperationEditor.Field.CATEGORY,category.get());
        if(pending==Pending.EDIT_OPERATION_SECTION) {
            var section=moveCategories().stream().map(c->sectionOf(c,YearMonth.from(now()))).filter(n->categoryKey(n).equals(categoryKey(text))).findFirst();
            if(section.isPresent()) {saveMoveDraft(new MoveDraft(draft.id(),section.get()));pending=Pending.EDIT_OPERATION_CATEGORY;
                return answer("📂 "+section.get()+"\nВыберите категорию кнопкой или напишите название.");}
        }
        return answer("Категория не найдена. Выберите существующую кнопкой или введите её название.");
    }

    private Optional<BotReply> beginEditField(financeos.core.OperationEditor.Field field) {
        if(field==financeos.core.OperationEditor.Field.CATEGORY)return beginOperationMove();
        if (navigation.current() != Screen.EDITOR_CARD) return beginEditOperations(false);
        try { findOperation(pendingCategory); }
        catch (IllegalArgumentException invalid) { return beginEditOperations(false); }
        return begin(switch (field) {
            case DATE -> Pending.EDIT_DATE;
            case AMOUNT -> Pending.EDIT_AMOUNT;
            case COMMENT -> Pending.EDIT_COMMENT;
            case CATEGORY -> Pending.EDIT_OPERATION_CATEGORY;
        }, Screen.EDITOR_INPUT, switch (field) {
            case DATE -> "Введите новую дату ГГГГ-ММ-ДД. Будущая дата недопустима.";
            case AMOUNT -> "Введите новую сумму. Для дробной части используйте точку.";
            case CATEGORY -> "Выберите категорию кнопкой или напишите название.";
            case COMMENT -> "Введите новый комментарий (до 500 символов). Отправьте минус (-), чтобы очистить его.";
        });
    }

    private String editFingerprint(String id) {
        try {
            String value = findOperation(id).toString() + "\n" + operationLinks.get(id);
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private Optional<BotReply> prepareOperationEdit(financeos.core.OperationEditor.Field field, String value) {
        try {
            String id = pending==Pending.EDIT_OPERATION_SECTION || pending==Pending.EDIT_OPERATION_CATEGORY ? moveDraft().id() : pendingCategory;
            var old = findOperation(id);
            var change = operationEditor.change(original, localEntries, debtState, goalState,
                    operationLinks, id, field, value, now());
            var replacement = change.entries().stream().filter(item -> item.entry().id().equals(id)).findFirst().orElseThrow();
            if (old.equals(replacement)) return operationCard(id);
            String before = switch (field) {
                case DATE -> old.entry().date().toString();
                case AMOUNT -> money(old.entry().amount()) + " ₽";
                case CATEGORY -> sectionOf(old.entry().category(),YearMonth.from(old.entry().date()))+" → "+old.entry().category();
                case COMMENT -> old.entry().description().isBlank() ? "—" : old.entry().description();
            };
            String after = switch (field) {
                case DATE -> replacement.entry().date().toString();
                case AMOUNT -> money(replacement.entry().amount()) + " ₽";
                case CATEGORY -> sectionOf(replacement.entry().category(),YearMonth.from(replacement.entry().date()))+" → "+replacement.entry().category();
                case COMMENT -> replacement.entry().description().isBlank() ? "—" : replacement.entry().description();
            };
            pendingCategory = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                    new EditDraft(id, field, value, editFingerprint(id)));
            pending = Pending.EDIT_CONFIRM;
            navigation.open(Screen.EDITOR_CONFIRM);
            return answer("Проверьте изменение: " + operationType(id) + "\nБыло · " + before
                    + "\nСтанет · " + after
                    + "\nПосле подтверждения отчёты и связанные остатки будут пересчитаны. Сохранить?");
        } catch (IllegalArgumentException invalid) { return answer(invalid.getMessage()); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalStateException("Could not prepare edit", invalid); }
    }

    private EditDraft editDraft() {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().readValue(pendingCategory, EditDraft.class); }
        catch (java.io.IOException invalid) { throw new IllegalArgumentException("Правка устарела. Выберите операцию заново."); }
    }

    private Optional<BotReply> returnToOperationCard() {
        if (navigation.current() != Screen.EDITOR_CONFIRM && navigation.current() != Screen.EDITOR_INPUT) {
            return beginEditOperations(false);
        }
        try {
            String id = navigation.current() == Screen.EDITOR_CONFIRM ? editDraft().id() :
                    (pending==Pending.EDIT_OPERATION_SECTION || pending==Pending.EDIT_OPERATION_CATEGORY ? moveDraft().id() : pendingCategory);
            while (navigation.current() == Screen.EDITOR_CONFIRM || navigation.current() == Screen.EDITOR_INPUT) navigation.back();
            return operationCard(id);
        } catch (IllegalArgumentException invalid) { return beginEditOperations(false); }
    }

    private Optional<BotReply> confirmOperationEdit() {
        if (navigation.current() != Screen.EDITOR_CONFIRM) return answer("Нет правки для подтверждения. Выберите операцию заново.");
        try {
            EditDraft draft = editDraft();
            if (!editFingerprint(draft.id()).equals(draft.expected())) {
                return answer("Операция уже изменилась. Отмените эту правку и выберите запись заново.");
            }
            if(draft.field()==financeos.core.OperationEditor.Field.CATEGORY && categories().stream().noneMatch(c->categoryKey(c).equals(categoryKey(draft.value()))))
                return answer("Категория больше недоступна. Отмените правку и выберите другую. Изменение не сохранено.");
            var change = operationEditor.change(original, localEntries, debtState, goalState,
                    operationLinks, draft.id(), draft.field(), draft.value(), now());
            undo.remember(snapshot());
            localEntries.clear();
            localEntries.addAll(change.entries());
            debtState.clear();
            debtState.addAll(change.debts());
            goalState.clear();
            goalState.addAll(change.goals());
            while (navigation.current() == Screen.EDITOR_CONFIRM || navigation.current() == Screen.EDITOR_INPUT) navigation.back();
            return returnToOperationList("✅ Операция изменена. Баланс, планы, отчёты и связанные остатки пересчитаны."
                    + "\nДля отмены нажмите «Отменить».");
        } catch (IllegalArgumentException invalid) { return answer("Изменение не сохранено. " + invalid.getMessage()); }
    }

    private record DeleteDraft(String id, String expected) {}
    private DeleteDraft deleteDraft() {
        try { return new com.fasterxml.jackson.databind.ObjectMapper().readValue(pendingCategory, DeleteDraft.class); }
        catch (java.io.IOException invalid) { throw new IllegalArgumentException("Выберите операцию заново."); }
    }
    private Optional<BotReply> prepareOperationDelete() {
        if (navigation.current() != Screen.EDITOR_CARD) return beginEditOperations(editorContext);
        try {
            String id = pendingCategory;
            var entry = findOperation(id).entry();
            operationEditor.delete(original, localEntries, debtState, goalState, operationLinks, deletedIds, id);
            pendingCategory = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(new DeleteDraft(id, editFingerprint(id)));
            pending = Pending.EDIT_DELETE_CONFIRM;
            navigation.open(Screen.EDITOR_DELETE_CONFIRM);
            return answer("🗑 Удалить операцию?\nДата · " + entry.date() + "\n" + UiText.flow(displayKind(entry), entry.amount())
                    + " · " + entry.category() + "\n" + entry.description()
                    + "\n\nБаланс, планы и связанные остатки будут пересчитаны. Удаление можно отменить кнопкой «Отменить».");
        } catch (IllegalArgumentException invalid) { return answer("Удаление не выполнено. " + invalid.getMessage()); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalStateException("Could not prepare deletion", invalid); }
    }
    private Optional<BotReply> cancelOperationDelete() {
        if (navigation.current() != Screen.EDITOR_DELETE_CONFIRM) return returnToOperationList("Удаление отменено.");
        try { String id = deleteDraft().id(); navigation.back(); return operationCard(id); }
        catch (IllegalArgumentException invalid) { return returnToOperationList("Удаление отменено. Выберите запись заново."); }
    }
    private Optional<BotReply> confirmOperationDelete() {
        if (navigation.current() != Screen.EDITOR_DELETE_CONFIRM) return answer("Нет удаления для подтверждения. Выберите операцию заново.");
        try {
            var draft = deleteDraft();
            if (!editFingerprint(draft.id()).equals(draft.expected())) return answer("Операция уже изменилась. Отмените удаление и выберите её заново.");
            var result = operationEditor.delete(original, localEntries, debtState, goalState, operationLinks, deletedIds, draft.id());
            undo.remember(snapshot());
            deletedIds.clear(); deletedIds.addAll(result.deletedIds());
            debtState.clear(); debtState.addAll(result.debts());
            goalState.clear(); goalState.addAll(result.goals());
            return returnToOperationList("✅ Операция удалена. Баланс, планы и связанные остатки пересчитаны.\nДля восстановления нажмите «Отменить».");
        } catch (IllegalArgumentException invalid) { return answer("Удаление не выполнено. " + invalid.getMessage()); }
    }

    private LegacyUserData manualData() {
        var raw=operationEditor.combined(original, localEntries, localPlans, debtState, goalState, limitState, operationLinks, deletedIds);
        var legacy=planEditor.apply(raw,planEdits.stream().filter(e->!e.canonical()).toList());
        return planEditor.apply(categorySections.apply(legacy),planEdits.stream().filter(financeos.core.PlanEdit::canonical)
                .map(e->new financeos.core.PlanEdit(e.month(),categorySections.canonical(e.source()),categorySections.canonical(e.category()),e.amount(),true)).toList());
    }

    private LegacyUserData combined() { return new financeos.core.DebtBudgetService().project(manualData(), operationLinks, now()); }

    private BotUndoState snapshot() { return new BotUndoState(List.copyOf(localEntries), List.copyOf(localPlans),
            List.copyOf(debtState), List.copyOf(goalState), Map.copyOf(limitState),
            recurring.state(), operationLinks, deletedIds, planEdits, categorySections); }
    private OffsetDateTime now() { return OffsetDateTime.now(clock.withZone(MOSCOW)); }
    private String money(BigDecimal value) { return value.setScale(2).toPlainString(); }
    private Optional<BotReply> open(Screen screen, String text) {
        navigation.open(screen);
        return answer(text);
    }
    private static String boundedReplyText(String text) {
        // Imported text may predate input limits. Reserve room for a clear truncation notice.
        if(text.length()<=8800)return text;
        int end=8800;
        if(Character.isHighSurrogate(text.charAt(end-1)))end--;
        return text.substring(0,end)+"\n… Сообщение сокращено. Уточните период или категорию.";
    }

    private Optional<BotReply> answer(String text) {
        text=boundedReplyText(text);
        cachedChoiceValues = null;
        if (pending==Pending.NONE && (text.contains("«Отменить»") || text.contains("Изменение можно отменить"))) {
            var actions=new ArrayList<MenuButton>();
            if(text.startsWith("✅ Доход сохранён"))actions.add(new MenuButton("➕ Ещё доход","/income"));
            else if(text.startsWith("✅ Расход сохранён"))actions.add(new MenuButton("➕ Ещё расход","/expense"));
            else if(text.startsWith("✅ Чек"))actions.add(new MenuButton("🧾 Ещё чек","/receipt"));
            actions.add(new MenuButton("↩️ Отменить изменение","/undo"));
            actions.add(new MenuButton("🏠 Меню","/start"));
            return Optional.of(new BotReply(text.replace("«Отменить»","«Отменить изменение»"),navigation.current(),null,actions));
        }
        if(pending==Pending.NEW_EXPENSE_PLAN || pending==Pending.NEW_EXPENSE_INCREASE)return Optional.of(new BotReply(text,navigation.current(),null,List.of(new MenuButton("Не планировать","/new_plan_unplanned/"+newExpenseDraft().nonce()),new MenuButton("В текущий план","/new_plan_keep/"+newExpenseDraft().nonce()),new MenuButton("Добавить сумму расхода","/new_plan_expense/"+newExpenseDraft().nonce()),new MenuButton("Другая прибавка","/new_plan_custom/"+newExpenseDraft().nonce()),new MenuButton("❌ Отмена","/new_plan_cancel/"+newExpenseDraft().nonce()))));
        if (pending==Pending.REPEAT_AMOUNT) return Optional.of(new BotReply(text,navigation.current(),null,List.of(new MenuButton("❌ Отмена","/quick_cancel"),new MenuButton("🏠 Меню","/start"))));
        if ((pending==Pending.QUICK_CATEGORY || pending==Pending.QUICK_ALL_CATEGORY) && choiceValues().isEmpty()) return Optional.of(new BotReply(text+"\nПодходящих категорий пока нет. Создайте категорию через «Ещё» → «Разделы».",navigation.current(),null,List.of(new MenuButton("Все категории","/quick_all"),new MenuButton("❌ Отмена","/quick_cancel"),new MenuButton("🏠 Меню","/start"))));
        if(navigation.current()==Screen.HISTORY&&pending==Pending.NONE){
            int count=LocalBotViews.historyCount(filteredData(),now(),historyFrom,historyThrough);int pages=(count+9)/10;
            var buttons=MenuCatalog.buttons(Screen.HISTORY).stream().filter(b->!b.command().equals("/history_prev")||historyPage>0).filter(b->!b.command().equals("/history_next")||historyPage+1<pages).toList();
            if(historyKind.equals("EXPENSE") && !historyCategory.isBlank()) {
                var total=filteredOperations(true).stream().map(LedgerEntry::amount).reduce(BigDecimal.ZERO,BigDecimal::add);
                text="💸 "+historyCategory+" · "+PlanFactView.money(total)+"\n"+text;
                buttons=buttons.stream().map(b->b.command().equals("/operations")?new MenuButton("📋 Категории","/report_categories"):b).toList();
            }
            return Optional.of(new BotReply(text,Screen.HISTORY,null,buttons));
        }
        if (!choiceValues().isEmpty()) return choices(text, pending == Pending.EDIT_SELECT || pending == Pending.HISTORY_EDIT_SELECT ? editorPage : 0);
        if(navigation.current()==Screen.EDITOR_CARD && !repeatable(pendingCategory)) return Optional.of(new BotReply(text,navigation.current(),null,MenuCatalog.buttons(Screen.EDITOR_CARD).stream().filter(b->!b.command().equals("/repeat_operation")).toList()));
        return Optional.of(new BotReply(text, navigation.current()));
    }

    private List<String> choiceValues() {
        if (cachedChoiceValues == null) cachedChoiceValues = computeChoiceValues();
        return cachedChoiceValues;
    }
    private List<String> computeChoiceValues() {
        LocalDate today = now().toLocalDate();
        return switch (pending) {
            case EDIT_OPERATION_SECTION -> moveCategories().stream().map(c->sectionOf(c,YearMonth.from(now()))).distinct().toList();
            case EDIT_OPERATION_CATEGORY -> moveCategories().stream().filter(c->sectionOf(c,YearMonth.from(now())).equals(moveDraft().section())).toList();
            case REPORT_EXPENSE_CATEGORY -> List.copyOf(reportExpenseTotals().keySet());
            case QUICK_CATEGORY -> quickCategories(true);
            case QUICK_ALL_CATEGORY -> quickCategories(false);
            case EDIT_SELECT, HISTORY_EDIT_SELECT -> filteredOperations(pending == Pending.HISTORY_EDIT_SELECT).stream().map(LedgerEntry::id).toList();
            case FILTER_CATEGORY -> LocalBotViews.historyEntries(combined(), now(), historyFrom, historyThrough).stream()
                    .map(LedgerEntry::category).filter(c -> !c.isBlank()).distinct().sorted().toList();
            case PLAN_FACT_MONTH -> {
                var months = new java.util.TreeSet<YearMonth>(java.util.Comparator.reverseOrder());
                months.add(YearMonth.from(now()));
                months.add(YearMonth.from(now()).minusMonths(1));
                combined().plans().forEach(plan -> months.add(plan.month()));
                combined().transactions().forEach(entry -> months.add(YearMonth.from(entry.entry().date())));
                yield months.stream().map(YearMonth::toString).toList();
            }
            case PLAN_EDIT_SELECT -> editablePlans(selectedPlanMonth()).stream().filter(p->sectionDraft().section().isBlank()||sectionOf(p.category(),selectedPlanMonth()).equals(sectionDraft().section())).map(BudgetPlan::category).toList();
            case PLAN_COPY_ITEM -> planCopyDraft().items().stream().map(PlanCopyItem::category).toList();
            case PLAN_COPY_MONTH -> List.of(YearMonth.from(now()).toString(), YearMonth.from(now()).plusMonths(1).toString(), YearMonth.from(now()).plusMonths(2).toString());
            case INCOME_SECTION, INCOME_CATEGORY -> incomeSources();
            case EXPENSE_SECTION -> expenseSections();
            case SECTION_PICK, CATEGORY_MOVE_TARGET, PLAN_SECTION_PICK -> categorySections.allSections();
            case SECTION_CATEGORY_PICK -> categories().stream().filter(c->sectionOf(c,YearMonth.from(now())).equals(sectionDraft().section())).toList();
            case ARCHIVED_CATEGORY_PICK -> categoryCatalog().all().stream().filter(categorySections::hidden).toList();
            case EXPENSE_CATEGORY -> categories().stream().filter(c->!incomeSource(c)).filter(c->sectionDraft().section().isBlank()||sectionOf(c,YearMonth.from(now())).equals(sectionDraft().section())).toList();
            case PLAN -> categories();
            case DELETE_PLAN -> localPlans.stream().filter(p -> p.month().equals(YearMonth.from(now())))
                    .map(BudgetPlan::category).distinct().toList();
            case PAY_DEBT, EDIT_DEBT, CLOSE_DEBT -> debtState.stream().filter(Debt::active).map(Debt::name).toList();
            case FUND_GOAL, CLOSE_GOAL -> goalState.stream().filter(Goal::active).map(Goal::name).toList();
            case DELETE_OPERATION -> localEntries.stream().filter(e -> e.entry().id().startsWith("local-")
                    && !deletedIds.contains(e.entry().id()) && !operationLinks.containsKey(e.entry().id())).map(e -> e.entry().id()).toList();
            case CONFIRM_RULE, SKIP_RULE -> recurring.pendingThrough(today).stream().map(o -> o.code()).toList();
            case DELETE_RULE -> recurring.state().rules().stream().map(r -> r.id()).toList();
            case REPORT_DATE, HISTORY_DATE -> List.of(today.toString(), today.minusDays(1).toString());
            case REPORT_PERIOD, HISTORY_PERIOD -> List.of(today.withDayOfMonth(1) + ".." + today,
                    today.minusMonths(1).withDayOfMonth(1) + ".." + today.withDayOfMonth(1).minusDays(1));
            default -> List.of();
        };
    }

    private Optional<BotReply> choices(String text, int page) {
        List<String> values = choiceValues();
        boolean editor = pending == Pending.EDIT_SELECT || pending == Pending.HISTORY_EDIT_SELECT;
        boolean quick = pending==Pending.QUICK_CATEGORY || pending==Pending.QUICK_ALL_CATEGORY;
        boolean compact = quick || pending==Pending.REPORT_EXPENSE_CATEGORY;
        // Named long choices occupy full rows; reserve three rows for navigation/actions.
        boolean wideChoices=compact && values.stream().anyMatch(v->v.codePointCount(0,v.length())>12);
        int pageSize = editor || (compact && !wideChoices) ? 5 : 3;
        int pages = Math.max(1, (values.size() + pageSize - 1) / pageSize);
        page = Math.max(0, Math.min(page, pages - 1));
        if (editor) editorPage = page;
        var buttons = new ArrayList<MenuButton>();
        var details = new StringBuilder(text).append(editor ? "\nНайдено · " + values.size() : "").append("\n\nВарианты — страница ").append(page + 1).append(" из ").append(pages).append(":");
        for (String value : values.stream().skip(page * (long) pageSize).limit(pageSize).toList()) {
            String encoded = values.indexOf(value) + "/" + choiceFingerprint(value);
            String label = choiceLabel(value);
            int number = values.indexOf(value) + 1;
            details.append("\n").append(number).append(") ").append(label);
            String buttonLabel = editor ? "№" + number : compact ? value : label;
            if(buttonLabel.codePointCount(0,buttonLabel.length())>40)
                buttonLabel=buttonLabel.substring(0,buttonLabel.offsetByCodePoints(0,39))+"…";
            buttons.add(new MenuButton(buttonLabel, "/choose/" + pending.name() + "/" + encoded));
        }
        if (page > 0) buttons.add(new MenuButton("⬅️ Предыдущие", "/choices/" + pending.name() + "/" + (page - 1)));
        if (page + 1 < pages) buttons.add(new MenuButton("➡️ Следующие", "/choices/" + pending.name() + "/" + (page + 1)));
        if (quick) {
            buttons.add(new MenuButton(pending==Pending.QUICK_CATEGORY ? "Все категории" : "Недавние", pending==Pending.QUICK_CATEGORY ? "/quick_all" : "/quick_recent"));
            buttons.add(new MenuButton("❌ Отмена", "/quick_cancel"));
        } else {
            buttons.add(new MenuButton("↩️ Отменить", "/undo"));
            buttons.add(new MenuButton("⬅️ Назад", "/back"));
        }
        buttons.add(new MenuButton("🏠 Меню", "/start"));
        return Optional.of(new BotReply(boundedReplyText(details.toString()), navigation.current(), null, buttons));
    }

    private String choiceLabel(String value) {
        if(pending==Pending.REPORT_EXPENSE_CATEGORY)return value+" · "+PlanFactView.money(reportExpenseTotals().getOrDefault(value,BigDecimal.ZERO));
        if (pending == Pending.DELETE_PLAN) return effectiveCategory(value, YearMonth.from(now()));
        if (pending == Pending.EDIT_SELECT || pending == Pending.HISTORY_EDIT_SELECT) {
            var entry = findOperation(value).entry();
            return entry.date() + " · " + UiText.flow(displayKind(entry), entry.amount()) + " · " + entry.category() + "\n" + entry.description();
        }
        if (pending == Pending.CONFIRM_RULE || pending == Pending.SKIP_RULE) {
            return recurring.pendingThrough(now().toLocalDate()).stream().filter(o -> o.code().equals(value))
                    .map(o -> o.date() + " " + o.rule().category() + " · " + money(o.rule().amount()) + " ₽")
                    .findFirst().orElse(value);
        }
        if (pending == Pending.DELETE_RULE) return recurring.state().rules().stream()
                .filter(r -> r.id().equals(value)).map(r -> r.category() + " · " + money(r.amount()) + " ₽")
                .findFirst().orElse(value);
        return value;
    }

    private String choiceFingerprint(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(((quickPending() || pending==Pending.EDIT_OPERATION_SECTION || pending==Pending.EDIT_OPERATION_CATEGORY) ? value + ":" + pendingCategory : value).getBytes(java.nio.charset.StandardCharsets.UTF_8))).substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private Optional<BotReply> choose(String command) {
        String value;
        try {
            String[] fields = command.split("/");
            if (fields.length != 5 || !pending.name().equals(fields[2])) {
                return answer("Эта кнопка устарела. Выберите действие заново.");
            }
            value = choiceValues().get(Integer.parseInt(fields[3]));
            if (!choiceFingerprint(value).equals(fields[4])) return answer("Вариант больше недоступен. Выберите другой.");
        } catch (RuntimeException invalid) { return answer("Не удалось прочитать выбор. Выберите действие заново."); }
        switch (pending) {
            case PAY_DEBT -> { pendingCategory=value; pending = Pending.PAY_DEBT_AMOUNT; return answer("Долг: " + value + ". Введите сумму платежа, дробную часть через точку."); }
            case FUND_GOAL -> { pendingCategory=value; pending = Pending.FUND_GOAL_AMOUNT; return answer("Цель: " + value + ". Введите сумму пополнения."); }
            case EDIT_DEBT -> { pendingCategory=value; pending = Pending.EDIT_DEBT_VALUES; return answer("Долг: " + value + ". Введите «остаток; ежемесячный платёж; день платежа»."); }
            case PLAN -> { pendingCategory=value; pending = Pending.PLAN_AMOUNT; return answer("Категория: " + value + ". Введите сумму плана."); }
            default -> { return handlePending(value); }
        }
    }
}
