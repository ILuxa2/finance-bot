package financeos.data;

import java.net.URI;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.time.OffsetDateTime;
import financeos.core.BalanceService;
import financeos.core.LegacyReportService;

/** Read-only smoke check. Prints only worksheet row counts, never financial values. */
public final class LocalSheetCheck {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("Usage: LocalSheetCheck <spreadsheet-id> <new-service-account-key-path>");
            System.exit(2);
        }
        var token = new GoogleServiceAccountToken(Path.of(args[1]));
        var reader = new GoogleSheetsReadOnly(URI.create("https://sheets.googleapis.com"), args[0], token);
        var sheets = reader.readAll();
        for (String name : LegacySheetSchema.headers().keySet()) {
            System.out.println(name + ": " + sheets.get(name).size() + " data rows");
            for (String field : Set.of("created_at", "month", "date", "amount", "total_balance")) {
                var shapes = new TreeSet<String>();
                for (var row : sheets.get(name)) {
                    String value = row.getOrDefault(field, "");
                    if (!value.isBlank()) shapes.add(value.replaceAll("[0-9]", "#"));
                }
                if (!shapes.isEmpty()) System.out.println("  " + field + " shapes: " + shapes);
            }
        }
        var users = new TreeSet<String>();
        for (var row : sheets.get("StartupState")) {
            String id = row.getOrDefault("telegram_user_id", "");
            if (!id.isBlank()) users.add(id);
        }
        int mapped = 0;
        for (String id : users) {
            var data = new LegacyDataMapper().user(sheets, id);
            new BalanceService().actualBalance(data, OffsetDateTime.now());
            new LegacyReportService().calculate(data, OffsetDateTime.now());
            mapped++;
        }
        System.out.println("Financial users parsed without writing: " + mapped);
    }
}
