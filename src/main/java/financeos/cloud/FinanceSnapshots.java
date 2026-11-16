package financeos.cloud;
import com.fasterxml.jackson.databind.*;import com.fasterxml.jackson.databind.node.*;import java.util.*;import java.time.*;import java.time.format.DateTimeFormatter;import java.security.*;import java.io.*;
/** Compact recovery data: no navigation, outbox images, deduplication or undo-history copies. */
public final class FinanceSnapshots {
 static final ObjectMapper M=new ObjectMapper();
 static final List<String> FIELDS=List.of("vkUserId","baseFingerprint","entries","plans","debts","goals","limits","nextEntryId","nextRuleId","rules","decisions","operationLinks","deletedIds","planEdits","categorySections","operationAuthors");
 public static ObjectNode project(byte[] state)throws IOException{var node=M.readTree(state);if(node==null||!node.isObject()||!node.path("entries").isArray())throw new IOException("Invalid financial source");var out=M.createObjectNode();for(var key:FIELDS)if(node.has(key))out.set(key,node.get(key).deepCopy());return out;}
 public static boolean changed(String before,byte[] after)throws IOException{return !before.isBlank()&&!project(Base64.getDecoder().decode(before)).equals(project(after));}
 static String hash(byte[] bytes){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(Exception e){throw new IllegalStateException(e);}}
 public static byte[] encode(byte[] state,Instant at)throws IOException{var data=project(state);var root=M.createObjectNode();root.put("formatVersion",1);root.put("createdAt",at.toString());root.put("checksum",hash(M.writeValueAsBytes(data)));root.set("financialState",data);return M.writeValueAsBytes(root);}
 public static ObjectNode validate(byte[] bytes)throws IOException{var root=M.readTree(bytes);if(root.path("formatVersion").asInt()!=1||!root.path("financialState").isObject()||!root.path("checksum").asText().equals(hash(M.writeValueAsBytes(root.path("financialState")))))throw new IOException("Recovery checksum mismatch");return (ObjectNode)root.path("financialState");}
 public static byte[] restore(byte[] current,byte[] snapshot)throws IOException{var root=(ObjectNode)M.readTree(current);var saved=validate(snapshot);for(var id:List.of("vkUserId","baseFingerprint"))if(!saved.path(id).equals(root.path(id)))throw new IOException("Recovery belongs to another budget");for(var key:FIELDS){root.remove(key);if(saved.has(key))root.set(key,saved.get(key).deepCopy());}root.putArray("undoHistory");return M.writeValueAsBytes(root);}
 public static String eventKey(byte[] state,Instant at)throws IOException{return "java-v1/restore-points/events/"+DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS").withZone(ZoneOffset.UTC).format(at)+"-"+hash(M.writeValueAsBytes(project(state)))+".json";}
 public static String dailyKey(Instant at){return "java-v1/restore-points/daily/"+at.atZone(ZoneId.of("Europe/Moscow")).toLocalDate()+".json";}
}
