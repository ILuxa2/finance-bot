package financeos.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import financeos.core.Screen;
import financeos.data.LegacyDataMapper;
import financeos.data.LocalSheetBackupReader;
import financeos.vk.BotReply;
import financeos.vk.LocalBotEngine;
import financeos.vk.LocalStateFile;
import financeos.vk.VkConversationMembersClient;
import financeos.vk.VkEvent;
import financeos.vk.VkHistoryClient;
import financeos.vk.VkMessagesClient;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.function.Supplier;

/** Object Storage trigger target. State and the reply are committed together before VK send. */
public final class CloudVkProcessor implements Function<CloudVkProcessor.TriggerRequest, String> {
    private static final int MAX_REPLIES = 100;
    private final HttpClient http;
    private final URI storageEndpoint;
    private final URI historyEndpoint;
    private final URI sendEndpoint;
    private final URI membersEndpoint;
    private final Supplier<String> iamToken;
    private final ObjectMapper mapper = new ObjectMapper();
    private final String bucket;
    private final String vkToken;
    private final long groupId;
    private final long ownerId;
    private final long chatPeerId;
    private final String backupKey;
    private final String stateKey;

    public CloudVkProcessor() {
        this(HttpClient.newHttpClient(), URI.create("https://storage.yandexcloud.net"),
                URI.create("https://api.vk.com/method/messages.getHistory"),
                URI.create("https://api.vk.com/method/messages.send"),
                URI.create("https://api.vk.com/method/messages.getConversationMembers"),
                new MetadataIamToken(), required("FINANCEOS_STATE_BUCKET"),
                required("FINANCEOS_VK_TOKEN"), Long.parseLong(required("FINANCEOS_VK_GROUP_ID")),
                Long.parseLong(required("FINANCEOS_VK_USER_ID")),
                Long.parseLong(required("FINANCEOS_VK_CHAT_PEER_ID")),
                required("FINANCEOS_BASE_KEY"), required("FINANCEOS_STATE_KEY"));
    }

    CloudVkProcessor(HttpClient http, URI storageEndpoint, URI historyEndpoint,
                     URI sendEndpoint, URI membersEndpoint, Supplier<String> iamToken,
                     String bucket, String vkToken, long groupId, long ownerId,
                     long chatPeerId, String backupKey, String stateKey) {
        this.http = http;
        this.storageEndpoint = storageEndpoint;
        this.historyEndpoint = historyEndpoint;
        this.sendEndpoint = sendEndpoint;
        this.membersEndpoint = membersEndpoint;
        this.iamToken = iamToken;
        this.bucket = bucket;
        this.vkToken = vkToken;
        this.groupId = groupId;
        this.ownerId = ownerId;
        this.chatPeerId = chatPeerId;
        this.backupKey = backupKey;
        this.stateKey = stateKey;
    }

    public static final class TriggerRequest { public java.util.List<Map<String, Object>> messages; }
    @Override public String apply(TriggerRequest request) {
        return apply(request == null || request.messages == null ? null : Map.of("messages", request.messages));
    }
    public String apply(Map<String, Object> trigger) {
        try {
            Object messages = trigger == null ? null : trigger.get("messages");
            if (!(messages instanceof java.util.List<?> items)) {
                throw new IOException("Object Storage trigger has no messages");
            }
            for (Object item : items) {
                if (!(item instanceof Map<?, ?> message)) continue;
                Object metadataValue = message.get("event_metadata");
                Object detailsValue = message.get("details");
                if (!(metadataValue instanceof Map<?, ?> metadata)
                        || !(detailsValue instanceof Map<?, ?> details)) continue;
                if (!(metadata.get("event_type") instanceof String eventType)
                        || !eventType.equals("yandex.cloud.events.storage.ObjectCreate")) continue;
                if (!bucket.equals(details.get("bucket_id"))) continue;
                Object keyValue = details.get("object_id");
                if (!(keyValue instanceof String key) || !key.matches("inbox/[0-9a-f]{64}\\.json")) continue;
                process(key);
            }
            new CloudSheetsSync().sync();
            return "ok";
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("VK event processing failed; trigger should retry", failure);
        }
    }

