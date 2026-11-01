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
        return "📊 Отчёт на " + now.toLocalDate() + "\n"
                + "Фактический баланс: " + money(report.actualBalance()) + " ₽\n"
                + "Планы за месяц: " + money(report.plannedExpenses()) + " ₽\n"
                + "Осталось оплатить по плану: " + money(report.remainingPlans()) + " ₽\n"
                + "Свободные деньги: " + money(report.freeMoney())
                + " ₽ (баланс минус остаток плана)\n"
                + "Незапланированные расходы за месяц: "
                + money(report.unplannedThisMonth()) + " ₽ (уже учтены в балансе)\n"
                + "Сегодня: доход " + money(report.incomeToday())
                + " ₽, расход " + money(report.expenseToday()) + " ₽";
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
        result.append("\nДоход: ").append(money(totals.income())).append(" ₽")
                .append("\nРасход: ").append(money(totals.expense())).append(" ₽")
                .append("\nНезапланированные расходы: ").append(money(unplanned))
                .append(" ₽ (уже учтены в балансе)");
        LocalDate snapshotDate = data.snapshotTime().atZoneSameInstant(MOSCOW).toLocalDate();
        if (!through.isBefore(snapshotDate)) {
            OffsetDateTime at = through.equals(now.toLocalDate()) ? now
                    : through.atTime(java.time.LocalTime.MAX).atZone(MOSCOW).toOffsetDateTime();
            var ending = new LegacyReportService().calculate(data, at);
            result.append("\nБаланс на конец периода: ").append(money(ending.actualBalance()))
                    .append(" ₽\nОсталось оплатить по плану: ").append(money(ending.remainingPlans()))
                    .append(" ₽\nСвободные деньги на конец периода: ")
                    .append(money(ending.freeMoney())).append(" ₽");
        } else {
            result.append("\nБаланс до начального снимка данных не рассчитывается.");
        }
        return result.toString();
    }

    public static String planFact(LegacyUserData data, OffsetDateTime now) {
        var month = YearMonth.from(now);
        var lines = new BudgetService().breakdown(month, data.plans(),
                data.transactions().stream().filter(t -> !t.timestamp().isAfter(now))
                        .map(LegacyUserData.TimedEntry::entry).toList());
        if (lines.isEmpty()) return "На " + month + " планов пока нет.";
        var result = new StringBuilder("📋 План / факт за " + month + "\n");
        for (var line : lines) {
            result.append(line.category()).append(": план ").append(money(line.planned()))
                    .append(" ₽, потрачено ").append(money(line.spent()))
                    .append(" ₽, остаток ").append(money(line.remaining())).append(" ₽\n");
            if (result.length() > 3000) { result.append("…"); break; }
        }
        return result.toString().strip();
    }

    public static String debtList(LegacyUserData data) {
        var debts = data.extras().debts();
        if (debts.isEmpty()) return "Долгов пока нет.";
        var result = new StringBuilder("💳 Долги\n");
        for (var debt : debts) {
            result.append(debt.name()).append(": осталось ").append(money(debt.remaining()))
                    .append(" ₽, платёж ").append(money(debt.monthlyPayment()))
                    .append(" ₽, день ").append(debt.paymentDay())
                    .append(debt.active() ? " (активен)" : " (закрыт)").append("\n");
        }
        return result.toString().strip();
    }

    public static String goalList(LegacyUserData data) {
        var goals = data.extras().goals();
        if (goals.isEmpty()) return "Целей пока нет.";
        var result = new StringBuilder("🎯 Цели\n");
        for (var goal : goals) {
            result.append(goal.name()).append(": накоплено ").append(money(goal.current()))
                    .append(" ₽ из ").append(money(goal.target()))
                    .append(" ₽, осталось ").append(money(goal.remaining()))
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
        return "⏱ Дневной лимит: " + money(effective) + " ₽."
                + " Осталось сегодня: " + money(remaining) + " ₽.";
    }

    public static String morningLimit(LegacyUserData data, OffsetDateTime now) {
        var date = now.toLocalDate();
        var base = data.extras().monthlyLimits().get(YearMonth.from(date));
        var override = data.extras().dailyOverrides().get(date);
        if (base == null && override == null) {
            return "🌅 На сегодня лимит не установлен. Откройте /set_limit.";
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
                result.append(payment.getKey()).append(" ").append(money(payment.getValue()))
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
        if (page < 0) throw new IllegalArgumentException("Invalid history page");
        var entries = historyEntries(data, now, from, through);
        if (entries.isEmpty()) return "Операций за выбранное время нет.";
        int pages = (entries.size() + 19) / 20;
        if (page >= pages) return "Это последняя страница истории.";
        var result = new StringBuilder("📜 История операций — страница ")
                .append(page + 1).append(" из ").append(pages);
        int end = Math.min(entries.size(), (page + 1) * 20);
        for (int i = page * 20; i < end; i++) {
            var entry = entries.get(i);
            result.append("\n").append(entry.date()).append(" | ")
                    .append(entry.kind() == EntryKind.TRANSFER ? "↔" :
                            entry.kind() == EntryKind.INCOME ? "+" : "−")
                    .append(money(entry.amount())).append(" ₽ | ").append(shorten(entry.category(), 60))
                    .append(" | ").append(shorten(entry.description(), 150));
            if (entry.id().startsWith("local-")) result.append(" [").append(entry.id()).append("]");
        }
        if (page + 1 < pages) result.append("\nНажмите «Дальше» для следующей страницы.");
        return result.toString();
    }

    private static java.util.List<LedgerEntry> historyEntries(LegacyUserData data, OffsetDateTime now,
                                                               LocalDate from, LocalDate through) {
        var entries = data.transactions().stream()
                .filter(t -> !t.timestamp().isAfter(now))
                .map(LegacyUserData.TimedEntry::entry).toList();
        if (from != null) entries = new HistoryService().between(entries, from, through);
        else entries = entries.stream().sorted(Comparator.comparing(LedgerEntry::date).reversed()
                .thenComparing(LedgerEntry::id)).toList();
        return entries;
    }

    private static String money(BigDecimal value) { return value.setScale(2).toPlainString(); }
    private static String shorten(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
