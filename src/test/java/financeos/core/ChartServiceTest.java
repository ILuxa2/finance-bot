package financeos.core;

import financeos.data.LegacyExtras;
import financeos.data.LegacyUserData;
import financeos.vk.ChartRenderer;
import java.io.ByteArrayInputStream;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

public final class ChartServiceTest {
    public static void main(String[] args) throws Exception {
        var at = OffsetDateTime.parse("2026-10-30T12:00:00+03:00");
        var date = LocalDate.of(2026, 10, 30);
        var data = new LegacyUserData(Money.of("100000"),
                OffsetDateTime.parse("2026-10-01T00:00:00+03:00"),
                List.of(
                        timed("1", "Еда", "100", EntryKind.EXPENSE, date, at),
                        timed("2", " еда ", "50", EntryKind.EXPENSE, date, at),
                        timed("3", "Такси", "200", EntryKind.EXPENSE, date, at),
                        timed("4", "Зарплата", "500", EntryKind.INCOME, date, at)),
                List.of(new BudgetPlan(YearMonth.of(2026, 10), "Еда", Money.of("250"))),
                new LegacyExtras(List.of(new Debt("Кредит", Money.of("1500"),
                        Money.of("1000"), 19, true)),
                        List.of(new Goal("Резерв", Money.of("10000"), Money.of("2500"),
                                Money.of("1000"), true)), List.of(), Map.of(), Map.of()));
        var charts = new ChartService();
        var expenses = charts.prepare(ChartKind.EXPENSES, data, at);
        if (!expenses.labels().equals(List.of("Такси", "Еда"))
                || expenses.series().getFirst().values().get(1).compareTo(Money.of("150")) != 0) {
            throw new AssertionError("Expenses chart did not group categories correctly");
        }
        var plan = charts.prepare(ChartKind.PLAN, data, at);
        if (plan.series().get(1).values().getFirst().compareTo(Money.of("150")) != 0) {
            throw new AssertionError("Plan chart has incorrect actual expenses");
        }
        for (ChartKind kind : ChartKind.values()) {
            var chart = charts.prepare(kind, data, at);
            var png = new ChartRenderer().png(chart);
            var decoded = ImageIO.read(new ByteArrayInputStream(png));
            if (decoded == null || decoded.getWidth() != 1100 || decoded.getHeight() < 420) {
                throw new AssertionError("Invalid PNG for " + kind);
            }
        }
        var empty = new LegacyUserData(Money.of("0"), data.snapshotTime(), List.of(), List.of());
        if (!charts.prepare(ChartKind.EXPENSES, empty, at).labels().isEmpty()) {
            throw new AssertionError("Empty chart should not invent data");
        }
        System.out.println("Chart values and PNG rendering passed.");
    }

    private static LegacyUserData.TimedEntry timed(String id, String category, String amount,
                                                    EntryKind kind, LocalDate date,
                                                    OffsetDateTime at) {
        return new LegacyUserData.TimedEntry(at, new LedgerEntry(id, date, kind,
                Money.of(amount), category, "", false));
    }
}
