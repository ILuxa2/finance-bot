package financeos.core;

import java.math.BigDecimal;

public record OperationDraft(BigDecimal amount, String description) {}