    private void process(String inboxKey) throws Exception {
        byte[] eventBytes = store(inboxKey).load()
                .orElseThrow(() -> new IOException("VK event object is missing")).bytes();
        VkEvent event = mapper.readValue(eventBytes, VkEvent.class);
        if (event.groupId() != groupId || event.userId() != ownerId
                || (event.peerId() != ownerId && event.peerId() != chatPeerId)) return;
        if (event.peerId() == chatPeerId && !members().onlyOwnerAndCommunity(chatPeerId, ownerId)) {
            return;
        }
        if ("message_new".equals(event.type())) {
            if (event.conversationMessageId() <= 0) {
                throw new IOException("VK event lacks conversation message ID");
            }
            processHistory(event);
        } else {
            processSingle(event);
        }
    }

    private void processHistory(VkEvent requested) throws Exception {
        var current = store(stateKey).load()
                .orElseThrow(() -> new IOException("Cloud state is not initialized"));
        var envelope = mapper.readValue(current.bytes(), Envelope.class);
        if (envelope.version() != 1) throw new IOException("Unsupported cloud state version");
        if (requested.conversationMessageId() <= envelope.lastConversationMessageId()) {
            SavedReply saved = envelope.replies().get(CloudVkIngress.eventId(requested));
            if (saved != null) send(requested, saved);
            return;
        }
        var history = new VkHistoryClient(http, historyEndpoint, vkToken, groupId);
        var pending = history.since(requested.peerId(), ownerId,
                envelope.lastConversationMessageId());
        boolean foundRequested = false;
        for (var ordered : pending) {
            if (ordered.conversationMessageId() == requested.conversationMessageId()) {
                foundRequested = true;
            }
            processSingle(ordered);
        }
        if (!foundRequested) {
            var latest = store(stateKey).load()
                    .orElseThrow(() -> new IOException("Cloud state disappeared"));
            var saved = mapper.readValue(latest.bytes(), Envelope.class);
            if (saved.lastConversationMessageId() < requested.conversationMessageId()) {
                throw new IOException("VK callback message is not yet visible in history");
            }
        }
    }

    private void processSingle(VkEvent event) throws Exception {
        String key = CloudVkIngress.eventId(event);
        byte[] backup = store(backupKey).load()
                .orElseThrow(() -> new IOException("Financial base snapshot is missing")).bytes();
        for (int attempt = 0; attempt < 6; attempt++) {
            var current = store(stateKey).load();
            Envelope envelope = current.map(snapshot -> {
                try { return mapper.readValue(snapshot.bytes(), Envelope.class); }
                catch (IOException invalid) { throw new IllegalStateException("Invalid cloud state", invalid); }
            }).orElseThrow(() -> new IOException("Cloud state is not initialized"));
            if (envelope.version() != 1) throw new IOException("Unsupported cloud state version");
            if (event.conversationMessageId() > 0
                    && event.conversationMessageId() <= envelope.lastConversationMessageId()) {
                SavedReply saved = envelope.replies().get(key);
                if (saved != null) send(event, saved);
                return;
            }
            SavedReply already = envelope.replies().get(key);
            if (already != null) {
                send(event, already);
                return;
            }
            Result result = calculate(backup, envelope.stateBase64(), event);
            LinkedHashMap<String, SavedReply> replies = new LinkedHashMap<>(envelope.replies());
            replies.put(key, SavedReply.from(result.reply()));
            while (replies.size() > MAX_REPLIES) {
                String oldestOrdinary = replies.keySet().stream()
                        .filter(candidate -> !candidate.startsWith("scheduled:"))
                        .findFirst().orElse(null);
                if (oldestOrdinary == null) break;
                replies.remove(oldestOrdinary);
            }
            byte[] updated = mapper.writeValueAsBytes(new Envelope(1,
                    Base64.getEncoder().encodeToString(result.state()), replies,
                    envelope.notified(), envelope.morningDate(),
                    Math.max(envelope.lastConversationMessageId(),
                            event.conversationMessageId())));
            String expected = current.map(ObjectStorageStateStore.Snapshot::etag).orElse(null);
            if (store(stateKey).save(expected, updated)) {
                send(event, replies.get(key));
                return;
            }
        }
        throw new IOException("Concurrent financial state writes did not settle");
    }

