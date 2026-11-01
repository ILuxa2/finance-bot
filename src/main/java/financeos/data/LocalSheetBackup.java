package financeos.data;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** Read-only export of all FinanceOS sheets to a private local JSON file. */
public final class LocalSheetBackup {
    private LocalSheetBackup() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException("Expected spreadsheet ID, key file path, output file path");
        }
        var sheets = new GoogleSheetsReadOnly(URI.create("https://sheets.googleapis.com"),
                args[0], new GoogleServiceAccountToken(Path.of(args[1]))).readAll();
        var mapper = new ObjectMapper();
        ObjectNode backup = mapper.createObjectNode();
        backup.put("formatVersion", 1);
        backup.put("spreadsheetId", args[0]);
        backup.put("createdAt", OffsetDateTime.now(ZoneId.of("Europe/Moscow")).toString());
        backup.set("headers", mapper.valueToTree(LegacySheetSchema.headers()));
        backup.set("sheets", mapper.valueToTree(sheets));
        Path path = Path.of(args[2]).toAbsolutePath();
        Files.createDirectories(path.getParent());
        Files.writeString(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(backup),
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE_NEW);
        System.out.println("Локальная резервная копия создана: " + path);
        System.out.println("Листов: " + sheets.size() + ". Ключ в файл не включён.");
    }
}
