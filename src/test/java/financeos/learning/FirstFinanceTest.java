package financeos.learning;

import financeos.core.CategoryCatalog;
import financeos.core.Money;
import financeos.core.ReportInput;
import financeos.core.ReportService;
import java.math.BigDecimal;

/** Small runnable examples. No token, network or real financial data is needed. */
public final class FirstFinanceTest {
    public static void main(String[] args) {
        freeMoneyExcludesReservedPlan();
        spentUnplannedMoneyIsNotSubtractedTwice();
        deficitCanBeNegative();
        categoryNamesIgnoreCaseAndOuterSpaces();
        System.out.println("First finance lesson: 4 business examples passed.");
    }

    private static void freeMoneyExcludesReservedPlan() {
        // Arrange: current cash is 1000, the unpaid plan reserves 300.
        var input = new ReportInput(Money.of("1000"), Money.of("300"), Money.of("0"));
        // Act: ask the report service for its result.
        var report = new ReportService().calculate(input);
        // Assert: the expected answer comes from the business rule, not a copy of the code.
        assertMoney("700", report.freeMoney());
        assertMoney("1000", report.actualBalance());
    }

    private static void spentUnplannedMoneyIsNotSubtractedTwice() {
        var input = new ReportInput(Money.of("900"), Money.of("300"), Money.of("100"));
        var report = new ReportService().calculate(input);
        assertMoney("600", report.freeMoney());
        assertMoney("100", report.unplannedExpenses());
    }

    private static void deficitCanBeNegative() {
        var report = new ReportService().calculate(new ReportInput(Money.of("100"), Money.of("150"), Money.of("0")));
        assertMoney("-50", report.freeMoney());
    }

    private static void categoryNamesIgnoreCaseAndOuterSpaces() {
        var categories = new CategoryCatalog();
        categories.ensure("Еда");
        categories.ensure(" еда ");
        categories.ensure("ЕДА");
        if (categories.all().size() != 1) {
            throw new AssertionError("Expected one category, got " + categories.all());
        }
    }

    private static void assertMoney(String expected, BigDecimal actual) {
        if (Money.of(expected).compareTo(actual) != 0) {
            throw new AssertionError("Expected " + expected + " but got " + actual);
        }
    }
}
