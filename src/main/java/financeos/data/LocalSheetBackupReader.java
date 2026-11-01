package financeos.data;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Loads a previously verified read-only export without contacting Google. */
public final class LocalSheetBackupReader {
    private final ObjectMapper mapper = new ObjectMapper();

    public Map<String, List<Map<String, String>>> read(Path path) throws IOException {
        var root = mapper.readTree(Files.readAllBytes(path));
        if (root.path("formatVersion").asInt() != 1 || !root.path("sheets").isObject()) {
            throw new IOException("Неподдерживаемый формат резервной копии");
        }
        var sheets = mapper.convertValue(root.path("sheets"),
                new TypeReference<Map<String, List<Map<String, String>>>>() {});
        for (String name : LegacySheetSchema.headers().keySet()) {
            if (!sheets.containsKey(name) || sheets.get(name) == null) {
                throw new IOException("В резервной копии отсутствует лист " + name);
            }
        }
        return Map.copyOf(sheets);
    }
}
