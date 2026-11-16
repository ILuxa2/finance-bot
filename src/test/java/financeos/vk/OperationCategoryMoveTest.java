package financeos.vk;

import financeos.core.*;
import financeos.data.*;
import java.time.*;
import java.util.*;
import java.nio.file.*;

public final class OperationCategoryMoveTest {
    private static long id;
    static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    static BotReply send(LocalBotEngine engine,String text){var r=engine.handle(new VkEvent("message_new",99,42,42,"move-"+(++id),text)).orElseThrow();new VkKeyboardFactory().keyboard(r.buttons()==null?MenuCatalog.buttons(r.screen()):r.buttons());return r;}
    static String button(BotReply reply,String label){return reply.buttons().stream().filter(b->b.label().equals(label)).findFirst().orElseThrow().command();}
    static String first(BotReply reply){return reply.buttons().stream().filter(b->b.command().startsWith("/choose/")).findFirst().orElseThrow().command();}
    public static void main(String[] args)throws Exception {
        var now=OffsetDateTime.parse("2026-11-17T12:00:00+03:00");
        var original=new LegacyUserData.TimedEntry(now,new LedgerEntry("old",now.toLocalDate(),EntryKind.EXPENSE,Money.of("100"),"Еда","покупка",false));
        var base=new LegacyUserData(Money.of("1000"),now.minusDays(5),List.of(original),List.of(new BudgetPlan(YearMonth.from(now),"Бензин",Money.of("500"))));
        var engine=new LocalBotEngine(42,base,Clock.fixed(now.toInstant(),ZoneId.of("Europe/Moscow")));
        var config=new CategorySections(List.of("Питание","Машина"),Map.of("Еда","Питание","Бензин","Машина"),Map.of(),Set.of());
        var field=LocalBotEngine.class.getDeclaredField("categorySections");field.setAccessible(true);field.set(engine,config);
        var report=send(engine,"/report").text();
        var list=send(engine,"/edit_operations");send(engine,first(list));send(engine,"/edit_category");
        var sections=send(engine,"/edit_category"); // A stale open command safely returns to the operation list.
        list=send(engine,"/edit_operations");send(engine,first(list));sections=send(engine,"/edit_category");
        send(engine,button(sections,"Машина"));
        check(engine.exportState().pending().equals("EDIT_OPERATION_CATEGORY"),"Section selection lost");
        check(send(engine,"/back").text().contains("название категории"),"Back did not retain move draft");
        var preview=send(engine,"бЕнЗиН");
        check(preview.text().contains("Питание → Еда")&&preview.text().contains("Машина → Бензин"),"Old and new paths missing");
        check(engine.exportState().entries().isEmpty(),"Preview changed finances");
        var path=Files.createTempFile("move-operation-",".json");
        try {
            var file=new LocalStateFile(path,42,base);file.save(engine.exportState());
            engine=new LocalBotEngine(42,base,Clock.fixed(now.toInstant(),ZoneId.of("Europe/Moscow")));engine.restoreState(file.load().orElseThrow());
            send(engine,"/edit_confirm");
            var changed=engine.exportState().entries().getFirst().entry();
            check(changed.category().equals("Бензин")&&changed.amount().equals(original.entry().amount())&&changed.date().equals(original.entry().date())&&changed.description().equals("покупка"),"Move altered other fields");
            var linesBefore=report.lines().filter(l->l.contains("Фактический баланс")).toList();
            check(send(engine,"/report").text().lines().filter(l->l.contains("Фактический баланс")).toList().equals(linesBefore),"Move changed actual balance");
            send(engine,"/edit_confirm");check(engine.exportState().entries().size()==1,"Repeated confirm duplicated operation");
            send(engine,"/undo");check(engine.exportState().entries().isEmpty(),"Undo did not restore original category");
            list=send(engine,"/edit_operations");send(engine,first(list));send(engine,"/edit_category");
            send(engine,"Бензин");send(engine,"/edit_cancel");check(engine.exportState().entries().isEmpty(),"Cancel saved move");
            var editor=new OperationEditor();
            for(var kind:EntryKind.values()) {
                var entry=new LegacyUserData.TimedEntry(now,new LedgerEntry("typed",now.toLocalDate(),kind,Money.of("20"),"Еда","тип",false));
                var data=new LegacyUserData(Money.of("100"),now.minusDays(1),List.of(entry),List.of());
                var result=editor.change(data,List.of(),List.of(),List.of(),Map.of(),"typed",OperationEditor.Field.CATEGORY,"Бензин",now);
                check(result.entries().getFirst().entry().kind()==kind&&result.entries().getFirst().entry().amount().equals(entry.entry().amount()),"Move changed operation type");
            }
            System.out.println("Category move: sections/direct text, preview, back, persistence, confirm/cancel/undo, duplicate guard, all entry kinds and stable balance passed.");
        } finally {Files.deleteIfExists(path);}
    }
}
