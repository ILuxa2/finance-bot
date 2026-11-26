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
    private final java.util.concurrent.ConcurrentHashMap<String,Long> progressMessages=new java.util.concurrent.ConcurrentHashMap<>();
    private final CloudMemoryCache memory = new CloudMemoryCache();
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
    private final Set<Long> allowedUsers;
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
        this(http,storageEndpoint,historyEndpoint,sendEndpoint,membersEndpoint,iamToken,bucket,vkToken,groupId,ownerId,chatPeerId,backupKey,stateKey,financeos.vk.ChatAccess.configured(ownerId));
    }
    CloudVkProcessor(HttpClient http,URI storageEndpoint,URI historyEndpoint,URI sendEndpoint,URI membersEndpoint,Supplier<String> iamToken,String bucket,String vkToken,long groupId,long ownerId,long chatPeerId,String backupKey,String stateKey,Set<Long> users){
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
        this.allowedUsers=Set.copyOf(users);
        if(!allowedUsers.contains(ownerId))throw new IllegalArgumentException("Missing owner");
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
            retryPendingReplies();
            return "ok";
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("VK event processing failed; trigger should retry; " + safeFailure(failure), failure);
        }
    }

    static String safeFailure(Throwable failure) {
        var result = new StringBuilder();
        for (int i = 0; failure != null && i < 8; i++, failure = failure.getCause()) {
            if (i > 0) result.append(" / ");
            result.append(failure.getClass().getSimpleName());
            String message = failure.getMessage();
            if(message!=null&&message.matches("VK cleanup (?:API error |HTTP )[0-9]{1,3}"))result.append(": ").append(message);
            if (message != null && message.matches("(?:VK (?:photo API error |API error |history API error )|Object Storage (?:read|write) failed with HTTP )[0-9]{1,3}")) {
                result.append(": ").append(message);
            }
            if (failure.getStackTrace().length > 0) {
                var frame = failure.getStackTrace()[0];
                result.append("@").append(frame.getClassName()).append(":").append(frame.getLineNumber());
            }
        }
        return result.toString();
    }

    private void process(String inboxKey) throws Exception {
        byte[] eventBytes = store(inboxKey).load()
                .orElseThrow(() -> new IOException("VK event object is missing")).bytes();
        VkEvent event = mapper.readValue(eventBytes, VkEvent.class);
        if (event.groupId() != groupId || !financeos.vk.ChatAccess.eventAllowed(event.userId(),event.peerId(),ownerId,chatPeerId,allowedUsers)) return;
        try {
            if (event.peerId() == chatPeerId && !chatAllowed(event.userId())) {
                return;
            }
            processingNotice(event);
            if ("message_new".equals(event.type())) {
                if (event.conversationMessageId() <= 0) {
                    throw new IOException("VK event lacks conversation message ID");
                }
                processHistory(event);
            } else {
                processSingle(event, false);
            }
        } catch (Exception failure) {
            if (!(failure instanceof ReportedFailure)) reportFailure(event, failure, Outcome.UNKNOWN);
            throw failure;
        }
    }

    private void processingNotice(VkEvent event) {
        if("/chat_clear_confirm".equals(event.text()))return;
        // Do not wait for financial storage to acknowledge a click. VK deduplicates
        // retried notices using this action's stable, separate random_id.
        try {
            long id=sender().notice(new VkEvent(event.type(), event.groupId(), event.userId(), event.peerId(),
                    event.eventId() + ":progress", ""), chartRequest(event)?"📊 Загружаю диаграммы…":"⏳ Обрабатываю…");
            if(progressMessages.size()>=MAX_REPLIES)progressMessages.clear();progressMessages.put(CloudVkIngress.eventId(event),id);
        } catch (Exception unavailable) {
            System.err.println("VK processing notice unavailable event=" + CloudVkIngress.eventId(event));
        }
    }

    private enum Outcome { NOT_SAVED, SAVED, UNKNOWN }
    private static final class ReportedFailure extends Exception {
        ReportedFailure(Exception cause) { super("Event failed; user notification attempted", cause); }
    }

    private void reportFailure(VkEvent event, Exception failure, Outcome outcome) {
        String id = CloudVkIngress.eventId(event);
        boolean financialSaved=false;
        System.err.println("VK processing failed event=" + id + " type=" + failure.getClass().getSimpleName());
        // A failed PUT can have succeeded. Only report 'not saved' before any commit attempt
        // and after a fresh read confirms that no result exists.
        try {
            var current = store(stateKey).load();
            if (current.isPresent()) {
                var envelope = mapper.readValue(current.get().bytes(), Envelope.class);
                if (envelope.replies().containsKey(id)){outcome = Outcome.SAVED;financialSaved=envelope.replies().get(id).financialChanged();}
            } else if (outcome != Outcome.SAVED) outcome = Outcome.UNKNOWN;
        } catch (Exception checkFailed) {
            if (outcome != Outcome.SAVED) outcome = Outcome.UNKNOWN;
        }
        String text = switch (outcome) {
            case SAVED -> "⏳ Отправка задерживается. Повторяю попытку…"
                    + (financialSaved?" Изменение сохранено.":"")+" Повторно вводить операцию не нужно.";
            case NOT_SAVED -> "⚠️ Действие пока не выполнено: изменения не сохранены."
                    + " Повторю попытку автоматически. Не вводите операцию повторно.";
            case UNKNOWN -> "⚠️ Не удалось завершить обработку. Проверяю результат сохранения."
                    + " Повторю попытку автоматически. Не вводите операцию повторно.";
        };
        try {
            sender().notice(new VkEvent(event.type(), event.groupId(), event.userId(), event.peerId(),
                    event.eventId() + ":error", ""), text + " Код: " + id.substring(0, 8));
        } catch (Exception notificationFailed) {
            System.err.println("VK error notification unavailable event=" + id);
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
        var pending = history.since(requested.peerId(), requested.peerId()==chatPeerId?members().authorizedMembers(chatPeerId,ownerId,allowedUsers):Set.of(ownerId),
                envelope.lastConversationMessageId());
        boolean foundRequested = false;
        for (var ordered : pending) {
            if (ordered.conversationMessageId() == requested.conversationMessageId()) {
                foundRequested = true;
            }
            processSingle(ordered, ordered.conversationMessageId() != requested.conversationMessageId());
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
        processSingle(event, true);
    }

    private void processSingle(VkEvent event, boolean announce) throws Exception {
        if(!financeos.vk.ChatAccess.afterActivation(event,ownerId,financeos.vk.ChatAccess.memberStart()))return;
        Outcome outcome = Outcome.UNKNOWN;
        try {
            String key = CloudVkIngress.eventId(event);
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
                    if (saved != null) { outcome = Outcome.SAVED; send(event, saved); }
                    return;
                }
                SavedReply already = envelope.replies().get(key);
                if (already != null) {
                    outcome = Outcome.SAVED;
                    send(event, already);
                    return;
                }
                outcome = Outcome.NOT_SAVED;
                if (announce) { processingNotice(event); announce = false; }
                byte[] backup = store(backupKey).load()
                        .orElseThrow(() -> new IOException("Financial base snapshot is missing")).bytes();
                Result result = calculate(backup, envelope.stateBase64(), event);
                LinkedHashMap<String, SavedReply> replies = new LinkedHashMap<>(envelope.replies());
                replies.put(key, SavedReply.from(result.reply()).forEvent(event).withFinancialChange(FinanceSnapshots.changed(envelope.stateBase64(),result.state())).withProgress(progressMessages.getOrDefault(key,0L)));
                while (replies.size() > MAX_REPLIES) {
                    String oldestOrdinary = replies.keySet().stream()
                            .filter(candidate -> !candidate.startsWith("scheduled:")
                                    && replies.get(candidate).delivered())
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
                boolean financialChange=FinanceSnapshots.changed(envelope.stateBase64(),result.state());
                if(financialChange){byte[] prior=Base64.getDecoder().decode(envelope.stateBase64());String keyBefore=FinanceSnapshots.eventKey(prior,java.time.Instant.now());var destination=store(keyBefore);byte[] recovery=FinanceSnapshots.encode(prior,java.time.Instant.now());if(!destination.save(null,recovery))FinanceSnapshots.validate(destination.load().orElseThrow().bytes());}
                outcome = Outcome.UNKNOWN;
                if (store(stateKey).save(expected, updated)) {
                    outcome = Outcome.SAVED;
                    send(event, replies.get(key));
                    if(financialChange)try{retention().trimEvents();}catch(Exception maintenance){if(maintenance instanceof InterruptedException)Thread.currentThread().interrupt();System.err.println("Recovery retention deferred: "+maintenance.getClass().getSimpleName());}
                    return;
                }
            }
            throw new IOException("Concurrent financial state writes did not settle");
        } catch (Exception failure) {
            reportFailure(event, failure, outcome);
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ReportedFailure(failure);
        }
    }

    private Result calculate(byte[] backup, String previousState, VkEvent event) throws Exception {
        if(!financeos.vk.ChatAccess.eventAllowed(event.userId(),event.peerId(),ownerId,chatPeerId,allowedUsers)||!financeos.vk.ChatAccess.afterActivation(event,ownerId,financeos.vk.ChatAccess.memberStart()))return new Result(Base64.getDecoder().decode(previousState),null);
        Path directory = Files.createTempDirectory("financeos-cloud-");
        Path backupFile = directory.resolve("base.json");
        Path stateFile = directory.resolve("state.json");
        try {
            Files.write(backupFile, backup);
            var data = memory.base(backup, () -> {
                var sheets = new LocalSheetBackupReader().read(backupFile);
                var legacyIds = new TreeSet<String>();
                for (var row : sheets.get("StartupState")) {
                    String id = row.getOrDefault("telegram_user_id", "");
                    if (!id.isBlank()) legacyIds.add(id);
                }
                if (legacyIds.size() != 1) throw new IOException("Expected one financial user");
                return new LegacyDataMapper().user(sheets, legacyIds.first());
            });
            var localFile = new LocalStateFile(stateFile, ownerId, data);
            var engine = new LocalBotEngine(ownerId, Set.of(ownerId, chatPeerId), data,
                    Clock.system(ZoneId.of("Europe/Moscow")),allowedUsers).receiptReader(financeos.vk.YandexReceiptReader.configured(http,iamToken));
            com.fasterxml.jackson.databind.node.ObjectNode prepared=null;
            if(allowedUsers.size()>1){
                localFile.save(engine.exportState());
                var defaults=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(Files.readAllBytes(stateFile));
                var previous=previousState.isBlank()?defaults:(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(Base64.getDecoder().decode(previousState));
                prepared=SharedChatState.prepare(previous,defaults,ownerId,event.userId());
                Files.write(stateFile,mapper.writeValueAsBytes(prepared));localFile.load().ifPresent(engine::restoreState);
            }else{
                if(!previousState.isBlank())Files.write(stateFile,Base64.getDecoder().decode(previousState));
                memory.state(previousState,data,ownerId,localFile::load).ifPresent(engine::restoreState);
            }
            boolean wrongCleanupPeer = event.text() != null && event.text().startsWith("/chat_") && (event.peerId() != chatPeerId || event.userId()!=ownerId);
            BotReply reply = wrongCleanupPeer
                    ? new BotReply("ℹ️ Управление очисткой доступно только в настроенной беседе с сообществом и только владельцу бюджета.", Screen.MAIN)
                    : engine.handle(event).orElse(null);
            var nextState = engine.exportState();
            localFile.save(nextState);
            byte[] encoded = Files.readAllBytes(stateFile);
            if(prepared!=null)encoded=mapper.writeValueAsBytes(SharedChatState.finish(prepared,(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(encoded),event.userId()));
            memory.rememberState(Base64.getEncoder().encodeToString(encoded), data, ownerId, java.util.Optional.of(nextState));
            return new Result(encoded, reply);
        } finally {
            Files.deleteIfExists(stateFile);
            Files.deleteIfExists(backupFile);
            Files.deleteIfExists(directory);
        }
    }

    private void send(VkEvent event, SavedReply reply) throws Exception {
        if (reply.delivered()) return;
        if(!financeos.vk.ChatAccess.eventAllowed(event.userId(),event.peerId(),ownerId,chatPeerId,allowedUsers))throw new IOException("Reply recipient is not authorized");
        if(event.peerId()==chatPeerId&&!chatAllowed(event.userId()))throw new IOException("Reply conversation is not authorized");
        BotReply value = reply.toReply();
        long messageId = 0;
        if (value != null) {
            if ("/chat_clear_confirm".equals(event.text()) && value.screen() == Screen.CHAT_CLEAR_RESULT) {
                var current = store(stateKey).load().orElseThrow();
                Envelope envelope = mapper.readValue(current.bytes(), Envelope.class);
                boolean waiting = envelope.replies().entrySet().stream().anyMatch(item -> !item.getKey().equals(CloudVkIngress.eventId(event))
                        && item.getValue().event() != null && !item.getValue().delivered());
                String text;
                if (event.peerId() != chatPeerId || event.conversationMessageId() <= 0) {
                    text = "ℹ️ Очистка доступна только в настроенной беседе с сообществом.";
                } else if (waiting || envelope.lastConversationMessageId() < event.conversationMessageId()) {
                    text = "⚠️ Очистка отложена: есть незавершённые ответы. Дождитесь их и запустите очистку заново. Финансовые данные сохранены.";
                } else {
                    var cleaner=cleaner();
                    var result=cleaner.clean(Math.min(envelope.lastConversationMessageId(), event.conversationMessageId()), java.time.Instant.now().plusSeconds(1));
                    if(result.refused()==0 && !result.more() && result.blocked().isBlank() && cleaner.isEmpty()) {
                        value=emptyChatHelp();text=null;
                    }else text=result.text();
                }
                if(text!=null)value = new BotReply(text, Screen.CHAT_CLEAR_RESULT);
            }
            if(reply.progressMessageId()>0&&!isPagination(event)) {
                try{sender().edit(event,new BotReply(value.images().isEmpty()?"⏳ Почти готово…":"📊 Загружаю диаграммы…",Screen.MAIN,null,java.util.List.of()),reply.progressMessageId());}
                catch(Exception statusUnavailable){if(statusUnavailable instanceof InterruptedException){Thread.currentThread().interrupt();throw statusUnavailable;}System.err.println("Delivery progress unavailable event="+CloudVkIngress.eventId(event));}
            }
            long target = paginationTarget(event, value);
            if (target > 0) {
                try { messageId = sender().edit(event, value, target); }
                catch (java.io.IOException editUnavailable) { messageId = sender().send(event, value); }
            } else messageId = sender().send(event, value);
        }
        finishPageProgress(event,reply,value);
        markDelivered(CloudVkIngress.eventId(event), messageId);
        progressMessages.remove(CloudVkIngress.eventId(event));
    }

    private static boolean chartRequest(VkEvent event){return java.util.Set.of("/report_chart","/chart_expenses","/chart_income","/chart_plan","/chart_debts","/chart_goals").contains(event.text()==null?"":event.text());}
    private static boolean isPagination(VkEvent event){String c=event.text()==null?"":event.text();return c.startsWith("/expense_chart_page/")||c.startsWith("/plan_page/")||c.startsWith("/choices/EDIT_SELECT/")||c.startsWith("/choices/HISTORY_EDIT_SELECT/")||c.equals("/history_next")||c.equals("/history_prev");}
    private void finishPageProgress(VkEvent event,SavedReply saved,BotReply value){
        if(saved.progressMessageId()<=0||value==null)return;
        String status=isPagination(event)?"✅ Готово · "+value.text().lines().findFirst().orElse("Страница обновлена"):"✅ Ответ отправлен.";
        try{sender().edit(event,new BotReply(status,Screen.HISTORY,null,java.util.List.of()),saved.progressMessageId());}
        catch(Exception unavailable){
            if(unavailable instanceof InterruptedException){Thread.currentThread().interrupt();return;}
            try{sender().notice(new VkEvent(event.type(),event.groupId(),event.userId(),event.peerId(),event.eventId()+":page-done",""),status);}
            catch(Exception failure){if(failure instanceof InterruptedException)Thread.currentThread().interrupt();System.err.println("Page completion notice unavailable event="+CloudVkIngress.eventId(event));}
        }
    }

    private long paginationTarget(VkEvent event, BotReply reply) throws Exception {
        if(!isPagination(event)||reply==null||reply.imagePng()!=null)return 0;
        var snapshot = store(stateKey).load().orElseThrow();
        var envelope = mapper.readValue(snapshot.bytes(), Envelope.class);
        // Only the immediately preceding delivered view is reusable. Never overwrite an old
        // page after a different section has intervened. This path never recalculates a financial action.
        var replies = new java.util.ArrayList<>(envelope.replies().values());
        for (int i = replies.size() - 1; i >= 0; i--) {
            var old = replies.get(i);
            if (!old.delivered() || old.event() == null || old.event().peerId() != event.peerId()) continue;
            return old.event().userId()==event.userId() && old.screen() == reply.screen() ? old.messageId() : 0;
        }
        return 0;
    }

    /** Outbox survives cold starts and send failures. No financial action is recomputed. */
    public void retryPendingReplies() throws Exception {
        var current = store(stateKey).load()
                .orElseThrow(() -> new IOException("Cloud state is not initialized"));
        Envelope envelope = mapper.readValue(current.bytes(), Envelope.class);
        boolean needsChatCheck = envelope.replies().values().stream().anyMatch(reply ->
                reply.event() != null && !reply.delivered() && reply.event().peerId() == chatPeerId);
        if (needsChatCheck && !chatAllowed(0)) {
            throw new IOException("Pending reply conversation membership is not authorized");
        }
        for (var entry : envelope.replies().entrySet()) {
            SavedReply reply = entry.getValue();
            if (reply.event() != null && !reply.delivered()) {
                try { send(reply.event(), reply); }
                catch (Exception failure) {
                    reportFailure(reply.event(), failure, Outcome.SAVED);
                    throw new ReportedFailure(failure);
                }
            }
        }
        runAutomaticCleanup(current);
    }

    private static BotReply emptyChatHelp(){return new BotReply(financeos.vk.MenuCatalog.welcomeHelp(),Screen.CHAT_CLEAR_RESULT,null,java.util.List.of());}

    private boolean chatAllowed(long actor) throws IOException,InterruptedException {var current=members().authorizedMembers(chatPeerId,ownerId,allowedUsers);return !current.isEmpty()&&(actor==0||current.contains(actor));}

    private StorageRetention retention(){return new StorageRetention(http,storageEndpoint,bucket,iamToken,stateKey);}
    public void maintainStorage()throws Exception{
        var snapshot=store(stateKey).load().orElseThrow();var envelope=mapper.readValue(snapshot.bytes(),Envelope.class);if(envelope.stateBase64().isBlank())return;
        var now=java.time.Instant.now();byte[] financial=FinanceSnapshots.encode(Base64.getDecoder().decode(envelope.stateBase64()),now);var daily=store(FinanceSnapshots.dailyKey(now));if(!daily.save(null,financial))FinanceSnapshots.validate(daily.load().orElseThrow().bytes());
        var result=retention().maintain(envelope,groupId,chatPeerId,now);System.out.println("Storage maintenance bytes="+result.bytes()+" removed="+result.removed()+" incomplete="+result.incomplete());
        long limit=Long.parseLong(System.getenv().getOrDefault("FINANCEOS_STORAGE_LIMIT_BYTES","1073741824"));
        if(result.bytes()>limit*3/4||result.incomplete())if(chatAllowed(0))sender().notice(new VkEvent("scheduled",groupId,ownerId,chatPeerId,"storage-warning:"+now.atZone(ZoneId.of("Europe/Moscow")).toLocalDate(),""),"⚠️ Хранилище бота: требуется проверка. Занято около "+(result.bytes()/1048576)+" МиБ из "+(limit/1048576)+" МиБ."+(result.incomplete()?" Очистка завершена частично.":""));
    }

    private financeos.vk.VkChatCleaner cleaner() {
        return new financeos.vk.VkChatCleaner(http, sendEndpoint, vkToken, groupId, ownerId, chatPeerId,allowedUsers);
    }

    /** Optional maintenance uses the existing event/daily invocations, never a separate timer. */
    public void runAutomaticCleanup() { runAutomaticCleanup(null); }
    private void runAutomaticCleanup(ObjectStorageStateStore.Snapshot initial) {
        boolean eligible = false;
        String lease="";int cleanupDays=0;
        try {
            var zone = ZoneId.of("Europe/Moscow");
            var instant = java.time.Instant.now();
            String today = instant.atZone(zone).toLocalDate().toString();
            for (int attempt = 0; attempt < 6; attempt++) {
                var current = attempt == 0 && initial != null ? initial : store(stateKey).load().orElseThrow();
                var envelope = mapper.readValue(current.bytes(), Envelope.class);
                var root = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(Base64.getDecoder().decode(envelope.stateBase64()));
                var settings = financeos.vk.ChatCleanupSettings.read(root);
                if (settings.days() == 0 || settings.lastDate().equals(today)) return;
                int attempts=settings.attemptDate().equals(today)?settings.attempts():0;
                if(attempts>=3||(!settings.notBefore().isBlank()&&instant.isBefore(java.time.Instant.parse(settings.notBefore()))))return;
                if (envelope.replies().values().stream().anyMatch(r -> r.event() != null && !r.delivered())) return;
                eligible = true;
                // A bounded lease survives cold starts; lastDate records completion, not an attempt.
                lease=java.util.UUID.randomUUID().toString();cleanupDays=settings.days();
                new financeos.vk.ChatCleanupSettings(settings.days(),settings.lastDate(),today,attempts+1,instant.plusSeconds(300).toString(),lease).write(root);
                var next = new Envelope(1, Base64.getEncoder().encodeToString(mapper.writeValueAsBytes(root)),
                        envelope.replies(), envelope.notified(), envelope.morningDate(), envelope.lastConversationMessageId());
                if (!store(stateKey).save(current.etag(), mapper.writeValueAsBytes(next))) continue;
                var check = store(stateKey).load().orElseThrow();
                var checkEnvelope = mapper.readValue(check.bytes(), Envelope.class);
                var checkSettings = financeos.vk.ChatCleanupSettings.read(mapper.readTree(Base64.getDecoder().decode(checkEnvelope.stateBase64())));
                if (checkSettings.days() != settings.days() || !checkSettings.leaseId().equals(lease)) return;
                // A fresh private-chat check is made inside the cleaner, before any delete.
                var result = cleaner().clean(envelope.lastConversationMessageId(), instant.minus(java.time.Duration.ofDays(settings.days())));
                boolean complete=result.refused()==0&&!result.more()&&result.blocked().isBlank();
                finishAutomaticCleanup(cleanupDays,lease,complete);
                if (result.refused() > 0 || result.more() || !result.blocked().isBlank()) {
                    if (chatAllowed(0)) sender().notice(new VkEvent("scheduled", groupId, ownerId, chatPeerId,
                            "auto-cleanup:" + today, ""), "⚠️ Автоочистка\n" + result.text());
                }
                else if (result.removed()>0 && cleaner().isEmpty() && chatAllowed(0)) {
                    sender().send(new VkEvent("scheduled",groupId,ownerId,chatPeerId,"auto-cleanup-help:"+today,""),emptyChatHelp());
                }
                return;
            }
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            System.err.println("Automatic cleanup failed: " + safeFailure(failure));
            if(!lease.isBlank()&&!(failure instanceof InterruptedException))try{finishAutomaticCleanup(cleanupDays,lease,false);}catch(Exception retryStateFailure){System.err.println("Cleanup retry state unavailable: "+safeFailure(retryStateFailure));}
            if (eligible && !(failure instanceof InterruptedException)) {
                try {
                    if (chatAllowed(0)) sender().notice(new VkEvent("scheduled", groupId, ownerId, chatPeerId,
                            "auto-cleanup:" + java.time.LocalDate.now(ZoneId.of("Europe/Moscow")), ""),
                            "⚠️ Автоочистка не завершилась. Финансовые данные сохранены. Повторю при следующем запуске после паузы; не более трёх попыток в сутки. Ручная очистка: «Ещё» → «Настройки» → «Очистка чата» → «Очистить сейчас».");
                } catch (Exception unavailable) { System.err.println("Cleanup notification unavailable"); }
            }
        }
    }

    private void finishAutomaticCleanup(int days,String lease,boolean complete)throws Exception{
        for(int retry=0;retry<6;retry++){
            var snapshot=store(stateKey).load().orElseThrow();var envelope=mapper.readValue(snapshot.bytes(),Envelope.class);
            var root=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.readTree(Base64.getDecoder().decode(envelope.stateBase64()));
            var settings=financeos.vk.ChatCleanupSettings.read(root);
            if(settings.days()!=days||!settings.leaseId().equals(lease))return;
            var now=java.time.Instant.now();String today=now.atZone(ZoneId.of("Europe/Moscow")).toLocalDate().toString();
            new financeos.vk.ChatCleanupSettings(days,complete?today:settings.lastDate(),settings.attemptDate(),settings.attempts(),complete?"":now.plusSeconds(settings.attempts()==1?60:300).toString(),"").write(root);
            var updated=new Envelope(1,Base64.getEncoder().encodeToString(mapper.writeValueAsBytes(root)),envelope.replies(),envelope.notified(),envelope.morningDate(),envelope.lastConversationMessageId());
            if(store(stateKey).save(snapshot.etag(),mapper.writeValueAsBytes(updated)))return;
        }
        throw new IOException("Cleanup completion conflict");
    }

    /** Called by the existing daily timer to recover callbacks whose trigger retries ran out. */
    public void recoverConversation() throws Exception {
        if (!chatAllowed(0)) {
            throw new IOException("Recovery conversation membership is not authorized");
        }
        var snapshot = store(stateKey).load().orElseThrow();
        Envelope envelope = mapper.readValue(snapshot.bytes(), Envelope.class);
        var pending = new VkHistoryClient(http, historyEndpoint, vkToken, groupId)
                .since(chatPeerId, members().authorizedMembers(chatPeerId,ownerId,allowedUsers), envelope.lastConversationMessageId());
        for (VkEvent event : pending) {
            try { processSingle(event); }
            catch (Exception failure) {
                if (!(failure instanceof ReportedFailure)) reportFailure(event, failure, Outcome.UNKNOWN);
                throw failure;
            }
        }
        retryPendingReplies();
    }

    private void markDelivered(String key, long messageId) throws Exception {
        for (int attempt = 0; attempt < 6; attempt++) {
            var current = store(stateKey).load().orElseThrow();
            Envelope envelope = mapper.readValue(current.bytes(), Envelope.class);
            SavedReply reply = envelope.replies().get(key);
            if (reply == null || reply.delivered()) return;
            var replies = new LinkedHashMap<>(envelope.replies());
            replies.put(key, reply.asDelivered(messageId));
            Envelope next = new Envelope(1, envelope.stateBase64(), replies,
                    envelope.notified(), envelope.morningDate(), envelope.lastConversationMessageId());
            if (store(stateKey).save(current.etag(), mapper.writeValueAsBytes(next))) return;
        }
        throw new IOException("Could not mark VK reply delivered");
    }

    private static String sha256(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private VkMessagesClient sender() {
        return new VkMessagesClient(sendEndpoint, vkToken, http);
    }

    private VkConversationMembersClient members() {
        return new VkConversationMembersClient(http, membersEndpoint, vkToken, groupId);
    }

    private ObjectStorageStateStore store(String key) {
        return new ObjectStorageStateStore(http, storageEndpoint, bucket, key, iamToken,
                key.equals(backupKey) || key.equals(stateKey) ? memory : null);
    }

    CloudMemoryCache.Stats cacheStats() { return memory.stats(); }

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

    public record SavedReply(String text, Screen screen, String imageBase64,
                             java.util.List<financeos.vk.MenuButton> buttons,
                             VkEvent event, boolean delivered, long messageId,long progressMessageId, @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY) java.util.List<String> additionalImages, boolean financialChanged) {
        public SavedReply(String text,Screen screen,String imageBase64,java.util.List<financeos.vk.MenuButton> buttons,VkEvent event,boolean delivered,long messageId,long progressMessageId,java.util.List<String> additionalImages){this(text,screen,imageBase64,buttons,event,delivered,messageId,progressMessageId,additionalImages,false);}
        public SavedReply(String text,Screen screen,String imageBase64,java.util.List<financeos.vk.MenuButton> buttons,VkEvent event,boolean delivered,long messageId,long progressMessageId){this(text,screen,imageBase64,buttons,event,delivered,messageId,progressMessageId,null);}
        public SavedReply(String text,Screen screen,String imageBase64,java.util.List<financeos.vk.MenuButton> buttons,VkEvent event,boolean delivered,long messageId){this(text,screen,imageBase64,buttons,event,delivered,messageId,0);}
        public SavedReply(String text, Screen screen, String imageBase64,
                          java.util.List<financeos.vk.MenuButton> buttons, VkEvent event, boolean delivered) {
            this(text, screen, imageBase64, buttons, event, delivered, 0);
        }
        public SavedReply(String text, Screen screen, String imageBase64) {
            this(text, screen, imageBase64, null, null, false);
        }
        static SavedReply from(BotReply reply) {
            return reply == null ? new SavedReply(null, null, null)
                    : new SavedReply(reply.text(), reply.screen(), reply.imagePng() == null
                    ? null : Base64.getEncoder().encodeToString(reply.imagePng()),
                    reply.buttons(), null, false,0,0,reply.additionalImages().stream().map(bytes->Base64.getEncoder().encodeToString(bytes)).toList());
        }
        SavedReply forEvent(VkEvent event) {
            return new SavedReply(text, screen, imageBase64, buttons, event, false,0,0,additionalImages,financialChanged);
        }
        SavedReply withFinancialChange(boolean changed){return new SavedReply(text,screen,imageBase64,buttons,event,delivered,messageId,progressMessageId,additionalImages,changed);}
        SavedReply withProgress(long id){return new SavedReply(text,screen,imageBase64,buttons,event,delivered,messageId,id,additionalImages,financialChanged);}
        SavedReply asDelivered() { return asDelivered(messageId); }
        SavedReply asDelivered(long id) {
            return new SavedReply(text, screen, imageBase64, buttons, event, true, id,progressMessageId,additionalImages,financialChanged);
        }
        BotReply toReply() {
            return text == null ? null : new BotReply(text, screen,
                    imageBase64 == null ? null : Base64.getDecoder().decode(imageBase64), buttons,additionalImages==null?java.util.List.of():additionalImages.stream().map(v->Base64.getDecoder().decode(v)).toList());
        }
    }
}
