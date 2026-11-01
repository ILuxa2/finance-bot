package financeos.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import financeos.data.*;
import financeos.vk.LocalStateFile;
import java.net.URI;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Serializes Sheets mirrors while financial state remains durably committed in Object Storage. */
public final class CloudSheetsSync {
    public void sync() throws Exception {
        String credentials=System.getenv("FINANCEOS_GOOGLE_CREDENTIALS");
        if(credentials==null || credentials.isBlank()) return;
        String bucket=System.getenv("FINANCEOS_STATE_BUCKET");
        var iam=new MetadataIamToken();
        var lock=new ObjectStorageStateStore(bucket,"java-v1/sheets-lock.json",iam);
        var mapper=new ObjectMapper();
        String owner=UUID.randomUUID().toString();
        String leaseEtag=null;
        var old=lock.load();
        if(old.isPresent() && mapper.readTree(old.get().bytes()).path("until").asLong()>System.currentTimeMillis())
            throw new java.io.IOException("Sheets sync busy; retry required");
        byte[] lease=mapper.writeValueAsBytes(Map.of("owner",owner,"until",System.currentTimeMillis()+180000));
        if(!lock.save(old.map(ObjectStorageStateStore.Snapshot::etag).orElse(null),lease))
            throw new java.io.IOException("Sheets sync lock conflict");
        var owned=lock.load().orElseThrow();
        if(!owner.equals(mapper.readTree(owned.bytes()).path("owner").asText()))
            throw new java.io.IOException("Sheets sync lock lost");
        leaseEtag=owned.etag();
        Path dir=Files.createTempDirectory("financeos-sheets-");
        try {
            var snapshot=new ObjectStorageStateStore(bucket,System.getenv("FINANCEOS_STATE_KEY"),iam).load().orElseThrow();
            var envelope=mapper.readValue(snapshot.bytes(),CloudVkProcessor.Envelope.class);
            byte[] state=Base64.getDecoder().decode(envelope.stateBase64());
            byte[] base=new ObjectStorageStateStore(bucket,System.getenv("FINANCEOS_BASE_KEY"),iam).load().orElseThrow().bytes();
            Files.write(dir.resolve("base.json"),base);
            Files.write(dir.resolve("state.json"),state);
            Files.writeString(dir.resolve("key.json"),credentials);
            var sheets=new LocalSheetBackupReader().read(dir.resolve("base.json"));
            String user=sheets.get("StartupState").stream().map(r->r.get("telegram_user_id")).filter(Objects::nonNull).distinct().reduce((a,b)->{throw new IllegalStateException("Multiple users");}).orElseThrow();
            var data=new LegacyDataMapper().user(sheets,user);
            var local=new LocalStateFile(dir.resolve("state.json"),Long.parseLong(System.getenv("FINANCEOS_VK_USER_ID")),data).load().orElseThrow();
            var target=new LegacySheetProjection().project(sheets,user,local,Instant.now().toString());
            var token=new GoogleServiceAccountToken(dir.resolve("key.json"),GoogleServiceAccountToken.Scope.READ_WRITE);
            String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(state));
            for(String id:List.of(System.getenv("FINANCEOS_GOOGLE_ORIGINAL_ID"),System.getenv("FINANCEOS_GOOGLE_COPY_ID"))) {
                new GoogleSheetsLedgerSync(URI.create("https://sheets.googleapis.com"),id,token).sync(sheets,target,hash);
            }
        } finally {
            for(String name:List.of("base.json","state.json","key.json")) Files.deleteIfExists(dir.resolve(name));
            Files.deleteIfExists(dir);
            lock.save(leaseEtag,mapper.writeValueAsBytes(Map.of("owner",owner,"until",0)));
        }
    }
}
