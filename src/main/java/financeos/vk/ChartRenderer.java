package financeos.vk;

import financeos.core.ChartData;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Locale;
import javax.imageio.ImageIO;

/** Converts chart values into a PNG suitable for local inspection and future VK upload. */
public final class ChartRenderer {
    private static final int WIDTH = 1100;
    private static final int PLOT_X = 285;
    private static final int PLOT_WIDTH = 620;
    private static final Color[] COLORS = {
            new Color(37, 99, 235), new Color(16, 185, 129)
    };

    public byte[] png(ChartData data) {
        if (data.labels().isEmpty()) throw new IllegalArgumentException("Chart has no data");
        if (data.series().size() > COLORS.length) throw new IllegalArgumentException("Too many series");
        int height = Math.max(420, 155 + data.labels().size() * 68);
        var image = new BufferedImage(WIDTH, height, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(new Color(248, 250, 252));
            g.fillRect(0, 0, WIDTH, height);
            g.setColor(Color.WHITE);
            g.fillRoundRect(20, 20, WIDTH - 40, height - 40, 22, 22);
            g.setColor(new Color(15, 23, 42));
            g.setFont(new Font("Segoe UI", Font.BOLD, 28));
            g.drawString(data.title(), 50, 70);
            g.setFont(new Font("Segoe UI", Font.PLAIN, 16));
            int legendX = 50;
            for (int i = 0; i < data.series().size(); i++) {
                g.setColor(COLORS[i]);
                g.fillRoundRect(legendX, 92, 17, 17, 5, 5);
                g.setColor(new Color(71, 85, 105));
                g.drawString(data.series().get(i).name(), legendX + 26, 107);
                legendX += Math.max(170, g.getFontMetrics().stringWidth(data.series().get(i).name()) + 60);
            }
            double max = data.series().stream().flatMap(s -> s.values().stream())
                    .mapToDouble(BigDecimal::doubleValue).max().orElse(0);
            if (max <= 0) max = 1;
            int axisTop = 130;
            int axisBottom = 145 + data.labels().size() * 68;
            g.setColor(new Color(226, 232, 240));
            for (int tick = 0; tick <= 4; tick++) {
                int x = PLOT_X + tick * PLOT_WIDTH / 4;
                g.drawLine(x, axisTop, x, axisBottom);
            }
            g.setFont(new Font("Segoe UI", Font.PLAIN, 17));
            for (int row = 0; row < data.labels().size(); row++) {
                int y = 150 + row * 68;
                g.setColor(new Color(51, 65, 85));
                g.drawString(fit(data.labels().get(row), 24), 48, y + 26);
                for (int series = 0; series < data.series().size(); series++) {
                    BigDecimal amount = data.series().get(series).values().get(row);
                    int width = amount.signum() == 0 ? 0
                            : Math.max(3, (int) Math.round(PLOT_WIDTH * amount.doubleValue() / max));
                    int barY = data.series().size() == 1 ? y + 12 : y + series * 23;
                    g.setColor(COLORS[series]);
                    g.fillRoundRect(PLOT_X, barY, width, 18, 8, 8);
                    g.setColor(new Color(30, 41, 59));
                    g.setFont(new Font("Segoe UI", Font.BOLD, 15));
                    g.drawString(amount(amount) + " ₽", PLOT_X + width + 10, barY + 15);
                    g.setFont(new Font("Segoe UI", Font.PLAIN, 17));
                }
            }
            var out = new ByteArrayOutputStream();
            if (!ImageIO.write(image, "png", out)) {
                throw new IllegalStateException("PNG encoder is unavailable");
            }
            return out.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("Could not render PNG", impossible);
        } finally {
            g.dispose();
        }
    }

    private String fit(String text, int maxChars) {
        return text.length() <= maxChars ? text : text.substring(0, maxChars - 1) + "…";
    }

    private String amount(BigDecimal amount) {
        var format = NumberFormat.getNumberInstance(Locale.forLanguageTag("ru-RU"));
        format.setMaximumFractionDigits(2);
        format.setMinimumFractionDigits(0);
        return format.format(amount);
    }
}
