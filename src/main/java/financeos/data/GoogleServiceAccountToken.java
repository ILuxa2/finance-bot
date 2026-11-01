package financeos.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/** Lazily obtains a Sheets token from a user-provided local key file. */
public final class GoogleServiceAccountToken implements Supplier<String> {
    public enum Scope { READ_ONLY, READ_WRITE }
    private final GoogleCredentials credentials;
    private final Scope scope;

    public GoogleServiceAccountToken(Path keyFile) throws IOException {
        this(keyFile, Scope.READ_ONLY);
    }

    public GoogleServiceAccountToken(Path keyFile, Scope scope) throws IOException {
        this.scope = java.util.Objects.requireNonNull(scope);
        byte[] json = Files.readAllBytes(keyFile);
        JsonNode root = new ObjectMapper().readTree(json);
        if (!"service_account".equals(root.path("type").asText())
                || !"https://oauth2.googleapis.com/token".equals(root.path("token_uri").asText())
                || !root.path("client_email").asText("").endsWith(".gserviceaccount.com")) {
            throw new IOException("Unexpected Google credential format or token endpoint");
        }
        try (var input = new ByteArrayInputStream(json)) {
            credentials = ServiceAccountCredentials.fromStream(input)
                    .createScoped(List.of(scope == Scope.READ_ONLY
                            ? "https://www.googleapis.com/auth/spreadsheets.readonly"
                            : "https://www.googleapis.com/auth/spreadsheets"));
        }
    }

    @Override public synchronized String get() {
        try {
            credentials.refreshIfExpired();
            return credentials.getAccessToken().getTokenValue();
        } catch (IOException error) {
            throw new IllegalStateException("Could not obtain Google Sheets token (" + scope + ")", error);
        }
    }
}
