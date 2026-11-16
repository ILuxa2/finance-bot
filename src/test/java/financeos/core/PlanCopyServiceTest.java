package financeos.core;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

public final class PlanCopyServiceTest {
    public static void main(String[] args) {
        var target = YearMonth.of(2027, 1);
        var source = target.minusMonths(1);
        var service = new PlanCopyService();
        var plans = List.of(new BudgetPlan(source, " еда ", Money.of("100")),
                new BudgetPlan(source, "ЕДА", Money.of("50")),
                new BudgetPlan(source, "Транспорт", Money.of("200")),
                new BudgetPlan(source, "Нулевая", Money.of("0")),
                new BudgetPlan(source.minusMonths(1), "Не переносить", Money.of("900")),
                new BudgetPlan(target, "Еда", Money.of("400")));
        var preview = service.preview(plans, target);
        if (!preview.source().equals(YearMonth.of(2026, 12)) || preview.retained() != 1 || preview.additions().size() != 2) {
            throw new AssertionError("Copy did not handle year boundary and existing categories");
        }
        if (preview.additions().stream().anyMatch(p -> p.category().equalsIgnoreCase("Еда"))) throw new AssertionError("Existing target plan was overwritten");
        var after = new ArrayList<>(plans); after.addAll(preview.additions());
        if (!service.preview(after, target).additions().isEmpty()) throw new AssertionError("Repeated copy added duplicates");
        var aggregate = service.preview(List.of(new BudgetPlan(source, "Еда", Money.of("100")), new BudgetPlan(source, " ЕДА ", Money.of("50"))), target);
        if (aggregate.additions().size() != 1 || aggregate.additions().getFirst().amount().compareTo(Money.of("150")) != 0) {
            throw new AssertionError("Source categories were not grouped by normalized name");
        }
        if (!service.preview(List.of(new BudgetPlan(source, "ＦＯＯＤ", Money.of("100")), new BudgetPlan(target, "food", Money.of("1"))), target).additions().isEmpty()) {
            throw new AssertionError("Unicode normalization produced duplicate categories");
        }
        if (service.fingerprint(plans, target).equals(service.fingerprint(after, target))) throw new AssertionError("Preview did not detect changed plans");
        if (!service.preview(List.of(), target).additions().isEmpty()) throw new AssertionError("Empty source created plans");
        System.out.println("Plan copying: year boundary, full amounts, duplicates, existing plans and stale preview checks passed.");
    }
}
