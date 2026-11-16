package financeos.vk;

import financeos.core.BudgetService;
import financeos.core.DailyLimitService;
import financeos.core.DebtForecastService;
import financeos.core.DebtSchedule;
import financeos.core.EntryKind;
import financeos.core.HistoryService;
import financeos.core.LedgerEntry;
import financeos.core.LegacyReportService;
import financeos.data.LegacyUserData;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.Comparator;

/** Read-only presentation of the current local financial state. */
public final class LocalBotViews {
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private LocalBotViews() {}

    public static String report(LegacyUserData data, OffsetDateTime now) {
        var report = new LegacyReportService().calculate(data, now);
        return "📊 Отчёт на " + now.toLocalDate() + "\n\n"
                + "Фактический баланс · " + money(report.actualBalance()) + " ₽\n"
                + "Свободные деньги · " + money(report.freeMoney()) + " ₽\n"
                + "Баланс минус остаток плана\n\n"
                + "Осталось оплатить по плану · " + money(report.remainingPlans()) + " ₽\n"
                + "Планы за месяц · " + money(report.plannedExpenses()) + " ₽\n"
                + "Незапланированные расходы за месяц · " + money(report.unplannedThisMonth())
                + " ₽\nУже учтены в балансе\n\nСегодня\n"
                + "Доходы · " + UiText.flow(EntryKind.INCOME, report.incomeToday())
                + "\nРасходы · " + UiText.flow(EntryKind.EXPENSE, report.expenseToday());
    }

    public static String periodReport(LegacyUserData data, OffsetDateTime now,
                                      LocalDate from, LocalDate through) {
        var visible = data.transactions().stream()
                .filter(t -> !t.timestamp().isAfter(now))
                .map(LegacyUserData.TimedEntry::entry).toList();
        var history = new HistoryService();
        var entries = history.between(visible, from, through);
        var totals = history.totals(entries, from, through);
        BigDecimal unplanned = BigDecimal.ZERO;
        for (YearMonth month = YearMonth.from(from);
             !month.isAfter(YearMonth.from(through)); month = month.plusMonths(1)) {
            unplanned = unplanned.add(new BudgetService().calculate(
                    month, data.plans(), entries).unplannedExpense());
        }
        var result = new StringBuilder("📊 Отчёт за ").append(from);
        if (!from.equals(through)) result.append(" — ").append(through);
        result.append("\nДоходы · ").append(UiText.flow(EntryKind.INCOME, totals.income()))
                .append("\nРасходы · ").append(UiText.flow(EntryKind.EXPENSE, totals.expense()))
                .append("\nНезапланированные расходы · ").append(money(unplanned))
                .append(" ₽ (уже учтены в балансе)");
        LocalDate snapshotDate = data.snapshotTime().atZoneSameInstant(MOSCOW).toLocalDate();
        if (!through.isBefore(snapshotDate)) {
            OffsetDateTime at = through.equals(now.toLocalDate()) ? now
                    : through.atTime(java.time.LocalTime.MAX).atZone(MOSCOW).toOffsetDateTime();
            var ending = new LegacyReportService().calculate(data, at);
            result.append("\nБаланс на конец периода · ").append(money(ending.actualBalance()))
                    .append(" ₽\nОсталось оплатить по плану · ").append(money(ending.remainingPlans()))
                    .append(" ₽\nСвободные деньги на конец периода · ")
                    .append(money(ending.freeMoney())).append(" ₽");
        } else {
            result.append("\nБаланс до начального снимка данных не рассчитывается.");
        }
        return result.toString();
    }

    public static String planFact(LegacyUserData data, OffsetDateTime now) {
        return planFact(data, now, YearMonth.from(now));
    }

    public static String planFact(LegacyUserData data, OffsetDateTime now, YearMonth month) {
        return PlanFactView.categories(data,now,month,"По категориям");
    }

    public static String debtList(LegacyUserData data) {
        var debts = data.extras().debts();
        if (debts.isEmpty()) return "Долгов пока нет.";
        var result = new StringBuilder("💳 Долги\n");
        for (var debt : debts) {
            result.append(debt.name()).append(" · осталось · ").append(money(debt.remaining()))
                    .append(" ₽ · платёж · ").append(money(debt.monthlyPayment()))
                    .append(" ₽ · день · ").append(debt.paymentDay())
                    .append(debt.active() ? " (активен)" : " (закрыт)").append("\n");
        }
        return result.toString().strip();
    }

    public static String goalList(LegacyUserData data) {
        var goals = data.extras().goals();
        if (goals.isEmpty()) return "Целей пока нет.";
        var result = new StringBuilder("🎯 Цели\n");
        for (var goal : goals) {
            result.append(goal.name()).append(" · накоплено · ").append(money(goal.current()))
                    .append(" ₽ · цель · ").append(money(goal.target()))
                    .append(" ₽ · осталось · ").append(money(goal.remaining()))
                    .append(goal.active() ? " ₽" : " ₽ (закрыта)").append("\n");
        }
        return result.toString().strip();
    }

