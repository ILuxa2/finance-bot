package financeos.vk;

import financeos.core.EntryKind;
import financeos.core.LegacyReportService;
import financeos.data.LegacyUserData;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/** Descriptive analytics: every amount comes from visible data, without a subjective score. */
final class AnalyticsViews {
    private AnalyticsViews() {}

    static String debtFreedom(LegacyUserData data, OffsetDateTime now) {
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal monthly = BigDecimal.ZERO;
        LocalDate finalDate = null;
        int active = 0;
        for (var debt : data.extras().debts()) {
            if (!debt.active() || debt.remaining().signum() == 0) continue;
            active++;
            total = total.add(debt.remaining());
            monthly = monthly.add(debt.monthlyPayment().min(debt.remaining()));
            YearMonth month = YearMonth.from(now);
            LocalDate next = paymentDate(month, debt.paymentDay());
            if (next.isBefore(now.toLocalDate())) {
                month = month.plusMonths(1);
                next = paymentDate(month, debt.paymentDay());
            }
            int count = debt.remaining().divide(debt.monthlyPayment(), 0,
                    RoundingMode.CEILING).intValueExact();
            LocalDate last = paymentDate(YearMonth.from(next).plusMonths(count - 1L),
                    debt.paymentDay());
            if (finalDate == null || last.isAfter(finalDate)) finalDate = last;
        }
        if (active == 0) return "🕊 Активных долгов нет. Запланированных платежей по ним нет.";
        return "🕊 Долговая свобода\nАктивных долгов: " + active
                + "\nОстаток долга · " + money(total) + " ₽"
                + "\nБлижайшая суммарная месячная нагрузка · " + money(monthly) + " ₽"
                + "\nРасчётная дата последнего платежа: " + finalDate
                + "\nРасчёт без процентов и новых займов; суммы платежей взяты из карточек долгов.";
    }

    static String financialHealth(LegacyUserData data, OffsetDateTime now) {
        var report = new LegacyReportService().calculate(data, now);
        BigDecimal debt = data.extras().debts().stream().filter(d -> d.active())
                .map(d -> d.remaining()).reduce(BigDecimal.ZERO, BigDecimal::add);
        String budget = report.freeMoney().signum() < 0
                ? "Неоплаченная часть плана превышает текущий баланс на " + money(report.freeMoney().abs()) + " ₽."
                : "После оставшихся плановых расходов свободно " + money(report.freeMoney()) + " ₽.";
        return "💚 Финансовое состояние на " + now.toLocalDate()
                + "\nБаланс · " + money(report.actualBalance()) + " ₽"
                + "\nПлан расходов · " + money(report.plannedExpenses()) + " ₽"
                + "\nОсталось оплатить по плану · " + money(report.remainingPlans()) + " ₽"
                + "\n" + budget
                + "\nНезапланированные расходы за месяц · "
                + money(report.unplannedThisMonth()) + " ₽ (уже учтены в балансе)"
                + "\nАктивный остаток долгов · " + money(debt) + " ₽";
    }

    static String insights(LegacyUserData data, OffsetDateTime now) {
        YearMonth month = YearMonth.from(now);
        BigDecimal income = BigDecimal.ZERO;
        BigDecimal expense = BigDecimal.ZERO;
        Map<String, BigDecimal> byCategory = new HashMap<>();
        for (var timed : data.transactions()) {
            if (timed.timestamp().toInstant().isAfter(now.toInstant())
                    || !YearMonth.from(timed.entry().date()).equals(month)) continue;
            var entry = timed.entry();
            if (entry.kind() == EntryKind.INCOME) income = income.add(entry.amount());
            if (entry.kind() == EntryKind.EXPENSE) {
                expense = expense.add(entry.amount());
                byCategory.merge(entry.category().isBlank() ? "Без категории" : entry.category(),
                        entry.amount(), BigDecimal::add);
            }
        }
        String top = byCategory.entrySet().stream()
                .max(Comparator.<Map.Entry<String, BigDecimal>, BigDecimal>comparing(Map.Entry::getValue)
                        .thenComparing(Map.Entry::getKey))
                .map(e -> e.getKey() + " · " + money(e.getValue()) + " ₽")
                .orElse("нет расходов");
        return "🧠 Итоги за " + month
                + "\nДоход · " + money(income) + " ₽"
                + "\nРасход · " + money(expense) + " ₽"
                + "\nРазница доходов и расходов · " + money(income.subtract(expense)) + " ₽"
                + "\nКрупнейшая статья расходов: " + top
                + "\nЭто сводка по записанным операциям, без прогноза будущих поступлений.";
    }

    private static String money(BigDecimal amount) { return amount.setScale(2).toPlainString(); }
    private static LocalDate paymentDate(YearMonth month, int day) {
        return month.atDay(Math.min(day, month.lengthOfMonth()));
    }
}
