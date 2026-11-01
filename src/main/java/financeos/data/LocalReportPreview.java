package financeos.data;

import financeos.core.LegacyReportService;
import java.net.URI;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.TreeSet;

/** Prints a read-only report for the owner to reconcile against expected balances. */
public final class LocalReportPreview {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: LocalReportPreview <spreadsheet-id> <key-file>");
            System.exit(2);
        }
        var sheets = new GoogleSheetsReadOnly(URI.create("https://sheets.googleapis.com"),
                args[0], new GoogleServiceAccountToken(Path.of(args[1]))).readAll();
        var ids = new TreeSet<String>();
        for (var row : sheets.get("StartupState")) {
            String id = row.getOrDefault("telegram_user_id", "");
            if (!id.isBlank()) ids.add(id);
        }
        if (ids.size() != 1) throw new IllegalStateException("Expected one financial user for local preview");
        var data = new LegacyDataMapper().user(sheets, ids.first());
        var now = OffsetDateTime.now(ZoneId.of("Europe/Moscow"));
        var report = new LegacyReportService().calculate(data, now);
        System.out.println("Date (Moscow): " + now.toLocalDate());
        System.out.println("Actual balance: " + report.actualBalance());
        System.out.println("Planned expenses: " + report.plannedExpenses());
        System.out.println("Remaining plans: " + report.remainingPlans());
        System.out.println("Free money: " + report.freeMoney());
        System.out.println("Unplanned expenses this month: " + report.unplannedThisMonth());
        System.out.println("Income today: " + report.incomeToday());
        System.out.println("Expense today: " + report.expenseToday());
    }
}
