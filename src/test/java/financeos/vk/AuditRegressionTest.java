package financeos.vk;

import financeos.core.*;
import financeos.data.*;
import java.time.*;
import java.util.*;
import java.awt.Font;
import java.awt.image.BufferedImage;

/** Synthetic regressions for QA-001 through QA-004; never contacts VK or live storage. */
public final class AuditRegressionTest {
    static long event;
    static BotReply send(LocalBotEngine engine, String text) {
        return engine.handle(new VkEvent("message_new",99,42,42,"regression-"+(++event),text)).orElseThrow();
    }
    static String button(BotReply reply,String prefix) {
        return reply.buttons().stream().filter(b->b.command().startsWith(prefix+"/")).findFirst().orElseThrow().command();
    }
    static void check(boolean condition,String reason) { if(!condition)throw new AssertionError(reason); }
    static LocalBotEngine fresh() {
        var at=OffsetDateTime.parse("2026-11-13T12:00:00+03:00");
        var data=new LegacyUserData(Money.of("10000"),at.minusDays(1),List.of(),List.of(new BudgetPlan(YearMonth.from(at),"Продукты",Money.of("2000"))));
        var engine=new LocalBotEngine(42,data,Clock.fixed(at.toInstant(),ZoneId.of("Europe/Moscow")));
        send(engine,"/defaults_setup");send(engine,"/defaults_confirm");return engine;
    }
    static BotReply draft(LocalBotEngine e,String category) {
        send(e,"/expense");send(e,"Питание");send(e,category);return send(e,"500, тест");
    }
    public static void main(String[] args) throws Exception {
        for(String action:List.of("keep","expense","custom","cancel","unplanned")) {
            var e=fresh();String old=button(draft(e,"QA A"),"/new_plan_"+action);
            send(e,"/start");var current=draft(e,"QA B");String saved=e.exportState().pendingCategory();
            send(e,old);check(e.exportState().entries().isEmpty(),"Stale button committed another draft");
            check(saved.equals(e.exportState().pendingCategory()),"Stale button replaced current draft");
            send(e,"/new_plan_keep");check(e.exportState().entries().isEmpty(),"Unbound legacy command committed");
            // The same button and draft remain usable after restoring persisted state.
            var state=e.exportState();e=fresh();e.restoreState(state);
            send(e,button(current,"/new_plan_expense"));check(e.exportState().entries().size()==1,"Valid persisted draft did not commit");
            send(e,button(current,"/new_plan_expense"));check(e.exportState().entries().size()==1,"Duplicate confirmation committed twice");
        }
        {
            var legacy=fresh();draft(legacy,"QA Legacy");
            var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
            var json=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(legacy.exportState().pendingCategory());json.remove("nonce");
            var field=LocalBotEngine.class.getDeclaredField("pendingCategory");field.setAccessible(true);field.set(legacy,mapper.writeValueAsString(json));
            send(legacy,"/new_plan_keep");check(legacy.exportState().pending().equals("NONE")&&legacy.exportState().entries().isEmpty(),"Legacy draft not safely expired");
        }
        for(boolean quick:List.of(false,true)) {
            var e=fresh();send(e,"/section_pick");send(e,"Питание");send(e,"/category_new");send(e,"QA Настройки");send(e,"/section_confirm");
            BotReply r;
            if(quick){send(e,"-500, тест");send(e,"/quick_all");r=send(e,"QA Настройки");}
            else r=draft(e,"QA Настройки");
            check(e.exportState().pending().equals("NEW_EXPENSE_PLAN"),"Settings category bypassed planning");
            check(e.exportState().entries().isEmpty(),"Saved before planning confirmation");
            send(e,button(r,"/new_plan_expense"));check(e.exportState().entries().size()==1,"Expense not saved");
            send(e,"/expense");send(e,"Питание");send(e,"QA Настройки");send(e,"100, повтор");
            check(e.exportState().entries().size()==2,"Existing used category asked planning again");
            send(e,"/undo");send(e,"/undo");check(e.exportState().entries().isEmpty(),"Undo failed");
        }
        for(String flow:List.of("/income","/expense","/transfer")) {
            var e=fresh();send(e,flow);
            if(flow.equals("/income"))send(e,"Зарплата");
            if(flow.equals("/expense")){send(e,"Питание");send(e,"Продукты");}
            var r=send(e,"100, "+"Я".repeat(8950));
            check(e.exportState().entries().isEmpty(),"Oversized input saved before confirmation: "+flow);
            check(r.text().length()<=9000,"Oversized validation reply");
        }
        var e=fresh();send(e,"/category_archive_list");var r=send(e,"Я".repeat(8950));
        check(r.text().length()<=9000 && e.exportState().pending().equals("ARCHIVED_CATEGORY_PICK"),"Archive accepted arbitrary oversized name");
        send(e,"/income");send(e,"Зарплата");r=send(e,"100, "+"Я".repeat(500));
        check(e.exportState().entries().size()==1 && r.text().length()<=9000,"Valid 500-character comment rejected");
        var image=new BufferedImage(1,1,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();
        try {
            g.setFont(new Font("Segoe UI",Font.PLAIN,19));
            for(String label:List.of("Ш".repeat(24),"🚗".repeat(30),"Short", "W".repeat(80))) {
                String fitted=ChartRenderer.fit(label,g.getFontMetrics(),221);
                check(g.getFontMetrics().stringWidth(fitted)<=221,"Label overlaps plot");
                check(fitted.isEmpty() || !Character.isHighSurrogate(fitted.charAt(fitted.length()-1)),"Broken surrogate pair");
                if(g.getFontMetrics().stringWidth(label)<=221)check(fitted.equals(label),"Short label changed");
            }
        } finally {g.dispose();}
        System.out.println("QA-001..004 regressions passed (synthetic data only).");
    }
}
