package financeos.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;

/** Atomically replaces changed visible tables and their sync marker; refuses external edits. */
public final class GoogleSheetsLedgerSync {
    public static void main(String[] args) throws Exception {
        var mapper=new ObjectMapper();
        var sheets=new LocalSheetBackupReader().read(java.nio.file.Path.of(args[0]));
        var envelope=mapper.readTree(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(args[1])));
        byte[] state=Base64.getDecoder().decode(envelope.path("stateBase64").asText());
        var user=sheets.get("StartupState").getFirst().get("telegram_user_id");
        var temp=java.nio.file.Files.createTempFile("financeos-sync-state-",".json");
        try {
            java.nio.file.Files.write(temp,state);
            var local=new financeos.vk.LocalStateFile(temp,564689957L,new LegacyDataMapper().user(sheets,user)).load().orElseThrow();
            var target=new LegacySheetProjection().project(sheets,user,local,java.time.Instant.now().toString());
            var token=new GoogleServiceAccountToken(java.nio.file.Path.of(args[2]),GoogleServiceAccountToken.Scope.READ_WRITE);
            String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(state));
            if(args.length>4 && "verify".equals(args[4])) {
                var verifier=new GoogleSheetsLedgerSync(URI.create("https://sheets.googleapis.com"),args[3],token);
                var values=verifier.call("GET","/values/"+MARKER+"!A1:C1",null).path("values").get(0);
                if(!hash.equals(values.get(1).asText())) throw new IOException("Cloud and Sheets state hashes differ");
                var visible=new GoogleSheetsReadOnly(URI.create("https://sheets.googleapis.com"),args[3],token).readAll();
                if(!fingerprint(visible).equals(values.get(2).asText())) throw new IOException("Visible table differs from saved marker");
                System.out.println("Cloud state and visible Sheets mirror verified.");
                return;
            }
            new GoogleSheetsLedgerSync(URI.create("https://sheets.googleapis.com"),args[3],token).sync(sheets,target,hash);
            if(args.length>4 && "probe".equals(args[4])) {
                GoogleSheetsCopyProbe.requireTestCopy(args[3]);
                var engine=new financeos.vk.LocalBotEngine(564689957L,new LegacyDataMapper().user(sheets,user),java.time.Clock.system(java.time.ZoneId.of("Europe/Moscow")));
                engine.restoreState(local);
                int seq=0;
                for(String command:List.of("/expense","Проверка синхронизации","1, временная проверка"))
                    engine.handle(new financeos.vk.VkEvent("message_new",242685493L,564689957L,564689957L,"sync-probe-"+(++seq),command));
                var probe=new LegacySheetProjection().project(sheets,user,engine.exportState(),java.time.Instant.now().toString());
                var writer=new GoogleSheetsLedgerSync(URI.create("https://sheets.googleapis.com"),args[3],token);
                writer.sync(sheets,probe,"probe-"+hash);
                writer.sync(sheets,probe,"probe-"+hash);
                engine.handle(new financeos.vk.VkEvent("message_new",242685493L,564689957L,564689957L,"sync-probe-undo","/undo"));
                var undone=new LegacySheetProjection().project(sheets,user,engine.exportState(),java.time.Instant.now().toString());
                writer.sync(sheets,undone,hash);
                System.out.println("Expense, duplicate retry and undo readback passed on test copy.");
            }
            System.out.println("Visible Sheets sync and readback passed.");
        } finally { java.nio.file.Files.deleteIfExists(temp); }
    }
    private static final String MARKER = "FinanceOS_Java_Sync_v1";
    private final URI api;
    private final String id;
    private final Supplier<String> token;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public GoogleSheetsLedgerSync(URI api, String id, Supplier<String> token) {
        if (id == null || !id.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid sheet ID");
        this.api = api; this.id = id; this.token = token;
    }

    public void sync(Map<String,List<Map<String,String>>> base,
                     Map<String,List<Map<String,String>>> target, String stateHash)
            throws Exception {
        var current = new GoogleSheetsReadOnly(api, id, token).readAll();
        var metadata = call("GET", "?fields=sheets(properties(sheetId,title,gridProperties))", null);
        Map<String,Integer> ids = new HashMap<>();
        metadata.path("sheets").forEach(sheet -> ids.put(sheet.path("properties").path("title").asText(),
                sheet.path("properties").path("sheetId").asInt()));
        String expected = fingerprint(base);
        if (ids.containsKey(MARKER)) {
            var values = call("GET", "/values/" + MARKER + "!A1:C1", null).path("values");
            if (!values.isArray() || values.size()!=1 || values.get(0).size()!=3
                    || !"financeos-sync-v1".equals(values.get(0).get(0).asText())) {
                throw new IOException("Unexpected Sheets sync marker; refusing overwrite");
            }
            expected = values.get(0).get(2).asText();
            if (stateHash.equals(values.get(0).get(1).asText())) {
                if (!expected.equals(fingerprint(current))) throw new IOException("Google table changed outside bot");
                return;
            }
        }
        if (!expected.equals(fingerprint(current))) throw new IOException("Google table changed outside bot; merge required");
        List<Object> requests = new ArrayList<>();
        int markerId;
        if (ids.containsKey(MARKER)) markerId=ids.get(MARKER);
        else {
            markerId=1900000000;
            while(ids.containsValue(markerId)) markerId--;
            requests.add(Map.of("addSheet",Map.of("properties",Map.of("sheetId",markerId,"title",MARKER,"hidden",true))));
        }
        for(var item : LegacySheetSchema.headers().entrySet()) {
            String name=item.getKey();
            var headers=item.getValue();
            if (rows(current.get(name),headers).equals(rows(target.get(name),headers))) continue;
            List<Object> rows=new ArrayList<>();
            for(var row:target.get(name)) {
                List<Object> cells=new ArrayList<>();
                for(String header:headers) cells.add(Map.of("userEnteredValue",Map.of("stringValue",row.getOrDefault(header,""))));
                rows.add(Map.of("values",cells));
            }
            requests.add(Map.of("updateCells",Map.of("range",Map.of("sheetId",ids.get(name),
                    "startRowIndex",1,"endRowIndex",Math.max(current.get(name).size(),target.get(name).size())+1,
                    "startColumnIndex",0,"endColumnIndex",headers.size()),"rows",rows,"fields","userEnteredValue")));
        }
        List<Object> markerCells=new ArrayList<>();
        for(String value:List.of("financeos-sync-v1",stateHash,fingerprint(target))) {
            markerCells.add(Map.of("userEnteredValue",Map.of("stringValue",value)));
        }
        requests.add(Map.of("updateCells",Map.of("start",Map.of("sheetId",markerId,"rowIndex",0,"columnIndex",0),
                "rows",List.of(Map.of("values",markerCells)),"fields","userEnteredValue")));
        call("POST",":batchUpdate",Map.of("requests",requests));
        var verified=new GoogleSheetsReadOnly(api,id,token).readAll();
        if(!fingerprint(target).equals(fingerprint(verified))) throw new IOException("Google table readback differs");
    }

    public static String fingerprint(Map<String,List<Map<String,String>>> sheets) throws Exception {
        var canonical=new TreeMap<String,Object>();
        LegacySheetSchema.headers().forEach((name,headers)->canonical.put(name,rows(sheets.get(name),headers)));
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(new ObjectMapper().writeValueAsBytes(canonical)));
    }

    private static List<List<String>> rows(List<Map<String,String>> source,List<String> headers) {
        return source.stream().map(row->headers.stream().map(h->row.getOrDefault(h,"")).toList()).toList();
    }

    private com.fasterxml.jackson.databind.JsonNode call(String method,String path,Object body) throws Exception {
        var request=HttpRequest.newBuilder(api.resolve("/v4/spreadsheets/"+id+path))
                .timeout(Duration.ofSeconds(25)).header("Authorization","Bearer "+token.get());
        if(method.equals("GET")) request.GET();
        else request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body),StandardCharsets.UTF_8));
        var response=http.send(request.build(),HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()<200 || response.statusCode()>=300) throw new IOException("Google sync HTTP "+response.statusCode());
        return mapper.readTree(response.body());
    }
}
