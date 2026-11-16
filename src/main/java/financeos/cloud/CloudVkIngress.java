package financeos.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import financeos.vk.VkEvent;
import financeos.vk.VkEventParser;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.function.Function;

/** Yandex Cloud Functions entry point. It acknowledges VK only after durable event storage. */
public final class CloudVkIngress implements Function<CloudVkIngress.Request, CloudVkIngress.Response> {
    public static final class Request {
        public String httpMethod;
        public String body;
        public boolean isBase64Encoded;
    }

    public static final class Response {
        public int statusCode;
        public String body;
        public Map<String, String> headers;

        public Response(int statusCode, String body) {
            this.statusCode = statusCode;
            this.body = body;
            this.headers = Map.of("Content-Type", "text/plain; charset=utf-8");
        }
    }

    private final VkEventParser parser;
    private final long ownerId, chatPeerId;
    private final java.util.Set<Long> allowedUsers;
    private final String confirmationCode;
    private final String bucket;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient client;
    private final java.util.function.Supplier<String> token;
    private final java.net.URI storageEndpoint;

    public CloudVkIngress() {
        this(new VkEventParser(Long.parseLong(required("FINANCEOS_VK_GROUP_ID")),
                        required("FINANCEOS_VK_CALLBACK_SECRET")),
                required("FINANCEOS_VK_CONFIRMATION_CODE"), required("FINANCEOS_STATE_BUCKET"),
                HttpClient.newHttpClient(), java.net.URI.create("https://storage.yandexcloud.net"),
                new MetadataIamToken(), Long.parseLong(required("FINANCEOS_VK_USER_ID")),
                Long.parseLong(required("FINANCEOS_VK_CHAT_PEER_ID")));
    }

    CloudVkIngress(VkEventParser parser, String confirmationCode, String bucket,
                   HttpClient client, java.net.URI storageEndpoint,
                   java.util.function.Supplier<String> token, long ownerId, long chatPeerId) {
        this(parser,confirmationCode,bucket,client,storageEndpoint,token,ownerId,chatPeerId,financeos.vk.ChatAccess.configured(ownerId));
    }
    CloudVkIngress(VkEventParser parser,String confirmationCode,String bucket,HttpClient client,java.net.URI storageEndpoint,java.util.function.Supplier<String> token,long ownerId,long chatPeerId,java.util.Set<Long> users){
        if(ownerId<=0 || chatPeerId<2_000_000_000L)throw new IllegalArgumentException("Invalid owner scope");
        this.ownerId=ownerId;this.chatPeerId=chatPeerId;this.allowedUsers=java.util.Set.copyOf(users);
        this.parser = parser;
        this.confirmationCode = confirmationCode;
        this.bucket = bucket;
        this.client = client;
        this.storageEndpoint = storageEndpoint;
        this.token = token;
    }

    @Override public Response apply(Request request) {
        if (request == null || !"POST".equals(request.httpMethod)) {
            return new Response(405, "method not allowed");
        }
        if (request.body == null || request.body.length() > 100_000) {
            return new Response(413, "event too large");
        }
        byte[] body;
        try {
            body = request.isBase64Encoded
                    ? java.util.Base64.getDecoder().decode(request.body)
                    : request.body.getBytes(StandardCharsets.UTF_8);
        } catch (IllegalArgumentException invalid) {
            return new Response(400, "bad request");
        }
        if (body.length > 65_536) return new Response(413, "event too large");
        VkEvent event;
        try {
            event = parser.parse(body);
        } catch (SecurityException forbidden) {
            return new Response(403, "forbidden");
        } catch (IOException invalid) {
            return new Response(400, "bad request");
        }
        if ("confirmation".equals(event.type())) {
            return new Response(200, confirmationCode);
        }
        if (!"message_new".equals(event.type()) && !"message_event".equals(event.type())) {
            return new Response(200, "ok");
        }
        if(!financeos.vk.ChatAccess.eventAllowed(event.userId(),event.peerId(),ownerId,chatPeerId,allowedUsers)||!financeos.vk.ChatAccess.afterActivation(event,ownerId,financeos.vk.ChatAccess.memberStart()))return new Response(200,"ok");
        String key = "inbox/" + eventId(event) + ".json";
        try {
            byte[] bytes = mapper.writeValueAsBytes(event);
            var store = new ObjectStorageStateStore(client, storageEndpoint, bucket, key, token);
            store.save(null, bytes);
            return new Response(200, "ok");
        } catch (IOException | InterruptedException | IllegalStateException unavailable) {
            if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
            return new Response(503, "event storage unavailable");
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }

    static String eventId(VkEvent event) {
        try {
            String stable = event.type() + ':' + event.groupId() + ':' + event.peerId()
                    + ':' + event.eventId();
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(stable.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
