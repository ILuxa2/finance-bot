package financeos.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import financeos.data.LegacyDataMapper;
import financeos.data.LocalSheetBackupReader;
import financeos.vk.LocalBotEngine;
import financeos.vk.LocalStateFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Base64;

/** Read-only compatibility check of a downloaded private cloud snapshot. Prints no financial data. */
public final class CloudSnapshotCompatibilityTest {
    public static void main(String[] args) throws Exception {
        var mapper = new ObjectMapper();
        var sheets = new LocalSheetBackupReader().read(Path.of(args[0]));
        String user = sheets.get("StartupState").getFirst().get("telegram_user_id");
        var data = new LegacyDataMapper().user(sheets, user);
        byte[] source = Files.readAllBytes(Path.of(args[1]));
        var envelope = mapper.readValue(source, CloudVkProcessor.Envelope.class);
        var roundTrip = mapper.readValue(mapper.writeValueAsBytes(envelope), CloudVkProcessor.Envelope.class);
        if (!roundTrip.stateBase64().equals(envelope.stateBase64())) throw new AssertionError("Envelope changed ledger");
        Path decoded = Files.createTempFile("financeos-compatibility-", ".json");
        try {
            byte[] bytes = Base64.getDecoder().decode(envelope.stateBase64());
            Files.write(decoded, bytes);
            long owner = mapper.readTree(bytes).path("vkUserId").asLong();
            var state = new LocalStateFile(decoded, owner, data).load().orElseThrow();
            var engine = new LocalBotEngine(owner, data, Clock.systemUTC());
            engine.restoreState(state);
            if (!engine.exportState().equals(state)) throw new AssertionError("Restored state changed data");
            // Compare pre-editor migration against the former calculation only for old formats.
            // Format 5 can legitimately contain replacements; the former append-only calculation
            // would double-count them and must never be used to validate an edited ledger.
            if (mapper.readTree(bytes).path("version").asInt() < 5) {
                var oldEntries = new java.util.ArrayList<>(data.transactions());
                oldEntries.addAll(state.entries());
                var oldPlans = new java.util.ArrayList<>(data.plans());
                oldPlans.addAll(state.plans());
                var oldExtras = new financeos.data.LegacyExtras(state.debts(), state.goals(), data.extras().categories(),
                        state.limits(), data.extras().dailyOverrides());
                var before = new financeos.data.LegacyUserData(data.startingBalance(), data.snapshotTime(), oldEntries, oldPlans, oldExtras);
                var after = new financeos.core.OperationEditor().combined(data, state.entries(), state.plans(), state.debts(), state.goals(), state.limits());
                var at = java.time.OffsetDateTime.now(java.time.ZoneId.of("Europe/Moscow"));
                var reports = new financeos.core.LegacyReportService();
                if (!reports.calculate(before, at).equals(reports.calculate(after, at))) throw new AssertionError("Migration changed financial totals");
                if (!before.transactions().equals(after.transactions())) throw new AssertionError("Migration changed the existing ledger");
            }
        } finally { Files.deleteIfExists(decoded); }
        System.out.println("Downloaded cloud snapshot is compatible; no data was changed.");
    }
}
