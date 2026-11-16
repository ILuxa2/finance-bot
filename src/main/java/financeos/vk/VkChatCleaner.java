package financeos.vk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** Deletes only processed messages in the explicitly configured private owner/community chat. */
public final class VkChatCleaner {
    private final HttpClient http;
    private final URI endpoint;
    private final String token;
    private final long group, owner, peer;
    private final Set<Long> allowedUsers;
    private final ObjectMapper mapper = new ObjectMapper();
    public record Result(int removed, int refused, boolean more, String blocked) {
        public String text() {
            if (!blocked.isBlank()) return "⚠️ Очистка не выполнена: " + blocked + " Финансовые данные сохранены.";
            return "🧹 Удалено в этой попытке · " + removed + ". VK не подтвердил удаление · " + refused
                    + (more ? ". В истории могут оставаться старые сообщения; очистку можно повторить." : ".")
                    + "\nФинансовые данные и история операций сохранены. Последние служебные сообщения остаются.";
        }
    }
    public VkChatCleaner(HttpClient http, URI endpoint, String token, long group, long owner, long peer) {
        this(http,endpoint,token,group,owner,peer,Set.of(owner));
    }
    public VkChatCleaner(HttpClient http,URI endpoint,String token,long group,long owner,long peer,Set<Long> users){
        this.allowedUsers=Set.copyOf(users);
        this.http = Objects.requireNonNull(http); this.endpoint = Objects.requireNonNull(endpoint);
        if (token == null || token.isBlank() || group <= 0 || owner <= 0 || peer < 2_000_000_000L) throw new IllegalArgumentException("Invalid cleanup configuration");
        this.token = token; this.group = group; this.owner = owner; this.peer = peer;
    }
    private static final class ApiFailure extends IOException {
        final int code;
        ApiFailure(int code) { super("VK cleanup API error " + code); this.code = code; }
    }
    public Result clean(long checkpoint, Instant cutoff) throws IOException, InterruptedException {
        try { return cleanChecked(checkpoint, cutoff); }
        catch (ApiFailure failure) {
            if (failure.code == 6 || failure.code == 10) throw failure;
            return new Result(0,0,false,"VK отказал в удалении (код " + failure.code + ").");
        }
    }
    private Result cleanChecked(long checkpoint, Instant cutoff) throws IOException, InterruptedException {
        long started = System.nanoTime();
        if (checkpoint <= 0) return new Result(0,0,false,"нет подтверждённой позиции обработки сообщений.");
        var members = new VkConversationMembersClient(http, endpoint.resolve("messages.getConversationMembers"), token, group);
        if (members.authorizedMembers(peer,owner,allowedUsers).isEmpty()) return new Result(0,0,false,"состав беседы изменился.");
        var conversations = api("messages.getConversationsById", Map.of("peer_ids", "" + peer, "group_id", "" + group));
        var items = conversations.path("response").path("items");
        if (!items.isArray() || items.size() != 1 || items.get(0).path("peer").path("id").asLong() != peer
                || !items.get(0).path("chat_settings").path("acl").path("can_moderate").asBoolean()) {
            return new Result(0,0,false,"боту нужны права модерации сообщений этой беседы.");
        }
        // Collect before deleting: deleting while paging by offset would skip older records.
        var candidates = new TreeSet<Long>(); boolean more = true;
        for (int page = 0; page < 50; page++) {
            if (System.nanoTime() - started > Duration.ofSeconds(20).toNanos()) break;
            var history = api("messages.getHistory", Map.of("peer_id", "" + peer, "count", "200", "offset", "" + (page * 200)));
            var messages = history.path("response").path("items");
            if (!messages.isArray()) throw new IOException("VK cleanup history response is invalid");
            for (var message : messages) {
                long sequence = message.path("conversation_message_id").asLong();
                long author = message.path("from_id").asLong();
                long date = message.path("date").asLong();
                if (message.path("peer_id").asLong() == peer && sequence > 0 && sequence <= checkpoint
                        && (allowedUsers.contains(author) || author == -group) && date > 0 && date < cutoff.getEpochSecond()
                        && !message.hasNonNull("action") && !message.path("is_deleted").asBoolean()
                        && message.path("deleted").asInt() != 1) candidates.add(sequence);
            }
            if (messages.size() < 200) { more = false; break; }
        }
        if (members.authorizedMembers(peer,owner,allowedUsers).isEmpty()) return new Result(0,0,false,"состав беседы изменился.");
        var ids = new ArrayList<>(candidates); int removed = 0, refused = 0;
        for (int start = 0; start < ids.size(); start += 100) {
            if (System.nanoTime() - started > Duration.ofSeconds(60).toNanos()) { more = true; break; }
            var batch = ids.subList(start, Math.min(start + 100, ids.size()));
            String cmids = batch.stream().map(Object::toString).collect(java.util.stream.Collectors.joining(","));
            JsonNode response;
            try { response = api("messages.delete", Map.of("peer_id", "" + peer, "cmids", cmids, "delete_for_all", "1")); }
            catch (ApiFailure failure) {
                if (failure.code == 6 || failure.code == 10) throw failure;
                return new Result(removed, refused + ids.size() - start, true, "");
            }
            var answers = response.path("response");
            if (!answers.isArray()) throw new IOException("VK cleanup delete response is invalid");
            var confirmed = new HashSet<Long>();
            for (var item : answers) {
                long cmid = item.path("conversation_message_id").asLong();
                if (item.path("peer_id").asLong() == peer && batch.contains(cmid)
                        && item.path("response").asInt() == 1 && !item.hasNonNull("error")) confirmed.add(cmid);
            }
            removed += confirmed.size(); refused += batch.size() - confirmed.size();
        }
        return new Result(removed, refused, more, "");
    }
    /** Fail closed: only claim empty after a complete read, including new messages above the checkpoint. */
    public boolean isEmpty() throws IOException, InterruptedException {
        var members=new VkConversationMembersClient(http,endpoint.resolve("messages.getConversationMembers"),token,group);
        if(!!members.authorizedMembers(peer,owner,allowedUsers).isEmpty())return false;
        long start=System.nanoTime();
        for(int page=0;page<50;page++){
            if(System.nanoTime()-start>Duration.ofSeconds(20).toNanos())return false;
            var items=api("messages.getHistory",Map.of("peer_id",""+peer,"count","200","offset",""+(page*200))).path("response").path("items");
            if(!items.isArray())throw new IOException("Invalid cleanup emptiness response");
            for(var item:items){
                if(item.path("is_deleted").asBoolean()||item.path("deleted").asInt()==1||item.hasNonNull("action"))continue;
                // Missing or foreign scope is not proof that a conversation is empty.
                return false;
            }
            if(items.size()<200)return !members.authorizedMembers(peer,owner,allowedUsers).isEmpty();
        }
        return false;
    }
    private JsonNode api(String method, Map<String,String> parameters) throws IOException, InterruptedException {
        var fields = new LinkedHashMap<>(parameters); fields.put("access_token", token); fields.put("v", "5.199");
        String body = fields.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8)).collect(java.util.stream.Collectors.joining("&"));
        var request = HttpRequest.newBuilder(endpoint.resolve(method)).timeout(Duration.ofSeconds(15))
                .header("Content-Type","application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException("VK cleanup HTTP " + response.statusCode());
        var root = mapper.readTree(response.body());
        if (root.has("error")) throw new ApiFailure(root.path("error").path("error_code").asInt());
        return root;
    }
}
