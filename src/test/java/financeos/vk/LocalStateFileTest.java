package financeos.vk;

import financeos.core.BudgetPlan;
import financeos.core.Money;
import financeos.data.LegacyUserData;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

public final class LocalStateFileTest {
    private static BotReply send(LocalBotEngine engine, String id, String text) {
        return engine.handle(new VkEvent("message_new", 99, 123, 123, id, text)).orElseThrow();
    }

    public static void main(String[] args) throws Exception {
        var data = new LegacyUserData(Money.of("100000"),
                OffsetDateTime.parse("2026-10-30T10:00:00+03:00"), List.of(),
                List.of(new BudgetPlan(YearMonth.of(2026, 10), "Еда", Money.of("25000"))));
        var clock = Clock.fixed(Instant.parse("2026-10-30T09:00:00Z"), ZoneId.of("UTC"));
        var testRoot = java.nio.file.Path.of(System.getProperty("user.dir"), ".local-state");
        Files.createDirectories(testRoot);
        var directory = Files.createTempDirectory(testRoot, "state-test-");
        try {
            var file = new LocalStateFile(directory.resolve("vk-123.json"), 123, data);
            var first = new LocalBotEngine(123, data, clock);
            send(first, "1", "/expense");
            send(first, "2", "Еда");
            send(first, "3", "100, обед");
            send(first, "salary", "/salary_rule");
            send(first, "salary-data", "2000; 30; оклад");
            file.save(first.exportState());
            var legacyJson = (com.fasterxml.jackson.databind.node.ObjectNode)
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                            Files.readString(directory.resolve("vk-123.json")));
            legacyJson.put("version", 3);
            legacyJson.remove("undoHistory");
            Files.writeString(directory.resolve("legacy-v3.json"), legacyJson.toString());
            var legacyFile = new LocalStateFile(directory.resolve("legacy-v3.json"), 123, data);
            if (legacyFile.load().orElseThrow().undoHistory().size() != 0) {
                throw new AssertionError("Old local state must load with an empty undo history");
            }
            var restored = new LocalBotEngine(123, data, clock);
            restored.restoreState(file.load().orElseThrow());
            if (!send(restored, "4", "/report").text().contains("Свободные деньги · 75000.00 ₽")) {
                throw new AssertionError("Local operation was lost after restart");
            }
            file.save(restored.exportState());
            if (file.load().orElseThrow().entries().size() != 1) {
                throw new AssertionError("Second local save lost the operation");
            }
            if (!send(restored, "pending", "/regular_pending").text()
                    .contains("rule-1@2026-10-30")) {
                throw new AssertionError("Monthly rule was lost after restart");
            }
            if (restored.handle(new VkEvent("message_new", 99, 123, 123, "3", "100, обед"))
                    .isPresent()) throw new AssertionError("VK event was repeated after restart");
            if (!send(restored, "undo-after-restart-1", "/undo").text()
                    .contains("изменение отменено")) {
                throw new AssertionError("Undo was lost after restart");
            }
            if (!send(restored, "undo-after-restart-2", "/undo").text()
                    .contains("изменение отменено")) {
                throw new AssertionError("Second undo was lost after restart");
            }
            if (!send(restored, "report-after-undo", "/report").text()
                    .contains("Свободные деньги · 75000.00 ₽")) {
                throw new AssertionError("Undo did not restore the pre-expense balance");
            }
            file.save(restored.exportState());
            var afterUndo = new LocalBotEngine(123, data, clock);
            afterUndo.restoreState(file.load().orElseThrow());
            if (!send(afterUndo, "undo-exhausted", "/undo").text()
                    .contains("Пока нечего отменять")) {
                throw new AssertionError("Consumed undo states returned after restart");
            }
            var conversationFile = new LocalStateFile(directory.resolve("conversation.json"), 123, data);
            var waiting = new LocalBotEngine(123, data, clock);
            send(waiting, "wait-1", "/expense");
            send(waiting, "wait-2", "Еда");
            conversationFile.save(waiting.exportState());
            var resumed = new LocalBotEngine(123, data, clock);
            resumed.restoreState(conversationFile.load().orElseThrow());
            if (!send(resumed, "wait-3", "50, после перезапуска").text()
                    .contains("🔴 −50.00 ₽")) {
                throw new AssertionError("Conversation step was lost after restart");
            }
            var debtFile = new LocalStateFile(directory.resolve("debt.json"), 123, data);
            var beforeDebtRestart = new LocalBotEngine(123, data, clock);
            send(beforeDebtRestart, "debt-1", "/add_debt");
            send(beforeDebtRestart, "debt-2", "Кредит; 1800; 600; 19");
            send(beforeDebtRestart, "debt-3", "/edit_debt");
            send(beforeDebtRestart, "debt-4", "Кредит; 1100; 600; 19");
            debtFile.save(beforeDebtRestart.exportState());
            var afterDebtRestart = new LocalBotEngine(123, data, clock);
            afterDebtRestart.restoreState(debtFile.load().orElseThrow());
            var debtForecast = send(afterDebtRestart, "debt-5", "/forecast").text();
            if (!debtForecast.contains("2026-11 · 600.00 ₽")
                    || !debtForecast.contains("2026-12 · 500.00 ₽")
                    || debtForecast.contains("2027-01")) {
                throw new AssertionError("Corrected debt schedule was lost after restart");
            }
            send(afterDebtRestart, "debt-6", "/undo");
            if (!send(afterDebtRestart, "debt-7", "/debt_list").text()
                    .contains("осталось · 1800.00 ₽")) {
                throw new AssertionError("Debt correction cannot be undone after restart");
            }
            var changedBase = new LegacyUserData(Money.of("101000"), data.snapshotTime(),
                    data.transactions(), data.plans());
            try {
                new LocalStateFile(directory.resolve("vk-123.json"), 123, changedBase).load();
                throw new AssertionError("Changed base data were combined with old local state");
            } catch (java.io.IOException expected) {
                // Prevent mixing trial operations with a different sheet snapshot.
            }
        } finally {
            Files.deleteIfExists(directory.resolve("vk-123.json"));
            Files.deleteIfExists(directory.resolve("conversation.json"));
            Files.deleteIfExists(directory.resolve("legacy-v3.json"));
            Files.deleteIfExists(directory.resolve("debt.json"));
            Files.deleteIfExists(directory);
        }
        System.out.println("Local state restoration passed.");
    }
}
