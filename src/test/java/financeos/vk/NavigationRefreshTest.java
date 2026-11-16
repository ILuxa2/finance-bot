package financeos.vk;
import financeos.core.*;
import financeos.data.*;
import java.time.*;
import java.util.*;
import java.nio.file.*;

/** Exercise real button commands, durable filters and the financial boundary of read-only navigation. */
public final class NavigationRefreshTest {
    private static long event;
    private static final Clock CLOCK=Clock.fixed(Instant.parse("2026-11-05T10:00:00Z"),ZoneId.of("Europe/Moscow"));
    private static BotReply send(LocalBotEngine e,String value) {
        return e.handle(new VkEvent("message_new",99,123,123,"ui-"+(++event),value)).orElseThrow();
    }
    private static void has(BotReply r,String value) { if(!r.text().contains(value)) throw new AssertionError("Missing "+value+" in "+r.text()); }
    private static LocalBotEngine reload(LocalBotEngine e,LegacyUserData data,Path file) throws Exception {
        var store=new LocalStateFile(file,123,data);store.save(e.exportState());
        var result=new LocalBotEngine(123,data,CLOCK);result.restoreState(store.load().orElseThrow());return result;
    }
    public static void main(String[] args) throws Exception {
        var data=new LegacyUserData(Money.of("1000"),OffsetDateTime.parse("2026-11-01T00:00:00+03:00"),List.of(),List.of(),
                new LegacyExtras(List.of(),List.of(),List.of("Еда","Такси"),Map.of(),Map.of()));
        var e=new LocalBotEngine(123,data,CLOCK);
        for(int i=0;i<12;i++){send(e,"/expense");send(e,"Еда");send(e,(10+i)+", Обед "+i);}
        send(e,"/expense");send(e,"Такси");send(e,"5, поездка");
        var before=e.exportState();
        send(e,"/operation_filters");send(e,"/filter_today");send(e,"/filter_comment");
        var page=send(e,"ОБЕД");
        if(page.buttons().stream().filter(b->b.command().startsWith("/choose/")).count()!=5)throw new AssertionError("Missing editor records");
        if(page.text().contains("поездка"))throw new AssertionError("Search leaked non-matching category");
        var next=page.buttons().stream().filter(b->b.command().startsWith("/choices/")).findFirst().orElseThrow();
        page=send(e,next.command());has(page,"страница 2 из 3");
        var chosen=page.buttons().stream().filter(b->b.command().startsWith("/choose/")).findFirst().orElseThrow();
        send(e,chosen.command());send(e,"/edit_amount");has(send(e,"45"),"Станет · 45.00");
        Path file=Files.createTempFile("financeos-navigation-",".json");
        try {
            e=reload(e,data,file);
            page=send(e,"/edit_confirm");has(page,"Операция изменена");has(page,"страница 2 из 3");
            if(e.exportState().history().editorPage()!=1||!e.exportState().history().query().equals("ОБЕД"))throw new AssertionError("Edit reset browsing position");
            e=reload(e,data,file);
            page=send(e,"/edit_return");has(page,"страница 2 из 3");
            send(e,"/operations");send(e,"/filter_today");send(e,"/filter_amount");
            var amount=send(e,"5.00");has(amount,"поездка");if(amount.text().contains("Обед"))throw new AssertionError("Amount filter ignored");
            send(e,"/operation_filters");send(e,"/filter_today");send(e,"/filter_categories");
            var category=send(e,"Такси");has(category,"поездка");
            if(category.text().contains("Обед"))throw new AssertionError("Category filter ignored");
            var state=e.exportState();
            send(e,"/operations");send(e,"/filter_yesterday");has(send(e,"/filter_apply"),"Операций за выбранное время нет");
            if(!state.entries().equals(e.exportState().entries()))throw new AssertionError("Browsing modified financial records");
            send(e,"/undo");if(!before.entries().equals(e.exportState().entries()))throw new AssertionError("Edit undo did not restore ledger");
            if(!UiText.delta(Money.of("-30"),false).equals("🟢 −30.00 ₽"))throw new AssertionError("Expense reduction has wrong marker");
            if(!UiText.delta(Money.of("0"),true).equals("⚪ 0.00 ₽"))throw new AssertionError("Zero marker wrong");
            if(!UiText.delta(Money.of("-30"),true).equals("🔴 −30.00 ₽"))throw new AssertionError("Income decrease marker wrong");
            var compareEntries = new ArrayList<LegacyUserData.TimedEntry>();
            for (String row : List.of("2026-09-02;INCOME;100", "2026-09-03;EXPENSE;80", "2026-10-02;INCOME;150", "2026-10-03;EXPENSE;50")) {
                var f=row.split(";");var day=LocalDate.parse(f[0]);
                compareEntries.add(new LegacyUserData.TimedEntry(day.atStartOfDay().atOffset(ZoneOffset.ofHours(3)),
                        new LedgerEntry(row,day,EntryKind.valueOf(f[1]),Money.of(f[2]),"Тест","",false)));
            }
            var comparisonData=new LegacyUserData(data.startingBalance(),data.snapshotTime(),compareEntries,List.of(),data.extras());
            String comparison=LocalBotViews.compareMonths(comparisonData,OffsetDateTime.now(CLOCK));
            if(!comparison.contains("Доходы · 🟢 +50.00 ₽") || !comparison.contains("Расходы · 🟢 −30.00 ₽"))throw new AssertionError("Month comparison sign semantics wrong");
            has(send(e,"/report"),"Фактический баланс · 809.00 ₽");
        }finally{Files.deleteIfExists(file);}
        System.out.println("UI filters, exact amount search, page retention across edit/restart, financial undo and semantic markers passed.");
    }
}