    private Result calculate(byte[] backup, String previousState, VkEvent event) throws Exception {
        Path directory = Files.createTempDirectory("financeos-cloud-");
        Path backupFile = directory.resolve("base.json");
        Path stateFile = directory.resolve("state.json");
        try {
            Files.write(backupFile, backup);
            var sheets = new LocalSheetBackupReader().read(backupFile);
            var legacyIds = new TreeSet<String>();
            for (var row : sheets.get("StartupState")) {
                String id = row.getOrDefault("telegram_user_id", "");
                if (!id.isBlank()) legacyIds.add(id);
            }
            if (legacyIds.size() != 1) throw new IOException("Expected one financial user");
            var data = new LegacyDataMapper().user(sheets, legacyIds.first());
            var localFile = new LocalStateFile(stateFile, ownerId, data);
            if (!previousState.isBlank()) Files.write(stateFile, Base64.getDecoder().decode(previousState));
            var engine = new LocalBotEngine(ownerId, Set.of(ownerId, chatPeerId), data,
                    Clock.system(ZoneId.of("Europe/Moscow")));
            localFile.load().ifPresent(engine::restoreState);
            BotReply reply = engine.handle(event).orElse(null);
            localFile.save(engine.exportState());
            return new Result(Files.readAllBytes(stateFile), reply);
        } finally {
            Files.deleteIfExists(stateFile);
            Files.deleteIfExists(backupFile);
            Files.deleteIfExists(directory);
        }
    }

    private void send(VkEvent event, SavedReply reply) throws Exception {
        new CloudSheetsSync().sync();
        BotReply value = reply.toReply();
        if (value != null) sender().send(event, value);
    }

    private VkMessagesClient sender() {
        return new VkMessagesClient(sendEndpoint, vkToken, http);
    }

    private VkConversationMembersClient members() {
        return new VkConversationMembersClient(http, membersEndpoint, vkToken, groupId);
    }

    private ObjectStorageStateStore store(String key) {
        return new ObjectStorageStateStore(http, storageEndpoint, bucket, key, iamToken);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    private record Result(byte[] state, BotReply reply) { }

    public record Envelope(int version, String stateBase64,
                           LinkedHashMap<String, SavedReply> replies,
                           java.util.LinkedHashSet<String> notified, String morningDate,
                           long lastConversationMessageId) {
        public Envelope(int version, String stateBase64,
                        LinkedHashMap<String, SavedReply> replies,
                        java.util.LinkedHashSet<String> notified, String morningDate) {
            this(version, stateBase64, replies, notified, morningDate, 0);
        }
        public Envelope {
            if (stateBase64 == null) stateBase64 = "";
            if (replies == null) replies = new LinkedHashMap<>();
            if (notified == null) notified = new java.util.LinkedHashSet<>();
            if (morningDate == null) morningDate = "";
            if (lastConversationMessageId < 0) throw new IllegalArgumentException("Invalid VK checkpoint");
        }
    }

    public record SavedReply(String text, Screen screen, String imageBase64) {
        static SavedReply from(BotReply reply) {
            return reply == null ? new SavedReply(null, null, null)
                    : new SavedReply(reply.text(), reply.screen(), reply.imagePng() == null
                    ? null : Base64.getEncoder().encodeToString(reply.imagePng()));
        }

        BotReply toReply() {
            return text == null ? null : new BotReply(text, screen,
                    imageBase64 == null ? null : Base64.getDecoder().decode(imageBase64));
        }
    }
}
