package financeos.core;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.Map;

public final class DebtForecastService {
    public Map<YearMonth, BigDecimal> payments(DebtSchedule debt, LocalDate today, int months) {
        if (months < 1 || months > 120) throw new IllegalArgumentException("Invalid forecast length");
        Map<YearMonth, BigDecimal> result = new LinkedHashMap<>();
        YearMonth first = YearMonth.from(today);
        YearMonth last = first.plusMonths(months - 1L);
        BigDecimal remaining = debt.remainingAmount();
        int dueDay = debt.paymentDay();
        YearMonth paymentMonth = YearMonth.from(debt.nextPaymentDate());
        LocalDate paymentDate = debt.nextPaymentDate();
        while (remaining.signum() > 0 && !paymentDate.isAfter(debt.finalPaymentDate())
                && !YearMonth.from(paymentDate).isAfter(last)) {
            if (!paymentDate.isBefore(today) && !YearMonth.from(paymentDate).isBefore(first)) {
                BigDecimal payment = debt.monthlyPayment().min(remaining);
                result.merge(YearMonth.from(paymentDate), payment, BigDecimal::add);
                remaining = remaining.subtract(payment);
            }
            paymentMonth = paymentMonth.plusMonths(1);
            paymentDate = paymentMonth.atDay(Math.min(dueDay, paymentMonth.lengthOfMonth()));
        }
        return Collections.unmodifiableMap(result);
    }
}
