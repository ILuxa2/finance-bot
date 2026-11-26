package financeos.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import financeos.data.LegacySheetSchema;
import financeos.vk.VkEvent;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public final class CloudVkProcessorTest {
    public static void main(String[] args) throws Exception {
        String diagnostic = CloudVkProcessor.safeFailure(new java.io.IOException("secret-token-and-financial-text",
                new java.io.IOException("VK photo API error 15")));
        if (diagnostic.contains("secret-token") || !diagnostic.contains("VK photo API error 15")) {
            throw new AssertionError("Diagnostic must preserve safe codes and hide arbitrary messages");
        }
        var mapper = new ObjectMapper();
        var objects = new ConcurrentHashMap<String, byte[]>();
        var sent = new AtomicInteger();
        var editedPages = new AtomicInteger();
        var completedPages=new AtomicInteger();
        var rejectPageEdit = new java.util.concurrent.atomic.AtomicBoolean(false);
        var injectSendFailure = new java.util.concurrent.atomic.AtomicBoolean(true);
        var randomIds = new java.util.ArrayList<String>();
        var recoverMissed = new java.util.concurrent.atomic.AtomicBoolean(false);
        var unavailableStateReads = new java.util.concurrent.atomic.AtomicBoolean(false);
        var loseCommitResponse = new java.util.concurrent.atomic.AtomicBoolean(false);
        var progressIds = new java.util.HashSet<String>();
        var notices = new java.util.ArrayList<String>();
        var sheets = new LinkedHashMap<String, Object>();
        LegacySheetSchema.headers().keySet().forEach(name -> sheets.put(name, List.of()));
        sheets.put("StartupState", List.of(Map.of("telegram_user_id", "7",
                "created_at", "2026-10-30T09:00:00+03:00", "total_balance", "100")));
        objects.put("/test-bucket/base.json", mapper.writeValueAsBytes(
                Map.of("formatVersion", 1, "sheets", sheets)));
        objects.put("/test-bucket/state.json", mapper.writeValueAsBytes(
                new CloudVkProcessor.Envelope(1, "", new LinkedHashMap<>(),
                        new java.util.LinkedHashSet<>(), "", 100)));
        VkEvent requested = new VkEvent("message_new", 99, 42, 2000000001L,
                "103", "1, test", 103);
        String inbox = "inbox/" + CloudVkIngress.eventId(requested) + ".json";
        objects.put("/test-bucket/" + inbox, mapper.writeValueAsBytes(requested));
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/test-bucket/", exchange -> {
            try {
                if(exchange.getRequestURI().getRawQuery()!=null&&exchange.getRequestURI().getRawQuery().startsWith("versions=")){byte[] listing="<ListVersionsResult><IsTruncated>false</IsTruncated></ListVersionsResult>".getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,listing.length);exchange.getResponseBody().write(listing);return;}
                String key = exchange.getRequestURI().getPath();
                byte[] existing = objects.get(key);
                if ("GET".equals(exchange.getRequestMethod())) {
                    if (key.equals("/test-bucket/state.json") && unavailableStateReads.get()) {
                        exchange.sendResponseHeaders(503, -1);
                        return;
                    }
                    if (existing == null) exchange.sendResponseHeaders(404, -1);
                    else if (etag(existing).equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                        exchange.sendResponseHeaders(304, -1);
                    } else {
                        exchange.getResponseHeaders().add("ETag", etag(existing));
                        exchange.sendResponseHeaders(200, existing.length);
                        exchange.getResponseBody().write(existing);
                    }
                } else if ("PUT".equals(exchange.getRequestMethod())) {
                    String expected = exchange.getRequestHeaders().getFirst("If-Match");
                    if (existing == null ? !"*".equals(exchange.getRequestHeaders().getFirst("If-None-Match"))
                            : !etag(existing).equals(expected)) {
                        exchange.sendResponseHeaders(412, -1);
                    } else {
                        objects.put(key, exchange.getRequestBody().readAllBytes());
                        if (key.equals("/test-bucket/state.json") && loseCommitResponse.getAndSet(false)) {
                            exchange.sendResponseHeaders(503, -1);
                        } else {
                            exchange.getResponseHeaders().set("ETag", etag(objects.get(key)));
                            exchange.sendResponseHeaders(200, -1);
                        }
                    }
                } else exchange.sendResponseHeaders(405, -1);
            } finally { exchange.close(); }
        });
        server.createContext("/history", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                String json = "{\"response\":{\"items\":["
                        + (recoverMissed.get() ? message(104, "/report") + "," : "")
                        + message(103, "1, test") + ","
                        + message(102, "Еда") + ","
                        + message(101, "/expense") + ","
                        + message(100, "old") + "]}}";
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        server.createContext("/members", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                byte[] bytes = "{\"response\":{\"count\":2,\"items\":[{\"member_id\":42},{\"member_id\":-99}]}}"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        server.createContext("/send", exchange -> {
            try {
                String form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String random = java.util.Arrays.stream(form.split("&")).filter(f -> f.startsWith("random_id="))
                        .findFirst().orElseThrow();
                String messageText = java.util.Arrays.stream(form.split("&"))
                        .filter(f -> f.startsWith("message=")).map(f -> java.net.URLDecoder.decode(f.substring(8), StandardCharsets.UTF_8))
                        .findFirst().orElseThrow();
                notices.add(messageText);
                if (messageText.equals("⏳ Обрабатываю…")) {
                    progressIds.add(random);
                    byte[] bytes = "{\"response\":1}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    return;
                }
                randomIds.add(random);
                int count = sent.incrementAndGet();
                if (count == 3 && injectSendFailure.getAndSet(false)) {
                    exchange.sendResponseHeaders(503, -1);
                    return;
                }
                byte[] bytes = "{\"response\":1}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        server.createContext("/messages.edit", exchange -> {
            String form=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            String message=java.util.Arrays.stream(form.split("&")).filter(f->f.startsWith("message=")).map(f->java.net.URLDecoder.decode(f.substring(8),StandardCharsets.UTF_8)).findFirst().orElseThrow();
            boolean completion=message.startsWith("✅ Готово ·");boolean progress=message.equals("⏳ Почти готово…")||message.equals("📊 Загружаю диаграммы…")||message.equals("✅ Ответ отправлен.");if(completion)completedPages.incrementAndGet();else if(!progress)editedPages.incrementAndGet();
            byte[] bytes = (!completion&&rejectPageEdit.get() ? "{\"error\":{\"error_code\":909}}" : "{\"response\":1}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (var out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var processor = new CloudVkProcessor(HttpClient.newHttpClient(), base,
                    base.resolve("/history"), base.resolve("/send"), base.resolve("/members"),
                    () -> "test-iam", "test-bucket", "test-vk-token", 99, 42,
                    2000000001L, "base.json", "state.json");
            var trigger = Map.<String, Object>of("messages", List.of(Map.of(
                    "event_metadata", Map.of("event_type", "yandex.cloud.events.storage.ObjectCreate"),
                    "details", Map.of("bucket_id", "test-bucket", "object_id", inbox))));
            try {
                processor.apply(trigger);
                throw new AssertionError("Injected VK failure did not request a retry");
            } catch (IllegalStateException expected) { }
            if (progressIds.size() != 3) throw new AssertionError("Menu, category and amount did not each receive progress");
            if (notices.stream().noneMatch(t -> t.contains("Изменение сохранено")&&t.contains("Отправка задерживается"))) throw new AssertionError("Saved result was reported incorrectly");
            var state = mapper.readTree(objects.get("/test-bucket/state.json"));
            if (state.path("lastConversationMessageId").asLong() != 103
                    || state.path("replies").size() != 3 || sent.get() != 4) {
                throw new AssertionError("Out-of-order callback did not process all VK commands in order");
            }
            var local = mapper.readTree(java.util.Base64.getDecoder().decode(
                    state.path("stateBase64").asText()));
            if (local.path("entries").size() != 1
                    || !"1.00".equals(local.path("entries").get(0).path("amount").asText())) {
                throw new AssertionError("Ordered VK expense was not saved exactly once");
            }
            if (objects.keySet().stream().noneMatch(k -> k.startsWith("/test-bucket/java-v1/restore-points/events/"))) {
                throw new AssertionError("Recovery snapshots were not stored");
            }
            processor.apply(trigger);
            if (sent.get() != 5 || !randomIds.get(2).equals(randomIds.get(4))) {
                throw new AssertionError("Failed reply did not retry with the same VK random_id");
            }
            processor.apply(trigger);
            if (sent.get() != 5 || progressIds.size() != 3) throw new AssertionError("Delivered action repeated a status or reply");
            var again = mapper.readTree(objects.get("/test-bucket/state.json"));
            if (again.path("stateBase64").asText().equals("")
                    || !again.path("stateBase64").asText().equals(state.path("stateBase64").asText())) {
                throw new AssertionError("Repeated trigger changed the financial state");
            }
            recoverMissed.set(true);
            processor.recoverConversation();
            var recovered = mapper.readTree(objects.get("/test-bucket/state.json"));
            if (recovered.path("lastConversationMessageId").asLong() != 104 || sent.get() != 6) {
                throw new AssertionError("Daily recovery did not process a message whose callback was missed");
            }
            processor.recoverConversation();
            if (sent.get() != 6) throw new AssertionError("Daily recovery duplicated a delivered response");
            if (progressIds.size() != 4) throw new AssertionError("Recovered action did not receive progress");
            byte[] beforeFailure = objects.get("/test-bucket/state.json");
            byte[] baseSnapshot = objects.remove("/test-bucket/base.json");
            var missingBaseEvent = new VkEvent("message_event", 99, 42, 2000000001L, "missing-base", "/report");
            var missingBaseTrigger = eventTrigger(objects, mapper, missingBaseEvent);
            expectFailure(processor, missingBaseTrigger);
            if (!notices.getLast().contains("изменения не сохранены")) throw new AssertionError("Pre-commit failure was not explained");
            if (!java.util.Arrays.equals(beforeFailure, objects.get("/test-bucket/state.json"))) throw new AssertionError("Pre-commit error changed state");
            objects.put("/test-bucket/base.json", baseSnapshot);
            var unknownEvent = new VkEvent("message_event", 99, 42, 2000000001L, "unknown", "/plans");
            var unknownTrigger = eventTrigger(objects, mapper, unknownEvent);
            unavailableStateReads.set(true);
            expectFailure(processor, unknownTrigger);
            if (!notices.getLast().contains("Проверяю результат сохранения") || notices.getLast().contains("не сохранены")) {
                throw new AssertionError("Unknown outcome encouraged an unsafe retry");
            }
            unavailableStateReads.set(false);
            if (!java.util.Arrays.equals(beforeFailure, objects.get("/test-bucket/state.json"))) throw new AssertionError("Unavailable storage changed state");
            var lostResponseEvent = new VkEvent("message_event", 99, 42, 2000000001L, "lost-put-response", "/add");
            var lostResponseTrigger = eventTrigger(objects, mapper, lostResponseEvent);
            loseCommitResponse.set(true);
            expectFailure(processor, lostResponseTrigger);
            if (!notices.getLast().contains("Отправка задерживается")||notices.getLast().contains("Изменение сохранено")) throw new AssertionError("Read-only response claimed a financial change");
            String committed = mapper.readTree(objects.get("/test-bucket/state.json")).path("stateBase64").asText();
            processor.apply(lostResponseTrigger);
            if (!committed.equals(mapper.readTree(objects.get("/test-bucket/state.json")).path("stateBase64").asText())) {
                throw new AssertionError("Retry after lost PUT response repeated the action");
            }
            for (int i = 0; i < 12; i++) {
                for (String command : List.of("/expense", "Еда", "1, страница " + i)) {
                    var commandEvent = new VkEvent("message_event", 99, 42, 2000000001L, "page-data-" + i + "-" + command, command);
                    processor.apply(eventTrigger(objects, mapper, commandEvent));
                }
            }
            var pickerEvent = new VkEvent("message_event",99,42,2000000001L,"picker","/edit_operations");
            processor.apply(eventTrigger(objects,mapper,pickerEvent));
            var pickerEnvelope=mapper.readValue(objects.get("/test-bucket/state.json"),CloudVkProcessor.Envelope.class);
            var pickerReply=pickerEnvelope.replies().get(CloudVkIngress.eventId(pickerEvent));
            String pageCommand=pickerReply.buttons().stream().filter(b->b.command().startsWith("/choices/")).findFirst().orElseThrow().command();
            long originalMessage=pickerReply.messageId();
            if(originalMessage<=0)throw new AssertionError("List message ID not persisted");
            var pageEvent=new VkEvent("message_event",99,42,2000000001L,"second-page",pageCommand);
            processor.apply(eventTrigger(objects,mapper,pageEvent));
            var pageEnvelope=mapper.readValue(objects.get("/test-bucket/state.json"),CloudVkProcessor.Envelope.class);
            var pageReply=pageEnvelope.replies().get(CloudVkIngress.eventId(pageEvent));
            if(editedPages.get()!=1||pageReply.messageId()!=originalMessage||completedPages.get()!=1||pageReply.progressMessageId()<=0)throw new AssertionError("Pagination did not reuse last delivered view");
            String backCommand=pageReply.buttons().stream().filter(b->b.command().endsWith("/0")).findFirst().orElseThrow().command();
            rejectPageEdit.set(true);int beforeFallback=sent.get();
            processor.apply(eventTrigger(objects,mapper,new VkEvent("message_event",99,42,2000000001L,"page-fallback",backCommand)));
            if(editedPages.get()!=2||sent.get()!=beforeFallback+1||completedPages.get()!=2)throw new AssertionError("Rejected page edit did not fall back to send");
            // A cold retry must retain and finish the original progress notice without recalculating finances.
            var beforePageRetry=mapper.readValue(objects.get("/test-bucket/state.json"),CloudVkProcessor.Envelope.class);
            var retryEvent=new VkEvent("message_event",99,42,2000000001L,"cold-page-retry",pageCommand);
            var retryReplies=new LinkedHashMap<>(beforePageRetry.replies());retryReplies.put(CloudVkIngress.eventId(retryEvent),CloudVkProcessor.SavedReply.from(pageReply.toReply()).forEvent(retryEvent).withProgress(987));
            objects.put("/test-bucket/state.json",mapper.writeValueAsBytes(new CloudVkProcessor.Envelope(1,beforePageRetry.stateBase64(),retryReplies,beforePageRetry.notified(),beforePageRetry.morningDate(),beforePageRetry.lastConversationMessageId())));
            int completionsBefore=completedPages.get();var freshProcessor=new CloudVkProcessor(HttpClient.newHttpClient(),base,base.resolve("/history"),base.resolve("/send"),base.resolve("/members"),()->"test-iam","test-bucket","test-vk-token",99,42,2000000001L,"base.json","state.json");freshProcessor.retryPendingReplies();
            var afterPageRetry=mapper.readValue(objects.get("/test-bucket/state.json"),CloudVkProcessor.Envelope.class);
            if(completedPages.get()!=completionsBefore+1||!afterPageRetry.stateBase64().equals(beforePageRetry.stateBase64())||!afterPageRetry.replies().get(CloudVkIngress.eventId(retryEvent)).delivered())throw new AssertionError("Cold retry left progress unfinished or recomputed finance");
            var stats=processor.cacheStats();
            if(stats.validatedHits()==0||stats.baseHits()==0||stats.stateHits()==0)throw new AssertionError("Real processor did not exercise warm cache layers");
            var beforeCold=mapper.readTree(java.util.Base64.getDecoder().decode(mapper.readTree(objects.get("/test-bucket/state.json")).path("stateBase64").asText()));
            var cold=new CloudVkProcessor(HttpClient.newHttpClient(),base,base.resolve("/history"),base.resolve("/send"),base.resolve("/members"),
                    ()->"test-iam","test-bucket","test-vk-token",99,42,2000000001L,"base.json","state.json");
            if(cold.cacheStats().rawEntries()!=0)throw new AssertionError("Cache leaked into another handler instance");
            cold.apply(trigger);
            cold.apply(eventTrigger(objects,mapper,new VkEvent("message_event",99,42,2000000001L,"cold-report","/report")));
            var afterCold=mapper.readTree(java.util.Base64.getDecoder().decode(mapper.readTree(objects.get("/test-bucket/state.json")).path("stateBase64").asText()));
            for(String field:List.of("entries","debts","goals","deletedIds")) {
                if(!beforeCold.path(field).equals(afterCold.path(field)))throw new AssertionError("Cold cache recovery changed financial field "+field);
            }

        } finally {
            server.stop(0);
        }
        System.out.println("Cloud VK ordered processing checks passed.");
    }

    private static void expectFailure(CloudVkProcessor processor, Map<String, Object> trigger) {
        try { processor.apply(trigger); throw new AssertionError("Injected failure succeeded"); }
        catch (IllegalStateException expected) { }
    }

    private static Map<String, Object> eventTrigger(Map<String, byte[]> objects, ObjectMapper mapper, VkEvent event) throws Exception {
        String key = "inbox/" + CloudVkIngress.eventId(event) + ".json";
        objects.put("/test-bucket/" + key, mapper.writeValueAsBytes(event));
        return Map.of("messages", List.of(Map.of("event_metadata", Map.of("event_type", "yandex.cloud.events.storage.ObjectCreate"),
                "details", Map.of("bucket_id", "test-bucket", "object_id", key))));
    }

    private static String message(int id, String text) {
        try {
            return new ObjectMapper().writeValueAsString(Map.of("id", id,
                    "conversation_message_id", id, "from_id", 42, "peer_id", 2000000001L,
                    "out", 0, "text", text));
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    private static String etag(byte[] bytes) {
        try {
            return "\"" + HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(bytes)) + "\"";
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }
}
