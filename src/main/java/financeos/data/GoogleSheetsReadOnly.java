package financeos.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Reads the existing Sheets layout. This class exposes no write operation. */
public final class GoogleSheetsReadOnly {
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final URI apiBase;
    private final String spreadsheetId;
    private final Supplier<String> accessToken;

    public GoogleSheetsReadOnly(URI apiBase, String spreadsheetId, Supplier<String> accessToken) {
        if (spreadsheetId == null || !spreadsheetId.matches("[A-Za-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid spreadsheet ID");
        }
        this.apiBase = apiBase;
        this.spreadsheetId = spreadsheetId;
        this.accessToken = accessToken;
    }

    public Map<String, List<Map<String, String>>> readAll() throws IOException, InterruptedException {
        StringBuilder query = new StringBuilder("?majorDimension=ROWS&valueRenderOption=FORMATTED_VALUE");
        List<String> names = new ArrayList<>(LegacySheetSchema.headers().keySet());
        names.sort(String::compareTo);
        for (String name : names) {
            query.append("&ranges=").append(URLEncoder.encode("'" + name + "'!A:Z", StandardCharsets.UTF_8));
        }
        URI uri = apiBase.resolve("/v4/spreadsheets/" + spreadsheetId + "/values:batchGet" + query);
        String token = accessToken.get();
        if (token == null || token.isBlank()) throw new IllegalArgumentException("Read-only access token missing");
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(25))
                .header("Authorization", "Bearer " + token).GET().build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("Google Sheets read failed with HTTP " + response.statusCode());
        }
        JsonNode ranges = mapper.readTree(response.body()).path("valueRanges");
        if (!ranges.isArray() || ranges.size() != names.size()) {
            throw new IOException("Unexpected Google Sheets response");
        }
        Map<String, List<Map<String, String>>> result = new LinkedHashMap<>();
        for (int index = 0; index < names.size(); index++) {
            String name = names.get(index);
            JsonNode values = ranges.get(index).path("values");
            if (!values.isArray() || values.isEmpty()) {
                throw new IOException("Missing headers in " + name);
            }
            List<String> headers = cells(values.get(0));
            LegacySheetSchema.requireCompatible(name, headers);
            List<Map<String, String>> rows = new ArrayList<>();
            for (int row = 1; row < values.size(); row++) {
                List<String> cells = cells(values.get(row));
                Map<String, String> mapped = new LinkedHashMap<>();
                for (int column = 0; column < headers.size(); column++) {
                    mapped.put(headers.get(column), column < cells.size() ? cells.get(column) : "");
                }
                rows.add(Map.copyOf(mapped));
            }
            result.put(name, List.copyOf(rows));
        }
        return Map.copyOf(result);
    }

    private List<String> cells(JsonNode row) throws IOException {
        if (!row.isArray()) throw new IOException("Expected a row of cells");
        List<String> result = new ArrayList<>();
        row.forEach(cell -> result.add(cell.asText("")));
        return result;
    }
}
