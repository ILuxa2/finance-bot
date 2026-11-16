package financeos.vk;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;

/** One image with every category. Each bar is measured against its own section total. */
public final class GroupedExpenseChart {
    private static final int WIDTH = 1250, BAR_WIDTH = 1130;
    private static final Color[] ACCENTS = {new Color(101,167,255),new Color(53,206,170),new Color(189,151,246),new Color(246,180,92)};

    static int barWidth(BigDecimal amount, BigDecimal sectionTotal) {
        if (sectionTotal.signum() <= 0 || amount.signum() <= 0) return 0;
        return Math.max(1, Math.min(BAR_WIDTH, amount.multiply(BigDecimal.valueOf(BAR_WIDTH))
                .divide(sectionTotal, 0, java.math.RoundingMode.HALF_UP).intValueExact()));
    }

    public byte[] png(List<ExpenseChartPages.Page> pages, boolean dark) {
        var groups = new LinkedHashMap<String, List<ExpenseChartPages.Page>>();
        pages.stream().filter(p -> !p.section().isBlank()).forEach(p ->
                groups.computeIfAbsent(p.section(), key -> new ArrayList<>()).add(p));
        if (groups.isEmpty()) throw new IllegalArgumentException("No expense categories");
        int rows = groups.values().stream().flatMap(List::stream).mapToInt(p -> p.chart().labels().size()).sum();
        int height = Math.addExact(275, Math.addExact(groups.size() * 125, rows * 76));
        // Bound allocations before decoding/allocating. Never silently omit categories.
        if (height > 30000) throw new IllegalArgumentException("Слишком много категорий для одной диаграммы. Выберите меньший период.");
        var image = new BufferedImage(WIDTH, height, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        Color background = dark ? new Color(8,11,14) : new Color(242,246,245);
        Color panel = dark ? new Color(19,25,31) : Color.WHITE;
        Color text = dark ? new Color(237,242,244) : new Color(25,45,54);
        Color muted = dark ? new Color(169,179,186) : new Color(78,104,99);
        Color track = dark ? new Color(40,49,58) : new Color(231,238,236);
        BigDecimal grand = groups.values().stream().map(parts -> parts.getFirst().total()).reduce(BigDecimal.ZERO,BigDecimal::add);
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(background);g.fillRect(0,0,WIDTH,height);
            g.setColor(text);g.setFont(new Font("SansSerif",Font.BOLD,29));
            g.drawString("РАСХОДЫ ПО РАЗДЕЛАМ И КАТЕГОРИЯМ",52,65);
            g.setFont(new Font("SansSerif",Font.PLAIN,23));g.setColor(muted);
            String title=pages.getFirst().chart().title();
            g.drawString(title,52,108);
            g.setColor(text);g.setFont(new Font("SansSerif",Font.BOLD,33));
            g.drawString("Всего расходов · "+PlanFactView.money(grand),52,169);
            int y=205,index=0;
            for(var item:groups.entrySet()) {
                var parts=item.getValue();var total=parts.getFirst().total();
                int count=parts.stream().mapToInt(p->p.chart().labels().size()).sum();
                int cardHeight=100+count*76;
                Color accent=ACCENTS[index++%ACCENTS.length];
                g.setColor(panel);g.fillRoundRect(30,y,WIDTH-60,cardHeight,24,24);
                g.setColor(accent);g.fillRoundRect(50,y+24,7,32,6,6);
                g.setFont(new Font("SansSerif",Font.BOLD,25));
                String summary=PlanFactView.money(total)+" · "+ExpenseChartPages.percent(total,grand)+" % всех расходов";
                int summaryWidth=g.getFontMetrics().stringWidth(summary);
                g.drawString(ChartRenderer.fit(item.getKey(),g.getFontMetrics(),WIDTH-summaryWidth-160),75,y+49);
                g.drawString(summary,WIDTH-60-summaryWidth,y+49);
                int rowY=y+83;
                for(var part:parts)for(int i=0;i<part.chart().labels().size();i++) {
                    var amount=part.chart().series().getFirst().values().get(i);
                    String detail=PlanFactView.money(amount)+" · "+ExpenseChartPages.percent(amount,total)+" % раздела";
                    g.setFont(new Font("SansSerif",Font.PLAIN,23));
                    int detailWidth=g.getFontMetrics().stringWidth(detail);
                    g.setColor(text);g.drawString(ChartRenderer.fit(part.chart().labels().get(i),g.getFontMetrics(),BAR_WIDTH-detailWidth-30),60,rowY+23);
                    g.setColor(muted);g.drawString(detail,WIDTH-60-detailWidth,rowY+23);
                    g.setColor(track);g.fillRoundRect(60,rowY+39,BAR_WIDTH,12,10,10);
                    g.setColor(accent);int width=barWidth(amount,total);if(width>0)g.fillRoundRect(60,rowY+39,width,12,10,10);
                    rowY+=76;
                }
                y+=cardHeight+25;
            }
            g.setFont(new Font("SansSerif",Font.PLAIN,21));g.setColor(muted);
            g.drawString("Полная полоса · 100 % расходов своего раздела",52,height-30);
            var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);return out.toByteArray();
        } catch(java.io.IOException error) {throw new IllegalStateException("Could not render grouped expenses",error);}
        finally {g.dispose();}
    }
}
