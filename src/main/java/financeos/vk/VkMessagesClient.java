package financeos.vk;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;

/** VK text sender. A stable random_id makes a retried event safe to resend. */
public final class VkMessagesClient {
    private final URI endpoint;
    private final String token;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final VkKeyboardFactory keyboards = new VkKeyboardFactory();
    private final VkPhotoUploader photos;

    public VkMessagesClient(URI endpoint, String token, HttpClient http) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("VK token is required");
        this.endpoint = java.util.Objects.requireNonNull(endpoint);
        this.token = token;
        this.http = java.util.Objects.requireNonNull(http);
        this.photos = new VkPhotoUploader(endpoint, token, http);
    }

    public long send(VkEvent event, BotReply reply) throws IOException, InterruptedException {
        if (event.peerId() <= 0 || reply.text() == null || reply.text().isBlank()
                || reply.text().length() > 9000) {
            throw new IllegalArgumentException("Invalid VK message");
        }
        var images=ChartAttachments.fit(reply.images());var attachments=new java.util.ArrayList<String>();
        try(var uploads=java.util.concurrent.Executors.newFixedThreadPool(3)){
            var pending=images.stream().map(image->uploads.submit(()->photos.upload(event.peerId(),image))).toList();
            for(var upload:pending)try{attachments.add(upload.get());}
            catch(java.util.concurrent.ExecutionException failure){for(var task:pending)task.cancel(true);var cause=failure.getCause();if(cause instanceof IOException io)throw io;if(cause instanceof InterruptedException interrupted){Thread.currentThread().interrupt();throw interrupted;}throw new IOException("VK chart upload failed",cause);}
        }
        return sendPart(event,reply,String.join(",",attachments));
    }

    private long sendPart(VkEvent event,BotReply reply,String attachment)throws IOException,InterruptedException {
        String body = form(event, reply.text(), attachment);
        if(reply.buttons()!=null && reply.buttons().isEmpty())return sendForm(body);
        String keyboard;
        try {
            keyboard = reply.buttons() == null ? keyboards.keyboard(reply.screen()) : keyboards.keyboard(reply.buttons());
        } catch (IllegalArgumentException | IllegalStateException obsoleteLayout) {
            // A queued reply may still contain the former five-item keyboard.
            // Deliver its saved result with the current section controls instead of retrying forever.
            keyboard = keyboards.keyboard(reply.screen());
            System.out.println("Сохранённая клавиатура обновлена до меню раздела.");
        }
        try {
            return sendForm(body + "&" + field("keyboard", keyboard));
        } catch (IOException invalidKeyboard) {
            if (!"VK API error 911".equals(invalidKeyboard.getMessage())) throw invalidKeyboard;
            System.out.println("VK отклонил клавиатуру. Повторяю отправку текста без кнопок.");
            String fallbackText = reply.text() + "\n\nVK временно не показал кнопки. Откройте меню через /start."
                    + " Справка по дополнительным командам доступна через /help.";
            if (fallbackText.length() > 9000) fallbackText = reply.text();
            return sendForm(form(event, fallbackText, attachment));
        }
    }

    /** Read-only pagination can reuse its last delivered list message. Failed edits safely fall back to send. */
    public long edit(VkEvent event, BotReply reply, long messageId) throws IOException, InterruptedException {
        if (messageId <= 0 || reply.imagePng() != null) throw new IllegalArgumentException("Invalid editable reply");
        String keyboard = reply.buttons() == null ? keyboards.keyboard(reply.screen()) : keyboards.keyboard(reply.buttons());
        String body = field("peer_id", Long.toString(event.peerId())) + "&" + field("message_id", Long.toString(messageId))
                + "&" + field("message", reply.text()) + (reply.buttons()!=null&&reply.buttons().isEmpty()?"":"&"+field("keyboard",keyboard))
                + "&" + field("access_token", token) + "&" + field("v", "5.199");
        var request = HttpRequest.newBuilder(endpoint.resolve("messages.edit")).timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) throw new IOException("VK edit HTTP failure");
        var json = mapper.readTree(response.body());
        if (json.has("error") || json.path("response").asInt() != 1) throw new IOException("VK edit rejected");
        return messageId;
    }

    /** A status message uses a separate stable ID and never replaces the final reply. */
    public long notice(VkEvent event, String text) throws IOException, InterruptedException {
        return sendForm(form(event, text, ""));
    }

    private String form(VkEvent event, String message, String attachment) {
        return field("peer_id", Long.toString(event.peerId())) + "&"
                + field("random_id", Integer.toString(randomId(event))) + "&"
                + field("message", message) + "&"
                + (attachment.isBlank() ? "" : field("attachment", attachment) + "&")
                + field("access_token", token) + "&" + field("v", "5.199");
    }

    private long sendForm(String body) throws IOException, InterruptedException {
        var request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException("VK HTTP status " + response.statusCode());
        }
        var json = mapper.readTree(response.body());
        if (json.path("error").isObject()) {
            throw new IOException("VK API error " + json.path("error").path("error_code").asInt());
        }
        long id = json.path("response").asLong(0);
        if (id <= 0) throw new IOException("VK API did not return a message ID");
        return id;
    }

    public static int randomId(VkEvent event) {
        try {
            String stable = event.type() + ":" + event.groupId() + ":"
                    + event.peerId() + ":" + event.eventId();
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(stable.getBytes(StandardCharsets.UTF_8));
            int value = ((bytes[0] & 0x7f) << 24) | ((bytes[1] & 0xff) << 16)
                    | ((bytes[2] & 0xff) << 8) | (bytes[3] & 0xff);
            return value == 0 ? 1 : value;
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private String field(String key, String value) {
        return URLEncoder.encode(key, StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
