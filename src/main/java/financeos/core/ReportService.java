package financeos.core;

public final class ReportService {
    public Report calculate(ReportInput input) {
        return new Report(input.actualBalance(), input.plannedExpenses(),
                input.actualBalance().subtract(input.plannedExpenses()),
                input.unplannedExpenses());
    }
}
