package financeos.vk;
import financeos.core.EntryKind;
import java.math.BigDecimal;
/** Semantic signs never depend on colour alone. */
public final class UiText {
    private UiText() {}
    public static String money(BigDecimal amount) { return amount.setScale(2).toPlainString() + " ₽"; }
    public static String delta(BigDecimal value, boolean higherIsBetter) {
        String marker = value.signum() == 0 ? "⚪" : (value.signum() > 0 == higherIsBetter ? "🟢" : "🔴");
        return marker + " " + (value.signum() > 0 ? "+" : value.signum() < 0 ? "−" : "") + money(value.abs());
    }
    public static String flow(EntryKind kind, BigDecimal amount) {
        return kind == EntryKind.TRANSFER ? "⚪ 0.00 ₽ · Перевод · " + money(amount)
                : delta(kind == EntryKind.INCOME ? amount : amount.negate(), true);
    }
}
