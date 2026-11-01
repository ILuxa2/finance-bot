package financeos.cloud;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.function.Supplier;

/** Retrieves the attached Cloud Functions service account token without a stored key. */
public final class MetadataIamToken implements Supplier<String> {
    private static final URI METADATA = URI.create("http://169.254.169.254/computeMetadata/v1/"
            + "instance/service-accounts/default/token");
    private final HttpClient client;
    private final URI endpoint;
    private final ObjectMapper mapper = new ObjectMapper();
    private String token;
    private Instant refreshAt = Instant.EPOCH;

    public MetadataIamToken() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(), METADATA);
    }

    public MetadataIamToken(HttpClient client, URI endpoint) {
        this.client = Objects.requireNonNull(client);
        this.endpoint = Objects.requireNonNull(endpoint);
    }

    @Override public synchronized String get() {
        if (token != null && Instant.now().isBefore(refreshAt)) return token;
        try {
            var request = HttpRequest.newBuilder(endpoint)
                    .timeout(Duration.ofSeconds(5))
                    .header("Metadata-Flavor", "Google")
                    .GET().build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw new IOException("Metadata HTTP " + response.statusCode());
            var body = mapper.readTree(response.body());
            String next = body.path("access_token").asText("");
            long seconds = body.path("expires_in").asLong(0);
            if (next.isBlank() || seconds < 60) throw new IOException("Invalid metadata token response");
            token = next;
            refreshAt = Instant.now().plusSeconds(Math.max(1, seconds - 60));
            return token;
        } catch (IOException | InterruptedException error) {
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Could not obtain function service account token", error);
        }
    }
}
