package financeos.core;

import java.math.BigDecimal;
import java.util.List;

public record ChartSeries(String name, List<BigDecimal> values) {
    public ChartSeries {
        values = List.copyOf(values);
        if (name == null || name.isBlank() || values.stream().anyMatch(v -> v.signum() < 0)) {
            throw new IllegalArgumentException("Invalid chart series");
        }
    }
}
