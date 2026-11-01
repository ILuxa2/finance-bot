package financeos.data;

import financeos.core.ChartKind;
import financeos.core.ChartService;
import financeos.vk.ChartRenderer;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.TreeSet;

/** Renders five read-only chart previews from the existing table. */
public final class LocalChartPreview {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("Expected spreadsheet ID, key file and output directory");
        }
        var sheets = new GoogleSheetsReadOnly(URI.create("https://sheets.googleapis.com"),
                args[0], new GoogleServiceAccountToken(Path.of(args[1]))).readAll();
        var ids = new TreeSet<String>();
        for (var row : sheets.get("StartupState")) {
            String id = row.getOrDefault("telegram_user_id", "");
            if (!id.isBlank()) ids.add(id);
        }
        if (ids.size() != 1) throw new IllegalStateException("Expected one user for local chart preview");
        var data = new LegacyDataMapper().user(sheets, ids.first());
        var now = OffsetDateTime.now(ZoneId.of("Europe/Moscow"));
        var output = Path.of(args[2]).toAbsolutePath();
        Files.createDirectories(output);
        var charts = new ChartService();
        var renderer = new ChartRenderer();
        for (ChartKind kind : ChartKind.values()) {
            var chart = charts.prepare(kind, data, now);
            if (chart.labels().isEmpty()) {
                System.out.println(kind + ": no data");
                continue;
            }
            Path file = output.resolve(kind.name().toLowerCase(java.util.Locale.ROOT) + ".png");
            Files.write(file, renderer.png(chart));
            System.out.println(kind + ": " + file);
        }
    }
}
