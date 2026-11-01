package financeos.vk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/** Uploads one PNG for a VK private message and returns its attachment identifier. */
final class VkPhotoUploader {
    private final URI methodEndpoint;
    private final String token;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    VkPhotoUploader(URI messagesEndpoint, String token, HttpClient http) {
        this.methodEndpoint = messagesEndpoint;
        this.token = token;
        this.http = http;
    }

    String upload(long peerId, byte[] png) throws IOException, InterruptedException {
        if (png == null || png.length < 8 || png.length > 10_000_000
                || png[0] != (byte) 0x89 || png[1] != 'P' || png[2] != 'N' || png[3] != 'G') {
            throw new IllegalArgumentException("Invalid PNG for VK upload");
        }
        JsonNode server = api("photos.getMessagesUploadServer",
                form("peer_id", Long.toString(peerId))).path("response");
        String uploadUrl = server.path("upload_url").asText("");
        if (uploadUrl.isBlank()) throw new IOException("VK did not return a photo upload URL");
        URI target = URI.create(uploadUrl);
        if (!"https".equalsIgnoreCase(target.getScheme()) && !isLocalTestTarget(target)) {
            throw new IOException("VK returned an unsupported photo upload URL");
        }
        String boundary = "financeos-" + UUID.randomUUID();
        byte[] multipart = multipart(boundary, png);
        var request = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(30))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(multipart)).build();
        HttpResponse<String> uploaded;
        try {
            uploaded = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw new IOException("VK photo upload connection failed");
        }
        if (uploaded.statusCode() != 200) throw new IOException("VK photo upload HTTP error");
        JsonNode upload = mapper.readTree(uploaded.body());
        String photo = upload.path("photo").asText("");
        String hash = upload.path("hash").asText("");
        int serverId = upload.path("server").asInt(0);
        if (photo.isBlank() || hash.isBlank() || serverId <= 0) {
            throw new IOException("VK photo upload response is incomplete");
        }
        JsonNode saved = api("photos.saveMessagesPhoto", form("photo", photo) + "&"
                + form("server", Integer.toString(serverId)) + "&" + form("hash", hash))
                .path("response");
        if (!saved.isArray() || saved.isEmpty()) throw new IOException("VK did not save the photo");
        JsonNode first = saved.get(0);
        long owner = first.path("owner_id").asLong(0);
        long id = first.path("id").asLong(0);
        if (owner == 0 || id <= 0) throw new IOException("VK saved photo has no ID");
        String attachment = "photo" + owner + "_" + id;
        String accessKey = first.path("access_key").asText("");
        return accessKey.isBlank() ? attachment : attachment + "_" + accessKey;
    }

    private JsonNode api(String method, String parameters) throws IOException, InterruptedException {
        URI target = methodEndpoint.resolve(method);
        String body = parameters + "&" + form("access_token", token) + "&" + form("v", "5.199");
        var request = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) throw new IOException("VK photo API HTTP error");
        JsonNode root = mapper.readTree(response.body());
        if (root.path("error").isObject()) {
            throw new IOException("VK photo API error " + root.path("error").path("error_code").asInt());
        }
        return root;
    }

    private byte[] multipart(String boundary, byte[] png) throws IOException {
        var out = new ByteArrayOutputStream();
        out.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"photo\"; filename=\"chart.png\"\r\n"
                + "Content-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(png);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private String form(String key, String value) {
        return URLEncoder.encode(key, StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private boolean isLocalTestTarget(URI target) {
        return "http".equalsIgnoreCase(target.getScheme())
                && ("127.0.0.1".equals(target.getHost())
                || "localhost".equals(target.getHost()));
    }
}
