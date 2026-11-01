package financeos.data;

import financeos.core.BudgetService;
import java.net.URI;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.TreeSet;

/** Read-only explanation of the remaining planned expenses. */
public final class LocalPlanBreakdown {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected spreadsheet ID and key path");
        var sheets = new GoogleSheetsReadOnly(URI.create("https://sheets.googleapis.com"),
                args[0], new GoogleServiceAccountToken(Path.of(args[1]))).readAll();
        var ids = new TreeSet<String>();
        for (var row : sheets.get("StartupState")) {
            String id = row.getOrDefault("telegram_user_id", "");
            if (!id.isBlank()) ids.add(id);
        }
        if (ids.size() != 1) throw new IllegalStateException("Expected one financial user");
        var data = new LegacyDataMapper().user(sheets, ids.first());
        YearMonth month = YearMonth.from(LocalDate.now(ZoneId.of("Europe/Moscow")));
        var entries = data.transactions().stream().map(LegacyUserData.TimedEntry::entry).toList();
        var service = new BudgetService();
        System.out.println("Month: " + month);
        for (var line : service.breakdown(month, data.plans(), entries)) {
            System.out.println(line.category() + " | plan " + line.planned()
                    + " | spent " + line.spent() + " | remaining " + line.remaining());
        }
        var totals = service.calculate(month, data.plans(), entries);
        System.out.println("Total planned: " + totals.planned());
        System.out.println("Total spent against plans: " + totals.spentAgainstPlan());
        System.out.println("Total remaining: " + totals.remaining());
        System.out.println("Unplanned expenses: " + totals.unplannedExpense());
    }

}
