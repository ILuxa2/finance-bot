package financeos.data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class LegacySheetSchema {
    private static final Map<String, List<String>> HEADERS = new LinkedHashMap<>();
    static {
        HEADERS.put("Transactions", List.of("created_at", "telegram_user_id", "type", "amount", "category", "description"));
        HEADERS.put("StartupState", List.of("created_at", "telegram_user_id", "cash_balance", "card_balance", "savings_balance", "total_balance", "month_income_so_far", "month_expense_so_far", "month_savings_so_far", "month_debt_paid_so_far", "financial_mode"));
        HEADERS.put("BudgetPlan", List.of("created_at", "telegram_user_id", "month", "category", "planned_amount"));
        HEADERS.put("Debts", List.of("created_at", "telegram_user_id", "debt_name", "initial_amount", "current_amount", "monthly_payment", "payment_day", "status"));
        HEADERS.put("DebtPayments", List.of("created_at", "telegram_user_id", "debt_name", "amount", "comment"));
        HEADERS.put("Goals", List.of("created_at", "telegram_user_id", "goal_name", "target_amount", "current_amount", "monthly_target", "status"));
        HEADERS.put("Categories", List.of("created_at", "telegram_user_id", "type", "category_name", "status", "is_favorite", "last_used_at"));
        HEADERS.put("DailyLimits", List.of("created_at", "telegram_user_id", "month", "daily_limit", "status"));
        HEADERS.put("UserSettings", List.of("created_at", "updated_at", "telegram_user_id", "daily_limit_reminder_enabled", "daily_limit_reminder_time", "timezone"));
        HEADERS.put("DailyLimitOverrides", List.of("created_at", "telegram_user_id", "date", "daily_limit", "status"));
    }

    private LegacySheetSchema() {}

    public static Map<String, List<String>> headers() {
        return Map.copyOf(HEADERS);
    }

    public static void requireCompatible(String sheet, List<String> actualHeaders) {
        List<String> expected = HEADERS.get(sheet);
        if (expected == null || actualHeaders.size() < expected.size()
                || !actualHeaders.subList(0, expected.size()).equals(expected)) {
            throw new IllegalArgumentException("Unexpected columns in " + sheet + "; refusing to write");
        }
    }
}
