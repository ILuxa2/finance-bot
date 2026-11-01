package financeos.core;

import java.math.BigDecimal;

public record Debt(String name, BigDecimal remaining, BigDecimal monthlyPayment, int paymentDay,
                   boolean active) {
    public Debt {
        if (name == null || name.isBlank() || remaining == null || remaining.signum() < 0
                || monthlyPayment == null || monthlyPayment.signum() <= 0
                || paymentDay < 1 || paymentDay > 31) {
            throw new IllegalArgumentException("Invalid debt");
        }
    }

    public Debt pay(BigDecimal amount) {
        if (!active || amount == null || amount.signum() <= 0 || amount.compareTo(remaining) > 0) {
            throw new IllegalArgumentException("Invalid debt payment");
        }
        BigDecimal after = remaining.subtract(amount);
        return new Debt(name, after, monthlyPayment, paymentDay, after.signum() > 0);
    }
}
