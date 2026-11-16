package financeos.vk;
import financeos.core.*;
import financeos.data.*;
import java.time.*;
import java.nio.file.*;
import java.util.*;
public final class ChatCleanupFlowTest {
    static long id;
    static final Clock CLOCK=Clock.fixed(Instant.parse("2026-11-07T10:00:00Z"),ZoneId.of("Europe/Moscow"));
    static BotReply send(LocalBotEngine e,String text){return e.handle(new VkEvent("message_new",99,123,2000000001L,"cleanup-"+(++id),text,id)).orElseThrow();}
    static LocalBotEngine load(LocalBotEngine e,LegacyUserData data,Path path)throws Exception{var file=new LocalStateFile(path,123,data);file.save(e.exportState());var next=new LocalBotEngine(123,Set.of(123L,2000000001L),data,CLOCK);next.restoreState(file.load().orElseThrow());return next;}
    public static void main(String[]a)throws Exception{
        var data=new LegacyUserData(Money.of("1000"),OffsetDateTime.parse("2026-11-01T00:00:00+03:00"),List.of(),List.of());
        var e=new LocalBotEngine(123,Set.of(123L,2000000001L),data,CLOCK);var dir=Files.createTempDirectory("chat-cleanup-");var path=dir.resolve("state.json");
        try{
            if(e.exportState().chatCleanup().days()!=0)throw new AssertionError("Cleanup must be opt in");
            if(MenuCatalog.buttons(Screen.PREFERENCES).stream().noneMatch(b->b.command().equals("/chat_cleanup")))throw new AssertionError("Chat button missing");
            if(MenuCatalog.buttons(Screen.CHAT_CLEAR_RESULT).stream().anyMatch(b->b.command().equals("/undo")))throw new AssertionError("Irreversible cleanup offers financial Undo");
            if(MenuCatalog.commandHelp().length()>9000)throw new AssertionError("Help no longer fits a VK message");
            if(send(e,"/chat_clear_confirm").screen()==Screen.CHAT_CLEAR_RESULT)throw new AssertionError("Unconfirmed clear accepted");
            send(e,"/expense");send(e,"Еда");send(e,"10, тест");var financial=e.exportState().entries();
            send(e,"/chat_clear");send(e,"/chat_cleanup");if(e.exportState().pending().equals("CHAT_CLEAR_CONFIRM"))throw new AssertionError("Cancel did not reset confirmation");
            send(e,"/chat_clear");e=load(e,data,path);if(send(e,"/chat_clear_confirm").screen()!=Screen.CHAT_CLEAR_RESULT)throw new AssertionError("Confirmed clear intent missing");
            if(!e.exportState().entries().equals(financial))throw new AssertionError("Clear changed money");
            if(send(e,"/chat_clear_confirm").screen()==Screen.CHAT_CLEAR_RESULT)throw new AssertionError("Stale confirm accepted twice");
            send(e,"/chat_auto_1");if(e.exportState().chatCleanup().days()!=0)throw new AssertionError("Preview enabled deletion");
            e=load(e,data,path);send(e,"/chat_auto_confirm");if(e.exportState().chatCleanup().days()!=1)throw new AssertionError("Confirmed policy lost");
            e=load(e,data,path);send(e,"/undo");if(!e.exportState().entries().isEmpty()||e.exportState().chatCleanup().days()!=1)throw new AssertionError("Financial undo corrupted cleanup preference");
            send(e,"/chat_auto_7");send(e,"/chat_auto_confirm");if(e.exportState().chatCleanup().days()!=7)throw new AssertionError("Seven day policy lost");
            send(e,"/chat_auto_30");send(e,"/chat_auto_confirm");if(e.exportState().chatCleanup().days()!=30)throw new AssertionError("Thirty day policy lost");
            send(e,"/chat_auto_off");e=load(e,data,path);if(e.exportState().chatCleanup().days()!=0)throw new AssertionError("Disable lost after restart");
            System.out.println("Cleanup UI: explicit confirmation, stale buttons, opt-in/off, restart and financial isolation passed.");
        }finally{Files.deleteIfExists(path);Files.deleteIfExists(dir);}
    }
}
