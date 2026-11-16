package financeos.core;

/** Explicit signs distinguish quick entry from ordinary conversation and amount prompts. */
public final class QuickOperationParser {
    public record Draft(EntryKind kind, java.math.BigDecimal amount, String comment) {}
    public static boolean signed(String text) {
        return text != null && !text.isEmpty() && (text.charAt(0)=='+' || text.charAt(0)=='-' || text.charAt(0)=='\u2212');
    }
    public Draft parse(String text) {
        if (text == null) throw new IllegalArgumentException("Missing quick operation");
        text = text.strip();
        if (!signed(text)) throw new IllegalArgumentException("Explicit sign required");
        int comma = text.indexOf(',');
        if (comma < 2) throw new IllegalArgumentException("Use sign amount, comment");
        String amount = text.substring(1, comma).strip(), comment = text.substring(comma+1).strip();
        if (!amount.matches("[0-9]{1,15}(\\.[0-9]{1,2})?") || comment.isBlank() || comment.length()>500)
            throw new IllegalArgumentException("Invalid quick operation");
        var value = Money.of(amount);
        if (value.signum()<=0) throw new IllegalArgumentException("Positive amount required");
        return new Draft(text.charAt(0)=='+' ? EntryKind.INCOME : EntryKind.EXPENSE, value, comment);
    }
}
