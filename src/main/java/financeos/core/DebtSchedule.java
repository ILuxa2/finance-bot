package financeos.core;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

public record DebtSchedule(String name, BigDecimal remainingAmount, BigDecimal monthlyPayment,
                           LocalDate nextPaymentDate, LocalDate finalPaymentDate, int paymentDay) {
    public DebtSchedule(String name, BigDecimal remainingAmount, BigDecimal monthlyPayment,
                        LocalDate nextPaymentDate, LocalDate finalPaymentDate) {
        this(name, remainingAmount, monthlyPayment, nextPaymentDate, finalPaymentDate,
                nextPaymentDate.getDayOfMonth());
    }

    public DebtSchedule {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Debt name is required");
        Objects.requireNonNull(remainingAmount);
        Objects.requireNonNull(monthlyPayment);
        Objects.requireNonNull(nextPaymentDate);
        Objects.requireNonNull(finalPaymentDate);
        if (remainingAmount.signum() < 0 || monthlyPayment.signum() <= 0
                || paymentDay < 1 || paymentDay > 31
                || finalPaymentDate.isBefore(nextPaymentDate)) {
            throw new IllegalArgumentException("Invalid debt schedule");
        }
    }
}
