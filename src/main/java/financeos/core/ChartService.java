package financeos.core;

import financeos.data.LegacyUserData;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Builds chart values without depending on image rendering or VK. */
public final class ChartService {
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

    public ChartData prepare(ChartKind kind, LegacyUserData data, OffsetDateTime now) {
        var month = YearMonth.from(now.atZoneSameInstant(MOSCOW));
        var entries = data.transactions().stream()
                .filter(t -> !t.timestamp().toInstant().isAfter(now.toInstant()))
                .map(LegacyUserData.TimedEntry::entry)
                .filter(e -> YearMonth.from(e.date()).equals(month)).toList();
        return switch (kind) {
            case EXPENSES -> grouped("Расходы по категориям · " + month, entries, EntryKind.EXPENSE);
            case INCOME -> grouped("Доходы по категориям · " + month, entries, EntryKind.INCOME);
            case PLAN -> plan(month, data.plans(), entries);
            case DEBTS -> debts(data);
            case GOALS -> goals(data);
        };
    }

    private ChartData grouped(String title, List<LedgerEntry> entries, EntryKind kind) {
        Map<String, BigDecimal> amounts = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        for (var entry : entries) {
            if (entry.kind() != kind) continue;
            String display = entry.category().isBlank() ? "Без категории" : entry.category();
            String key = key(display);
            labels.putIfAbsent(key, display);
            amounts.merge(key, entry.amount(), BigDecimal::add);
        }
        var sorted = amounts.entrySet().stream()
                .sorted(Map.Entry.<String, BigDecimal>comparingByValue().reversed())
                .toList();
        return new ChartData(title, sorted.stream().map(e -> labels.get(e.getKey())).toList(),
                List.of(new ChartSeries("Сумма, ₽", sorted.stream().map(Map.Entry::getValue).toList())));
    }

    private ChartData plan(YearMonth month, List<BudgetPlan> plans, List<LedgerEntry> entries) {
        var lines = new BudgetService().breakdown(month, plans, entries);
        return new ChartData("План и факт · " + month,
                lines.stream().map(BudgetLine::category).toList(),
                List.of(new ChartSeries("План, ₽", lines.stream().map(BudgetLine::planned).toList()),
                        new ChartSeries("Факт, ₽", lines.stream().map(BudgetLine::spent).toList())));
    }

    private ChartData debts(LegacyUserData data) {
        var debts = data.extras().debts().stream().filter(Debt::active)
                .sorted(Comparator.comparing(Debt::remaining).reversed()).limit(12).toList();
        return new ChartData("Остаток долгов", debts.stream().map(Debt::name).toList(),
                List.of(new ChartSeries("Осталось, ₽", debts.stream().map(Debt::remaining).toList())));
    }

    private ChartData goals(LegacyUserData data) {
        var goals = data.extras().goals().stream().filter(Goal::active)
                .sorted(Comparator.comparing(Goal::target).reversed()).limit(12).toList();
        return new ChartData("Накопления по целям", goals.stream().map(Goal::name).toList(),
                List.of(new ChartSeries("Накоплено, ₽", goals.stream().map(Goal::current).toList()),
                        new ChartSeries("Цель, ₽", goals.stream().map(Goal::target).toList())));
    }

    private String key(String category) {
        return Normalizer.normalize(category.strip().replaceAll("\\s+", " "),
                Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
}
