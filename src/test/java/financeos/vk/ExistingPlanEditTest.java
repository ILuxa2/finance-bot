package financeos.vk;
import financeos.core.*;
import financeos.data.*;
import java.time.*;
import java.nio.file.*;
import java.util.*;
public final class ExistingPlanEditTest {
    static long id; static final Clock CLOCK=Clock.fixed(Instant.parse("2026-11-07T10:00:00Z"),ZoneId.of("Europe/Moscow"));
    static BotReply send(LocalBotEngine e,String text){return e.handle(new VkEvent("message_new",99,123,123,"plan-edit-"+(++id),text)).orElseThrow();}
    static void has(BotReply r,String value){if(!r.text().contains(value))throw new AssertionError("Missing "+value+" in "+r.text());}
    static LocalBotEngine reload(LocalBotEngine e,LegacyUserData data,Path path)throws Exception{var file=new LocalStateFile(path,123,data);file.save(e.exportState());var next=new LocalBotEngine(123,data,CLOCK);next.restoreState(file.load().orElseThrow());return next;}
    static void amount(LocalBotEngine e,String category,String value){send(e,"/plan_edit_current");send(e,category);send(e,"/plan_edit_amount");send(e,value);}
    public static void main(String[]a)throws Exception{
        var nov=YearMonth.of(2026,11);var at=OffsetDateTime.parse("2026-11-04T10:00:00+03:00");
        var data=new LegacyUserData(Money.of("10000"),OffsetDateTime.parse("2026-11-01T00:00:00+03:00"),List.of(new LegacyUserData.TimedEntry(at,new LedgerEntry("original",at.toLocalDate(),EntryKind.EXPENSE,Money.of("30"),"Еда","покупка",false))),
            List.of(new BudgetPlan(nov,"Еда",Money.of("1000")),new BudgetPlan(nov,"Транспорт",Money.of("100")),new BudgetPlan(nov.plusMonths(1),"Еда",Money.of("1500")),new BudgetPlan(nov.minusMonths(1),"Еда",Money.of("900"))));
        String original=data.toString();var e=new LocalBotEngine(123,data,CLOCK);var dir=Files.createTempDirectory("existing-plan-edit-");var path=dir.resolve("state.json");
        try{
            send(e,"/plan_fact_current");if(MenuCatalog.buttons(Screen.PLAN_FACT).stream().noneMatch(b->b.command().equals("/plan_edit")))throw new AssertionError("Edit absent in plan/fact");
            send(e,"/plan_edit");send(e,"Еда");send(e,"/plan_edit_amount");has(send(e,"2000"),"1000.00 → 2000.00");
            if(!e.exportState().planEdits().isEmpty())throw new AssertionError("Preview changed budget");
            e=reload(e,data,path);has(send(e,"/plan_edit_confirm"),"План изменён");has(send(e,"/report"),"Свободные деньги · 7900.00");has(send(e,"/report"),"Фактический баланс · 9970.00");
            send(e,"/plan_edit_confirm");if(e.exportState().planEdits().size()!=1)throw new AssertionError("Repeated confirmation duplicated edit");
            send(e,"/plan_edit_current");send(e,"Еда");send(e,"/plan_edit_name");has(send(e," транспорт "),"уже есть");send(e,"Продукты");send(e,"/plan_edit_confirm");
            has(send(e,"/plan_fact_current"),"Продукты\nПлан · 2 000 ₽\nФакт · 30 ₽");has(send(e,"/report"),"Незапланированные расходы за месяц · 0.00");
            e=reload(e,data,path);send(e,"/expense");send(e,"Продукты");send(e,"10, новая покупка");has(send(e,"/plan_fact_current"),"Факт · 40 ₽");
            send(e,"/undo");has(send(e,"/report"),"Фактический баланс · 9970.00");e=reload(e,data,path);send(e,"/undo");has(send(e,"/plan_fact_current"),"Еда\nПлан · 2 000 ₽");
            send(e,"/undo");has(send(e,"/report"),"Свободные деньги · 8900.00");
            send(e,"/plan_fact_previous");has(send(e,"/plan_edit"),"текущий или будущий");
            send(e,"/plan_fact_month");send(e,"2026-12");send(e,"/plan_edit");send(e,"Еда");send(e,"/plan_edit_amount");send(e,"1700");send(e,"/plan_edit_confirm");
            has(send(e,"/plan_fact_month"),"месяц");has(send(e,"2026-12"),"Еда\nПлан · 1 700 ₽");has(send(e,"/report"),"Свободные деньги · 8900.00");
            amount(e,"Еда","-1");if(e.exportState().planEdits().size()!=1)throw new AssertionError("Negative amount changed plan");send(e,"0");send(e,"/plan_edit_cancel");has(send(e,"/plan_fact_current"),"Еда\nПлан · 1 000 ₽");
            amount(e,"Еда","0");send(e,"/plan_edit_confirm");has(send(e,"/plan_fact_current"),"Еда\nПлан · 0 ₽\nФакт · 30 ₽");
            send(e,"/add_plan");send(e,"123, Тест");send(e,"/plan_edit_current");send(e,"Тест");send(e,"/plan_edit_name");send(e,"Тест новый");send(e,"/plan_edit_confirm");
            amount(e,"Тест новый","200");send(e,"/plan_edit_confirm");send(e,"/delete_plan");send(e,"2026-11");send(e,"Прочее");send(e,"Тест новый");has(send(e,"/section_confirm"),"удалён");
            send(e,"/add_plan");send(e,"300, Тест новый");has(send(e,"/plan_fact_current"),"Тест новый\nПлан · 300 ₽");
            send(e,"/undo");send(e,"/undo");has(send(e,"/plan_fact_current"),"Тест новый\nПлан · 200 ₽");
            if(!data.toString().equals(original))throw new AssertionError("Original data changed");
            var stale=new LocalBotEngine(123,data,CLOCK);amount(stale,"Еда","3000");var state=stale.exportState();
            stale.restoreState(new BotLocalState(state.entries(),List.of(new BudgetPlan(nov,"Другое",Money.of("5"))),state.debts(),state.goals(),state.limits(),state.nextEntryId(),state.seenEvents(),state.recurring(),state.pending(),state.pendingCategory(),state.navigation(),state.undoHistory(),state.operationLinks(),state.history(),state.deletedIds(),state.chartTheme(),state.chatCleanup(),state.planEdits()));
            has(send(stale,"/plan_edit_confirm"),"План или месяц изменился");if(!stale.exportState().planEdits().isEmpty())throw new AssertionError("Stale draft applied");
            System.out.println("Existing plans: original/current/future edits, fact aliasing, unchanged balance, confirmation, duplicate/stale/zero guards and persistent undo passed.");
        }finally{Files.deleteIfExists(path);Files.deleteIfExists(dir);}
    }
}