    public static String dailyLimit(LegacyUserData data, OffsetDateTime now) {
        var date = now.toLocalDate();
        var extras = data.extras();
        var base = extras.monthlyLimits().get(YearMonth.from(date));
        var override = extras.dailyOverrides().get(date);
        if (base == null && override == null) return "Дневной лимит на этот месяц не установлен.";
        var entries = data.transactions().stream()
                .filter(t -> !t.timestamp().isAfter(now))
                .map(LegacyUserData.TimedEntry::entry).toList();
        BigDecimal effective = override == null ? base : override;
        BigDecimal remaining = new DailyLimitService().remaining(date, effective, null, entries);
        return "⏱ Дневной лимит · " + money(effective) + " ₽."
                + " Осталось сегодня · " + money(remaining) + " ₽.";
    }

    public static String morningLimit(LegacyUserData data, OffsetDateTime now) {
        var date = now.toLocalDate();
        var base = data.extras().monthlyLimits().get(YearMonth.from(date));
        var override = data.extras().dailyOverrides().get(date);
        if (base == null && override == null) {
            return "🌅 На сегодня лимит не установлен. Нажмите «Установить лимит».";
        }
        BigDecimal effective = override == null ? base : override;
        var entries = data.transactions().stream()
                .filter(t -> !t.timestamp().isAfter(now))
                .map(LegacyUserData.TimedEntry::entry).toList();
        BigDecimal remaining = new DailyLimitService().remaining(date, effective, null, entries);
        return "🌅 Лимит на сегодня " + date + ": " + money(effective)
                + " ₽. На момент просмотра доступно " + money(remaining) + " ₽.";
    }

    public static String debtFreedom(LegacyUserData data, OffsetDateTime now) {
        return AnalyticsViews.debtFreedom(data, now);
    }

    public static String financialHealth(LegacyUserData data, OffsetDateTime now) {
        return AnalyticsViews.financialHealth(data, now);
    }

    public static String insights(LegacyUserData data, OffsetDateTime now) {
        return AnalyticsViews.insights(data, now);
    }

    public static String forecast(LegacyUserData data, OffsetDateTime now) {
        var today = now.toLocalDate();
        var result = new StringBuilder("📈 Платежи по долгам на 12 месяцев\n");
        boolean any = false;
        for (var debt : data.extras().debts()) {
            if (!debt.active() || debt.remaining().signum() == 0) continue;
            String reserveCategory = financeos.core.DebtBudgetService.category(debt.name());
            var reserves = data.plans().stream().filter(p -> p.category().equals(reserveCategory)
                    && !p.month().isBefore(YearMonth.from(today)) && p.month().isBefore(YearMonth.from(today).plusMonths(12)))
                    .sorted(java.util.Comparator.comparing(financeos.core.BudgetPlan::month)).toList();
            if (!reserves.isEmpty()) {
                var line = new StringBuilder(debt.name()).append(": ");
                boolean hasPayment = false;
                var actual = data.transactions().stream().filter(t -> !t.timestamp().isAfter(now)).map(LegacyUserData.TimedEntry::entry).toList();
                for (var reserve : reserves) {
                    var budget = new BudgetService().breakdown(reserve.month(), data.plans(), actual).stream()
                            .filter(b -> b.category().equals(reserveCategory)).findFirst().orElseThrow();
                    if (budget.remaining().signum() > 0) { hasPayment = true; line.append(reserve.month()).append(" · ").append(money(budget.remaining())).append(" ₽; "); }
                }
                if (hasPayment) { any = true; result.append(line).append("\n"); }
                continue;
            }
            var month = YearMonth.from(today);
            LocalDate next = month.atDay(Math.min(debt.paymentDay(), month.lengthOfMonth()));
            if (next.isBefore(today)) {
                month = month.plusMonths(1);
                next = month.atDay(Math.min(debt.paymentDay(), month.lengthOfMonth()));
            }
            int count = debt.remaining().divide(debt.monthlyPayment(),
                    0, java.math.RoundingMode.CEILING).intValueExact();
            YearMonth lastMonth = YearMonth.from(next).plusMonths(count - 1L);
            LocalDate last = lastMonth.atDay(Math.min(debt.paymentDay(), lastMonth.lengthOfMonth()));
            var payments = new DebtForecastService().payments(new DebtSchedule(
                    debt.name(), debt.remaining(), debt.monthlyPayment(), next, last,
                    debt.paymentDay()), today, 12);
            if (payments.isEmpty()) continue;
            any = true;
            result.append(debt.name()).append(": ");
            for (var payment : payments.entrySet()) {
                result.append(payment.getKey()).append(" · ").append(money(payment.getValue()))
                        .append(" ₽; ");
            }
            result.append("\n");
        }
        if (!any) return "Активных платежей по долгам на ближайшие 12 месяцев нет.";
        return result.toString().strip();
    }

