package financeos.core;

import java.math.BigDecimal;

public record Goal(String name, BigDecimal target, BigDecimal current, BigDecimal monthlyTarget,
                   boolean active) {
    public Goal {
        if (name == null || name.isBlank() || target == null || target.signum() <= 0
                || current == null || current.signum() < 0 || monthlyTarget == null
                || monthlyTarget.signum() < 0) {
            throw new IllegalArgumentException("Invalid goal");
        }
    }

    public Goal contribute(BigDecimal amount) {
        if (!active || amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("Invalid goal contribution");
        }
        return new Goal(name, target, current.add(amount), monthlyTarget, active);
    }

    public BigDecimal remaining() {
        return target.subtract(current).max(BigDecimal.ZERO);
    }

    public Goal close() {
        return new Goal(name, target, current, monthlyTarget, false);
    }
}
