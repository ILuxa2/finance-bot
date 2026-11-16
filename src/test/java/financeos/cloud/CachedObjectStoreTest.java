package financeos.cloud;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.*;
import java.io.IOException;
public final class CachedObjectStoreTest {
    public static void main(String[] args) throws Exception {
        var value=new AtomicReference<byte[]>("first".getBytes(StandardCharsets.UTF_8));
        var tag=new AtomicReference<String>("\"one\"");var error=new AtomicBoolean();var loseWrite=new AtomicBoolean();
        var bodies=new AtomicInteger();var unchanged=new AtomicInteger();var reads=new AtomicInteger();
        var server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),0),0);
        server.createContext("/test-bucket/state.json",x->{try{
            if(!"Bearer test-token".equals(x.getRequestHeaders().getFirst("Authorization"))){x.sendResponseHeaders(403,-1);return;}
            if(x.getRequestMethod().equals("GET")){
                reads.incrementAndGet();
                if(error.get()){x.sendResponseHeaders(503,-1);return;}
                if(value.get()==null){x.sendResponseHeaders(404,-1);return;}
                if(tag.get().equals(x.getRequestHeaders().getFirst("If-None-Match"))){unchanged.incrementAndGet();x.sendResponseHeaders(304,-1);return;}
                bodies.incrementAndGet();x.getResponseHeaders().set("ETag",tag.get());x.sendResponseHeaders(200,value.get().length);x.getResponseBody().write(value.get());
            }else if(x.getRequestMethod().equals("PUT")){
                if(!tag.get().equals(x.getRequestHeaders().getFirst("If-Match"))){x.sendResponseHeaders(412,-1);return;}
                value.set(x.getRequestBody().readAllBytes());tag.set("\"write-"+reads.get()+"\"");
                if(loseWrite.getAndSet(false)){x.sendResponseHeaders(503,-1);return;}
                x.getResponseHeaders().set("ETag",tag.get());x.sendResponseHeaders(200,-1);
            }else x.sendResponseHeaders(405,-1);
        }finally{x.close();}});
        server.start();
        try{
            var memory=new CloudMemoryCache();var store=new ObjectStorageStateStore(HttpClient.newHttpClient(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()),"test-bucket","state.json",()->"test-token",memory);
            expect("first",store);expect("first",store);
            if(bodies.get()!=1||unchanged.get()!=1||reads.get()!=2)throw new AssertionError("Cache skipped validation or downloaded unchanged bytes");
            value.set("external".getBytes(StandardCharsets.UTF_8));tag.set("\"two\"");expect("external",store);
            error.set(true);try{store.load();throw new AssertionError("Cached financial data served on validation error");}catch(IOException expected){}
            error.set(false);expect("external",store);
            if(!store.save("\"two\"","own".getBytes(StandardCharsets.UTF_8)))throw new AssertionError("Valid CAS rejected");
            int before=bodies.get();expect("own",store);if(bodies.get()!=before)throw new AssertionError("Acknowledged write was not cached");
            value.set("other-writer".getBytes(StandardCharsets.UTF_8));tag.set("\"three\"");
            if(store.save("\"two\"","stale".getBytes(StandardCharsets.UTF_8)))throw new AssertionError("Stale CAS accepted");expect("other-writer",store);
            loseWrite.set(true);try{store.save("\"three\"","committed".getBytes(StandardCharsets.UTF_8));throw new AssertionError("Lost write acknowledgement accepted");}catch(IOException expected){}
            expect("committed",store);
            value.set(null);if(store.load().isPresent())throw new AssertionError("Deleted object served from cache");
            if(memory.stats().rawEntries()!=0)throw new AssertionError("404 did not invalidate cache");
        }finally{server.stop(0);}
        System.out.println("Validated cache: 304, external writes, no stale fallback, acknowledged/uncertain writes, CAS and deletion passed.");
    }
    private static void expect(String expected,ObjectStorageStateStore store)throws Exception{
        String actual=new String(store.load().orElseThrow().bytes(),StandardCharsets.UTF_8);
        if(!actual.equals(expected))throw new AssertionError("Expected "+expected+" but got "+actual);
    }
}
