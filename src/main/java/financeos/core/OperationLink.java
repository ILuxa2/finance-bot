package financeos.core;

/** Explicit relation; a comment or category is never used to guess a financial link. */
public record OperationLink(Type type, String target) {
    public enum Type { DEBT_PAYMENT, GOAL_FUND }
    public OperationLink {
        if (type == null || target == null || target.isBlank()) throw new IllegalArgumentException("Invalid operation relation");
    }
}
