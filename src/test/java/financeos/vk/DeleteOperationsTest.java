package financeos.vk;
import financeos.core.*;
import financeos.data.*;
import java.time.*;
import java.nio.file.*;
import java.util.*;
public final class DeleteOperationsTest {
    private static long sequence;
    private static final Clock CLOCK=Clock.fixed(Instant.parse("2026-11-10T09:00:00Z"),ZoneId.of("Europe/Moscow"));
    private static LegacyUserData.TimedEntry entry(String id,String day,EntryKind kind,String amount,String note) {
        var at=OffsetDateTime.parse(day+"T10:00:00+03:00");return new LegacyUserData.TimedEntry(at,new LedgerEntry(id,at.toLocalDate(),kind,Money.of(amount),"Еда",note,false));
    }
    private static final LegacyUserData DATA=new LegacyUserData(Money.of("1000"),OffsetDateTime.parse("2026-11-01T00:00:00+03:00"),
        List.of(entry("old-expense","2026-10-31",EntryKind.EXPENSE,"100","старый расход"),entry("old-income","2026-10-30",EntryKind.INCOME,"200","старый доход"),entry("post-expense","2026-11-02",EntryKind.EXPENSE,"50","новый расход")),
        List.of(),new LegacyExtras(List.of(new Debt("Кредит",Money.of("500"),Money.of("100"),19,true)),List.of(new Goal("Резерв",Money.of("1000"),Money.of("100"),Money.of("50"),true)),List.of("Еда"),Map.of(),Map.of()));
    private static BotReply send(LocalBotEngine e,String s){return e.handle(new VkEvent("message_new",99,123,123,"delete-test-"+(++sequence),s)).orElseThrow();}
    private static void has(BotReply r,String s){if(!r.text().contains(s))throw new AssertionError("Missing "+s+" in "+r.text());}
    private static void balance(LocalBotEngine e,String expected){has(send(e,"/report"),"Фактический баланс · "+Money.of(expected).setScale(2).toPlainString()+" ₽");}
    private static String last(LocalBotEngine e){return e.exportState().entries().getLast().entry().id();}
    private static void select(LocalBotEngine e,String id){send(e,"/edit_operations");has(send(e,id),"Выберите, что изменить");}
    private static void remove(LocalBotEngine e,String id){select(e,id);has(send(e,"/edit_delete"),"Удалить операцию?");has(send(e,"/edit_delete_confirm"),"Операция удалена");}
    private static LocalBotEngine reload(LocalBotEngine e,Path file)throws Exception{var store=new LocalStateFile(file,123,DATA);store.save(e.exportState());var n=new LocalBotEngine(123,DATA,CLOCK);n.restoreState(store.load().orElseThrow());return n;}
    public static void main(String[] args)throws Exception {
        Path file=Files.createTempFile("financeos-deletion-",".json");
        try {
            var e=new LocalBotEngine(123,DATA,CLOCK);balance(e,"950");
            if(MenuCatalog.buttons(Screen.ADD).stream().anyMatch(b->b.command().equals("/delete_operation")))throw new AssertionError("Delete still shown under Add");
            if(MenuCatalog.buttons(Screen.EDITOR_CARD).stream().noneMatch(b->b.command().equals("/edit_delete")))throw new AssertionError("Delete absent from operation card");
            select(e,"old-expense");var before=e.exportState();send(e,"/edit_delete");
            if(!before.entries().equals(e.exportState().entries())||!e.exportState().deletedIds().isEmpty())throw new AssertionError("Preview deleted an operation");
            e=reload(e,file);send(e,"/edit_delete_cancel");balance(e,"950");
            select(e,"old-expense");send(e,"/edit_delete");send(e,"/back");balance(e,"950");
            remove(e,"old-expense");balance(e,"1050");e=reload(e,file);
            if(send(e,"/history_all").text().contains("старый расход"))throw new AssertionError("Deleted base entry still visible");
            has(send(e,"/edit_delete_confirm"),"Нет удаления");balance(e,"1050");send(e,"/undo");balance(e,"950");
            select(e,"old-expense");send(e,"/edit_amount");send(e,"150");send(e,"/edit_confirm");balance(e,"900");
            remove(e,"old-expense");balance(e,"1050");send(e,"/undo");balance(e,"900");send(e,"/undo");balance(e,"950");
            remove(e,"old-income");balance(e,"750");send(e,"/undo");balance(e,"950");
            remove(e,"post-expense");balance(e,"1000");send(e,"/undo");balance(e,"950");
            send(e,"/expense");send(e,"Еда");send(e,"20, проверка");String expense=last(e);balance(e,"930");
            remove(e,expense);balance(e,"950");send(e,"/undo");balance(e,"930");send(e,"/undo");balance(e,"950");
            send(e,"/income");send(e,"Еда");send(e,"100, проверка дохода");String income=last(e);balance(e,"1050");
            remove(e,income);balance(e,"950");send(e,"/undo");send(e,"/undo");balance(e,"950");
            send(e,"/fund_goal");send(e,"Резерв; 50");String fund=last(e);balance(e,"900");
            select(e,fund);send(e,"/edit_delete");e=reload(e,file);send(e,"/edit_delete_confirm");balance(e,"950");has(send(e,"/goal_list"),"накоплено · 100.00");
            e=reload(e,file);send(e,"/undo");balance(e,"900");has(send(e,"/goal_list"),"накоплено · 150.00");send(e,"/undo");balance(e,"950");
            send(e,"/pay_debt");send(e,"Кредит; 100");String debt=last(e);balance(e,"850");remove(e,debt);balance(e,"950");has(send(e,"/debt_list"),"осталось · 500.00");
            send(e,"/undo");has(send(e,"/debt_list"),"осталось · 400.00");send(e,"/undo");balance(e,"950");
            send(e,"/pay_debt");send(e,"Кредит; 500");debt=last(e);balance(e,"450");remove(e,debt);balance(e,"950");if(!e.exportState().debts().getFirst().active())throw new AssertionError("Deleted final payment did not reopen debt");send(e,"/undo");send(e,"/undo");
            send(e,"/transfer");send(e,"40, проверка перевода");String transfer=last(e);remove(e,transfer);balance(e,"950");send(e,"/undo");send(e,"/undo");
            for(int i=0;i<8;i++){send(e,"/expense");send(e,"Еда");send(e,"1, строка "+i);}
            var page=send(e,"/edit_operations");page=send(e,page.buttons().stream().filter(b->b.command().startsWith("/choices/")).findFirst().orElseThrow().command());
            page=send(e,page.buttons().stream().filter(b->b.command().endsWith("/2")).findFirst().orElseThrow().command());
            var button=page.buttons().stream().filter(b->b.command().startsWith("/choose/")).findFirst().orElseThrow();send(e,button.command());send(e,"/edit_delete");
            has(send(e,"/edit_delete_confirm"),"страница 2 из 2");if(e.exportState().history().editorPage()!=1)throw new AssertionError("Deletion did not clamp final page");
            if(DATA.transactions().size()!=3||DATA.transactions().getFirst().entry().amount().compareTo(Money.of("100"))!=0)throw new AssertionError("Base snapshot changed");
        }finally{Files.deleteIfExists(file);}
        System.out.println("Deletion: preview/cancel, snapshot boundaries, edited originals, every entry kind, linked balances, restart, undo and page clamp passed.");
    }
}
