package financeos.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import financeos.core.RecurringBook;
import financeos.core.Screen;
import financeos.data.LegacyDataMapper;
import financeos.data.LocalSheetBackupReader;
import financeos.vk.BotReply;
import financeos.vk.DailyReminderSettings;
import financeos.vk.LocalBotEngine;
import financeos.vk.LocalStateFile;
import financeos.vk.RecurringReminderService;
import financeos.vk.VkConversationMembersClient;
import financeos.vk.VkEvent;
import financeos.vk.VkMessagesClient;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.TreeSet;
import java.util.function.Function;

/** Timer target: records reminders before sending and retries unsent reminders. */
public final class CloudReminders implements Function<CloudReminders.TimerRequest, String> {
    private static final URI STORAGE = URI.create("https://storage.yandexcloud.net");
    private final HttpClient http = HttpClient.newHttpClient();
    private final MetadataIamToken iamToken = new MetadataIamToken();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String bucket = required("FINANCEOS_STATE_BUCKET");
    private final String vkToken = required("FINANCEOS_VK_TOKEN");
    private final long groupId = Long.parseLong(required("FINANCEOS_VK_GROUP_ID"));
    private final long ownerId = Long.parseLong(required("FINANCEOS_VK_USER_ID"));
    private final long chatPeerId = Long.parseLong(required("FINANCEOS_VK_CHAT_PEER_ID"));
    private final String backupKey = required("FINANCEOS_BASE_KEY");
    private final String stateKey = required("FINANCEOS_STATE_KEY");

    public CloudReminders() { }

    public static final class TimerRequest { public Object messages; }
    @Override public String apply(TimerRequest ignored) {
        try {
            new CloudVkProcessor().recoverConversation();
            for (int attempt = 0; attempt < 6; attempt++) {
                var current = store(stateKey).load()
                        .orElseThrow(() -> new IOException("Cloud state is not initialized"));
                var envelope = mapper.readValue(current.bytes(), CloudVkProcessor.Envelope.class);
                var pending = prepare(envelope);
                if (pending.isEmpty()) break;
                var replies = new LinkedHashMap<>(envelope.replies());
                replies.putAll(pending);
                var updated = new CloudVkProcessor.Envelope(1, envelope.stateBase64(),
                        replies, envelope.notified(), envelope.morningDate(),
                        envelope.lastConversationMessageId());
                if (store(stateKey).save(current.etag(), mapper.writeValueAsBytes(updated))) break;
                if (attempt == 5) throw new IOException("Could not reserve reminders");
            }
            deliverPending();
            new CloudVkProcessor().runAutomaticCleanup();
            new CloudVkProcessor().maintainStorage();
            return "ok";
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Cloud reminders failed; timer should retry", failure);
        }
    }

