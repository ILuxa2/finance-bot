package financeos.core;
import financeos.data.LegacyUserData;
import java.time.YearMonth;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
public final class PlanEditService {
    public String key(String name){return java.text.Normalizer.normalize(name.strip().replaceAll("\\s+"," "),java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);}
    public String resolve(List<PlanEdit> edits,YearMonth month,String category){for(var edit:edits)if(edit.month().equals(month)&&key(category).equals(key(edit.source())))category=edit.category();return category;}
    public LegacyUserData apply(LegacyUserData data,List<PlanEdit> edits){
        var plans=new ArrayList<>(data.plans());var entries=new ArrayList<>(data.transactions());
        for(var edit:edits){
            boolean exists=plans.stream().anyMatch(p->p.month().equals(edit.month())&&key(p.category()).equals(key(edit.source())));
            if(!exists)continue;
            plans.removeIf(p->p.month().equals(edit.month())&&key(p.category()).equals(key(edit.source())));
            plans.add(new BudgetPlan(edit.month(),edit.category(),edit.amount()));
            entries.replaceAll(t->{var e=t.entry();if(e.kind()!=EntryKind.EXPENSE||!YearMonth.from(e.date()).equals(edit.month())||!key(e.category()).equals(key(edit.source())))return t;
                return new LegacyUserData.TimedEntry(t.timestamp(),new LedgerEntry(e.id(),e.date(),e.kind(),e.amount(),edit.category(),e.description(),e.planned()));});
        }
        return new LegacyUserData(data.startingBalance(),data.snapshotTime(),entries,plans,data.extras());
    }
    public String fingerprint(List<BudgetPlan> plans,YearMonth month){try{
        String text=plans.stream().filter(p->p.month().equals(month)).map(BudgetPlan::toString).sorted().collect(java.util.stream.Collectors.joining("\n"));
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    }catch(java.security.NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
}
