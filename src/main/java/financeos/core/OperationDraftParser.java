package financeos.core;

public final class OperationDraftParser {
    public OperationDraft parse(String input) {
        if (input == null) {
            throw new IllegalArgumentException("Enter: amount, message");
        }
        int comma = input.indexOf(',');
        if (comma <= 0 || comma == input.length() - 1) {
            throw new IllegalArgumentException("Enter: amount, message");
        }
        String amount = input.substring(0, comma).trim();
        String description = input.substring(comma + 1).trim();
        if (description.isBlank()) {
            throw new IllegalArgumentException("Message cannot be empty");
        }
        var parsed = Money.of(amount);
        if (parsed.signum() <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        return new OperationDraft(parsed, description);
    }
}