    private Map<String, CloudVkProcessor.SavedReply> prepare(CloudVkProcessor.Envelope envelope)
            throws Exception {
        Path directory = Files.createTempDirectory("financeos-reminders-");
        Path backupFile = directory.resolve("base.json");
        Path stateFile = directory.resolve("state.json");
        try {
            byte[] backup = store(backupKey).load()
                    .orElseThrow(() -> new IOException("Financial base snapshot is missing")).bytes();
            Files.write(backupFile, backup);
            var sheets = new LocalSheetBackupReader().read(backupFile);
            var legacyIds = new TreeSet<String>();
            for (var row : sheets.get("StartupState")) {
                String id = row.getOrDefault("telegram_user_id", "");
                if (!id.isBlank()) legacyIds.add(id);
            }
            if (legacyIds.size() != 1) throw new IOException("Expected one financial user");
            var data = new LegacyDataMapper().user(sheets, legacyIds.first());
            Files.write(stateFile, Base64.getDecoder().decode(envelope.stateBase64()));
            var engine = new LocalBotEngine(ownerId, java.util.Set.of(ownerId, chatPeerId), data,
                    Clock.system(ZoneId.of("Europe/Moscow")));
            new LocalStateFile(stateFile, ownerId, data).load().ifPresent(engine::restoreState);
            var now = ZonedDateTime.now(ZoneId.of("Europe/Moscow"));
            var book = new RecurringBook();
            book.restore(engine.exportState().recurring());
            var notifiedCodes = envelope.notified().stream()
                    .filter(key -> key.startsWith("scheduled:reminder-"))
                    .map(key -> key.substring("scheduled:reminder-".length()))
                    .collect(java.util.stream.Collectors.toSet());
            var due = new RecurringReminderService().toNotify(book, now.toLocalDate(),
                    now.toLocalTime(), notifiedCodes);
            Map<String, CloudVkProcessor.SavedReply> result = new LinkedHashMap<>();
            for (var item : due) {
                String key = "scheduled:reminder-" + item.code();
                if (envelope.replies().containsKey(key)) continue;
                var rule = item.rule();
                String text = "⏳ Подтвердите регулярную операцию за " + item.date()
                        + "\n" + rule.category() + ": " + rule.amount().setScale(2).toPlainString()
                        + " ₽\nКод: " + item.code()
                        + "\nНажмите «Подтвердить» и выберите операцию."
                        + " Можно нажать «Пропуск» и выбрать операцию.";
                result.put(key, CloudVkProcessor.SavedReply.from(
                        new BotReply(text, Screen.RECURRING)));
            }
            var settings = DailyReminderSettings.fromRows(sheets.get("UserSettings"), legacyIds.first());
            if (settings.due(now, envelope.morningDate())) {
                String text = engine.morningLimitText();
                if (!text.contains("не установлен")) {
                    String key = "scheduled:morning-" + now.toLocalDate();
                    if (!envelope.replies().containsKey(key) && !envelope.notified().contains(key)) {
                        result.put(key, CloudVkProcessor.SavedReply.from(
                                new BotReply(text, Screen.LIMITS)));
                    }
                }
            }
            return result;
        } finally {
            Files.deleteIfExists(stateFile);
            Files.deleteIfExists(backupFile);
            Files.deleteIfExists(directory);
        }
    }

    private void deliverPending() throws Exception {
        var members = new VkConversationMembersClient(http,
                URI.create("https://api.vk.com/method/messages.getConversationMembers"),
                vkToken, groupId);
        var sender = new VkMessagesClient(URI.create("https://api.vk.com/method/messages.send"),
                vkToken, http);
        var snapshot = store(stateKey).load()
                .orElseThrow(() -> new IOException("Cloud state disappeared"));
        var envelope = mapper.readValue(snapshot.bytes(), CloudVkProcessor.Envelope.class);
        for (var entry : envelope.replies().entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith("scheduled:") || envelope.notified().contains(key)) continue;
            if (members.authorizedMembers(chatPeerId,ownerId,financeos.vk.ChatAccess.configured(ownerId)).isEmpty()) {
                throw new IOException("VK private chat membership is not confirmed");
            }
            String eventId = key.substring("scheduled:".length());
            sender.send(new VkEvent("message_new", groupId, ownerId, chatPeerId, eventId, ""),
                    entry.getValue().toReply());
            markDelivered(key);
        }
    }

    private void markDelivered(String key) throws Exception {
        for (int attempt = 0; attempt < 6; attempt++) {
            var current = store(stateKey).load()
                    .orElseThrow(() -> new IOException("Cloud state disappeared"));
            var envelope = mapper.readValue(current.bytes(), CloudVkProcessor.Envelope.class);
            if (envelope.notified().contains(key)) return;
            var notified = new LinkedHashSet<>(envelope.notified());
            notified.add(key);
            var replies = new LinkedHashMap<>(envelope.replies());
            replies.remove(key);
            String morningDate = key.startsWith("scheduled:morning-")
                    ? key.substring("scheduled:morning-".length()) : envelope.morningDate();
            var updated = new CloudVkProcessor.Envelope(1, envelope.stateBase64(),
                    replies, notified, morningDate, envelope.lastConversationMessageId());
            if (store(stateKey).save(current.etag(), mapper.writeValueAsBytes(updated))) return;
        }
        throw new IOException("Could not mark reminder as sent");
    }

    private ObjectStorageStateStore store(String key) {
        return new ObjectStorageStateStore(http, STORAGE, bucket, key, iamToken);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
