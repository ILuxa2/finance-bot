package financeos.cloud;
import financeos.vk.*;
public final class ReceiptSharedTest {
 static BotReply send(long actor,String text)throws Exception{return SharedChatStateTest.send(actor,text);}
 static BotReply click(long actor,BotReply reply,String label)throws Exception{return send(actor,reply.buttons().stream().filter(b->b.label().equals(label)).findFirst().orElseThrow().command());}
 static String firstCategory(BotReply reply){return reply.buttons().stream().filter(b->b.command().contains("/cat-")).findFirst().orElseThrow().command();}
 public static void main(String[] args)throws Exception{
  SharedChatStateTest.main(args);int count=SharedChatStateTest.root.path("entries").size();
  send(42,"/receipt");var owner=send(42,"100");send(77,"/receipt");var member=send(77,"200");
  owner=click(42,owner,"Одной категорией");owner=send(42,firstCategory(owner));String ownerSave=owner.buttons().stream().filter(b->b.label().equals("✅ Сохранить чек")).findFirst().orElseThrow().command();
  member=click(77,member,"Одной категорией");member=send(77,firstCategory(member));String memberSave=member.buttons().stream().filter(b->b.label().equals("✅ Сохранить чек")).findFirst().orElseThrow().command();
  send(77,ownerSave);if(SharedChatStateTest.root.path("entries").size()!=count)throw new AssertionError("Cross-member receipt committed");
  send(42,ownerSave);send(77,memberSave);if(SharedChatStateTest.root.path("entries").size()!=count+2)throw new AssertionError("Independent receipts lost");
  send(42,ownerSave);send(77,memberSave);if(SharedChatStateTest.root.path("entries").size()!=count+2)throw new AssertionError("Receipts repeated");
  System.out.println("Shared receipts: independent persisted drafts, cross-user buttons and duplicate confirmation passed.");
 }
}
