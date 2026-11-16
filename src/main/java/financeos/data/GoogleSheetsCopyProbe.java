package financeos.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** One-shot write/read/delete check restricted to the owner's test copy. */
public final class GoogleSheetsCopyProbe {
    public static final String TEST_COPY_ID = "1ZMbKpXVC9qDqxdEvp5vF-lO7NxSa9-F8id7jDadNEiw";
    private static final URI GOOGLE_API = URI.create("https://sheets.googleapis.com");
    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final URI apiBase;
    private final String spreadsheetId;
    private final Supplier<String> token;

    public GoogleSheetsCopyProbe(URI apiBase, String spreadsheetId, Supplier<String> token) {
        requireTestCopy(spreadsheetId);
        this.apiBase = apiBase;
        this.spreadsheetId = spreadsheetId;
        this.token = token;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public static void requireTestCopy(String spreadsheetId) {
        if (!TEST_COPY_ID.equals(spreadsheetId)) {
            throw new IllegalArgumentException("Only the designated test copy may be used for this write check");
        }
    }

    public void run() throws IOException, InterruptedException {
        // Reject an unexpected layout before creating even a temporary sheet.
        new GoogleSheetsReadOnly(apiBase, spreadsheetId, token).readAll();
        String title = "FinanceOS_Java_Probe_" + UUID.randomUUID().toString().replace("-", "");
        String marker = "financeos-probe-" + UUID.randomUUID();
        Integer sheetId = null;
        try {
            JsonNode created = post("/v4/spreadsheets/" + spreadsheetId + ":batchUpdate",
                    Map.of("requests", List.of(Map.of("addSheet", Map.of("properties",
                            Map.of("title", title, "hidden", true))))));
            JsonNode id = created.path("replies").path(0).path("addSheet")
                    .path("properties").path("sheetId");
            if (!id.isIntegralNumber()) {
                throw new IOException("Google Sheets did not return the temporary sheet ID");
            }
            sheetId = id.intValue();
            JsonNode updated = put("/v4/spreadsheets/" + spreadsheetId + "/values/"
                            + title + "!A1?valueInputOption=RAW",
                    Map.of("range", title + "!A1", "majorDimension", "ROWS",
                            "values", List.of(List.of(marker))));
            if (updated.path("updatedCells").asInt() != 1) {
                throw new IOException("Google Sheets did not confirm the probe cell update");
            }
            JsonNode readBack = get("/v4/spreadsheets/" + spreadsheetId + "/values/" + title + "!A1");
            if (!marker.equals(readBack.path("values").path(0).path(0).asText())) {
                throw new IOException("Google Sheets probe read-back did not match");
            }
        } finally {
            if (sheetId != null) {
                post("/v4/spreadsheets/" + spreadsheetId + ":batchUpdate",
                        Map.of("requests", List.of(Map.of("deleteSheet", Map.of("sheetId", sheetId)))));
            }
        }
    }

    private JsonNode get(String path) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(apiBase.resolve(path)).GET());
    }

    private JsonNode post(String path, Object value) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(apiBase.resolve(path))
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(value), StandardCharsets.UTF_8))
                .header("Content-Type", "application/json; charset=UTF-8"));
    }

    private JsonNode put(String path, Object value) throws IOException, InterruptedException {
        return send(HttpRequest.newBuilder(apiBase.resolve(path))
                .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(value), StandardCharsets.UTF_8))
                .header("Content-Type", "application/json; charset=UTF-8"));
    }

    private JsonNode send(HttpRequest.Builder builder) throws IOException, InterruptedException {
        String accessToken = token.get();
        if (accessToken == null || accessToken.isBlank()) throw new IOException("Google Sheets token missing");
        HttpRequest request = builder.timeout(Duration.ofSeconds(25))
                .header("Authorization", "Bearer " + accessToken).build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Google Sheets copy check failed with HTTP " + response.statusCode());
        }
        return mapper.readTree(response.body());
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected path to the service account key file");
        }
        var credentials = new GoogleServiceAccountToken(Path.of(args[0]),
                GoogleServiceAccountToken.Scope.READ_WRITE);
        new GoogleSheetsCopyProbe(GOOGLE_API, TEST_COPY_ID, credentials).run();
        System.out.println("Test copy: write, read-back, and temporary-sheet cleanup passed.");
    }
}
