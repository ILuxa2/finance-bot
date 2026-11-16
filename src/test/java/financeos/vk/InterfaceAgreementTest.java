package financeos.vk;

import financeos.core.*;
import financeos.data.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.nio.file.*;
import java.io.*;
import javax.imageio.ImageIO;

public final class InterfaceAgreementTest {
    private static long id;
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
    private static BotReply send(LocalBotEngine engine,String text){return engine.handle(new VkEvent("message_new",99,42,42,"agreement-"+(++id),text)).orElseThrow();}
    public static void main(String[] args)throws Exception {
        var expected=List.of("/expense","/income","/receipt","/report_menu","/operations","/plans","/regular_pending","/settings","/help");
        check(MenuCatalog.buttons(Screen.MAIN).stream().map(MenuButton::command).toList().equals(expected),"Main menu changed");
        var now=OffsetDateTime.parse("2026-11-17T12:00:00+03:00");
        var entries=new ArrayList<LegacyUserData.TimedEntry>();
        var names=List.of("Бензин","Ремонт","Продукты","Кафе");
        var amounts=List.of("500","500","600","400");
        for(int i=0;i<names.size();i++)entries.add(new LegacyUserData.TimedEntry(now,new LedgerEntry("agreement-"+i,now.toLocalDate(),EntryKind.EXPENSE,Money.of(amounts.get(i)),names.get(i),"Искусственные данные",false)));
        var data=new LegacyUserData(Money.of("10000"),now.minusDays(10),entries,List.of());
        var config=new CategorySections(List.of("Машина","Питание"),Map.of("Бензин","Машина","Ремонт","Машина","Продукты","Питание","Кафе","Питание"),Map.of(),Set.of());
        var pages=ExpenseChartPages.build(data,config,List.of(),now,YearMonth.from(now));
        check(GroupedExpenseChart.barWidth(Money.of("500"),Money.of("1000"))==565,"50% bar must occupy half the track");
        check(GroupedExpenseChart.barWidth(Money.of("600"),Money.of("1000"))==678,"60% bar must match its percentage");
        check(GroupedExpenseChart.barWidth(BigDecimal.ZERO,BigDecimal.ZERO)==0,"Empty denominator");
        var directory=Path.of(".local-output","interface-2026-11-17");Files.createDirectories(directory);
        for(boolean dark:List.of(true,false)) {
            var png=new GroupedExpenseChart().png(pages,dark);
            var image=ImageIO.read(new ByteArrayInputStream(png));
            int color=image.getRGB(0,0)&0xffffff;
            check(color==(dark?0x080b0e:0xf2f6f5),"Common theme mismatch");
            // A 50% first category occupies exactly half of the full section track.
            int barY=205+83+44;
            check(image.getRGB(620,barY)!=image.getRGB(630,barY),"Rendered bar ignores section denominator");
            Files.write(directory.resolve(dark?"expenses-dark.png":"expenses-light.png"),png);
        }
        var distinct=new ArrayList<byte[]>();
        for(int i=0;i<12;i++) {
            var tile=new java.awt.image.BufferedImage(20,10,java.awt.image.BufferedImage.TYPE_INT_RGB);
            var g=tile.createGraphics();try{g.setColor(new java.awt.Color(20+i,30+i,40+i));g.fillRect(0,0,20,10);}finally{g.dispose();}
            var bytes=new ByteArrayOutputStream();ImageIO.write(tile,"png",bytes);distinct.add(bytes.toByteArray());
        }
        var composed=ChartAttachments.fit(distinct);check(composed.size()==6,"Large bundle must fit one message");
        for(int i=0;i<6;i++) {
            var composite=ImageIO.read(new ByteArrayInputStream(composed.get(i)));
            check(composite.getHeight()==20,"Composite lost a chart");
            check((composite.getRGB(5,5)&255)==40+i*2 && (composite.getRGB(5,15)&255)==41+i*2,"Chart order/content changed");
        }
        var engine=new LocalBotEngine(42,data,Clock.fixed(now.toInstant(),ZoneId.of("Europe/Moscow")));
        var before=engine.exportState();
        var help=send(engine,"/help");
        check(help.text().contains("500.50")&&!help.text().contains("/expense"),"Help defaults to command dump");
        check(send(engine,"/commands").text().contains("/expense"),"Alternative commands lost");
        send(engine,"/settings");check(send(engine,"/preferences").screen()==Screen.PREFERENCES,"Preferences is not separate from More");
        check(send(engine,"/back").screen()==Screen.SETTINGS,"Back does not return to More");
        var chart=send(engine,"/report_chart");check(chart.images().size()==2,"Expense-only report must contain grouped expense and comparison charts");
        check(before.entries().equals(engine.exportState().entries())&&before.plans().equals(engine.exportState().plans()),"Views mutated finances");
        MenuCatalogTest.main(args);
        System.out.println("Agreed interface: exact main menu, stable Back footer, help/commands, preferences, grouped chart scale/themes and read-only reports passed.");
    }
}
