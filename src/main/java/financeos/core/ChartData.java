package financeos.core;

import java.util.List;

public record ChartData(String title, List<String> labels, List<ChartSeries> series) {
    public ChartData {
        labels = List.copyOf(labels);
        series = List.copyOf(series);
        int labelCount = labels.size();
        if (title == null || title.isBlank() || series.isEmpty()
                || series.stream().anyMatch(item -> item.values().size() != labelCount)) {
            throw new IllegalArgumentException("Invalid chart data");
        }
    }
}
