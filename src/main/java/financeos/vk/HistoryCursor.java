package financeos.vk;
import java.time.LocalDate;
/** Durable browsing context, independent from financial undo snapshots. */
public record HistoryCursor(LocalDate from, LocalDate through, int page, String category,
                            String query, String amount, int editorPage, boolean editorContext, String kind) {
    public HistoryCursor(LocalDate from, LocalDate through, int page, String category, String query, String amount, int editorPage, boolean editorContext) { this(from,through,page,category,query,amount,editorPage,editorContext,""); }
    public HistoryCursor(LocalDate from, LocalDate through, int page) { this(from, through, page, "", "", "", 0, false); }
    public HistoryCursor {
        if (page < 0 || editorPage < 0 || (from == null) != (through == null)
                || (from != null && from.isAfter(through))) throw new IllegalArgumentException("Invalid history cursor");
        if(kind==null || !java.util.Set.of("", "EXPENSE", "INCOME").contains(kind)) throw new IllegalArgumentException("Invalid history kind");
        java.util.Objects.requireNonNull(category); java.util.Objects.requireNonNull(query); java.util.Objects.requireNonNull(amount);
    }
    public static HistoryCursor empty() { return new HistoryCursor(null, null, 0); }
}
