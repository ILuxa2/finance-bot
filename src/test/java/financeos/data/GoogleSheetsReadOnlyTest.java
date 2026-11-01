package financeos.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class GoogleSheetsReadOnlyTest {
    public static void main(String[] args) throws Exception {
        var ranges = new ArrayList<Map<String, Object>>();
        var names = new ArrayList<>(LegacySheetSchema.headers().keySet());
        names.sort(String::compareTo);
        for (String name : names) {
            List<List<String>> values = new ArrayList<>();
            values.add(LegacySheetSchema.headers().get(name));
            if (name.equals("Transactions")) {
                values.add(List.of("2026-10-30", "42", "expense", "100.00", "Еда", "Обед"));
            }
            ranges.add(Map.of("values", values));
        }
        byte[] response = new ObjectMapper().writeValueAsBytes(Map.of("valueRanges", ranges));
        AtomicBoolean authenticated = new AtomicBoolean();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v4/spreadsheets/local-test/values:batchGet", exchange -> {
            authenticated.set("Bearer local-read-only-token".equals(
                    exchange.getRequestHeaders().getFirst("Authorization")));
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) { output.write(response); }
        });
        server.start();
        try {
            var reader = new GoogleSheetsReadOnly(
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                    "local-test", () -> "local-read-only-token");
            var data = reader.readAll();
            if (!authenticated.get() || data.size() != 10 || data.get("Transactions").size() != 1
                    || !data.get("Transactions").get(0).get("category").equals("Еда")) {
                throw new AssertionError("Read-only sheet mapping failed");
            }
        } finally {
            server.stop(0);
        }
        System.out.println("Google Sheets read-only adapter checks passed.");
    }
}
