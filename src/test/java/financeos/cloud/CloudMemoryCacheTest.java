package financeos.cloud;
import financeos.data.*;
import financeos.vk.*;
import financeos.core.*;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
public final class CloudMemoryCacheTest {
    public static void main(String[] args) throws Exception {
        var cache=new CloudMemoryCache();var count=new AtomicInteger();
        byte[] source={1,2,3};var entries=new ArrayList<LegacyUserData.TimedEntry>();
        var data=new LegacyUserData(Money.of("100"),OffsetDateTime.parse("2026-11-01T00:00:00Z"),entries,new ArrayList<BudgetPlan>());
        var first=cache.base(source,()->{count.incrementAndGet();return data;});
        var repeated=cache.base(source.clone(),()->{throw new AssertionError("Identical base was parsed again");});
        if(first!=repeated||count.get()!=1)throw new AssertionError("Parsed base cache missed");
        try{first.transactions().clear();throw new AssertionError("Shared base is mutable");}catch(UnsupportedOperationException expected){}
        source[0]=9;
        var changed=cache.base(source,()->{count.incrementAndGet();return data;});
        if(changed==first||count.get()!=2)throw new AssertionError("Changed base did not invalidate mapping");
        var state=new BotLocalState(List.of(),List.of(),List.of(),List.of(),Map.of(),1,List.of());
        if(cache.state("old",changed,123,()->Optional.of(state)).orElseThrow()!=state)throw new AssertionError("Decoded state missing");
        cache.state("old",changed,123,()->{throw new AssertionError("Same state decoded again");});
        cache.state("new",changed,123,()->{count.incrementAndGet();return Optional.of(state);});
        cache.state("new",changed,456,()->{count.incrementAndGet();return Optional.of(state);});
        if(count.get()!=4)throw new AssertionError("State content/owner was not part of cache identity");
        var anotherBase=new LegacyUserData(data.startingBalance(),data.snapshotTime(),List.of(),List.of());
        cache.state("new",anotherBase,456,()->{count.incrementAndGet();return Optional.empty();});
        if(count.get()!=5)throw new AssertionError("Changed base did not invalidate decoded state");
        try{cache.base(new byte[]{7},()->{throw new java.io.IOException("broken base");});throw new AssertionError("Decode failure hidden");}catch(java.io.IOException expected){}
        var key=URI.create("https://test.example/base");byte[] raw={1};
        cache.remember(key,new ObjectStorageStateStore.Snapshot(raw,"\"1\""));raw[0]=2;
        var snapshot=cache.snapshot(key).orElseThrow();byte[] copy=snapshot.bytes();copy[0]=3;
        if(cache.snapshot(key).orElseThrow().bytes()[0]!=1)throw new AssertionError("Cached bytes escaped by reference");
        cache.remember(URI.create("https://test.example/state"),snapshot);cache.remember(URI.create("https://other.example/state"),snapshot);
        if(cache.stats().rawEntries()!=2||cache.snapshot(key).isPresent())throw new AssertionError("Raw cache is not bounded");
        cache.remember(key,new ObjectStorageStateStore.Snapshot(new byte[4*1024*1024+1],"\"big\""));
        if(cache.snapshot(key).isPresent())throw new AssertionError("Oversize object retained");
        System.out.println("Memory cache: immutable values, content/owner/base invalidation, decode errors and memory bounds passed.");
    }
}
