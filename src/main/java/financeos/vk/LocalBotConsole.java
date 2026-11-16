package financeos.vk;

import financeos.core.BudgetPlan;
import financeos.core.Money;
import financeos.data.GoogleServiceAccountToken;
import financeos.data.GoogleSheetsReadOnly;
import financeos.data.LegacyDataMapper;
import financeos.data.LegacyUserData;
import financeos.data.LocalSheetBackupReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Scanner;
import java.util.TreeSet;

/** Manual local conversation. It never sends messages to VK or writes to Google Sheets. */
public final class LocalBotConsole {
    public static void main(String[] args) throws Exception {
        long vkUserId;
        LegacyUserData data;
        Path statePath = null;
        if (args.length == 0) {
            vkUserId = 1;
            var now = OffsetDateTime.now(ZoneId.of("Europe/Moscow"));
            data = new LegacyUserData(Money.of("100000"), now, List.of(), List.of(
                    new BudgetPlan(YearMonth.from(now), "Еда", Money.of("25000"))));
            System.out.println("Демонстрационные данные: баланс 100000 ₽, план «Еда» 25000 ₽.");
        } else if (args.length == 4) {
            vkUserId = Long.parseLong(args[2]);
            statePath = Path.of(args[3]);
            var sheets = args[0].equals("--backup")
                    ? new LocalSheetBackupReader().read(Path.of(args[1]))
                    : new GoogleSheetsReadOnly(URI.create("https://sheets.googleapis.com"),
                            args[0], new GoogleServiceAccountToken(Path.of(args[1]))).readAll();
            var ids = new TreeSet<String>();
            for (var row : sheets.get("StartupState")) {
                String id = row.getOrDefault("telegram_user_id", "");
                if (!id.isBlank()) ids.add(id);
            }
            if (ids.size() != 1) throw new IllegalStateException(
                    "Нужен один финансовый пользователь для локальной проверки");
            data = new LegacyDataMapper().user(sheets, ids.first());
            System.out.println(args[0].equals("--backup")
                    ? "Локальная резервная копия загружена без сети."
                    : "Данные Google Sheets загружены только для чтения.");
        } else {
            throw new IllegalArgumentException("Ожидаются: <ID таблицы> <путь к JSON-ключу>"
                    + " <ID VK> <путь к локальному состоянию>");
        }
        var engine = new LocalBotEngine(vkUserId, data, Clock.system(ZoneId.of("Europe/Moscow")));
        LocalStateFile stateFile = statePath == null ? null : new LocalStateFile(statePath, vkUserId, data);
        if (stateFile != null) {
            stateFile.load().ifPresent(engine::restoreState);
            System.out.println("Локальные изменения сохраняются в " + statePath.toAbsolutePath());
            System.out.println("Google Sheets не изменяется. Выход: exit.");
        } else {
            System.out.println("Изменения остаются только в памяти этого запуска. Выход: exit.");
        }
        try (var scanner = new Scanner(System.in, StandardCharsets.UTF_8)) {
            print(engine.handle(new VkEvent("message_new", 1, vkUserId, vkUserId,
                    java.util.UUID.randomUUID().toString(), "/start")).orElseThrow());
            while (scanner.hasNextLine()) {
                String line = scanner.nextLine().strip();
                if (line.equalsIgnoreCase("exit")) break;
                if (line.isBlank()) continue;
                var reply = engine.handle(new VkEvent("message_new", 1, vkUserId, vkUserId,
                        java.util.UUID.randomUUID().toString(), line));
                if (stateFile != null) stateFile.save(engine.exportState());
                reply.ifPresent(LocalBotConsole::print);
            }
        }
    }

    private static void print(BotReply reply) {
        System.out.println("\n" + reply.text());
        for (var image : reply.images()) {
            try {
                Path directory = Path.of(System.getProperty("user.dir"), ".local-output", "charts");
                Files.createDirectories(directory);
                Path file = directory.resolve("chart-" + java.util.UUID.randomUUID() + ".png");
                Files.write(file, image);
                System.out.println("Диаграмма сохранена: " + file.toAbsolutePath());
            } catch (java.io.IOException error) {
                throw new java.io.UncheckedIOException("Не удалось сохранить диаграмму", error);
            }
        }
        String options = MenuCatalog.buttons(reply.screen()).stream()
                .map(button -> button.label() + " " + button.command())
                .collect(java.util.stream.Collectors.joining(" | "));
        System.out.println("Кнопки: " + options + "\n");
    }
}
