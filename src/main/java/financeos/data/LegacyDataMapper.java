package financeos.data;

import financeos.core.BudgetPlan;
import financeos.core.CategoryCatalog;
import financeos.core.Debt;
import financeos.core.EntryKind;
import financeos.core.Goal;
import financeos.core.LedgerEntry;
import financeos.core.Money;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.HashMap;

/** Converts existing sheet rows to the new domain model without modifying them. */
public final class LegacyDataMapper {
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

    public LegacyUserData user(Map<String, List<Map<String, String>>> sheets, String legacyUserId) {
        if (legacyUserId == null || !legacyUserId.matches("[0-9]+")) {
            throw new IllegalArgumentException("A numeric legacy data ID is required");
        }
        List<Map<String, String>> startupRows = mine(sheets, "StartupState", legacyUserId);
        Map<String, String> latest = startupRows.stream()
                .max(Comparator.comparing(row -> timestamp(row, "created_at")))
                .orElseThrow(() -> new IllegalArgumentException("No starting balance for this user"));
        var plans = new ArrayList<BudgetPlan>();
        for (var row : mine(sheets, "BudgetPlan", legacyUserId)) {
            plans.add(new BudgetPlan(YearMonth.parse(required(row, "month")),
                    required(row, "category"), Money.of(required(row, "planned_amount"))));
        }
        var entries = new ArrayList<LegacyUserData.TimedEntry>();
        List<Map<String, String>> transactionRows = sheets.getOrDefault("Transactions", List.of());
        for (int index = 0; index < transactionRows.size(); index++) {
            var row = transactionRows.get(index);
            if (!legacyUserId.equals(row.get("telegram_user_id"))) continue;
            EntryKind kind = switch (required(row, "type")) {
                case "income" -> EntryKind.INCOME;
                case "expense" -> EntryKind.EXPENSE;
                default -> throw new IllegalArgumentException("Unknown transaction type");
            };
            OffsetDateTime created = timestamp(row, "created_at");
            var entry = new LedgerEntry("legacy-row-" + (index + 2),
                    created.atZoneSameInstant(MOSCOW).toLocalDate(), kind,
                    Money.of(required(row, "amount")), row.get("category"),
                    row.get("description"), false);
            entries.add(new LegacyUserData.TimedEntry(created, entry));
        }
        return new LegacyUserData(Money.of(required(latest, "total_balance")),
                timestamp(latest, "created_at"), List.copyOf(entries), List.copyOf(plans),
                extras(sheets, legacyUserId));
    }

    private LegacyExtras extras(Map<String, List<Map<String, String>>> sheets, String userId) {
        Map<String, Map<String, String>> debtRows = latestByName(
                mine(sheets, "Debts", userId), "debt_name");
        var debts = new ArrayList<Debt>();
        for (var row : debtRows.values()) {
            debts.add(new Debt(required(row, "debt_name"),
                    Money.of(required(row, "current_amount")),
                    Money.of(required(row, "monthly_payment")),
                    Integer.parseInt(required(row, "payment_day")),
                    required(row, "status").equalsIgnoreCase("active")));
        }
        Map<String, Map<String, String>> goalRows = latestByName(
                mine(sheets, "Goals", userId), "goal_name");
        var goals = new ArrayList<Goal>();
        for (var row : goalRows.values()) {
            String monthly = row.getOrDefault("monthly_target", "").strip();
            goals.add(new Goal(required(row, "goal_name"),
                    Money.of(required(row, "target_amount")),
                    Money.of(required(row, "current_amount")),
                    monthly.isEmpty() ? BigDecimal.ZERO : Money.of(monthly),
                    required(row, "status").equalsIgnoreCase("active")));
        }
        var catalog = new CategoryCatalog();
        for (var row : mine(sheets, "Categories", userId)) {
            if (row.getOrDefault("status", "").equalsIgnoreCase("active")) {
                catalog.ensure(required(row, "category_name"));
            }
        }
        Map<YearMonth, Map<String, String>> limitRows = new HashMap<>();
        for (var row : mine(sheets, "DailyLimits", userId)) {
            if (!row.getOrDefault("status", "").equalsIgnoreCase("active")) continue;
            YearMonth month = YearMonth.parse(required(row, "month"));
            var old = limitRows.get(month);
            if (old == null || timestamp(row, "created_at").isAfter(timestamp(old, "created_at"))) {
                limitRows.put(month, row);
            }
        }
        Map<YearMonth, BigDecimal> limits = new HashMap<>();
        limitRows.forEach((month, row) -> limits.put(month, Money.of(required(row, "daily_limit"))));
        Map<LocalDate, Map<String, String>> overrideRows = new HashMap<>();
        for (var row : mine(sheets, "DailyLimitOverrides", userId)) {
            if (!row.getOrDefault("status", "").equalsIgnoreCase("active")) continue;
            LocalDate date = LocalDate.parse(required(row, "date"));
            var old = overrideRows.get(date);
            if (old == null || timestamp(row, "created_at").isAfter(timestamp(old, "created_at"))) {
                overrideRows.put(date, row);
            }
        }
        Map<LocalDate, BigDecimal> overrides = new HashMap<>();
        overrideRows.forEach((date, row) -> overrides.put(date, Money.of(required(row, "daily_limit"))));
        return new LegacyExtras(debts, goals, catalog.all(), limits, overrides);
    }

    private Map<String, Map<String, String>> latestByName(List<Map<String, String>> rows,
                                                            String nameField) {
        Map<String, Map<String, String>> latest = new LinkedHashMap<>();
        for (var row : rows) {
            String name = required(row, nameField);
            var old = latest.get(name);
            if (old == null || timestamp(row, "created_at").isAfter(timestamp(old, "created_at"))) {
                latest.put(name, row);
            }
        }
        return latest;
    }

    private List<Map<String, String>> mine(Map<String, List<Map<String, String>>> sheets,
                                           String name, String userId) {
        return sheets.getOrDefault(name, List.of()).stream()
                .filter(row -> userId.equals(row.get("telegram_user_id"))).toList();
    }

    private String required(Map<String, String> row, String field) {
        String value = row.get(field);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + field);
        return value.strip();
    }

    private OffsetDateTime timestamp(Map<String, String> row, String field) {
        return OffsetDateTime.parse(required(row, field));
    }
}
