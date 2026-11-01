package financeos.core;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class CategoryCatalog {
    private final Map<String, String> categories = new LinkedHashMap<>();

    public String ensure(String proposed) {
        if (proposed == null || proposed.isBlank()) {
            throw new IllegalArgumentException("Category cannot be empty");
        }
        String display = proposed.strip().replaceAll("\\s+", " ");
        String key = Normalizer.normalize(display, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        return categories.computeIfAbsent(key, ignored -> display);
    }

    public List<String> all() {
        return List.copyOf(categories.values());
    }
}
