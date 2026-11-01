package financeos.data;

import financeos.core.HistoryService;
import java.net.URI;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.TreeSet;

/** Read-only local operation history for a selected day or period. */
public final class LocalHistoryPreview {
    public static void main(String[] args) throws Exception {
        if (args.length != 4) {
            System.err.println("Usage: LocalHistoryPreview <spreadsheet-id> <key-file> <from> <through>");
            System.exit(2);
        }
        LocalDate from = LocalDate.parse(args[2]);
        LocalDate through = LocalDate.parse(args[3]);
        var sheets = new GoogleSheetsReadOnly(URI.create("https://sheets.googleapis.com"),
                args[0], new GoogleServiceAccountToken(Path.of(args[1]))).readAll();
        var ids = new TreeSet<String>();
        for (var row : sheets.get("StartupState")) {
            String id = row.getOrDefault("telegram_user_id", "");
            if (!id.isBlank()) ids.add(id);
        }
        if (ids.size() != 1) throw new IllegalStateException("Expected one financial user");
        var entries = new LegacyDataMapper().user(sheets, ids.first()).transactions().stream()
                .map(LegacyUserData.TimedEntry::entry).toList();
        var history = new HistoryService();
        for (var entry : history.between(entries, from, through)) {
            System.out.println(entry.date() + " | " + entry.kind() + " | " + entry.amount()
                    + " | " + entry.category() + " | " + entry.description());
        }
        var totals = history.totals(entries, from, through);
        System.out.println("Income: " + totals.income());
        System.out.println("Expense: " + totals.expense());
    }
}
