package financeos.core;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.YearMonth;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class BudgetService {
    public List<BudgetLine> breakdown(YearMonth month, List<BudgetPlan> plans, List<LedgerEntry> entries) {
        Map<String, BigDecimal> planned = new HashMap<>();
        Map<String, String> displayNames = new LinkedHashMap<>();
        for (BudgetPlan plan : plans) {
            if (plan.month().equals(month)) {
                String category = key(plan.category());
                planned.merge(category, plan.amount(), BigDecimal::add);
                displayNames.putIfAbsent(category, plan.category());
            }
        }
        Map<String, BigDecimal> spent = new HashMap<>();
        for (LedgerEntry entry : entries) {
            if (entry.kind() != EntryKind.EXPENSE || !YearMonth.from(entry.date()).equals(month)) continue;
            String category = key(entry.category());
            if (planned.containsKey(category)) {
                spent.merge(category, entry.amount(), BigDecimal::add);
            }
        }
        List<BudgetLine> lines = new ArrayList<>();
        for (var plan : planned.entrySet()) {
            BigDecimal used = spent.getOrDefault(plan.getKey(), BigDecimal.ZERO);
            lines.add(new BudgetLine(displayNames.get(plan.getKey()), plan.getValue(), used,
                    plan.getValue().subtract(used).max(BigDecimal.ZERO)));
        }
        lines.sort(java.util.Comparator.comparing(BudgetLine::category));
        return List.copyOf(lines);
    }

    public BudgetSnapshot calculate(YearMonth month, List<BudgetPlan> plans, List<LedgerEntry> entries) {
        List<BudgetLine> lines = breakdown(month, plans, entries);
        BigDecimal totalPlanned = BigDecimal.ZERO;
        BigDecimal totalSpent = BigDecimal.ZERO;
        BigDecimal remaining = BigDecimal.ZERO;
        for (BudgetLine line : lines) {
            totalPlanned = totalPlanned.add(line.planned());
            totalSpent = totalSpent.add(line.spent());
            remaining = remaining.add(line.remaining());
        }
        var matched = lines.stream().map(line -> key(line.category())).collect(java.util.stream.Collectors.toSet());
        BigDecimal unplanned = BigDecimal.ZERO;
        for (LedgerEntry entry : entries) {
            if (entry.kind() == EntryKind.EXPENSE && YearMonth.from(entry.date()).equals(month)
                    && !matched.contains(key(entry.category()))) {
                unplanned = unplanned.add(entry.amount());
            }
        }
        return new BudgetSnapshot(totalPlanned, totalSpent, remaining, unplanned);
    }

    private String key(String category) {
        return Normalizer.normalize(category.strip().replaceAll("\\s+", " "),
                Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
}
