package financeos.core;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.YearMonth;
import java.util.*;

/** Copies planned amounts, never actual spending or paid/remaining balances. */
public final class PlanCopyService {
    public record Preview(YearMonth source, YearMonth target, List<BudgetPlan> additions, int retained) {
        public Preview { additions = List.copyOf(additions); }
    }
    public Preview preview(List<BudgetPlan> plans, YearMonth target) {
        YearMonth source = target.minusMonths(1);
        var totals = new TreeMap<String, BigDecimal>();
        var names = new LinkedHashMap<String, String>();
        var existing = new HashSet<String>();
        for (var plan : plans) {
            String key = key(plan.category());
            if (plan.month().equals(source)) {
                totals.merge(key, plan.amount(), BigDecimal::add);
                names.putIfAbsent(key, plan.category().strip().replaceAll("\\s+", " "));
            }
            if (plan.month().equals(target)) existing.add(key);
        }
        var additions = new ArrayList<BudgetPlan>();
        int retained = 0;
        for (var item : totals.entrySet()) {
            if (existing.contains(item.getKey())) retained++;
            else additions.add(new BudgetPlan(target, names.get(item.getKey()), item.getValue()));
        }
        return new Preview(source, target, additions, retained);
    }
    public String fingerprint(List<BudgetPlan> plans, YearMonth target) {
        String value = plans.stream().filter(p -> p.month().equals(target) || p.month().equals(target.minusMonths(1)))
                .map(BudgetPlan::toString).sorted().collect(java.util.stream.Collectors.joining("\n"));
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private String key(String category) {
        return Normalizer.normalize(category.strip().replaceAll("\\s+", " "), Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
}
