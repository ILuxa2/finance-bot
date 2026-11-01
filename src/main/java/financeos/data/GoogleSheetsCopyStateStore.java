package financeos.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.HexFormat;

/** Append-only copies of the Java bot's local state in a new sheet of the test copy. */
public final class GoogleSheetsCopyStateStore implements StateSnapshotStore {
    private static final String TAB = "FinanceOS_Java_State_v1";
    private static final List<String> HEADER = List.of("snapshot_id", "created_at", "vk_user_id",
            "chunk_index", "chunk_total", "sha256", "payload_base64");
    private static final int CHUNK_SIZE = 12000;
    private final URI apiBase;
    private final String spreadsheetId;
    private final Supplier<String> token;
    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public GoogleSheetsCopyStateStore(URI apiBase, String spreadsheetId, Supplier<String> token) {
        GoogleSheetsCopyProbe.requireTestCopy(spreadsheetId);
        this.apiBase = apiBase;
        this.spreadsheetId = spreadsheetId;
        this.token = token;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public void save(byte[] stateJson) throws IOException, InterruptedException {
        JsonNode state = mapper.readTree(stateJson);
        if (state == null || !state.isObject() || !state.path("vkUserId").canConvertToLong()
                || !state.path("baseFingerprint").isTextual()
                || !state.path("entries").isArray() || !state.path("plans").isArray()) {
            throw new IOException("Invalid local bot state; refusing to upload");
        }
        ensureSheet();
        String encoded = Base64.getEncoder().encodeToString(stateJson);
        String hash = sha256(stateJson);
        String snapshotId = UUID.randomUUID().toString();
        int count = Math.max(1, (encoded.length() + CHUNK_SIZE - 1) / CHUNK_SIZE);
        List<List<Object>> rows = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            String chunk = encoded.substring(index * CHUNK_SIZE,
                    Math.min(encoded.length(), (index + 1) * CHUNK_SIZE));
            rows.add(List.of(snapshotId, OffsetDateTime.now().toString(),
                    state.path("vkUserId").asText(), index, count, hash, chunk));
        }
        JsonNode response = post("/v4/spreadsheets/" + spreadsheetId + "/values/" + TAB
                        + "!A:G:append?valueInputOption=RAW&insertDataOption=INSERT_ROWS",
                Map.of("range", TAB + "!A:G", "majorDimension", "ROWS", "values", rows));
        if (response.path("updates").path("updatedRows").asInt() != count) {
            throw new IOException("Google Sheets did not confirm all snapshot rows");
        }
        byte[] readBack = latest().orElseThrow(() -> new IOException("Saved state not found"));
        if (!MessageDigest.isEqual(stateJson, readBack)) {
            throw new IOException("Saved state differs from local state");
        }
    }

    public Optional<byte[]> latest() throws IOException, InterruptedException {
        if (!sheetExists()) return Optional.empty();
        requireHeader();
        JsonNode values = get("/v4/spreadsheets/" + spreadsheetId + "/values/" + TAB + "!A:G")
                .path("values");
        if (!values.isArray() || values.size() <= 1) return Optional.empty();
        Map<String, List<JsonNode>> snapshots = new LinkedHashMap<>();
        for (int row = 1; row < values.size(); row++) {
            JsonNode cells = values.get(row);
            if (cells.isArray() && cells.size() == HEADER.size()) {
                snapshots.computeIfAbsent(cells.get(0).asText(), ignored -> new ArrayList<>())
                        .add(cells);
            }
        }
        var groups = new ArrayList<>(snapshots.values());
        for (int group = groups.size() - 1; group >= 0; group--) {
            Optional<byte[]> decoded = decode(groups.get(group));
            if (decoded.isPresent()) return decoded;
        }
        throw new IOException("No complete and valid Java state snapshot in test copy");
    }

    private Optional<byte[]> decode(List<JsonNode> rows) {
        if (rows.isEmpty()) return Optional.empty();
        try {
            int count = Integer.parseInt(rows.get(0).get(4).asText());
            String hash = rows.get(0).get(5).asText();
            String userId = rows.get(0).get(2).asText();
            if (count != rows.size() || count < 1 || count > 1000) return Optional.empty();
            String[] chunks = new String[count];
            for (JsonNode row : rows) {
                int index = Integer.parseInt(row.get(3).asText());
                if (index < 0 || index >= count || chunks[index] != null
                        || !hash.equals(row.get(5).asText())
                        || !userId.equals(row.get(2).asText())
                        || count != Integer.parseInt(row.get(4).asText())) return Optional.empty();
                chunks[index] = row.get(6).asText();
            }
            byte[] bytes = Base64.getDecoder().decode(String.join("", chunks));
            if (!hash.equals(sha256(bytes))) return Optional.empty();
            JsonNode state = mapper.readTree(bytes);
            if (!userId.equals(state.path("vkUserId").asText())) return Optional.empty();
            return Optional.of(bytes);
        } catch (Exception invalid) {
            return Optional.empty();
        }
    }

    private void ensureSheet() throws IOException, InterruptedException {
        if (sheetExists()) {
            requireHeader();
            return;
        }
        post("/v4/spreadsheets/" + spreadsheetId + ":batchUpdate",
                Map.of("requests", List.of(Map.of("addSheet", Map.of("properties",
                        Map.of("title", TAB, "hidden", true))))));
        put("/v4/spreadsheets/" + spreadsheetId + "/values/" + TAB
                        + "!A1:G1?valueInputOption=RAW",
                Map.of("range", TAB + "!A1:G1", "majorDimension", "ROWS",
                        "values", List.of(HEADER)));
        requireHeader();
    }

    private boolean sheetExists() throws IOException, InterruptedException {
        JsonNode sheets = get("/v4/spreadsheets/" + spreadsheetId
                + "?fields=sheets(properties(title))").path("sheets");
        if (!sheets.isArray()) throw new IOException("Could not inspect test-copy sheets");
        for (JsonNode sheet : sheets) {
            if (TAB.equals(sheet.path("properties").path("title").asText())) return true;
        }
        return false;
    }

    private void requireHeader() throws IOException, InterruptedException {
        JsonNode values = get("/v4/spreadsheets/" + spreadsheetId + "/values/" + TAB + "!A1:G1")
                .path("values");
        if (!values.isArray() || values.size() != 1 || !values.get(0).isArray()
                || values.get(0).size() != HEADER.size()) {
            throw new IOException("Unexpected Java state sheet header");
        }
        for (int index = 0; index < HEADER.size(); index++) {
            if (!HEADER.get(index).equals(values.get(0).get(index).asText())) {
                throw new IOException("Unexpected Java state sheet header");
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
        HttpResponse<String> response = client.send(builder.timeout(Duration.ofSeconds(25))
                .header("Authorization", "Bearer " + accessToken).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Google Sheets copy state operation failed with HTTP " + response.statusCode());
        }
        return mapper.readTree(response.body());
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
