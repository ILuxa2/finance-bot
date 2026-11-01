package financeos.data;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;

/** Read-only shape check for implementing legacy adapters; omits names and amounts. */
public final class LocalDataShape {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected spreadsheet ID and key file");
        var sheets = new GoogleSheetsReadOnly(URI.create("https://sheets.googleapis.com"),
                args[0], new GoogleServiceAccountToken(Path.of(args[1]))).readAll();
        for (String sheet : List.of("Transactions", "Debts", "DebtPayments", "Goals",
                "Categories", "DailyLimits", "DailyLimitOverrides", "UserSettings")) {
            var rows = sheets.get(sheet);
            System.out.println(sheet + ": " + rows.size() + " rows");
            for (String field : List.of("type", "status", "payment_day", "timezone")) {
                var values = new TreeSet<String>();
                for (var row : rows) {
                    String value = row.getOrDefault(field, "").strip();
                    if (!value.isEmpty()) values.add(value);
                }
                if (!values.isEmpty()) System.out.println("  " + field + ": " + values);
            }
        }
    }
}