    public static String history(LegacyUserData data, OffsetDateTime now,
                                 LocalDate from, LocalDate through) {
        return history(data, now, from, through, 0);
    }

    public static int historyCount(LegacyUserData data, OffsetDateTime now,
                                   LocalDate from, LocalDate through) {
        return historyEntries(data, now, from, through).size();
    }

    public static String history(LegacyUserData data, OffsetDateTime now,
                                 LocalDate from, LocalDate through, int page) {
        return history(data, now, from, through, page, java.util.Map.of());
    }

    public static String history(LegacyUserData data, OffsetDateTime now,
                                 LocalDate from, LocalDate through, int page,
                                 java.util.Map<String, financeos.core.OperationLink> links) {
        if (page < 0) throw new IllegalArgumentException("Invalid history page");
        var entries = historyEntries(data, now, from, through);
        if (entries.isEmpty()) return "Операций за выбранное время нет.";
        int pages = (entries.size() + 9) / 10;
        if (page >= pages) return "Это последняя страница истории.";
        var result = new StringBuilder("📜 История операций — страница ")
                .append(page + 1).append(" из ").append(pages);
        result.append(from!=null&&through!=null?"\nПериод · "+from+" — "+through:"\nЗа всё время");
        int end = Math.min(entries.size(), (page + 1) * 10);
        for (int i = page * 10; i < end; i++) {
            var entry = entries.get(i);
            var link = links.get(entry.id());
            result.append("\n\n").append(entry.date()).append(" · ")
                    .append(link != null && link.type() == financeos.core.OperationLink.Type.GOAL_FUND ? "🎯 " : "")
                    .append(UiText.flow(entry.kind(), entry.amount())).append(" · ").append(shorten(entry.category(), 60))
                    .append("\n").append(shorten(entry.description(), 150));
            if (entry.id().startsWith("local-")) result.append(" [").append(entry.id()).append("]");
        }
        if (page + 1 < pages) result.append("\nНажмите «Следующие» для более старых операций.");
        else result.append("\nПоказаны все оставшиеся операции выбранного периода.");
        return result.toString();
    }

    public static java.util.List<LedgerEntry> historyEntries(LegacyUserData data, OffsetDateTime now,
                                                               LocalDate from, LocalDate through) {
        var entries = data.transactions().stream()
                .filter(t -> !t.timestamp().isAfter(now))
                .map(LegacyUserData.TimedEntry::entry).toList();
        if (from != null) entries = new HistoryService().between(entries, from, through);
        else entries = entries.stream().sorted(Comparator.comparing(LedgerEntry::date).reversed()
                .thenComparing(LedgerEntry::id)).toList();
        return entries;
    }

    /** Compare complete calendar months so a partial current month cannot look artificially better. */
    public static String compareMonths(LegacyUserData data, OffsetDateTime now) {
        YearMonth current = YearMonth.from(now).minusMonths(1), previous = current.minusMonths(1);
        BigDecimal incomeNow = BigDecimal.ZERO, expenseNow = BigDecimal.ZERO, incomeBefore = BigDecimal.ZERO, expenseBefore = BigDecimal.ZERO;
        for (var timed : data.transactions()) {
            if (timed.timestamp().isAfter(now)) continue;
            var e = timed.entry(); var month = YearMonth.from(e.date());
            if (month.equals(current)) {
                if (e.kind() == EntryKind.INCOME) incomeNow = incomeNow.add(e.amount());
                if (e.kind() == EntryKind.EXPENSE) expenseNow = expenseNow.add(e.amount());
            } else if (month.equals(previous)) {
                if (e.kind() == EntryKind.INCOME) incomeBefore = incomeBefore.add(e.amount());
                if (e.kind() == EntryKind.EXPENSE) expenseBefore = expenseBefore.add(e.amount());
            }
        }
        return "📊 Сравнение полных месяцев · " + current + " и " + previous
                + "\nИзменение относительно " + previous
                + "\n\nДоходы · " + UiText.delta(incomeNow.subtract(incomeBefore), true)
                + "\nРасходы · " + UiText.delta(expenseNow.subtract(expenseBefore), false)
                + "\nРазница доходов и расходов · " + UiText.delta(incomeNow.subtract(expenseNow).subtract(incomeBefore.subtract(expenseBefore)), true)
                + "\n\nЗнак показывает разницу. Зелёный цвет расходов означает снижение."
                + "\nСравнение по записанным операциям.";
    }

    private static String money(BigDecimal value) { return value.setScale(2).toPlainString(); }
    private static String shorten(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
