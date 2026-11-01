package financeos.core;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Money {
    private Money() {}

    public static BigDecimal of(String value) {
        if (value == null || !value.matches("-?\\d+(\\.\\d{1,2})?")) {
            throw new IllegalArgumentException("Use a number with a dot and at most two decimal places");
        }
        return new BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY);
    }
}
