package financeos.cloud;
import financeos.core.*;import financeos.data.*;import financeos.vk.*;import com.fasterxml.jackson.databind.*;import com.fasterxml.jackson.databind.node.*;import java.time.*;import java.nio.file.*;import java.util.*;
public final class FinanceSnapshotsTest {
 public static void main(String[]a)throws Exception {
  var m=new ObjectMapper();var at=Instant.parse("2026-11-13T09:00:00Z");var data=new LegacyUserData(Money.of("1000"),OffsetDateTime.parse("2026-11-01T00:00:00+03:00"),List.of(),List.of());var e=new LocalBotEngine(42,data,Clock.fixed(at,ZoneId.of("Europe/Moscow")));var path=Files.createTempFile("financial-recovery-",".json");try{
   var f=new LocalStateFile(path,42,data);f.save(e.exportState());byte[] initial=Files.readAllBytes(path);var view=(ObjectNode)m.readTree(initial);view.put("pending","INCOME_CATEGORY");view.put("historyPage",20);view.putArray("seenEvents").add("view-event");view.putObject("memberSessions").putObject("77").put("pending","EXPENSE_AMOUNT");
   if(FinanceSnapshots.changed(Base64.getEncoder().encodeToString(initial),m.writeValueAsBytes(view)))throw new AssertionError("Read-only UI created a finance recovery point");
   e.handle(new VkEvent("message_new",99,42,42,"expense-menu","/expense"));e.handle(new VkEvent("message_new",99,42,42,"expense-cat","Еда"));e.handle(new VkEvent("message_new",99,42,42,"expense-save","100, test"));f.save(e.exportState());byte[] spent=Files.readAllBytes(path);
   if(!FinanceSnapshots.changed(Base64.getEncoder().encodeToString(initial),spent))throw new AssertionError("Financial change has no recovery point");
   byte[] backup=FinanceSnapshots.encode(initial,at);var saved=FinanceSnapshots.validate(backup);if(saved.has("undoHistory")||saved.has("seenEvents")||saved.has("pending")||saved.has("memberSessions"))throw new AssertionError("Technical state copied into finance backup");
   var current=(ObjectNode)m.readTree(spent);current.putArray("seenEvents").add("must-not-replay");current.putObject("memberSessions").putObject("42").put("pending","NONE");byte[] restored=FinanceSnapshots.restore(m.writeValueAsBytes(current),backup);var restoredNode=m.readTree(restored);if(!restoredNode.path("seenEvents").equals(current.path("seenEvents"))||!restoredNode.path("memberSessions").equals(current.path("memberSessions")))throw new AssertionError("Restore rolled back technical state");
   Files.write(path,restored);var state=f.load().orElseThrow();if(!state.entries().isEmpty()||!state.undoHistory().isEmpty())throw new AssertionError("Recovery did not restore finances/reset obsolete undo");
   var corrupted=(ObjectNode)m.readTree(backup);((ObjectNode)corrupted.path("financialState")).put("nextEntryId",999);try{FinanceSnapshots.validate(m.writeValueAsBytes(corrupted));throw new AssertionError("Corrupt backup accepted");}catch(java.io.IOException expected){}
   current.put("vkUserId",77);try{FinanceSnapshots.restore(m.writeValueAsBytes(current),backup);throw new AssertionError("Other budget restored");}catch(java.io.IOException expected){}
   if(!FinanceSnapshots.dailyKey(at).endsWith("2026-11-13.json")||!FinanceSnapshots.eventKey(initial,at).matches("java-v1/restore-points/events/[0-9T]+-[a-f0-9]{64}\\.json"))throw new AssertionError("Invalid recovery key");
  }finally{Files.deleteIfExists(path);}
  System.out.println("Financial recovery: views ignored, mutations detected, compact verified snapshots, restorable finance, original budget and dedup/session protection passed.");
 }
}
