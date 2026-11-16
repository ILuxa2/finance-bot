package financeos.vk;

import financeos.core.*;
import financeos.data.*;
import java.time.*;
import java.nio.file.*;
import java.util.*;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;

public final class PlanningExperienceTest {
    private static long id;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-07T09:00:00Z"), ZoneId.of("Europe/Moscow"));
    private static BotReply send(LocalBotEngine engine, String text) { return engine.handle(new VkEvent("message_new",99,123,123,"experience-"+(++id),text)).orElseThrow(); }
    private static void has(BotReply reply,String value) { if(!reply.text().contains(value)) throw new AssertionError("Missing "+value+" in "+reply.text()); }
    private static LocalBotEngine reload(LocalBotEngine engine,LegacyUserData data,Path path) throws Exception {
        var file=new LocalStateFile(path,123,data); file.save(engine.exportState());
        var restored=new LocalBotEngine(123,data,CLOCK);restored.restoreState(file.load().orElseThrow());return restored;
    }
    public static void main(String[] args) throws Exception {
        var data=new LegacyUserData(Money.of("50000"),OffsetDateTime.parse("2026-11-01T00:00:00+03:00"),List.of(),
                List.of(new BudgetPlan(YearMonth.of(2026,10),"Еда",Money.of("3000")),new BudgetPlan(YearMonth.of(2026,10),"Транспорт",Money.of("1000"))));
        var engine=new LocalBotEngine(123,data,CLOCK); var dir=Files.createTempDirectory("planning-experience-"); var path=dir.resolve("state.json");
        try {
            send(engine,"/plan_copy_previous");send(engine,"/plan_copy_edit");send(engine,"Еда");
            engine=reload(engine,data,path);has(send(engine,"Продукты; 4200.50"),"Продукты · 4200.50");
            if(!engine.exportState().plans().isEmpty())throw new AssertionError("Draft already changed plans");
            engine=reload(engine,data,path);has(send(engine,"/plan_copy_confirm"),"План скопирован");
            has(send(engine,"/plan_fact_current"),"Баланс на конец месяца по плану · 44 799.50 ₽");has(send(engine,"/plan_categories"),"Баланс на конец месяца по плану · 44 799.50 ₽");has(send(engine,"/plan_fact_current"),"Продукты");has(send(engine,"/report"),"Планы за месяц · 5200.50");
            send(engine,"/plan_copy_next");send(engine,"/plan_copy_edit");send(engine,"Продукты");
            has(send(engine,"Транспорт; 5"),"уже есть");has(send(engine,"Продукты; -1"),"не может быть отрицательной");
            send(engine,"Продукты декабря; 5000");send(engine,"/plan_copy_confirm");
            if(engine.exportState().plans().stream().noneMatch(p->p.month().equals(YearMonth.of(2026,12))&&p.category().equals("Продукты декабря")&&p.amount().compareTo(Money.of("5000"))==0))throw new AssertionError("Future edited copy missing");
            has(send(engine,"/plan_edit"),"2026-12");send(engine,"Продукты декабря");send(engine,"/plan_edit_amount");send(engine,"5500");send(engine,"/plan_edit_cancel");
            engine=reload(engine,data,path);send(engine,"/undo");
            if(engine.exportState().plans().stream().anyMatch(p->p.month().equals(YearMonth.of(2026,12))))throw new AssertionError("Batch undo failed");
            send(engine,"/plan_copy_month");has(send(engine,"2026-10"),"текущий или будущий");
            if(send(engine,"/report_menu").screen()!=Screen.REPORT_OPTIONS)throw new AssertionError("Report selector missing");
            if(MenuCatalog.buttons(Screen.MAIN).stream().noneMatch(b->b.command().equals("/report_menu")))throw new AssertionError("Main button bypasses selector");
            send(engine,"/expense");send(engine,"Еда");send(engine,"10, проверка");
            send(engine,"/chart_theme_dark");engine=reload(engine,data,path);
            var dark=send(engine,"/report_chart");var image=ImageIO.read(new ByteArrayInputStream(dark.imagePng()));
            if((image.getRGB(1,1)&0xffffff)!=0x12181c)throw new AssertionError("Dark background missing");
            send(engine,"/chart_theme_light");engine=reload(engine,data,path);
            var light=send(engine,"/chart_expenses");image=ImageIO.read(new ByteArrayInputStream(light.imagePng()));
            if((image.getRGB(1,1)&0xffffff)!=0xf6f7f3)throw new AssertionError("Light preference lost");
            if(send(engine,"/report").imagePng()!=null)throw new AssertionError("Text report contains an image");
            System.out.println("Plan copy edits/months/restart/undo/duplicates, report selector and persisted chart themes passed.");
        } finally { Files.deleteIfExists(path);Files.deleteIfExists(dir); }
    }
}
