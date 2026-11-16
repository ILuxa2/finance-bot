package financeos.core;
import financeos.data.*;
import java.util.*;
public record CategorySections(List<String> names,Map<String,String> assignments,Map<String,String> aliases,Set<String> archived,String setup) {
    public static final String OTHER="Прочее";
    public CategorySections(List<String> names,Map<String,String> assignments,Map<String,String> aliases,Set<String> archived){this(names,assignments,aliases,archived,null);}
    public CategorySections {
        setup=setup==null?(names.isEmpty()&&assignments.isEmpty()&&aliases.isEmpty()?"UNSEEN":"READY"):setup;
        if(!Set.of("UNSEEN","SKIPPED","READY").contains(setup))throw new IllegalArgumentException("Invalid setup state");
        names=List.copyOf(names);assignments=Map.copyOf(assignments);aliases=Map.copyOf(aliases);archived=Set.copyOf(archived);
        var seen=new HashSet<String>();for(var name:names){valid(name,40);if(!seen.add(key(name)))throw new IllegalArgumentException("Duplicate section");}
        var categories=new HashSet<String>();for(var entry:assignments.entrySet()){valid(entry.getKey(),80);valid(entry.getValue(),40);if(!categories.add(key(entry.getKey())))throw new IllegalArgumentException("Duplicate category mapping");if(!seen.contains(key(entry.getValue()))&&!key(entry.getValue()).equals(key(OTHER)))throw new IllegalArgumentException("Unknown section");}
        for(var entry:aliases.entrySet()){valid(entry.getKey(),80);valid(entry.getValue(),80);String value=entry.getValue();var visited=new HashSet<String>(Set.of(key(entry.getKey())));for(int i=0;i<=aliases.size();i++){if(!visited.add(key(value)))throw new IllegalArgumentException("Cyclic category aliases");String next=null;for(var e:aliases.entrySet())if(key(e.getKey()).equals(key(value)))next=e.getValue();if(next==null)break;value=next;}}
    }
    static void valid(String s,int n){if(s==null||s.isBlank()||s.codePointCount(0,s.length())>n)throw new IllegalArgumentException("Invalid name");}
    public static String key(String s){return java.text.Normalizer.normalize(s.strip().replaceAll("\\s+"," "),java.text.Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);}
    public static CategorySections empty(){return new CategorySections(List.of(),Map.of(),Map.of(),Set.of());}
    public CategorySections skipSetup(){return new CategorySections(names,assignments,aliases,archived,"SKIPPED");}
    public boolean enabled(){return !names.isEmpty()||!assignments.isEmpty()||!aliases.isEmpty();}
    public String canonical(String value){for(int i=0;i<=aliases.size();i++){String next=null;for(var e:aliases.entrySet())if(key(e.getKey()).equals(key(value)))next=e.getValue();if(next==null)return value.strip().replaceAll("\\s+"," ");value=next;}throw new IllegalArgumentException("Cyclic aliases");}
    public boolean hidden(String value){String k=key(canonical(value));return archived.stream().anyMatch(v->key(canonical(v)).equals(k));}
    public String section(String category){String k=key(canonical(category));return assignments.entrySet().stream().filter(e->key(canonical(e.getKey())).equals(k)).map(Map.Entry::getValue).findFirst().orElse(OTHER);}
    public List<String> allSections(){var result=new ArrayList<>(names);if(result.stream().noneMatch(n->key(n).equals(key(OTHER))))result.add(OTHER);return List.copyOf(result);}
    public CategorySections create(String name){valid(name,40);if(allSections().stream().anyMatch(n->key(n).equals(key(name))))throw new IllegalArgumentException("Раздел уже существует.");var n=new ArrayList<>(names);n.add(name.strip());return new CategorySections(n,assignments,aliases,archived);}
    public CategorySections assign(String category,String section){String target=allSections().stream().filter(n->key(n).equals(key(section))).findFirst().orElseThrow();var m=new LinkedHashMap<>(assignments);String name=canonical(category);m.keySet().removeIf(k->key(canonical(k)).equals(key(name)));m.put(name,target);var a=new HashSet<>(archived);a.removeIf(k->key(canonical(k)).equals(key(name)));return new CategorySections(names,m,aliases,a);}
    public CategorySections renameSection(String before,String after){valid(after,40);if(key(before).equals(key(OTHER)))throw new IllegalArgumentException("Раздел Прочее сохраняется для нераспределённых категорий.");if(allSections().stream().anyMatch(n->!key(n).equals(key(before))&&key(n).equals(key(after))))throw new IllegalArgumentException("Раздел уже существует.");var n=names.stream().map(v->key(v).equals(key(before))?after.strip():v).toList();var m=new LinkedHashMap<>(assignments);m.replaceAll((k,v)->key(v).equals(key(before))?after.strip():v);return new CategorySections(n,m,aliases,archived);}
    public CategorySections deleteSection(String name){if(key(name).equals(key(OTHER)))throw new IllegalArgumentException("Раздел Прочее удалить нельзя.");var n=names.stream().filter(v->!key(v).equals(key(name))).toList();var m=new LinkedHashMap<>(assignments);m.replaceAll((k,v)->key(v).equals(key(name))?OTHER:v);return new CategorySections(n,m,aliases,archived);}
    public CategorySections archive(String name){var a=new HashSet<>(archived);a.add(canonical(name));return new CategorySections(names,assignments,aliases,a);}
    public CategorySections renameCategory(String before,String after,String section){valid(after,80);String old=canonical(before);if(key(old).equals(key(after)))return this;var aliasesNext=new LinkedHashMap<String,String>();for(var e:aliases.entrySet())if(!key(e.getKey()).equals(key(after))){aliasesNext.put(e.getKey(),key(canonical(e.getKey())).equals(key(old))?after.strip():e.getValue());}aliasesNext.put(old,after.strip());var m=new LinkedHashMap<>(assignments);m.keySet().removeIf(k->key(canonical(k)).equals(key(old)));m.put(after.strip(),section);var a=new HashSet<>(archived);boolean hidden=hidden(old);a.removeIf(k->key(canonical(k)).equals(key(old)));if(hidden)a.add(after.strip());return new CategorySections(names,m,aliasesNext,a);}
    public LegacyUserData apply(LegacyUserData data){
        var entries=data.transactions().stream().map(t->{var e=t.entry();if(e.kind()==EntryKind.TRANSFER)return t;return new LegacyUserData.TimedEntry(t.timestamp(),new LedgerEntry(e.id(),e.date(),e.kind(),e.amount(),canonical(e.category()),e.description(),e.planned()));}).toList();
        var plans=data.plans().stream().map(p->new BudgetPlan(p.month(),canonical(p.category()),p.amount())).toList();
        return new LegacyUserData(data.startingBalance(),data.snapshotTime(),entries,plans,data.extras());
    }
}
