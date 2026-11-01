package financeos.cloud;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** A single state object with compare-and-swap writes through S3 conditional headers. */
public final class ObjectStorageStateStore {
    public record Snapshot(byte[] bytes, String etag) {
        public Snapshot {
            bytes = Arrays.copyOf(Objects.requireNonNull(bytes), bytes.length);
            if (etag == null || etag.isBlank()) throw new IllegalArgumentException("Missing ETag");
        }

        @Override public byte[] bytes() { return Arrays.copyOf(bytes, bytes.length); }
    }

    private final HttpClient client;
    private final URI objectUri;
    private final Supplier<String> iamToken;

    public ObjectStorageStateStore(String bucket, String key, Supplier<String> iamToken) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build(),
                URI.create("https://storage.yandexcloud.net"), bucket, key, iamToken);
    }

    public ObjectStorageStateStore(HttpClient client, URI endpoint, String bucket, String key,
                                   Supplier<String> iamToken) {
        if (bucket == null || !bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")) {
            throw new IllegalArgumentException("Invalid Object Storage bucket");
        }
        if (key == null || !key.matches("[A-Za-z0-9_./-]+") || key.startsWith("/")
                || key.contains("..") || key.contains("//")) {
            throw new IllegalArgumentException("Invalid Object Storage key");
        }
        this.client = Objects.requireNonNull(client);
        boolean secure = endpoint != null && "https".equals(endpoint.getScheme());
        boolean localTest = endpoint != null && "http".equals(endpoint.getScheme())
                && ("127.0.0.1".equals(endpoint.getHost()) || "localhost".equals(endpoint.getHost()));
        if ((!secure && !localTest)
                || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null) {
            throw new IllegalArgumentException("Object Storage endpoint must use HTTPS");
        }
        this.objectUri = endpoint.resolve("/" + bucket + "/" + key);
        this.iamToken = Objects.requireNonNull(iamToken);
    }

    public Optional<Snapshot> load() throws IOException, InterruptedException {
        var request = authorized(HttpRequest.newBuilder(objectUri)).GET().build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() == 404) return Optional.empty();
        if (response.statusCode() != 200) {
            throw new IOException("Object Storage read failed with HTTP " + response.statusCode());
        }
        String etag = response.headers().firstValue("ETag")
                .orElseThrow(() -> new IOException("Object Storage returned no ETag"));
        return Optional.of(new Snapshot(response.body(), etag));
    }

    /** Returns false on a concurrent write; the caller must reload and recompute. */
    public boolean save(String expectedEtag, byte[] bytes) throws IOException, InterruptedException {
        Objects.requireNonNull(bytes);
        var builder = authorized(HttpRequest.newBuilder(objectUri))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Content-MD5", md5(bytes));
        if (expectedEtag == null) builder.header("If-None-Match", "*");
        else {
            if (!expectedEtag.matches("\"[A-Za-z0-9-]+\"")) {
                throw new IllegalArgumentException("Invalid expected ETag");
            }
            builder.header("If-Match", expectedEtag);
        }
        var response = client.send(builder.PUT(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),
                HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() == 412 || response.statusCode() == 409) return false;
        if (response.statusCode() != 200 && response.statusCode() != 201) {
            throw new IOException("Object Storage write failed with HTTP " + response.statusCode());
        }
        return true;
    }

    private HttpRequest.Builder authorized(HttpRequest.Builder builder) throws IOException {
        String token = iamToken.get();
        if (token == null || token.isBlank() || token.indexOf('\n') >= 0) {
            throw new IOException("Object Storage IAM token unavailable");
        }
        return builder.timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + token);
    }

    private static String md5(byte[] bytes) {
        try {
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("MD5").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
